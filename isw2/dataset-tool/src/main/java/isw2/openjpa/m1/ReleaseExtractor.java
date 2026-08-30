package isw2.openjpa.m1;

import isw2.openjpa.Config;
import isw2.openjpa.util.JiraClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milestone 1: "find releases and related dates".
 *
 * Based on getReleaseInfo.java provided in class.
 * Deviations C1-C4 are recorded in isw2/docs/PROVIDED_CODE_CHANGES.md.
 */
public class ReleaseExtractor {

    // C2: the provided code keys these by release date, which drops one of two
    // versions released on the same day. JIRA's version id is unique per version.
    private static final Map<String, String>    releaseNames = new HashMap<>();
    private static final Map<String, LocalDate> releaseDates = new HashMap<>();
    private static final List<String>           releases     = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        String url = Config.JIRA_BASE + "/project/" + Config.PROJECT_KEY;   // C1
        JSONObject json = JiraClient.getJson(url);
        JSONArray versions = json.getJSONArray("versions");

        // Ignores versions with no release date, exactly as the provided code does.
        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            if (v.has("releaseDate") && v.has("name") && v.has("id")) {
                addRelease(v.getString("id"), v.getString("name"), v.getString("releaseDate"));
            }
        }

        // C3: date first, then version number, so same-day releases have a defined order.
        releases.sort(Comparator
                .comparing((String id) -> releaseDates.get(id))
                .thenComparing(id -> releaseNames.get(id), ReleaseExtractor::compareVersions));

        writeCsv();
        System.out.println("releases found: " + releases.size());
    }

    private static void addRelease(String id, String name, String strDate) {
        if (!releases.contains(id)) {
            releases.add(id);
        }
        releaseNames.put(id, name);
        releaseDates.put(id, LocalDate.parse(strDate));
    }

    /** Compares "2.4.3" against "3.0.0" segment by segment, numerically. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? numericPrefix(pa[i]) : 0;
            int nb = i < pb.length ? numericPrefix(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return a.compareTo(b);
    }

    private static int numericPrefix(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(segment.substring(0, end));
    }

    private static void writeCsv() throws IOException {
        Path out = Path.of("data", Config.PROJECT_KEY + "VersionInfo.csv");   // C4
        Files.createDirectories(out.getParent());
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("Index,Version ID,Version Name,Date\n");
            for (int i = 0; i < releases.size(); i++) {
                String id = releases.get(i);
                w.write((i + 1) + "," + id + ","
                        + releaseNames.get(id) + ","
                        + releaseDates.get(id) + "\n");
            }
        }
        System.out.println("wrote " + out.toAbsolutePath());
    }
}