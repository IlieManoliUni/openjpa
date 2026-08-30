package isw2.openjpa;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Milestone 1, workflow step 4: for each kept release, one row per class carrying
 * the 16 metrics. Definitions and scope decisions: docs/PROVIDED_CODE_CHANGES.md.
 *
 * Strategy: diff every commit once into a cache, then aggregate that cache once per
 * release. OpenJPA's releases sit on parallel maintenance branches - release 13
 * (2.0.0-M3, trunk) and release 14 (1.2.2, the 1.2.x branch) are not ancestors of one
 * another - so a single history walk cannot serve them all. Each release aggregates
 * over its own ancestor set; the sets overlap heavily, so no commit is diffed twice.
 */
public class MetricsComputer {

    private static final Path REPO_DIR = Path.of("..", "..");
    private static final Path RELEASES = Path.of("data", "releases.csv");
    private static final Path TICKETS  = Path.of("data", "tickets.csv");
    private static final Path OUT      = Path.of("data", "metrics.csv");

    private static final Pattern TICKET_KEY =
            Pattern.compile(Config.PROJECT_KEY + "-\\d+", Pattern.CASE_INSENSITIVE);

    private record Rel(int index, String name, LocalDate date, String sha) { }
    private record Change(String path, int added, int deleted) { }

    /** A rename, tagged with the commit that performed it and when. */
    private record Rename(String sha, int time, String oldPath, String newPath) { }

    private static final class CommitInfo {
        int time;
        String author;
        boolean fix;
        int changeSetSize;
        List<Change> changes = new ArrayList<>();
    }

    /** Per-class accumulator for one release. */
    private static final class Acc {
        int nr, nfix, locAdded, maxLocAdded, locTouched, churn, chgSetSum, maxChgSet;
        int maxChurn = Integer.MIN_VALUE;
        int firstCommitTime = Integer.MAX_VALUE;
        double weightedNum, weightedDen;
        Set<String> authors = new HashSet<>();
    }

    /**
     * Every rename seen in history, sorted by commit time before use.
     *
     * Two properties matter. Renames are applied PER RELEASE, not globally: one that
     * happened after a release must not be used when aggregating it, or changes get
     * attributed to a path that did not exist yet. And they are applied in COMMIT-TIME
     * order: when a path is renamed more than once, the chain must be built in the order
     * it actually happened, otherwise the result depends on hash iteration order.
     */
    private static final List<Rename> renames = new ArrayList<>();

