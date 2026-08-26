package isw2.openjpa;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal JIRA REST reader: fetch a URL, parse the body as JSON. */
public final class JiraClient {

    private JiraClient() { }

    public static JSONObject getJson(String url) throws IOException {
        URL endpoint = URI.create(url).toURL();
        try (InputStream in = endpoint.openStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new JSONObject(body);
        }
    }
}