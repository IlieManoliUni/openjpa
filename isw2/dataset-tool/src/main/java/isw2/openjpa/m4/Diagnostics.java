package isw2.openjpa.m4;

import isw2.openjpa.Config;
import isw2.openjpa.m1.SonarSmells;
import isw2.openjpa.util.Csv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The SonarCloud issue list for the two selected classes.
 *
 * Milestone 4's refactoring prompt asks for the smells to be removed to be
 * stated explicitly, as reported by SonarCloud. Counts are not enough for that:
 * "126 code smells" tells a language model nothing it can act on, whereas
 * "S1192: define a constant instead of duplicating this literal, 18 times" does.
 *
 * A different endpoint from the one that produced the ranking. api/issues/search
 * returns individual issues rather than an aggregate, which is why the ranking
 * used measures/component_tree - that endpoint is not subject to the 10,000
 * issue cap this one has. Here the cap is irrelevant: two files, 127 issues
 * between them.
 *
 * SELF-CHECK
 *
 * The number of open CODE_SMELL issues returned for each class must equal the
 * code_smells measure recorded during selection - 126 and 1. The two numbers
 * come from different endpoints computing the same thing, so a mismatch means
 * one of them is filtered differently than assumed, and neither could then be
 * trusted in the report. It is checked rather than hoped for.
 *
 * Reads the classes from m4_selection.csv rather than naming them, so the
 * selection rule stays the single source of truth: change the filter and this
 * follows automatically.
 */
public class Diagnostics {

    private static final Path SELECTION = Path.of("data", "m4_selection.csv");
    private static final Path OUT_CSV   = Path.of("data", "m4_diagnostics.csv");
    private static final Path OUT_TXT   = Path.of("data", "m4_diagnostics.txt");

    private static final int PAGE_SIZE = 500;

    /** How many line numbers to list per rule before abbreviating. */
    private static final int MAX_LINES_SHOWN = 20;

    private record Selected(String role, String name, String path, int expected) { }

    private record Issue(String rule, String severity, String type, int line,
                         String effort, String message) { }

    public static void main(String[] args) throws IOException, InterruptedException {

        List<Selected> selected = readSelection();
        String token = SonarSmells.readToken();
        HttpClient http = HttpClient.newHttpClient();

        Map<Selected, List<Issue>> all = new LinkedHashMap<>();

        for (Selected s : selected) {
            System.out.printf("%n=== %s : %s ===%n", s.role(), s.name());
            List<Issue> issues = fetch(http, token, s.path());
            all.put(s, issues);

            System.out.printf("  issues returned      : %d%n", issues.size());
            System.out.printf("  code_smells measure  : %d%n", s.expected());
            if (issues.size() == s.expected()) {
                System.out.println("  -> match");
            } else {
                System.out.printf("  -> *** MISMATCH of %d. The issue list and the measure are "
                        + "not describing the same set.%n", issues.size() - s.expected());
            }
        }

        writeCsv(all);
        writeText(all);
        printSummary(all);
    }

    /* ---------------- fetch ---------------- */

    /**
     * All open code smells on one file.
     *
     * resolved=false excludes issues already marked fixed or won't-fix;
     * types=CODE_SMELL excludes bugs and vulnerabilities, which the code_smells
     * measure also excludes. Both filters exist to make this list comparable to
     * the measure the ranking used.
     */
    private static List<Issue> fetch(HttpClient http, String token, String path)
            throws IOException, InterruptedException {

        String component = Config.SONAR_PROJECT_KEY + ":" + path;
        List<Issue> issues = new ArrayList<>();

        int page = 1;
        int total = -1;
        int fetched = 0;

        do {
            String url = Config.SONAR_API + "/issues/search"
                    + "?componentKeys=" + URLEncoder.encode(component, StandardCharsets.UTF_8)
                    + "&types=CODE_SMELL"
                    + "&resolved=false"
                    + "&ps=" + PAGE_SIZE
                    + "&p=" + page;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("SonarCloud returned HTTP " + response.statusCode()
                        + " for " + component + ": " + response.body());
            }

            JSONObject json = new JSONObject(response.body());
            total = json.optInt("total", json.optJSONObject("paging") == null
                    ? 0 : json.getJSONObject("paging").optInt("total", 0));

            JSONArray array = json.getJSONArray("issues");
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                issues.add(new Issue(
                        o.optString("rule", "?"),
                        o.optString("severity", ""),
                        o.optString("type", ""),
                        o.optInt("line", -1),
                        o.optString("effort", ""),
                        o.optString("message", "").replace('\n', ' ').replace('\r', ' ')));
            }

