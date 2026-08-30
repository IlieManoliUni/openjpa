package isw2.openjpa.m2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Effort-aware accuracy metrics, from Carka, Esposito and Falessi,
 * "On effort-aware metrics for defect prediction", EMSE 27:152, 2022).
 *
 * The idea: Precision/Recall/AUC/Kappa all treat every class as equally
 * expensive to inspect. A developer's budget is not "20% of the classes", it is
 * "20% of the lines I have time to read". A classifier that flags one 3000-line
 * class is far less useful than one that flags ten 30-line classes, even if both
 * score the same recall.
 *
 *   PofB20  - rank by predicted probability of being buggy,
 *             inspect until 20% of the codebase's lines are consumed,
 *             report the fraction of all bugs found.
 *
 *   NPofB20 - identical, except the ranking key is probability DIVIDED BY size.
 *             This is the paper's contribution: the developer should open the
 *             class with the best bugs-per-line-read ratio, not the one with the
 *             highest raw probability.
 *
 * The paper's own worked example: 7 entities, 1790 LOC, 3 defective, budget 358
 * LOC. Ranked by probability the budget buys 2 entities and finds 1 of 3 bugs
 * (PofB20 = 33%). Ranked by probability/size it buys 3 entities and finds 2 of 3
 * (NPofB20 = 66%). Same classifier, same predictions, double the score.
 */
public final class Npofb {

    /** The inspection budget: the "20" in NPofB20. */
    private static final double BUDGET_FRACTION = 0.20;

    private Npofb() { }   // utility class: never instantiated

    /**
     * One cross-validated prediction. Size is carried alongside the probability
     * because effort-aware metrics are meaningless without it, and because the
     * feature-selection filter may well have deleted LOC from the attribute set
     * the classifier saw.
     */
    public static final class Entry {

        private final double probability;
        private final double size;
        private final boolean buggy;

        public Entry(double probability, double size, boolean buggy) {
            this.probability = probability;
            // A zero-size class would have infinite density and would always be
            // ranked first while costing nothing - it would silently poison the
            // ranking. Charging a minimum of one line removes that.
            this.size = Math.max(size, 1.0);
            this.buggy = buggy;
        }

        public double probability() { return probability; }

        public double size() { return size; }

        public boolean buggy() { return buggy; }

        /** The NPofB ranking key: predicted bugs per line of inspection. */
        public double density() { return probability / size; }
    }

    /** Ranked by raw predicted probability - the un-normalised metric. */
    public static double pofB20(List<Entry> entries) {
        return compute(entries, Comparator.comparingDouble(Entry::probability).reversed());
    }

    /** Ranked by probability / size - the paper's normalised metric. */
    public static double nPofB20(List<Entry> entries) {
        return compute(entries, Comparator.comparingDouble(Entry::density).reversed());
    }

    private static double compute(List<Entry> entries, Comparator<Entry> order) {

        if (entries.isEmpty()) {
            return 0.0;
        }

        double totalSize = 0.0;
        int totalBuggy = 0;
        for (Entry e : entries) {
            totalSize += e.size();
            if (e.buggy()) {
                totalBuggy++;
            }
        }

        // No bugs to find means the metric is undefined, not zero-out-of-zero.
        // This cannot happen on our dataset (2994 buggy rows) but a guard here
        // is cheaper than a NaN propagating into the CSV.
        if (totalBuggy == 0) {
            return 0.0;
        }

        // Ties on the ranking key are broken by putting the smaller class first:
        // among equally promising candidates the cheaper one is the rational
        // choice, and it makes the result reproducible instead of depending on
        // whatever order the folds happened to produce.
        List<Entry> ranked = new ArrayList<>(entries);
        ranked.sort(order.thenComparing(Comparator.comparingDouble(Entry::size)));

        double budget = BUDGET_FRACTION * totalSize;
        double inspected = 0.0;
        int found = 0;

        for (Entry e : ranked) {
            inspected += e.size();
            if (e.buggy()) {
                found++;
            }
            // The class that crosses the budget is counted as inspected: the
            // developer opened the file, they do not stop reading it halfway.
            // The alternative convention (skip anything that would overshoot)
            // lets one huge class block every smaller class behind it.
            if (inspected >= budget) {
                break;
            }
        }

        return (double) found / totalBuggy;
    }
}