package isw2.openjpa.m2;

import weka.classifiers.AbstractClassifier;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;

/**
 * The three classifiers named in the Milestone 2 assignment:
 * "Di tre classificatori (RandomForest / NaiveBayes / Ibk)".
 *
 * All three are constructed with Weka's defaults and nothing is tuned. That is
 * deliberate and it is the honest choice: the milestone asks which classifier
 * is most accurate, and tuning one of them by hand would make the comparison a
 * measurement of how much effort each got rather than of the algorithms.
 * Hyper-parameter tuning would need its own inner validation loop, which is a
 * different experiment.
 */
public enum ClassifierKind {

    /** 100 bagged random trees. Strong default, handles mixed-scale metrics. */
    RANDOM_FOREST("RandomForest"),

    /** Assumes the 17 metrics are conditionally independent - they are not,
     *  LOC and LOC_touched correlate heavily. Expect feature selection to help
     *  this one most. */
    NAIVE_BAYES("NaiveBayes"),

    /** k-nearest-neighbour, k=1 by default. Lazy: no training cost, but every
     *  prediction scans the whole training fold. This is the slow one. */
    IBK("Ibk");

    private final String label;

    ClassifierKind(String label) {
        this.label = label;
    }

    /**
     * The string written to the CSV. Spelled exactly as in the provided
     * example output - note "Ibk", not "IBk".
     */
    public String label() {
        return label;
    }

    public AbstractClassifier build() {
        switch (this) {
            case RANDOM_FOREST:
                return new RandomForest();
            case NAIVE_BAYES:
                return new NaiveBayes();
            case IBK:
                return new IBk();
            default:
                throw new IllegalStateException("unhandled classifier: " + this);
        }
    }
}