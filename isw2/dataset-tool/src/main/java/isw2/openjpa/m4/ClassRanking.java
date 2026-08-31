package isw2.openjpa.m4;

import isw2.openjpa.m1.PmdSmells;
import isw2.openjpa.util.Csv;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural metrics for every class of the last release, plus a PMD smell count.
 *
 * WHICH RELEASE
 *
 * The project's last release, 4.1.1 - not the last release of dataset A. The
 * dataset deliberately keeps only the oldest third of the releases, so its final
 * entry (1.2.2, 2010) is the last release of the DATASET, not of the project.
 * The practical reasons agree with the literal reading: Milestone 4 asks whether
 * the refactored class compiles and expects SonarCloud diagnostics, neither of
 * which is achievable against a 2010 maintenance branch.
 *
 * WHAT THIS PRODUCES, AND WHAT IT DOES NOT
 *
 * The smell ranking that drives the selection comes from SonarCloud, not from
 * here. That is forced by the milestone: the refactoring prompt must report
 * SonarCloud diagnostics, and the results section asks whether the refactored
 * class still has smells and whether they are old or new - a before/after
 * comparison that is only meaningful if both sides come from the same detector.
 *
 * This class supplies the two things the SonarCloud API cannot:
 *
 *   1. The structural metrics the "filter out classes that are too small" step
 *      needs - declared kind, method count, NCSS.
 *
 *   2. An independent smell count from PMD, using the identical code path,
 *      rulesets and counting as the NSmells column of dataset A.
 *
 * SCOPE
 *
 * The module exclusions below mirror the SonarCloud analysis exactly. Without
 * them the two counts describe different populations and the cross-check is
 * worthless - the first run ranked openbooks' ANTLR-generated JavaParser first
 * with 2,747 violations and JavaLexer fifth, neither of which SonarCloud sees at
 * all. Both live in the OpenBooks sample application.
 *
 * The exclusions are methodological rather than convenient. Sample applications,
 * integration harnesses, build tooling and documentation are not product
 * classes; and refactoring generated code is meaningless, because the next build
 * regenerates it from its grammar.
 *
 * It deliberately does not filter by size and does not select. That threshold is
 * chosen from the measured distribution in a later pass; this stage is the
 * evidence for it.
 */
public class ClassRanking {

    private static final Path REPO_DIR = Path.of("..", "..");
    private static final Path RELEASES = Path.of("data", "releases.csv");
    private static final Path WORK     = Path.of("data", "m4-work");
    private static final Path REPORT   = Path.of("data", "m4-pmd-report.csv");
    private static final Path OUT      = Path.of("data", "m4_classes.csv");

    /** The project's last release. */
    private static final String TARGET = "4.1.1";

    /** Mirrors sonar.exclusions. Tests are already excluded by the src/main filter. */
    private static final List<String> OUT_OF_SCOPE = List.of(
            "openjpa-examples/",
            "openjpa-integration/",
            "openjpa-tools/",
            "openjpa-project/");

    public record Klass(String path, String module, String name, String kind,
                        int smells, int loc, int ncss, int methods) { }

