package isw2.openjpa.m4;

import isw2.openjpa.util.Csv;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Milestone 4, step 8: rank the classes of the last release by NSmells, filter
 * out the ones too small to refactor, and select two by the name rule.
 *
 * THE RANKING
 *
 * SonarCloud's code_smells for the 4.1.1 analysis. PMD's independent count is
 * carried alongside and their rank correlation is reported, so the selection can
 * be shown not to depend on which detector was used.
 *
 * THE FILTER
 *
 * Step 8.2 says to filter out classes that are "too small (e.g., few and simple
 * methods)" without saying where the line falls. Three criteria are applied, and
 * every threshold is the median of this release rather than a chosen number, so
 * they are properties of the codebase and move with it.
 *
 *   1. The class must be a class. Interfaces, annotations and enums have no
 *      method bodies to refactor, and the unfiltered ranking's bottom ten was
 *      made entirely of them - three to four lines each, zero methods.
 *
 *   2. It must be at least median size: NCSS >= median, methods >= median.
 *
 *   3. Its methods must not be trivially short: NCSS / methods >= median.
 *
 * WHY THE THIRD CRITERION EXISTS
 *
 * It was added after the first two produced a bottom selection of
 * AbstractFieldManager - twenty methods, each a single "throw new
 * InternalException()", whose own javadoc reads "Throws exceptions for all
 * methods". It scored zero smells because there is nothing in it to smell, and
 * it passed a size test because twenty one-line methods plus imports reach 86
 * NCSS.
 *
 * That is a filter measuring "few methods" while ignoring "simple methods",
 * which is half of what the criterion actually says. A per-method size test is
 * the missing half: the median class here is 8.0 NCSS per method, and
 * AbstractFieldManager was 4.3.
 *
 * Adding a criterion after seeing which class the previous one selected deserves
 * scrutiny, so the check that matters: the high selection is IDENTICAL with and
 * without it. Only the low end moves, which is where the defect was. The change
 * cannot have been chosen to reach a particular class at the top.
 *
 * The consequence is not cosmetic. Both selected classes are also the input to
 * the testing module, and a class whose every method throws unconditionally
 * admits no test beyond asserting that it throws.
 *
 * THE SELECTION RULE
 *
 * First name Ilie -> I is the 9th letter -> 9 mod 5 = 4 -> case 4, "classes
 * first +4 and last -4": the fifth from the top and the fifth from the bottom of
 * the filtered ranking, zero-indexed as positions 4 and size-5.
 *
 * Ordering is by SonarCloud smells descending, then NCSS descending, then path.
 * The tie-break is not decoration: many clean classes share a smell count of
 * zero, so without a documented secondary key the low selection would depend on
 * file-system iteration order and would not reproduce.
 */
public class ClassSelector {

    private static final Path CLASSES = Path.of("data", "m4_classes.csv");
    private static final Path SONAR   = Path.of("data", "m4_sonar_smells.csv");
    private static final Path RANKED  = Path.of("data", "m4_ranked.csv");
    private static final Path OUT     = Path.of("data", "m4_selection.csv");

    /** Ilie -> I = 9 -> 9 mod 5 = 4 -> "first +4 and last -4". */
    private static final int OFFSET = 4;

    public record Candidate(String path, String module, String name, String kind,
                            int sonar, int pmd, int loc, int ncss, int methods) {

        /** NCSS per method - the "simple methods" half of the size criterion. */
        double linesPerMethod() {
            return methods == 0 ? 0.0 : (double) ncss / methods;
        }
    }

