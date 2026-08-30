package isw2.openjpa.m1;

import isw2.openjpa.Config;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Milestone 1, labelling step: SZZ.
 *
 * A commit fixes ticket K when its message references K and K is one of the confirmed
 * fixed bug tickets. The .java files that commit changes are the buggy classes, and they
 * are labelled buggy for every release in [IV, FV).
 */
public class Labeler {

    private static final Path REPO_DIR   = Path.of("..", "..");
    private static final Path RELEASES   = Path.of("data", "releases.csv");
    private static final Path PROPORTION = Path.of("data", "proportion.csv");
    private static final Path OUT        = Path.of("data", "buggy.csv");

    private static final Pattern TICKET_KEY =
            Pattern.compile(Config.PROJECT_KEY + "-\\d+", Pattern.CASE_INSENSITIVE);

    /** iv and fv are 1-based release indices. */
    private record Ticket(String key, int iv, int fv) { }

    public static void main(String[] args) throws Exception {

        Set<Integer> keptReleases = readKeptReleaseIndices();
        List<Ticket> tickets = readTickets();
        Map<String, Ticket> byKey = new HashMap<>();
        for (Ticket t : tickets) {
            byKey.put(t.key(), t);
        }
        System.out.println("kept releases    : " + keptReleases.size());
        System.out.println("tickets with IV  : " + tickets.size());

        Map<String, Set<String>> filesByTicket = new HashMap<>();
        int commitsScanned = 0, fixCommits = 0;

        try (Git git = Git.open(REPO_DIR.toFile());
             RevWalk walk = new RevWalk(git.getRepository());
             ObjectReader reader = git.getRepository().newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            Repository repo = git.getRepository();
            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            // all refs, so fixes made on maintenance branches are seen too
            for (RevCommit c : git.log().all().call()) {
                commitsScanned++;

                Set<String> keys = keysIn(c.getFullMessage(), byKey.keySet());
                if (keys.isEmpty() || c.getParentCount() > 1) {
                    continue;
                }
                fixCommits++;

                List<DiffEntry> entries;
                if (c.getParentCount() == 0) {
                    entries = df.scan(new EmptyTreeIterator(),
                            new CanonicalTreeParser(null, reader, c.getTree()));
                } else {
                    RevCommit parent = walk.parseCommit(c.getParent(0).getId());
                    entries = df.scan(parent.getTree(), c.getTree());
                }

                for (DiffEntry e : entries) {
                    String path = e.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? e.getOldPath() : e.getNewPath();
                    if (!path.endsWith(".java") || !path.contains("/src/main/java/")) {
                        continue;
                    }
                    for (String k : keys) {
                        filesByTicket.computeIfAbsent(k, x -> new HashSet<>()).add(path);
                    }
                }

                if (fixCommits % 250 == 0) {
                    System.out.printf("    %5d fixing commits found (%d scanned)%n",
                            fixCommits, commitsScanned);
                }
            }
        }

        System.out.println("commits scanned  : " + commitsScanned);
        System.out.println("fixing commits   : " + fixCommits);
        System.out.println("tickets matched  : " + filesByTicket.size()
                + "   unmatched: " + (tickets.size() - filesByTicket.size()));

        // ---- expand each ticket over [IV, FV) ----
        Set<String> pairs = new TreeSet<>();
        int ticketsUsed = 0;
        for (Ticket t : tickets) {
            Set<String> files = filesByTicket.get(t.key());
            if (files == null || files.isEmpty()) {
                continue;
            }
            boolean used = false;
            for (int v = t.iv(); v < t.fv(); v++) {
                if (!keptReleases.contains(v)) {
                    continue;
                }
                for (String f : files) {
                    pairs.add(v + "," + f);
                    used = true;
                }
            }
            if (used) {
                ticketsUsed++;
            }
        }

        Files.createDirectories(OUT.getParent());
        try (Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            w.write("Version,FileName\n");
            for (String p : pairs) {
                w.write(p + "\n");
            }
        }

        System.out.println("\ntickets contributing labels : " + ticketsUsed);
        System.out.println("buggy (class, release) pairs : " + pairs.size());
        System.out.println("wrote " + OUT.toAbsolutePath());
    }

    private static Set<String> keysIn(String message, Set<String> known) {
        Set<String> out = new HashSet<>();
        Matcher m = TICKET_KEY.matcher(message);
        while (m.find()) {
            String k = m.group().toUpperCase(Locale.ROOT);
            if (known.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static Set<Integer> readKeptReleaseIndices() throws IOException {
        Set<Integer> out = new HashSet<>();
        for (String line : dataLines(RELEASES)) {
            String[] f = line.split(",", -1);
            if ("true".equalsIgnoreCase(f[6].trim())) {
                out.add(Integer.parseInt(f[0].trim()));
            }
        }
        return out;
    }

    /** proportion.csv: Key,Created,Resolved,OV,FV,IVfromAV,IV,IVSource,UsedForP */
    private static List<Ticket> readTickets() throws IOException {
        List<Ticket> out = new ArrayList<>();
        for (String line : dataLines(PROPORTION)) {
            String[] f = line.split(",", -1);
            int fv = Integer.parseInt(f[4].trim());
            int iv = Integer.parseInt(f[6].trim());
            if (iv > 0 && fv > iv) {
                out.add(new Ticket(f[0].trim().toUpperCase(Locale.ROOT), iv, fv));
            }
        }
        return out;
    }

    private static List<String> dataLines(Path p) throws IOException {
        List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
        return all.subList(1, all.size());
    }
}