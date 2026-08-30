package isw2.openjpa;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milestone 1, final step: join metrics, NSmells and bugginess into the dataset.
 *
 *   dataset.csv   one row per (class, release), as the Milestone 1 slide specifies
 *   dataset.arff  the same rows for Weka, features only
 */
public class DatasetBuilder {

    private static final Path METRICS = Path.of("data", "metrics.csv");
    private static final Path SMELLS  = Path.of("data", "pmd_smells.csv");
    private static final Path BUGGY   = Path.of("data", "buggy.csv");
    private static final Path OUT_CSV = Path.of("data", "dataset.csv");
    private static final Path OUT_ARFF= Path.of("data", "dataset.arff");

    /** The 16 metrics plus NSmells, in order. These are the Weka features. */
    private static final String[] FEATURES = {
            "LOC", "LOC_touched", "NR", "NFix", "NAuth", "LOC_added",
            "MAX_LOC_added", "AVG_LOC_added", "Churn", "MAX_Churn", "AVG_Churn",
            "ChgSetSize", "MAX_ChgSet", "AVG_ChgSet", "Age", "WeightedAge", "NSmells"
    };

    public static void main(String[] args) throws IOException {

        List<String[]> metrics = readCsv(METRICS);
        String[] metricHeader = metrics.remove(0);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < metricHeader.length; i++) {
            col.put(metricHeader[i].trim(), i);
        }

        Map<String, Integer> smells = new HashMap<>();
        List<String[]> smellRows = readCsv(SMELLS);
        smellRows.remove(0);
        for (String[] r : smellRows) {
            smells.put(key(r[0], r[1]), Integer.parseInt(r[2].trim()));
        }

        Set<String> buggy = new HashSet<>();
        List<String[]> buggyRows = readCsv(BUGGY);
        buggyRows.remove(0);
        for (String[] r : buggyRows) {
            buggy.add(key(r[0], r[1]));
        }

        System.out.println("metrics rows   : " + metrics.size());
        System.out.println("smell entries  : " + smells.size());
        System.out.println("buggy pairs    : " + buggy.size());

        int rows = 0, buggyRowsOut = 0, missingSmells = 0;

        Files.createDirectories(OUT_CSV.getParent());
        try (Writer csv  = Files.newBufferedWriter(OUT_CSV,  StandardCharsets.UTF_8);
             Writer arff = Files.newBufferedWriter(OUT_ARFF, StandardCharsets.UTF_8)) {

            csv.write("Project,Version,FileName");
            for (String f : FEATURES) {
                csv.write("," + f);
            }
            csv.write(",Buggy\n");

            arff.write("@relation " + Config.PROJECT_KEY.toLowerCase() + "_defects\n\n");
            for (String f : FEATURES) {
                arff.write("@attribute " + f + " numeric\n");
            }
            arff.write("@attribute Buggy {no,yes}\n\n@data\n");

            for (String[] m : metrics) {
                String version = m[col.get("Version")].trim();
                String file    = m[col.get("FileName")].trim();
                String k       = key(version, file);

                Integer ns = smells.get(k);
                if (ns == null) {
                    ns = 0;
                    missingSmells++;
                }
                String label = buggy.contains(k) ? "yes" : "no";
                if (label.equals("yes")) {
                    buggyRowsOut++;
                }

                StringBuilder values = new StringBuilder();
                for (String f : FEATURES) {
                    if (values.length() > 0) {
                        values.append(',');
                    }
                    values.append(f.equals("NSmells") ? String.valueOf(ns)
                            : m[col.get(f)].trim());
                }

                csv.write(Config.PROJECT_KEY + "," + version + "," + file
                        + "," + values + "," + label + "\n");
                arff.write(values + "," + label + "\n");
                rows++;
            }
        }

        System.out.println("\nrows written            : " + rows);
        System.out.println("buggy                   : " + buggyRowsOut
                + String.format("  (%.1f%%)", 100.0 * buggyRowsOut / rows));
        System.out.println("rows with no PMD entry  : " + missingSmells);
        System.out.println("wrote " + OUT_CSV.toAbsolutePath());
        System.out.println("wrote " + OUT_ARFF.toAbsolutePath());
    }

    private static String key(String version, String file) {
        return version.trim() + "\u0000" + file.trim();
    }

    private static List<String[]> readCsv(Path p) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            out.add(line.split(",", -1));
        }
        return out;
    }
}