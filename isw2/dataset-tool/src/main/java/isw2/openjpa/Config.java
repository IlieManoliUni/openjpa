package isw2.openjpa;

/**
 * All project-wide constants in one place.
 * Every value here is a decision that must be justified in the report.
 */
public final class Config {

    private Config() { }   // utility class: never instantiated

    /** Apache's public JIRA REST endpoint (API version 2). */
    public static final String JIRA_BASE = "https://issues.apache.org/jira/rest/api/2";

    /** Our assigned project. Last-name initial M -> 13 mod 6 = 1 -> OPENJPA. */
    public static final String PROJECT_KEY = "OPENJPA";

    /** Falessi M1: "ignore last 66% of releases" -> keep the first third. */
    public static final double KEEP_FRACTION = 1.0 / 3.0;
}