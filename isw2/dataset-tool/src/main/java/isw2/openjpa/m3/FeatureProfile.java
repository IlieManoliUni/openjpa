package isw2.openjpa.m3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import weka.core.Instances;

/**
 * Per-feature profile of the what-if datasets, as in the reference study:
 * for every feature, its average value and its correlation with NSmells
 * and with defectiveness.
 *
 * This serves two milestones.
 *
 * For Milestone 3 it characterises the datasets before any model is trained. If
 * B+ and C differed wildly on features other than NSmells, then a difference in
 * predicted defectiveness between them could not be attributed to smells at all
 * - it would be confounded. The correlation of each feature WITH NSmells is the
 * measurement of that confounding.
 *
 * For Milestone 4 it is a prerequisite. That milestone asks whether any feature
 * "positively correlated with bugginess" is higher in the refactored class than
 * in the original, and the same for negatively correlated features. Neither
 * question can be answered without first establishing, on the real data, which
 * features those are and in which direction they point.
 *
 * SPEARMAN RATHER THAN PEARSON
 *
 * Spearman's rank correlation is used throughout. The metrics here are heavily
 * skewed - NSmells runs from 0 to 430 with a median of 3, and the LOC and churn
 * columns are similar - so Pearson would be dominated by a handful of enormous
 * classes and would additionally assume a linear relationship that nothing here
 * justifies. Spearman measures whether the relationship is monotonic, which is
 * the actual claim of interest: does a class with more smells tend to be
 * buggier? It is also the coefficient discussed in the feature-selection
 * material.
 *
 * The class attribute is binary, so its "rank correlation" with a feature is a
 * point-biserial coefficient computed on ranks. The magnitude is not directly
 * comparable to a feature-to-feature correlation, but the SIGN is exactly what
 * Milestone 4 needs, and the ordering by magnitude is meaningful.
 */
public final class FeatureProfile {

    /** One feature's profile across the datasets. */
    public static final class Row {

        private final String name;
        private final double meanAll;
        private final double meanSmelly;
        private final double meanClean;
        private final double withSmells;
        private final double withBuggy;

        private Row(String name, double meanAll, double meanSmelly, double meanClean,
                    double withSmells, double withBuggy) {
            this.name = name;
            this.meanAll = meanAll;
            this.meanSmelly = meanSmelly;
            this.meanClean = meanClean;
            this.withSmells = withSmells;
            this.withBuggy = withBuggy;
        }

        public String name()       { return name; }
        public double meanAll()    { return meanAll; }
        public double meanSmelly() { return meanSmelly; }
        public double meanClean()  { return meanClean; }
        public double withSmells() { return withSmells; }
        public double withBuggy()  { return withBuggy; }

        /**
         * The direction Milestone 4 asks about: does a higher value of this
         * feature go with more defects, or fewer?
         */
        public String direction() {
            if (Double.isNaN(withBuggy)) {
                return "undefined";
            }
            return withBuggy > 0 ? "positive" : withBuggy < 0 ? "negative" : "none";
        }
    }

    private FeatureProfile() { }

    /**
     * Profiles every feature. The class attribute is excluded; NSmells is not,
     * because its correlation with itself must come out at 1.000 and is a free
     * check that the ranking code is right.
     */
    public static List<Row> compute(WhatIfDatasets datasets) {

        Instances all = datasets.all();
        Instances smelly = datasets.smelly();
        Instances clean = datasets.clean();

        double[] smells = all.attributeToDoubleArray(datasets.smellsIndex());
        double[] buggy = classAsNumeric(all, datasets.positiveIndex());

        List<Row> rows = new ArrayList<>();

        for (int i = 0; i < all.numAttributes(); i++) {

            if (i == all.classIndex()) {
                continue;
            }

            double[] column = all.attributeToDoubleArray(i);

            rows.add(new Row(
                    all.attribute(i).name(),
                    mean(column),
                    mean(smelly.attributeToDoubleArray(i)),
                    mean(clean.attributeToDoubleArray(i)),
                    spearman(column, smells),
                    spearman(column, buggy)));
        }

        return rows;
    }

    /** The same rows, strongest relationship with defectiveness first. */
    public static List<Row> byStrength(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingDouble(
                (Row r) -> Double.isNaN(r.withBuggy()) ? 0.0 : Math.abs(r.withBuggy())).reversed());
        return sorted;
    }

    /** 1.0 where the class is "yes", 0.0 otherwise. */
    private static double[] classAsNumeric(Instances instances, int positiveIndex) {
        double[] values = new double[instances.numInstances()];
        for (int i = 0; i < instances.numInstances(); i++) {
            values[i] = (int) instances.instance(i).classValue() == positiveIndex ? 1.0 : 0.0;
        }
        return values;
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    /** Pearson's correlation applied to the ranks of both variables. */
    private static double spearman(double[] x, double[] y) {
        return pearson(ranks(x), ranks(y));
    }

    /**
     * Fractional ranks, ties sharing the average of the positions they occupy.
     *
     * Midranks are not optional here. NSmells has 3,779 rows tied at zero and
     * thousands more tied at small integers; assigning those arbitrary distinct
     * ranks would invent an ordering the data does not contain and would bias
     * every coefficient in the table.
     */
    private static double[] ranks(double[] values) {

        int n = values.length;

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));

        double[] result = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            double rank = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                result[order[k]] = rank;
            }
            i = j + 1;
        }
        return result;
    }

    private static double pearson(double[] x, double[] y) {

        double meanX = mean(x);
        double meanY = mean(y);

        double sxy = 0.0;
        double sxx = 0.0;
        double syy = 0.0;

        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }

        // A constant column has no variance and no correlation - not zero
        // correlation, but an undefined one. Returning NaN keeps that
        // distinction instead of reporting a confident 0.000.
        if (sxx == 0.0 || syy == 0.0) {
            return Double.NaN;
        }
        return sxy / Math.sqrt(sxx * syy);
    }
}