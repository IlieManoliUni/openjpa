package isw2.openjpa.m1;

import isw2.openjpa.util.Csv;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * NSmells per (class, release), computed with PMD.
 *
 * PMD analyses source and compiles nothing, so it runs against 2006-era checkouts
 * whose Maven build no longer works - which is why SonarCloud cannot produce this
 * column. See docs/PROVIDED_CODE_CHANGES.md.
 *
 * For each kept release: export that release's src/main Java to a scratch folder,
 * run PMD over it, count violations per class, delete the scratch folder.
 *
 * The export, PMD and counting helpers below are public because Milestone 4
 * reuses them to measure the last release of the project. Sharing the code path
 * rather than copying it is what makes the two smell counts directly comparable
 * instead of merely similar: same rulesets, same export filter, same counting.
 * The per-release loop itself, and the release list it reads, stay private -
 * they are this milestone's own logic, not part of the shared toolkit.
 */
public class PmdSmells {

    private static final Path REPO_DIR = Path.of("..", "..");
    private static final Path RELEASES = Path.of("data", "releases.csv");
    private static final Path WORK     = Path.of("data", "pmd-work");
    private static final Path REPORT   = Path.of("data", "pmd-report.csv");
    private static final Path OUT      = Path.of("data", "pmd_smells.csv");

    public static final String RULESETS =
            "category/java/design.xml,"
                    + "category/java/errorprone.xml,"
                    + "category/java/bestpractices.xml";

    private record Rel(int index, String name, String sha) { }

    public static void main(String[] args) throws IOException, InterruptedException {

        Path pmdHome = locatePmd();
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path pmd = pmdHome.resolve("bin").resolve(windows ? "pmd.bat" : "pmd");
        if (!Files.exists(pmd)) {
            throw new IllegalStateException("PMD launcher not found: " + pmd.toAbsolutePath());
        }
        System.out.println("pmd: " + pmd.toAbsolutePath().normalize());

        List<Rel> kept = readKeptReleases();
        Files.createDirectories(OUT.getParent());
        int rowsWritten = 0;

        try (Git git = Git.open(REPO_DIR.toFile());
             Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {

            Repository repo = git.getRepository();
            w.write("Version,FileName,NSmells\n");

            for (Rel r : kept) {
                deleteTree(WORK);
                exportSources(repo, r.sha(), WORK);

                Files.deleteIfExists(REPORT);
                int exit = runPmd(pmd, WORK, REPORT);

                Map<String, Integer> counts = countViolations(WORK, REPORT);

                // every exported class gets a row, including those with zero violations
                Map<String, Integer> all = new TreeMap<>();
                for (String path : listExported(WORK)) {
                    all.put(path, counts.getOrDefault(path, 0));
                }
                for (Map.Entry<String, Integer> e : all.entrySet()) {
                    w.write(r.index() + "," + e.getKey() + "," + e.getValue() + "\n");
                    rowsWritten++;
                }

                int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                long zero = all.values().stream().filter(v -> v == 0).count();
                System.out.printf(
                        "  release %2d %-12s classes=%5d  violations=%6d  zero=%5d  (pmd exit %d)%n",
                        r.index(), r.name(), all.size(), total, zero, exit);
            }
            deleteTree(WORK);
            Files.deleteIfExists(REPORT);
        }

        System.out.println("\nrows written: " + rowsWritten);
        System.out.println("wrote " + OUT.toAbsolutePath());
    }

    /**
     * PMD comes from $PMD_HOME, or failing that from tools/pmd-bin-* three levels above
     * dataset-tool. The fallback exists because an IDE does not inherit the shell's
     * environment, and that folder is outside the git repository.
     */
    public static Path locatePmd() throws IOException {
        String env = System.getenv("PMD_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path tools = Path.of("..", "..", "..", "tools");
        if (Files.isDirectory(tools)) {
            try (var s = Files.list(tools)) {
                Optional<Path> hit = s.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("pmd-bin-"))
                        .max(Comparator.comparing(p -> p.getFileName().toString()));
                if (hit.isPresent()) {
                    return hit.get();
                }
            }
        }
        throw new IllegalStateException("No PMD found. Set $PMD_HOME, or install it under "
                + tools.toAbsolutePath().normalize());
    }

    /** Write this release's src/main Java files into dest, preserving repo-relative paths. */
    public static int exportSources(Repository repo, String sha, Path dest) throws IOException {
        int n = 0;
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
                Path target = dest.resolve(path);
                Files.createDirectories(target.getParent());
                Files.write(target, repo.open(tw.getObjectId(0)).getBytes());
                n++;
            }
        }
        return n;
    }

    public static int runPmd(Path pmd, Path dir, Path report)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
                pmd.toAbsolutePath().normalize().toString(), "check",
                "-d", dir.toAbsolutePath().normalize().toString(),
                "-R", RULESETS,
                "-f", "csv",
                "-r", report.toAbsolutePath().normalize().toString(),
                "--no-progress", "--no-fail-on-violation"));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PMD_JAVA_OPTS", "-Xmx2g");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start().waitFor();
    }

    /** Violations per class, keyed by repo-relative path. */
    public static Map<String, Integer> countViolations(Path work, Path report) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        if (!Files.exists(report)) {
            return counts;                      // PMD writes nothing when there are none
        }
        String prefix = work.toAbsolutePath().normalize() + java.io.File.separator;

        List<List<String>> rows = Csv.read(report);
        for (int i = 1; i < rows.size(); i++) {   // skip header
            List<String> row = rows.get(i);
            if (row.size() < 3) {
                continue;
            }
            String file = row.get(2);             // the "File" column
            if (file.startsWith(prefix)) {
                file = file.substring(prefix.length());
            }
            counts.merge(file.replace('\\', '/'), 1, Integer::sum);
        }
        return counts;
    }

    public static List<String> listExported(Path work) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(work)) {
            return out;
        }
        try (var s = Files.walk(work)) {
            s.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> out.add(work.relativize(p).toString().replace('\\', '/')));
        }
        return out;
    }

    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static List<Rel> readKeptReleases() throws IOException {
        List<Rel> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(RELEASES, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if ("true".equalsIgnoreCase(f[6].trim())) {
                out.add(new Rel(Integer.parseInt(f[0].trim()), f[2].trim(), f[4].trim()));
            }
        }
        return out;
    }
}