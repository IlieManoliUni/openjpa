package isw2.openjpa.m1;

import isw2.openjpa.Config;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Milestone 1, workflow steps 3 and 4.1:
 *   - apply the 66% rule (keep the oldest third)
 *   - map each release name to the commit it was cut from
 *
 * No code is provided in class for either step.
 */
public class ReleaseResolver {

    /** dataset-tool -> isw2 -> openjpa-repo */
    private static final Path REPO_DIR = Path.of("..", "..");
    private static final Path IN  = Path.of("data", Config.PROJECT_KEY + "VersionInfo.csv");
    private static final Path OUT = Path.of("data", "releases.csv");

    private record Release(int index, String versionId, String name, LocalDate date) { }
    private record Resolved(String sha, String source) { }

    public static void main(String[] args) throws IOException {
        List<Release> releases = readReleases();
        int keep = (int) Math.floor(releases.size() * Config.KEEP_FRACTION);

        System.out.println("releases read : " + releases.size());
        System.out.println("keeping       : " + keep + "  (first "
                + Math.round(Config.KEEP_FRACTION * 100) + "%)");

        try (Git git = Git.open(REPO_DIR.toFile());
             Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {

            Repository repo = git.getRepository();
            ObjectId branch = branchToSearch(repo);

            w.write("Index,Version ID,Version Name,Date,Commit,ShaSource,Kept\n");

            int viaTag = 0, viaIncubating = 0, viaDate = 0, unresolved = 0;

            for (Release r : releases) {
                Resolved res = resolve(repo, branch, r);
                boolean kept = r.index() <= keep;

                switch (res.source()) {
                    case "tag"        -> viaTag++;
                    case "incubating" -> viaIncubating++;
                    case "date"       -> viaDate++;
                    default           -> unresolved++;
                }

                w.write(r.index() + "," + r.versionId() + "," + r.name() + ","
                        + r.date() + "," + res.sha() + "," + res.source() + "," + kept + "\n");

                if (kept) {
                    System.out.printf("  %2d  %-14s %s  %s  [%s]%n",
                            r.index(), r.name(), r.date(),
                            res.sha().substring(0, Math.min(10, res.sha().length())),
                            res.source());
                }
            }

            System.out.println("\nresolved by exact tag        : " + viaTag);
            System.out.println("resolved by -incubating tag  : " + viaIncubating);
            System.out.println("resolved by date fallback    : " + viaDate);
            System.out.println("UNRESOLVED                   : " + unresolved);
            System.out.println("wrote " + OUT.toAbsolutePath());
        }
    }

    /** Tag with the exact name, else name+"-incubating", else newest commit on or before the date. */
    private static Resolved resolve(Repository repo, ObjectId branch, Release r) throws IOException {
        ObjectId id = repo.resolve(r.name() + "^{commit}");
        if (id != null) {
            return new Resolved(id.getName(), "tag");
        }

        id = repo.resolve(r.name() + "-incubating^{commit}");
        if (id != null) {
            return new Resolved(id.getName(), "incubating");
        }

        RevCommit c = lastCommitOnOrBefore(repo, branch, r.date());
        return c == null ? new Resolved("", "UNRESOLVED")
                : new Resolved(c.getName(), "date");
    }

    private static RevCommit lastCommitOnOrBefore(Repository repo, ObjectId branch, LocalDate date)
            throws IOException {
        // end of the release day, UTC
        long cutoff = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(branch));
            walk.sort(RevSort.COMMIT_TIME_DESC);
            for (RevCommit c : walk) {
                if (c.getCommitTime() < cutoff) {
                    return c;
                }
            }
        }
        return null;
    }

    /** Apache's history, so our own commits can never be picked as a release commit. */
    private static ObjectId branchToSearch(Repository repo) throws IOException {
        for (String ref : new String[]{
                "refs/remotes/upstream/master", "refs/remotes/origin/master", "HEAD"}) {
            ObjectId id = repo.resolve(ref);
            if (id != null) {
                System.out.println("searching history of: " + ref);
                return id;
            }
        }
        throw new IllegalStateException("no branch found to search");
    }

    private static List<Release> readReleases() throws IOException {
        List<Release> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(IN, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {      // skip header
            String[] f = lines.get(i).split(",");
            out.add(new Release(Integer.parseInt(f[0].trim()), f[1].trim(),
                    f[2].trim(), LocalDate.parse(f[3].trim())));
        }
        return out;
    }
}