    public static void main(String[] args) throws IOException, InterruptedException {

        String release = args.length > 0 ? args[0] : TARGET;
        String sha = findSha(release);
        System.out.printf("release %s -> %s%n", release, sha);

        Path pmd = PmdSmells.locatePmd().resolve("bin")
                .resolve(System.getProperty("os.name").toLowerCase().contains("win")
                        ? "pmd.bat" : "pmd");
        System.out.println("pmd: " + pmd.toAbsolutePath().normalize());

        List<Klass> classes = new ArrayList<>();
        int skipped = 0;

        try (Git git = Git.open(REPO_DIR.toFile())) {
            Repository repo = git.getRepository();

            PmdSmells.deleteTree(WORK);
            int exported = PmdSmells.exportSources(repo, sha, WORK);
            System.out.printf("exported %d source files%n", exported);

            Files.deleteIfExists(REPORT);
            int exit = PmdSmells.runPmd(pmd, WORK, REPORT);
            Map<String, Integer> smells = PmdSmells.countViolations(WORK, REPORT);
            System.out.printf("pmd exit %d, %d files with at least one violation%n",
                    exit, smells.size());

            for (String rel : PmdSmells.listExported(WORK)) {

                if (!inScope(rel)) {
                    skipped++;
                    continue;
                }

                Path file = WORK.resolve(rel);
                String source = Files.readString(file, StandardCharsets.ISO_8859_1);
                Source parsed = analyse(source);

                classes.add(new Klass(
                        rel,
                        rel.substring(0, Math.max(0, rel.indexOf('/'))),
                        rel.substring(rel.lastIndexOf('/') + 1).replace(".java", ""),
                        parsed.kind(),
                        smells.getOrDefault(rel, 0),
                        parsed.loc(),
                        parsed.ncss(),
                        parsed.methods()));
            }

            PmdSmells.deleteTree(WORK);
            Files.deleteIfExists(REPORT);
        }

        System.out.printf("out of scope (examples / integration / tools / project): %d%n", skipped);

        classes.sort(Comparator.comparingInt(Klass::smells).reversed()
                .thenComparing(Comparator.comparingInt(Klass::ncss).reversed())
                .thenComparing(Klass::path));

        write(classes);
        report(classes);
    }

