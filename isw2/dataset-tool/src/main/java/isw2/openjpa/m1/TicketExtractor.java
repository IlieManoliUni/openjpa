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

/**
 * Milestone 1, labelling step: retrieve fixed bug tickets from JIRA.
 *
 * Based on RetrieveTicketsID.java provided in class. The query, the paging
 * arithmetic and the field list are unchanged; the provided version prints the ticket
 * keys to the console, this one writes the fields Proportion needs to a CSV.
 */
public class TicketExtractor {

    private static final Path OUT = Path.of("data", "tickets.csv");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUT.getParent());

        int i = 0, j, total = 1;
        int withAffected = 0;

        try (Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            w.write("Key,Created,ResolutionDate,AffectedVersions\n");

            do {
                j = i + 1000;
                String url = Config.JIRA_BASE + "/search"
                        + "?jql=project=%22" + Config.PROJECT_KEY + "%22"
                        + "AND%22issueType%22=%22Bug%22"
                        + "AND(%22status%22=%22closed%22OR%22status%22=%22resolved%22)"
                        + "AND%22resolution%22=%22fixed%22"
                        + "&fields=key,resolutiondate,versions,created"
                        + "&startAt=" + i + "&maxResults=" + j;

                JSONObject json = JiraClient.getJson(url);
                JSONArray issues = json.getJSONArray("issues");
                total = json.getInt("total");

                if (i == 0) {
                    System.out.println("tickets matching the query: " + total);
                }

                for (; i < total && i < j; i++) {
                    int idx = i % 1000;
                    if (idx >= issues.length()) {
                        throw new IllegalStateException(
                                "page returned " + issues.length()
                                        + " issues, index " + idx + " out of range");
                    }
                    JSONObject issue  = issues.getJSONObject(idx);
                    JSONObject fields = issue.getJSONObject("fields");

                    String key      = issue.getString("key");
                    String created  = fields.optString("created", "");
                    String resolved = fields.optString("resolutiondate", "");

                    // "versions" is JIRA's Affects Version/s, NOT Fix Version/s.
                    // Semicolon-separated so it stays inside one CSV field.
                    StringBuilder av = new StringBuilder();
                    JSONArray versions = fields.optJSONArray("versions");
                    if (versions != null) {
                        for (int v = 0; v < versions.length(); v++) {
                            if (v > 0) av.append(';');
                            av.append(versions.getJSONObject(v).getString("name"));
                        }
                        if (versions.length() > 0) withAffected++;
                    }

                    w.write(key + "," + created + "," + resolved + "," + av + "\n");
                }
            } while (i < total);
        }

        System.out.println("tickets written        : " + i);
        System.out.println("with affected versions : " + withAffected
                + "   (these are the ones that give P)");
        System.out.println("wrote " + OUT.toAbsolutePath());
    }
}