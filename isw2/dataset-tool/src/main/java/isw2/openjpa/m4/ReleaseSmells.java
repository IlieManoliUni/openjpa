package isw2.openjpa.m4;

import isw2.openjpa.Config;
import isw2.openjpa.m1.SonarSmells;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
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
import java.util.Map;

/**
 * Per-class code smells for the last release of the project, from SonarCloud.
 *
 * This is the ranking Milestone 4 selects from. SonarCloud rather than PMD,
 * because the milestone fixes the detector for us: the refactoring prompt must
 * report SonarCloud diagnostics, and the results section asks whether the
 * refactored class still has smells and whether they are old or new. That
 * before/after comparison only means something if both sides come from the same
 * tool, so the ranking has to come from it too - selecting with one detector and
 * defining the work with another would be incoherent.
 *
 * Same endpoint, paging and authentication as the Milestone 1 fetcher, reusing
 * its token resolution and measure extraction directly. measures/component_tree
 * rather than the issues endpoint, because it returns one aggregated count per
 * file and so is not subject to the 10,000-issue cap a project this size hits
 * immediately.
 *
 * WRITES A DIFFERENT FILE ON PURPOSE
 *
 * data/m4_sonar_smells.csv, not data/smells.csv. The Milestone 1 file records an
 * analysis of master (4.2.0-SNAPSHOT) and is the evidence behind that
 * milestone's write-up. The project key now holds the 4.1.1 analysis, so running
 * the Milestone 1 fetcher today would silently replace master numbers with
 * release numbers under a filename that claims to be the former.
 *
 * VERIFIES WHAT IT IS READING
 *
 * Because one project key now holds a different analysis than it did, the class
 * first asks SonarCloud which version was last analysed and prints it. If that
 * does not say 4.1.1, the numbers below belong to something else and the run
 * should be stopped rather than interpreted.
 */
public class ReleaseSmells {

    private static final Path OUT = Path.of("data", "m4_sonar_smells.csv");
    private static final int PAGE_SIZE = 500;

    /** The release the analysis is expected to describe. */
    private static final String EXPECTED_VERSION = "4.1.1";

    public static void main(String[] args) throws IOException, InterruptedException {

        String token = SonarSmells.readToken();
        HttpClient http = HttpClient.newHttpClient();

        reportLastAnalysis(http, token);

        Map<String, Integer> smellsByPath = new LinkedHashMap<>();
        int page = 1;
        int total = -1;
        int fetched = 0;

        do {
            String url = Config.SONAR_API + "/measures/component_tree"
                    + "?component=" + Config.SONAR_PROJECT_KEY
                    + "&metricKeys=code_smells"
                    + "&qualifiers=FIL"
                    + "&ps=" + PAGE_SIZE
                    + "&p=" + page;

            JSONObject json = get(http, token, url);

            total = json.getJSONObject("paging").getInt("total");
            JSONArray components = json.getJSONArray("components");

            for (int i = 0; i < components.length(); i++) {
                JSONObject c = components.getJSONObject(i);
                String path = c.optString("path", "");
                if (!path.endsWith(".java")) {
                    continue;
                }
                smellsByPath.put(path, SonarSmells.measureValue(c, "code_smells"));
            }

            fetched += components.length();
            System.out.printf("page %2d: %4d components (%d / %d)%n",
                    page, components.length(), fetched, total);
            page++;

        } while (fetched < total);

        write(smellsByPath);
        report(smellsByPath);
    }

    /**
     * Asks which analysis the project key currently holds. This is a guard, not
     * a decoration: the same key held a master analysis until the 4.1.1 scan
     * replaced it, and reading the wrong one would be invisible in the numbers.
     */
    private static void reportLastAnalysis(HttpClient http, String token) {
        try {
            JSONObject json = get(http, token, Config.SONAR_API
                    + "/project_analyses/search"
                    + "?project=" + Config.SONAR_PROJECT_KEY
                    + "&ps=3");

            JSONArray analyses = json.optJSONArray("analyses");
            if (analyses == null || analyses.isEmpty()) {
                System.out.println("WARNING: SonarCloud reports no analyses for this project.");
                return;
            }

            System.out.println("=== analyses on record (most recent first) ===");
            for (int i = 0; i < analyses.length(); i++) {
                JSONObject a = analyses.getJSONObject(i);
                System.out.printf("  %-26s version %s%n",
                        a.optString("date", "?"), a.optString("projectVersion", "?"));
            }

            String latest = analyses.getJSONObject(0).optString("projectVersion", "?");
            if (latest.startsWith(EXPECTED_VERSION)) {
                System.out.printf("  -> latest analysis is %s, as expected%n%n", latest);
            } else {
                System.out.printf("%n  *** WARNING: latest analysis is \"%s\", expected %s.%n",
                        latest, EXPECTED_VERSION);
                System.out.println("  *** The counts below describe that version, not the "
                        + "last release. Stop and re-run the scan.%n");
            }
        } catch (Exception e) {
            // Not fatal: the metric fetch below is the deliverable, and this
            // endpoint can be restricted independently of the measures API.
            System.out.println("could not read the analysis history: " + e.getMessage());
        }
    }

    private static JSONObject get(HttpClient http, String token, String url)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("SonarCloud returned HTTP " + response.statusCode()
                    + " for " + url + ": " + response.body());
        }
        return new JSONObject(response.body());
    }

    private static void write(Map<String, Integer> smellsByPath) throws IOException {
        Files.createDirectories(OUT.getParent());
        try (Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            w.write("Path,CodeSmells\n");
            for (Map.Entry<String, Integer> e : smellsByPath.entrySet()) {
                w.write(e.getKey() + "," + e.getValue() + "\n");
            }
        }
        System.out.println("wrote " + OUT.toAbsolutePath());
    }

    private static void report(Map<String, Integer> smellsByPath) {

        int sum = smellsByPath.values().stream().mapToInt(Integer::intValue).sum();
        long zero = smellsByPath.values().stream().filter(v -> v == 0).count();

        System.out.println();
        System.out.println("java files            : " + smellsByPath.size());
        System.out.println("total code smells     : " + sum);
        System.out.println("files with zero smells: " + zero);

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(smellsByPath.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));

        System.out.println();
        System.out.println("=== top 15 by code smells ===");
        System.out.printf("  %-4s %-40s %7s%n", "#", "class", "smells");
        for (int i = 0; i < Math.min(15, ranked.size()); i++) {
            String path = ranked.get(i).getKey();
            System.out.printf("  %-4d %-40s %7d%n", i + 1,
                    path.substring(path.lastIndexOf('/') + 1), ranked.get(i).getValue());
        }

        System.out.println();
        System.out.println("For comparison, the same classes on master (4.2.0-SNAPSHOT) were:");
        System.out.println("  DBDictionary 298, PCEnhancer 228, XMLPersistenceMetaDataParser 194,");
        System.out.println("  BrokerImpl 184, FieldMetaData 147, SelectImpl 134.");
        System.out.println("Close but not identical is what 4.1.1 should look like - 326 commits");
        System.out.println("separate the two, and 275 of the analysed files changed between them.");
    }
}