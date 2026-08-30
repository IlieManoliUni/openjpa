package isw2.openjpa.m1;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milestone 1, labelling step: "Apply Proportion (Total)".
 *
 * From 8_Presentation Proportion.pdf:
 *     P            = (FV - IV) / (FV - OV)
 *     predicted IV = FV - (FV - OV) * P
 * and an AV is "consistent" when the oldest AV is not after the OV, i.e. IV <= OV.
 *
 * "Total" (Milestone 1 slide) = compute one P over all tickets that have a usable
 * IV, then apply it to those that do not.
 *
 * OV is the last release on or before the report date - the released version the
 * reporter was running. FV is the first release on or after the resolution date -
 * the first release that contains the fix. See docs/PROVIDED_CODE_CHANGES.md.
 */
public class Proportion {

    private static final Path RELEASES = Path.of("data", "releases.csv");
    private static final Path TICKETS  = Path.of("data", "tickets.csv");
    private static final Path OUT      = Path.of("data", "proportion.csv");

    private record Rel(int index, String name, LocalDate date) { }

    /** ov/fv/iv are 1-based release indices; 0 means "not determined". */
    private static final class Ticket {
        String key;
        LocalDate created, resolved;
        int ov, fv, ivFromAv, iv;
        String ivSource = "none";
        boolean consistent;
    }

    public static void main(String[] args) throws IOException {

        List<Rel> releases = readReleases();
        Map<String, Integer> indexByName = new HashMap<>();
        for (Rel r : releases) {
            indexByName.put(r.name(), r.index());
        }

        List<Ticket> tickets = readTickets(releases, indexByName);

        // ---- pass 1: compute P over tickets with an observed, consistent IV ----
        double sum = 0;
        int pool = 0;
        int noOv = 0, noFv = 0, inconsistent = 0, fvEqualsOv = 0;

        for (Ticket t : tickets) {
            if (t.ov == 0) { noOv++; continue; }
            if (t.fv == 0) { noFv++; continue; }
            if (t.ivFromAv == 0) { continue; }              // no AV: nothing to learn from

            // his consistency rule: the oldest AV must not be after the OV
            if (!(t.ivFromAv <= t.ov && t.ov <= t.fv && t.ivFromAv < t.fv)) {
                inconsistent++;
                continue;
            }
            if (t.fv == t.ov) { fvEqualsOv++; continue; }   // P undefined: divide by zero

            t.consistent = true;
            sum += (double) (t.fv - t.ivFromAv) / (t.fv - t.ov);
            pool++;
        }

        double p = pool == 0 ? 1.0 : sum / pool;

        // ---- pass 2: assign an IV to every ticket ----
        int fromAv = 0, predicted = 0, unusable = 0;
        for (Ticket t : tickets) {
            if (t.ov == 0 || t.fv == 0) {
                t.ivSource = "unusable";
                unusable++;
                continue;
            }

            if (t.ivFromAv > 0 && t.ivFromAv <= t.ov) {
                t.iv = t.ivFromAv;
                t.ivSource = "AV";
                fromAv++;
            } else {
                int est = (int) Math.round(t.fv - (t.fv - t.ov) * p);
                t.iv = Math.max(1, Math.min(est, t.ov));    // clamp to [1, OV]
                t.ivSource = "predicted";
                predicted++;
            }
        }

        // ---- report ----
        System.out.printf("tickets                                   : %5d%n", tickets.size());
        System.out.printf("  no OV (reported before the first release): %5d%n", noOv);
        System.out.printf("  no FV (resolved after the last release)  : %5d%n", noFv);
        System.out.printf("  AV present but inconsistent              : %5d%n", inconsistent);
        System.out.printf("  FV == OV (P undefined)                   : %5d%n", fvEqualsOv);
        System.out.printf("  clean AV tickets used to compute P       : %5d%n", pool);
        System.out.println();
        System.out.printf("P = %.3f%n", p);
        System.out.println();
        System.out.printf("IV taken from AV                          : %5d%n", fromAv);
        System.out.printf("IV predicted via P                        : %5d%n", predicted);
        System.out.printf("unusable (no OV or no FV)                 : %5d%n", unusable);

        if (fromAv + predicted + unusable != tickets.size()) {
            throw new IllegalStateException("ticket buckets do not sum to the total");
        }

        try (Writer w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8)) {
            w.write("Key,Created,Resolved,OV,FV,IVfromAV,IV,IVSource,UsedForP\n");
            for (Ticket t : tickets) {
                w.write(t.key + "," + t.created + "," + t.resolved + ","
                        + t.ov + "," + t.fv + "," + t.ivFromAv + ","
                        + t.iv + "," + t.ivSource + "," + t.consistent + "\n");
            }
        }
        System.out.println("\nwrote " + OUT.toAbsolutePath());
    }

    /** First release on or after the given date; 0 if there is none. */
    private static int releaseOnOrAfter(List<Rel> releases, LocalDate d) {
        for (Rel r : releases) {
            if (!r.date().isBefore(d)) {
                return r.index();
            }
        }
        return 0;
    }

    /** Last release on or before the given date; 0 if there is none. */
    private static int releaseOnOrBefore(List<Rel> releases, LocalDate d) {
        int best = 0;
        for (Rel r : releases) {
            if (!r.date().isAfter(d)) {
                best = r.index();
            }
        }
        return best;
    }

    private static List<Ticket> readTickets(List<Rel> releases, Map<String, Integer> indexByName)
            throws IOException {
        List<Ticket> out = new ArrayList<>();
        for (String line : dataLines(TICKETS)) {
            Ticket t = new Ticket();
            t.key      = field(line, 0);
            t.created  = LocalDate.parse(field(line, 1).substring(0, 10));
            t.resolved = LocalDate.parse(field(line, 2).substring(0, 10));

            t.ov = releaseOnOrBefore(releases, t.created);   // released version at report time
            t.fv = releaseOnOrAfter(releases, t.resolved);   // first release containing the fix

            // IV = minimum affected version, over those that are dated releases
            String raw = field(line, 3);
            int min = 0;
            if (!raw.isEmpty()) {
                for (String name : raw.split(";")) {
                    Integer idx = indexByName.get(name);
                    if (idx != null && (min == 0 || idx < min)) {
                        min = idx;
                    }
                }
            }
            t.ivFromAv = min;
            out.add(t);
        }
        return out;
    }

    private static List<Rel> readReleases() throws IOException {
        List<Rel> out = new ArrayList<>();
        for (String line : dataLines(RELEASES)) {
            out.add(new Rel(Integer.parseInt(field(line, 0)),
                    field(line, 2),
                    LocalDate.parse(field(line, 3))));
        }
        return out;
    }

    private static List<String> dataLines(Path p) throws IOException {
        List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
        return all.subList(1, all.size());
    }

    private static String field(String line, int index) {
        String[] f = line.split(",", -1);
        return index < f.length ? f[index].trim() : "";
    }
}