    /** True when the path belongs to a module SonarCloud also analyses. */
    private static boolean inScope(String path) {
        for (String prefix : OUT_OF_SCOPE) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /* ---------------- source analysis ---------------- */

    private record Source(String kind, int loc, int ncss, int methods) { }

    private static final Pattern TYPE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|strictfp\\s+)*"
                    + "(@interface|interface|enum|record|class)\\s+\\w+");

    /**
     * A method declaration. Deliberately approximate: it drives a coarse filter,
     * not a measurement, and the handful of classes that survive to selection
     * are inspected by hand anyway.
     *
     * Every quantifier loops over a simple character class rather than over an
     * alternation, which is what keeps it iterative - see stripNonCode.
     */
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^\\s*(?:@\\w+\\s+)*(?:public|protected|private|static|final|synchronized"
                    + "|abstract|native|default)[\\w\\s<>\\[\\],.?]*\\s+\\w+\\s*\\([^;{}]*\\)"
                    + "\\s*(?:throws[\\w\\s,.]+)?\\{");

    private static Source analyse(String raw) {

        int loc = raw.split("\r\n|\r|\n", -1).length;

        String code = stripNonCode(raw);

        int ncss = 0;
        for (String line : code.split("\r\n|\r|\n", -1)) {
            if (!line.isBlank()) {
                ncss++;
            }
        }

        String kind = "unknown";
        Matcher t = TYPE.matcher(code);
        if (t.find()) {
            kind = t.group(1).equals("@interface") ? "annotation" : t.group(1);
        }

        int methods = 0;
        Matcher m = METHOD.matcher(code);
        while (m.find()) {
            methods++;
        }

        return new Source(kind, loc, ncss, methods);
    }

    /**
     * Removes comments, string literals and character literals in a single
     * left-to-right pass.
     *
     * This was originally three regular expressions, and the string-literal one
     * was written as "(\\.|[^"\\])*" - an alternation inside a star. Java's
     * regex engine recurses once per repetition of such a group, so a single
     * long string literal drove the recursion as deep as the literal was long
     * and the JVM stack gave out with a StackOverflowError. A character scan has
     * no backtracking, no recursion, and is faster.
     *
     * Newlines inside comments and literals are preserved so the NCSS count
     * still sees them as blank lines rather than losing the line structure.
     */
    private static String stripNonCode(String s) {

        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();

        while (i < n) {
            char c = s.charAt(i);

            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    if (s.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');

            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');

            } else if (c == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n && !(s.charAt(i) == '"'
                        && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"')) {
                    if (s.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 3);
                out.append("\"\"");

            } else if (c == '"') {
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == '"') {
                        i++;
                        break;
                    }
                    if (d == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                out.append("\"\"");

            } else if (c == '\'') {
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                out.append("' '");

            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /* ---------------- output ---------------- */

    private static void write(List<Klass> classes) throws IOException {
        Files.createDirectories(OUT.getParent());
        try (Writer out = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            out.write("Rank,Module,Class,Kind,PmdSmells,LOC,NCSS,Methods,Path\n");
            int rank = 1;
            for (Klass k : classes) {
                out.write(String.format(Locale.US, "%d,%s,%s,%s,%d,%d,%d,%d,%s%n",
                        rank++, k.module(), k.name(), k.kind(),
                        k.smells(), k.loc(), k.ncss(), k.methods(), k.path()));
            }
        }
        System.out.println("wrote " + OUT.toAbsolutePath());
    }

    /** Prints what is needed to choose the "too small" threshold with evidence. */
    private static void report(List<Klass> classes) {

        System.out.printf("%n%d classes in scope%n%n", classes.size());

        System.out.println("=== by declared kind ===");
        for (String kind : List.of("class", "interface", "enum", "annotation", "record", "unknown")) {
            List<Klass> of = classes.stream().filter(k -> k.kind().equals(kind)).toList();
            if (!of.isEmpty()) {
                System.out.printf("  %-11s %5d   median NCSS %4d   median methods %3d   "
                                + "median smells %3d%n",
                        kind, of.size(), median(of, Klass::ncss),
                        median(of, Klass::methods), median(of, Klass::smells));
            }
        }

        System.out.println();
        System.out.println("=== distribution over concrete classes only ===");
        List<Klass> real = classes.stream().filter(k -> k.kind().equals("class")).toList();
        for (int p : new int[] { 5, 10, 25, 50, 75, 90, 99 }) {
            System.out.printf("  p%-3d  NCSS %5d   methods %4d   smells %4d%n", p,
                    percentile(real, Klass::ncss, p),
                    percentile(real, Klass::methods, p),
                    percentile(real, Klass::smells, p));
        }

        System.out.println();
        System.out.println("=== top 15 by PMD smells ===");
        print(classes.subList(0, Math.min(15, classes.size())));

        System.out.println();
        System.out.println("=== bottom 10 (unfiltered - this is why the filter step exists) ===");
        print(classes.subList(Math.max(0, classes.size() - 10), classes.size()));
    }

    private static void print(List<Klass> rows) {
        System.out.printf("  %-34s %-10s %7s %6s %8s%n",
                "class", "kind", "smells", "ncss", "methods");
        for (Klass k : rows) {
            System.out.printf("  %-34s %-10s %7d %6d %8d%n",
                    k.name(), k.kind(), k.smells(), k.ncss(), k.methods());
        }
    }

    private static int median(List<Klass> rows, java.util.function.ToIntFunction<Klass> f) {
        return percentile(rows, f, 50);
    }

    private static int percentile(List<Klass> rows,
                                  java.util.function.ToIntFunction<Klass> f, int p) {
        if (rows.isEmpty()) {
            return 0;
        }
        int[] values = rows.stream().mapToInt(f).sorted().toArray();
        return values[Math.min(values.length - 1, (int) Math.round(p / 100.0 * values.length))];
    }

    private static String findSha(String release) throws IOException {
        List<List<String>> rows = Csv.read(RELEASES);
        List<String> header = rows.get(0);
        int nameCol = header.indexOf("Version Name");
        int shaCol = header.indexOf("Commit");
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).get(nameCol).trim().equals(release)) {
                return rows.get(i).get(shaCol).trim();
            }
        }
        throw new IllegalArgumentException("release not found in releases.csv: " + release);
    }
}