    public static void main(String[] args) throws IOException {

        List<Rel> kept = readKeptReleases();
        Set<String> ticketKeys = readTicketKeys();
        System.out.println("kept releases : " + kept.size());
        System.out.println("ticket keys   : " + ticketKeys.size());

        try (Git git = Git.open(REPO_DIR.toFile())) {
            Repository repo = git.getRepository();

            // ---- which commits do we need? the union of every release's ancestry ----
            Map<Integer, Set<String>> ancestorsByRelease = new LinkedHashMap<>();
            Set<String> allNeeded = new HashSet<>();
            for (Rel r : kept) {
                Set<String> anc = ancestors(repo, r.sha());
                ancestorsByRelease.put(r.index(), anc);
                allNeeded.addAll(anc);
                System.out.printf("  release %2d %-12s ancestors: %5d%n",
                        r.index(), r.name(), anc.size());
            }
            System.out.println("distinct commits to diff: " + allNeeded.size());

            // ---- diff each commit exactly once ----
            Map<String, CommitInfo> cache = buildCommitCache(repo, allNeeded, ticketKeys);
            renames.sort(Comparator.comparingInt(Rename::time));
            System.out.println("commits cached (merges skipped): " + cache.size());
            System.out.println("renames recorded              : " + renames.size());

            // ---- aggregate per release ----
            Files.createDirectories(OUT.getParent());
            int rowsWritten = 0;
            try (Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
                w.write("Version,FileName,LOC,LOC_touched,NR,NFix,NAuth,LOC_added,"
                        + "MAX_LOC_added,AVG_LOC_added,Churn,MAX_Churn,AVG_Churn,"
                        + "ChgSetSize,MAX_ChgSet,AVG_ChgSet,Age,WeightedAge\n");

                for (Rel r : kept) {
                    Set<String> anc = ancestorsByRelease.get(r.index());
                    Map<String, Integer> classes = classesAt(repo, r.sha());

                    // Only renames already performed by this release, in the order they
                    // happened, so a multi-step chain resolves correctly.
                    Map<String, String> renameMap = new HashMap<>();
                    for (Rename rn : renames) {
                        if (anc.contains(rn.sha())) {
                            renameMap.put(rn.oldPath(), rn.newPath());
                        }
                    }

                    // Age baseline is the release COMMIT's timestamp, not JIRA's date:
                    // the snapshot being measured is that commit, and JIRA's midnight-UTC
                    // date can precede it, which produced negative ages.
                    long releaseTime;
                    try (RevWalk rw = new RevWalk(repo)) {
                        releaseTime = rw.parseCommit(ObjectId.fromString(r.sha())).getCommitTime();
                    }

                    Map<String, Acc> acc = new HashMap<>();
                    for (String path : classes.keySet()) {
                        acc.put(path, new Acc());
                    }

                    for (String sha : anc) {
                        CommitInfo ci = cache.get(sha);
                        if (ci == null) {
                            continue;                 // merge commit, deliberately skipped
                        }
                        for (Change ch : ci.changes) {
                            Acc a = acc.get(resolve(ch.path(), renameMap));
                            if (a == null) {
                                continue;             // not a class present at this release
                            }
                            int touched = ch.added() + ch.deleted();
                            int net     = ch.added() - ch.deleted();

                            a.nr++;
                            if (ci.fix) {
                                a.nfix++;
                            }
                            a.authors.add(ci.author);
                            a.locAdded    += ch.added();
                            a.locTouched  += touched;
                            a.churn       += net;
                            a.maxLocAdded  = Math.max(a.maxLocAdded, ch.added());
                            a.maxChurn     = Math.max(a.maxChurn, net);
                            a.chgSetSum   += ci.changeSetSize;
                            a.maxChgSet    = Math.max(a.maxChgSet, ci.changeSetSize);
                            a.firstCommitTime = Math.min(a.firstCommitTime, ci.time);

                            double ageDays = (releaseTime - ci.time) / 86400.0;
                            a.weightedNum += ageDays * touched;
                            a.weightedDen += touched;
                        }
                    }

                    for (Map.Entry<String, Integer> e : new TreeMap<>(classes).entrySet()) {
                        String path = e.getKey();
                        Acc a = acc.get(path);
                        int nr = a.nr;

                        double ageDays = a.firstCommitTime == Integer.MAX_VALUE
                                ? 0.0 : (releaseTime - a.firstCommitTime) / 86400.0;
                        double weightedAge = a.weightedDen == 0
                                ? 0.0 : a.weightedNum / a.weightedDen;

                        w.write(String.format(Locale.ROOT,
                                "%d,%s,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%.2f,%d,%d,%.2f,%.2f,%.2f%n",
                                r.index(), path, e.getValue(),
                                a.locTouched, nr, a.nfix, a.authors.size(),
                                a.locAdded, a.maxLocAdded,
                                nr == 0 ? 0.0 : (double) a.locAdded / nr,
                                a.churn,
                                a.maxChurn == Integer.MIN_VALUE ? 0 : a.maxChurn,
                                nr == 0 ? 0.0 : (double) a.churn / nr,
                                a.chgSetSum, a.maxChgSet,
                                nr == 0 ? 0.0 : (double) a.chgSetSum / nr,
                                ageDays, weightedAge));
                        rowsWritten++;
                    }
                    System.out.printf("  release %2d %-12s classes: %5d  renames applied: %4d%n",
                            r.index(), r.name(), classes.size(), renameMap.size());
                }
            }
            System.out.println("\nrows written: " + rowsWritten);
            System.out.println("wrote " + OUT.toAbsolutePath());
        }
    }

    /** Follow the rename chain forward, using only renames valid for this release. */
    private static String resolve(String path, Map<String, String> renameMap) {
        String p = path;
        for (int i = 0; i < 20 && renameMap.containsKey(p); i++) {
            p = renameMap.get(p);
        }
        return p;
    }

