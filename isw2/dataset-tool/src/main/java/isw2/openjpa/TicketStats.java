package isw2.openjpa;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Milestone 1 diagnostic: how many tickets carry a usable Injected Version,
 * and how many need one estimated by Proportion.
 *
 * Reads data/releases.csv and data/tickets.csv; produces the counts quoted in
 * the report, so they can be regenerated rather than transcribed.
 */
public class TicketStats {

    private static final Path RELEASES = Path.of("data", "releases.csv");
    private static final Path TICKETS  = Path.of("data", "tickets.csv");
    private static final Path OUT      = Path.of("data", "ticket_stats.csv");

    public static void main(String[] args) throws IOException {

        // Every version name that corresponds to a dated release.
        Set<String> dated = new HashSet<>();
        for (String line : dataLines(RELEASES)) {
            dated.add(field(line, 2));                 // Version Name
        }

        int total = 0, noAv = 0, allKnown = 0, partial = 0, noneKnown = 0;
        Map<String, Integer> unknown = new TreeMap<>();

        for (String line : dataLines(TICKETS)) {
            total++;
            String raw = field(line, 3);               // AffectedVersions

            if (raw.isEmpty()) {
                noAv++;
                continue;
            }

            String[] versions = raw.split(";");
            int known = 0;
            for (String v : versions) {
                if (dated.contains(v)) {
                    known++;
                } else {
                    unknown.merge(v, 1, Integer::sum);
                }
            }

            if (known == versions.length) {
                allKnown++;
            } else if (known > 0) {
                partial++;
            } else {
                noneKnown++;
            }
        }

        int usable = allKnown + partial;
        int needP  = noAv + noneKnown;

        System.out.println("dated releases known           : " + dated.size());
        System.out.println();
        System.out.printf("total tickets                          : %5d%n", total);
        System.out.printf("  no affected version at all           : %5d%n", noAv);
        System.out.printf("  has affected versions                : %5d%n", total - noAv);
        System.out.printf("     all are dated releases            : %5d%n", allKnown);
        System.out.printf("     some dated, some not (min ok)     : %5d%n", partial);
        System.out.printf("     NONE is a dated release (unusable): %5d%n", noneKnown);
        System.out.println();
        System.out.printf("=> usable IV from affected versions    : %5d%n", usable);
        System.out.printf("=> need P to estimate IV               : %5d%n", needP);

        System.out.println("\naffected-version names that are not dated releases:");
        unknown.forEach((name, count) ->
                System.out.printf("  %-16s %4d mentions%n", name, count));

        if (usable + needP != total) {
            throw new IllegalStateException("counts do not sum to the total");
        }

        try (Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            w.write("Measure,Value\n");
            w.write("TotalTickets," + total + "\n");
            w.write("NoAffectedVersion," + noAv + "\n");
            w.write("AllAffectedVersionsDated," + allKnown + "\n");
            w.write("SomeAffectedVersionsDated," + partial + "\n");
            w.write("NoAffectedVersionDated," + noneKnown + "\n");
            w.write("UsableIVFromAffectedVersions," + usable + "\n");
            w.write("NeedProportion," + needP + "\n");
        }
        System.out.println("\nwrote " + OUT.toAbsolutePath());
    }

    private static List<String> dataLines(Path p) throws IOException {
        List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
        return all.subList(1, all.size());             // drop the header row
    }

    private static String field(String line, int index) {
        String[] f = line.split(",", -1);              // -1 keeps trailing empty fields
        return index < f.length ? f[index].trim() : "";
    }
}