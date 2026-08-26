package isw2.openjpa;

import org.json.JSONArray;
import org.json.JSONObject;

public class Main {

    public static void main(String[] args) throws Exception {
        String url = Config.JIRA_BASE + "/project/" + Config.PROJECT_KEY;
        JSONObject project = JiraClient.getJson(url);
        JSONArray versions = project.getJSONArray("versions");

        int total = versions.length();
        int withDate = 0, released = 0, datedNotReleased = 0, releasedNoDate = 0;

        for (int i = 0; i < total; i++) {
            JSONObject v = versions.getJSONObject(i);
            boolean hasDate    = v.has("releaseDate");
            boolean isReleased = v.optBoolean("released", false);

            if (hasDate)    withDate++;
            if (isReleased) released++;

            if (hasDate && !isReleased) {
                datedNotReleased++;
                System.out.println("  dated but NOT released : "
                        + v.getString("name") + "  " + v.getString("releaseDate"));
            }
            if (isReleased && !hasDate) {
                releasedNoDate++;
                System.out.println("  released but NO date   : " + v.getString("name"));
            }
        }

        System.out.println("total versions         : " + total);
        System.out.println("with releaseDate       : " + withDate);
        System.out.println("released == true       : " + released);
        System.out.println("dated but not released : " + datedNotReleased);
        System.out.println("released but undated   : " + releasedNoDate);
    }
}