            fetched += array.length();
            page++;

        } while (fetched < total && total > 0);

        return issues;
    }

    /* ---------------- input ---------------- */

    /**
     * Columns of m4_selection.csv:
     * Role,Position,Module,Class,SonarSmells,PmdSmells,LOC,NCSS,Methods,LinesPerMethod,Path
     */
    private static List<Selected> readSelection() throws IOException {
        List<Selected> out = new ArrayList<>();
        List<List<String>> rows = Csv.read(SELECTION);
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() < 11) {
                continue;
            }
            out.add(new Selected(r.get(0).trim(), r.get(3).trim(), r.get(10).trim(),
                    Integer.parseInt(r.get(4).trim())));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("no classes in " + SELECTION.toAbsolutePath()
                    + " - run ClassSelector first");
        }
        return out;
    }

    /* ---------------- output ---------------- */

    private static void writeCsv(Map<Selected, List<Issue>> all) throws IOException {
        Files.createDirectories(OUT_CSV.getParent());
        try (Writer out = Files.newBufferedWriter(OUT_CSV, StandardCharsets.UTF_8)) {
            out.write("Class,Rule,Severity,Type,Line,Effort,Message\n");
            for (Map.Entry<Selected, List<Issue>> e : all.entrySet()) {
                for (Issue i : e.getValue()) {
                    out.write(String.format(Locale.US, "%s,%s,%s,%s,%s,%s,\"%s\"%n",
                            e.getKey().name(), i.rule(), i.severity(), i.type(),
                            i.line() < 0 ? "" : String.valueOf(i.line()),
                            i.effort(), i.message().replace("\"", "\"\"")));
                }
            }
        }
        System.out.println("\nwrote " + OUT_CSV.toAbsolutePath());
    }

    /**
     * The paste-ready form. Grouped by rule and ordered by how often each fires,
     * because that is the order in which they are worth fixing and the order a
     * prompt should present them.
     */
    private static void writeText(Map<Selected, List<Issue>> all) throws IOException {
        try (Writer out = Files.newBufferedWriter(OUT_TXT, StandardCharsets.UTF_8)) {
            for (Map.Entry<Selected, List<Issue>> e : all.entrySet()) {

                Selected s = e.getKey();
                out.write(String.format("%s  (%s)%n", s.name(), s.role()));
                out.write(s.path() + "\n");
                out.write(String.format("%d SonarCloud code smells%n%n", e.getValue().size()));

                for (Map.Entry<String, List<Issue>> g : groupByRule(e.getValue()).entrySet()) {
                    List<Issue> g2 = g.getValue();
                    out.write(String.format("%-14s x%-4d %s%n",
                            g.getKey(), g2.size(), g2.get(0).message()));
                    out.write("               lines: " + lines(g2) + "\n\n");
                }
                out.write("\n" + "-".repeat(78) + "\n\n");
            }
        }
        System.out.println("wrote " + OUT_TXT.toAbsolutePath());
    }

    private static Map<String, List<Issue>> groupByRule(List<Issue> issues) {
        Map<String, List<Issue>> byRule = new TreeMap<>();
        for (Issue i : issues) {
            byRule.computeIfAbsent(i.rule(), k -> new ArrayList<>()).add(i);
        }
        // most frequent first: that is the order worth fixing them in
        Map<String, List<Issue>> ordered = new LinkedHashMap<>();
        byRule.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<Issue>>>comparingInt(
                                en -> en.getValue().size()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(en -> ordered.put(en.getKey(), en.getValue()));
        return ordered;
    }

    private static String lines(List<Issue> issues) {
        List<Integer> ls = issues.stream()
                .map(Issue::line).filter(l -> l > 0).sorted().toList();
        if (ls.isEmpty()) {
            return "(file level)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_LINES_SHOWN, ls.size()); i++) {
            sb.append(i > 0 ? ", " : "").append(ls.get(i));
        }
        if (ls.size() > MAX_LINES_SHOWN) {
            sb.append(", ... (").append(ls.size() - MAX_LINES_SHOWN).append(" more)");
        }
        return sb.toString();
    }

    private static void printSummary(Map<Selected, List<Issue>> all) {
        for (Map.Entry<Selected, List<Issue>> e : all.entrySet()) {
            System.out.printf("%n=== %s : rules by frequency ===%n", e.getKey().name());
            System.out.printf("  %-14s %6s  %s%n", "rule", "count", "message");
            groupByRule(e.getValue()).forEach((rule, issues) -> {
                String m = issues.get(0).message();
                System.out.printf("  %-14s %6d  %s%n", rule, issues.size(),
                        m.length() > 84 ? m.substring(0, 81) + "..." : m);
            });
        }
    }
}