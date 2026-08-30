package isw2.openjpa;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NSmells for the last release, from SonarCloud.
 *
 * Uses measures/component_tree rather than the issues endpoint: it returns one
 * aggregated count per file, so it is not subject to the 10,000-issue cap that
 * a project this size would hit immediately.
 *
 * Requires the SONAR_TOKEN environment variable (set by env.ps1 from a file
 * kept outside the repository).
 */
public class SonarSmells {

    private static final Path OUT = Path.of("data", "smells.csv");
    private static final int PAGE_SIZE = 500;

    public static void main(String[] args) throws IOException, InterruptedException {

        String token = readToken();
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "SONAR_TOKEN is not set. Run '. env.ps1' first.");
        }

        HttpClient http = HttpClient.newHttpClient();
        Map<String, Integer> smellsByPath = new LinkedHashMap<>();

        int page = 1, total = -1, fetched = 0;
        do {
            String url = Config.SONAR_API + "/measures/component_tree"
                    + "?component=" + Config.SONAR_PROJECT_KEY
                    + "&metricKeys=code_smells"
                    + "&qualifiers=FIL"
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
                        + " for page " + page + ": " + response.body());
            }

            JSONObject json = new JSONObject(response.body());
            total = json.getJSONObject("paging").getInt("total");

            JSONArray components = json.getJSONArray("components");
            for (int i = 0; i < components.length(); i++) {
                JSONObject c = components.getJSONObject(i);
                String path = c.optString("path", "");
                if (!path.endsWith(".java")) {
                    continue;                       // only Java classes go in the dataset
                }
                smellsByPath.put(path, measureValue(c, "code_smells"));
            }
            fetched += components.length();
            System.out.printf("page %2d: %4d components (%d / %d)%n",
                    page, components.length(), fetched, total);
            page++;
        } while (fetched < total);

        Files.createDirectories(OUT.getParent());
        try (Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            w.write("Path,CodeSmells\n");
            for (Map.Entry<String, Integer> e : smellsByPath.entrySet()) {
                w.write(e.getKey() + "," + e.getValue() + "\n");
            }
        }

        int sum = smellsByPath.values().stream().mapToInt(Integer::intValue).sum();
        long zero = smellsByPath.values().stream().filter(v -> v == 0).count();

        System.out.println();
        System.out.println("java files            : " + smellsByPath.size());
        System.out.println("total code smells     : " + sum);
        System.out.println("files with zero smells: " + zero);
        System.out.println("wrote " + OUT.toAbsolutePath());
    }

    /** A component omits a measure entirely when its value is zero. */
    private static int measureValue(JSONObject component, String metric) {
        JSONArray measures = component.optJSONArray("measures");
        if (measures == null) {
            return 0;
        }
        for (int i = 0; i < measures.length(); i++) {
            JSONObject m = measures.getJSONObject(i);
            if (metric.equals(m.optString("metric"))) {
                return Integer.parseInt(m.optString("value", "0"));
            }
        }
        return 0;
    }

    /**
     * The token comes from $SONAR_TOKEN, or failing that from sonar-token.txt in
     * the workspace root (three levels above dataset-tool). That file lives outside
     * the git repository and is never committed.
     */
    private static String readToken() throws IOException {
        String env = System.getenv("SONAR_TOKEN");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path file = Path.of("..", "..", "..", "sonar-token.txt");
        if (Files.exists(file)) {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        }
        throw new IllegalStateException(
                "No SonarCloud token. Set $SONAR_TOKEN, or place it in "
                        + file.toAbsolutePath().normalize());
    }
}