    private static Set<String> ancestors(Repository repo, String sha) throws IOException {
        Set<String> out = new HashSet<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(ObjectId.fromString(sha)));
            for (RevCommit c : walk) {
                out.add(c.getName());
            }
        }
        return out;
    }

    private static Map<String, CommitInfo> buildCommitCache(
            Repository repo, Set<String> shas, Set<String> ticketKeys) throws IOException {

        Map<String, CommitInfo> cache = new HashMap<>();
        int done = 0, merges = 0;

        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            for (String sha : shas) {
                RevCommit c = walk.parseCommit(ObjectId.fromString(sha));

                // Skip merges: a merge diffed against its first parent shows changes that
                // are already counted in the branch commits, which are themselves in the
                // ancestor set. Including merges double-counts instead of adding anything.
                if (c.getParentCount() > 1) {
                    merges++;
                    done++;
                    continue;
                }

                CommitInfo ci = new CommitInfo();
                ci.time   = c.getCommitTime();
                ci.author = c.getAuthorIdent().getEmailAddress();
                ci.fix    = isFix(c.getFullMessage(), ticketKeys);

                List<DiffEntry> entries;
                if (c.getParentCount() == 0) {
                    entries = df.scan(new EmptyTreeIterator(),
                            new CanonicalTreeParser(null, reader, c.getTree()));
                } else {
                    RevCommit parent = walk.parseCommit(c.getParent(0).getId());
                    entries = df.scan(parent.getTree(), c.getTree());
                }
                ci.changeSetSize = entries.size();

                for (DiffEntry e : entries) {
                    if (e.getChangeType() == DiffEntry.ChangeType.RENAME) {
                        renames.add(new Rename(sha, ci.time, e.getOldPath(), e.getNewPath()));
                    }
                    String path = e.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? e.getOldPath() : e.getNewPath();
                    if (!path.endsWith(".java")) {
                        continue;
                    }
                    int added = 0, deleted = 0;
                    for (Edit edit : df.toFileHeader(e).toEditList()) {
                        added   += edit.getEndB() - edit.getBeginB();
                        deleted += edit.getEndA() - edit.getBeginA();
                    }
                    ci.changes.add(new Change(path, added, deleted));
                }
                cache.put(sha, ci);

                if (++done % 250 == 0) {
                    System.out.printf("    diffed %5d / %d commits%n", done, shas.size());
                }
            }
        }
        System.out.println("    merge commits skipped: " + merges);
        return cache;
    }

    private static boolean isFix(String message, Set<String> ticketKeys) {
        Matcher m = TICKET_KEY.matcher(message);
        while (m.find()) {
            if (ticketKeys.contains(m.group().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** src/main Java classes present at a commit, mapped to their line count. */
    private static Map<String, Integer> classesAt(Repository repo, String sha) throws IOException {
        Map<String, Integer> out = new HashMap<>();
        try (RevWalk walk = new RevWalk(repo);
             TreeWalk tw = new TreeWalk(repo)) {

            RevCommit c = walk.parseCommit(ObjectId.fromString(sha));
            tw.addTree(c.getTree());
            tw.setRecursive(true);

            while (tw.next()) {
                String path = tw.getPathString();
                if (!path.endsWith(".java") || !path.contains("/src/main/java/")) {
                    continue;
                }
                out.put(path, countLines(repo.open(tw.getObjectId(0)).getBytes()));
            }
        }
        return out;
    }

    private static int countLines(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int lines = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                lines++;
            }
        }
        return bytes[bytes.length - 1] == '\n' ? lines : lines + 1;
    }

    private static List<Rel> readKeptReleases() throws IOException {
        List<Rel> out = new ArrayList<>();
        for (String line : dataLines(RELEASES)) {
            String[] f = line.split(",", -1);
            if (!"true".equalsIgnoreCase(f[6].trim())) {
                continue;                                   // Kept column
            }
            out.add(new Rel(Integer.parseInt(f[0].trim()), f[2].trim(),
                    LocalDate.parse(f[3].trim()), f[4].trim()));
        }
        return out;
    }

    private static Set<String> readTicketKeys() throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : dataLines(TICKETS)) {
            out.add(line.split(",", -1)[0].trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static List<String> dataLines(Path p) throws IOException {
        List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
        return all.subList(1, all.size());
    }
}