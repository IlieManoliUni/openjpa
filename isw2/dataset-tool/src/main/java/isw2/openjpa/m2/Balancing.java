package isw2.openjpa.m2;

import java.util.Locale;

import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.supervised.instance.Resample;
import weka.filters.supervised.instance.SpreadSubsample;

/**
 * The three sampling techniques listed in the balancing material, plus
 * the "no balancing" baseline.
 *
 * Every filter here is a SUPERVISED INSTANCE filter. That matters: Weka's
 * FilteredClassifier applies such a filter to the training batch only and lets
 * test instances pass through untouched. Balancing the test set would be
 * cheating twice over - it changes the class prior the metrics are measured
 * against, and with replacement-based oversampling it can put copies of the
 * same row on both sides of the split.
 */
public enum Balancing {

    /** Baseline: the natural 20.3% / 79.7% distribution of the dataset. */
    NONE("No"),

    /** SpreadSubsample -M 1.0: discard majority rows until the classes match. */
    UNDERSAMPLING("Undersampling"),

    /** Resample -B 1.0 -Z <computed>: draw with replacement until they match. */
    OVERSAMPLING("Oversampling"),

    /** SMOTE -P <computed>: synthesise new minority rows between neighbours. */
    SMOTE("SMOTE");

    private final String label;

    Balancing(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Builds a freshly configured filter for this technique, or null for NONE.
     *
     * The percentages are recomputed from the training fold that is passed in
     * rather than hard-coded from the whole dataset. Both filters express their
     * target as a percentage, so a global constant would very nearly work - but
     * "very nearly" is not a thing to write in a report when the correct value
     * costs one pass over the fold.
     */
    public Filter filterFor(Instances training) throws Exception {

        switch (this) {

            case NONE:
                return null;

            case UNDERSAMPLING: {
                // -M is the maximum ratio between the largest and smallest
                // class after filtering. 1.0 means "exactly equal".
                SpreadSubsample filter = new SpreadSubsample();
                filter.setOptions(new String[] { "-M", "1.0" });
                return filter;
            }

            case OVERSAMPLING: {
                // -B 1.0  biasToUniformClass: sample so the classes come out
                //         equal rather than in their natural proportion.
                // -Z      sampleSizePercent, as a percentage of the INPUT size.
                // Sampling is with replacement (Weka's default), which is what
                // the assignment means by "noReplacement=false".
                Resample filter = new Resample();
                filter.setOptions(new String[] {
                        "-B", "1.0",
                        "-Z", format(oversamplingPercent(training)),
                        "-S", "1"
                });
                return filter;
            }

            case SMOTE: {
                // Fully qualified so the enum constant SMOTE above does not
                // shadow the class name.
                // -P  how many new minority instances to synthesise, as a
                //     percentage of the current minority class size.
                // -K  neighbours to interpolate between (Chawla et al. default).
                weka.filters.supervised.instance.SMOTE filter =
                        new weka.filters.supervised.instance.SMOTE();
                filter.setOptions(new String[] {
                        "-P", format(smotePercent(training)),
                        "-K", "5",
                        "-S", "1"
                });
                return filter;
            }

            default:
                throw new IllegalStateException("unhandled balancing: " + this);
        }
    }

    /**
     * Resample's -Z, as a percentage of the input size.
     *
     * To end up with `majority` instances of each of the k classes the output
     * must hold k * majority rows, so
     *
     *     Z = 100 * k * majority / total
     *
     * Diabetes check against the material's own example: k=2, majority=500,
     * total=768 -> 100 * 1000 / 768 = 130.2, and the material says 130.3.
     *
     * The formula printed under that heading, 100*(majority-minority)/minority,
     * gives 86.6 for diabetes - it would shrink the training set instead of
     * growing it. That expression is the correct one for SMOTE's -P;
     * see smotePercent below.
     */
    public static double oversamplingPercent(Instances data) {
        int[] counts = classCounts(data);
        int majority = 0;
        int total = 0;
        for (int count : counts) {
            majority = Math.max(majority, count);
            total += count;
        }
        if (total == 0) {
            return 100.0;
        }
        return 100.0 * counts.length * majority / total;
    }

    /**
     * SMOTE's -P: how many new minority rows to create, as a percentage of the
     * minority class. To close the gap exactly:
     *
     *     P = 100 * (majority - minority) / minority
     *
     * Diabetes check: 100 * (500-268)/268 = 86.6, and 268 + 86.6% of 268 = 500,
     * exactly the majority count. This is the formula misprinted under the
     * oversampling bullet.
     */
    public static double smotePercent(Instances data) {
        int[] counts = classCounts(data);
        int majority = 0;
        int minority = Integer.MAX_VALUE;
        for (int count : counts) {
            majority = Math.max(majority, count);
            minority = Math.min(minority, count);
        }
        if (minority <= 0) {
            return 0.0;
        }
        return 100.0 * (majority - minority) / minority;
    }

    private static int[] classCounts(Instances data) {
        int[] counts = new int[data.numClasses()];
        for (int i = 0; i < data.numInstances(); i++) {
            if (!data.instance(i).classIsMissing()) {
                counts[(int) data.instance(i).classValue()]++;
            }
        }
        return counts;
    }

    /**
     * Weka parses option strings with Double.parseDouble, which is locale
     * independent - but String.format is not. On an Italian Windows the default
     * locale would produce "159,4556" and Weka would reject it. Locale.US is
     * not decoration here.
     */
    private static String format(double value) {
        return String.format(Locale.US, "%.4f", value);
    }
}