    public static void main(String[] args) throws IOException {

        Map<String, Integer> sonar = readSonar();
        List<Candidate> joined = join(sonar);

        System.out.printf("%njoined %d classes (SonarCloud had %d java files)%n%n",
                joined.size(), sonar.size());

        agreement(joined);

        List<Candidate> concrete = joined.stream()
                .filter(c -> c.kind().equals("class"))
                .toList();

        List<Candidate> withMethods = concrete.stream()
                .filter(c -> c.methods() > 0)
                .toList();

        int ncssCut = percentile(concrete, Candidate::ncss, 50);
        int methodCut = percentile(concrete, Candidate::methods, 50);
        double ratioCut = medianRatio(withMethods);

        System.out.println("=== filter (step 8.2), all thresholds are this release's medians ===");
        System.out.printf("  concrete classes                    : %d of %d%n",
                concrete.size(), joined.size());
        System.out.printf("  median NCSS                         : %d%n", ncssCut);
        System.out.printf("  median methods                      : %d%n", methodCut);
        System.out.printf("  median NCSS per method              : %.2f%n", ratioCut);

        List<Candidate> eligible = new ArrayList<>(concrete.stream()
                .filter(c -> c.ncss() >= ncssCut)
                .filter(c -> c.methods() >= methodCut)
                .filter(c -> c.linesPerMethod() >= ratioCut)
                .toList());

        eligible.sort(Comparator.comparingInt(Candidate::sonar).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::ncss).reversed())
                .thenComparing(Candidate::path));

        System.out.printf("  eligible after all three criteria   : %d%n", eligible.size());
        long clean = eligible.stream().filter(c -> c.sonar() == 0).count();
        System.out.printf("  of which with zero smells           : %d%n%n", clean);

        if (eligible.size() < 2 * OFFSET + 2) {
            throw new IllegalStateException("not enough eligible classes to select from");
        }

        Candidate top = eligible.get(OFFSET);
        Candidate bottom = eligible.get(eligible.size() - 1 - OFFSET);

        writeRanked(eligible);
        writeSelection(top, bottom, eligible.size());
        report(eligible, top, bottom);
    }

    /* ---------------- input ---------------- */

    private static Map<String, Integer> readSonar() throws IOException {
        Map<String, Integer> out = new HashMap<>();
        List<List<String>> rows = Csv.read(SONAR);
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() >= 2 && !r.get(0).isBlank()) {
                out.put(r.get(0).trim(), Integer.parseInt(r.get(1).trim()));
            }
        }
        return out;
    }

    /**
     * Inner join on the repository-relative path. A class missing from either
     * side is dropped rather than defaulted: absent from SonarCloud means out of
     * its analysis scope, and absent from the PMD pass means it is not a
     * src/main source file. Either way it is not a candidate.
     */
    private static List<Candidate> join(Map<String, Integer> sonar) throws IOException {

        List<Candidate> out = new ArrayList<>();
        int unmatched = 0;

        List<List<String>> rows = Csv.read(CLASSES);
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() < 9) {
                continue;
            }
            String path = r.get(8).trim();
            Integer s = sonar.get(path);
            if (s == null) {
                unmatched++;
                continue;
            }
            out.add(new Candidate(path, r.get(1).trim(), r.get(2).trim(), r.get(3).trim(),
                    s,
                    Integer.parseInt(r.get(4).trim()),
                    Integer.parseInt(r.get(5).trim()),
                    Integer.parseInt(r.get(6).trim()),
                    Integer.parseInt(r.get(7).trim())));
        }

        System.out.printf("classes measured by PMD but absent from SonarCloud: %d%n", unmatched);
        return out;
    }

    /* ---------------- cross-check ---------------- */

    /**
     * Spearman rank correlation between the two detectors over the joined set.
     * A high value means the ranking is a property of the code rather than of
     * SonarCloud, so the selection would survive changing tools.
     */
    private static void agreement(List<Candidate> joined) {

        double[] a = joined.stream().mapToDouble(Candidate::sonar).toArray();
        double[] b = joined.stream().mapToDouble(Candidate::pmd).toArray();

        System.out.printf("=== detector agreement ===%n  Spearman(SonarCloud, PMD) over %d "
                + "classes = %.3f%n%n", joined.size(), pearson(ranks(a), ranks(b)));
    }

    private static double[] ranks(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));
        double[] result = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            double rank = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                result[order[k]] = rank;
            }
            i = j + 1;
        }
        return result;
    }

    private static double pearson(double[] x, double[] y) {
        double mx = 0;
        double my = 0;
        for (int i = 0; i < x.length; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= x.length;
        my /= y.length;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return (sxx == 0 || syy == 0) ? Double.NaN : sxy / Math.sqrt(sxx * syy);
    }

    /* ---------------- output ---------------- */

    private static void writeRanked(List<Candidate> eligible) throws IOException {
        Files.createDirectories(RANKED.getParent());
        try (Writer out = Files.newBufferedWriter(RANKED, StandardCharsets.UTF_8)) {
            out.write("Rank,Module,Class,SonarSmells,PmdSmells,LOC,NCSS,Methods,"
                    + "LinesPerMethod,Path\n");
            for (int i = 0; i < eligible.size(); i++) {
                Candidate c = eligible.get(i);
                out.write(String.format(Locale.US, "%d,%s,%s,%d,%d,%d,%d,%d,%.2f,%s%n",
                        i + 1, c.module(), c.name(), c.sonar(), c.pmd(),
                        c.loc(), c.ncss(), c.methods(), c.linesPerMethod(), c.path()));
            }
        }
        System.out.println("wrote " + RANKED.toAbsolutePath());
    }

    private static void writeSelection(Candidate top, Candidate bottom, int size)
            throws IOException {
        try (Writer out = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            out.write("Role,Position,Module,Class,SonarSmells,PmdSmells,LOC,NCSS,Methods,"
                    + "LinesPerMethod,Path\n");
            row(out, "C_0 high (first +" + OFFSET + ")", OFFSET + 1, top);
            row(out, "C_0 low (last -" + OFFSET + ")", size - OFFSET, bottom);
        }
        System.out.println("wrote " + OUT.toAbsolutePath());
    }

    private static void row(Writer out, String role, int position, Candidate c)
            throws IOException {
        out.write(String.format(Locale.US, "%s,%d,%s,%s,%d,%d,%d,%d,%d,%.2f,%s%n",
                role, position, c.module(), c.name(), c.sonar(), c.pmd(),
                c.loc(), c.ncss(), c.methods(), c.linesPerMethod(), c.path()));
    }

    private static void report(List<Candidate> eligible, Candidate top, Candidate bottom) {

        System.out.println("=== eligible ranking, both ends ===");
        System.out.printf("  %-4s %-36s %7s %7s %6s %8s %8s%n",
                "#", "class", "sonar", "pmd", "ncss", "methods", "l/method");
        for (int i = 0; i < Math.min(8, eligible.size()); i++) {
            print(i + 1, eligible.get(i), i == OFFSET);
        }
        System.out.println("  ...");
        for (int i = Math.max(0, eligible.size() - 8); i < eligible.size(); i++) {
            print(i + 1, eligible.get(i), i == eligible.size() - 1 - OFFSET);
        }

        System.out.println();
        System.out.println("=== SELECTED ===");
        describe("C_0 high", top);
        describe("C_0 low ", bottom);
    }

    private static void describe(String role, Candidate c) {
        System.out.printf("  %s  %s%n            %s%n            sonar %d, pmd %d, "
                        + "%d NCSS, %d methods, %.1f lines/method%n",
                role, c.name(), c.path(), c.sonar(), c.pmd(),
                c.ncss(), c.methods(), c.linesPerMethod());
    }

    private static void print(int position, Candidate c, boolean selected) {
        System.out.printf("  %-4d %-36s %7d %7d %6d %8d %8.1f%s%n",
                position, c.name(), c.sonar(), c.pmd(), c.ncss(), c.methods(),
                c.linesPerMethod(), selected ? "   <== SELECTED" : "");
    }

    private static int percentile(List<Candidate> rows,
                                  java.util.function.ToIntFunction<Candidate> f, int p) {
        if (rows.isEmpty()) {
            return 0;
        }
        int[] values = rows.stream().mapToInt(f).sorted().toArray();
        return values[Math.min(values.length - 1, (int) Math.round(p / 100.0 * values.length))];
    }

    private static double medianRatio(List<Candidate> rows) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        double[] values = rows.stream().mapToDouble(Candidate::linesPerMethod).sorted().toArray();
        return values[values.length / 2];
    }
}