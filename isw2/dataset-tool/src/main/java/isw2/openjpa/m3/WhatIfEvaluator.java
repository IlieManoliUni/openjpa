package isw2.openjpa.m3;

import isw2.openjpa.Config;
import isw2.openjpa.m2.ClassifierKind;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;

/**
 * Milestone 3: how many buggy classes could have been prevented by having zero
 * smells?
 *
 * Steps 3 to 7 of the Milestone 3 assignment:
 *
 *   3. choose the best classifier from Milestone 2, aka BClassifier
 *   5. create B+, C and B
 *   6. train BClassifier on A, aka BClassifierA
 *   7. predict A, B, B+, C and produce the results table
 *
 * Writes four files under data/:
 *
 *   m3_whatif.csv           the results table, shaped as the reference table
 *                           of the what-if study
 *   m3_prevention.csv       the three figures the milestone asks for
 *   m3_feature_profile.csv  the feature profile; also the input to the
 *                           Milestone 4 questions about feature direction
 *   m3_sensitivity.csv      estimated defectiveness if only the smelliest
 *                           fraction of classes were cleaned
 */
public class WhatIfEvaluator {

    private static final Path ARFF        = Path.of("data", "dataset.arff");
    private static final Path OUT_TABLE   = Path.of("data", "m3_whatif.csv");
    private static final Path OUT_PREVENT = Path.of("data", "m3_prevention.csv");
    private static final Path OUT_PROFILE = Path.of("data", "m3_feature_profile.csv");
    private static final Path OUT_SENSE   = Path.of("data", "m3_sensitivity.csv");

    /**
     * BClassifier. Milestone 2 answered this: RandomForest was the most
     * accurate classifier on every metric in all four preprocessing settings,
     * under both 10x10 cross validation and walk-forward.
     *
     * It is used with no feature selection and no balancing, which was also its
     * best cell in the Milestone 2 table (AUC 0.960, kappa 0.746). Both choices
     * matter here for reasons beyond accuracy:
     *
     *   - no feature selection, because this analysis works by manipulating
     *     NSmells, so the model must be one that actually reads it;
     *
     *   - no balancing, because the analysis COUNTS predicted-buggy rows.
     *     Balancing shifts the decision boundary towards the minority class and
     *     would inflate every count in the table, putting the estimates on a
     *     different scale from the actual counts they sit beside.
     */
    private static final ClassifierKind B_CLASSIFIER = ClassifierKind.RANDOM_FOREST;

    public static void main(String[] args) throws Exception {

        WhatIfDatasets datasets = WhatIfDatasets.load(ARFF);
        System.out.println(datasets.describe());
        System.out.println();

        // ---- characterise the features before training anything ----
        List<FeatureProfile.Row> profile = FeatureProfile.compute(datasets);
        writeProfile(profile);
        reportProfile(profile);

        // ---- step 6: train BClassifier on A ----
        System.out.printf("%nBClassifier = %s, trained on all of A (%d rows)%n",
                B_CLASSIFIER.label(), datasets.all().numInstances());

        Classifier model = B_CLASSIFIER.build();
        model.buildClassifier(datasets.all());

        // ---- step 7: predict A, B+, B, C ----
        Estimate onAll    = predict(model, datasets, datasets.all(),    "A",  true);
        Estimate onSmelly = predict(model, datasets, datasets.smelly(), "B+", true);
        Estimate onZeroed = predict(model, datasets, datasets.zeroed(), "B",  false);
        Estimate onClean  = predict(model, datasets, datasets.clean(),  "C",  true);

        writeTable(List.of(onAll, onSmelly, onZeroed, onClean));
        reportTable(List.of(onAll, onSmelly, onZeroed, onClean));

        // ---- the three questions the assignment asks ----
        prevention(onAll, onSmelly, onZeroed);

        // ---- beyond the requirement: partial cleaning ----
        sensitivity(model, datasets, onAll);

        System.out.println();
        System.out.println("done. files written under data/");
    }

    /** One dataset's row in the results table. */
    private record Estimate(String name, int rows, Integer actual,
                            int estimated, double expected) { }

    /**
     * Counts how many rows the model calls buggy.
     *
     * "Estimated" is a hard count at Weka's default 0.5 threshold, because that
     * is what the reference table reports - integers, and 57 + 63 = 120 exactly. The
     * expected count, the sum of the predicted probabilities, is carried
     * alongside it: it uses the whole distribution instead of a threshold, so it
     * does not jump when a class sits at 0.49 rather than 0.51.
     *
     * @param hasTruth false for B, which is counterfactual and has no actual
     *                 value - the code that had no smells was never written
     */
    private static Estimate predict(Classifier model, WhatIfDatasets datasets,
                                    Instances data, String name, boolean hasTruth)
            throws Exception {

        int estimated = 0;
        double expected = 0.0;

        for (int i = 0; i < data.numInstances(); i++) {

            Instance instance = data.instance(i);

            Instance masked = (Instance) instance.copy();
            masked.setDataset(data);
            masked.setClassMissing();

            double[] distribution = model.distributionForInstance(masked);
            double probability = distribution[datasets.positiveIndex()];

            expected += probability;
            if (probability >= 0.5) {
                estimated++;
            }
        }

        return new Estimate(name, data.numInstances(),
                hasTruth ? datasets.buggy(data) : null, estimated, expected);
    }

    private static void reportTable(List<Estimate> rows) {
        System.out.println();
        System.out.println("=== what-if results table ===");
        System.out.printf("  %-4s %8s %10s %12s %12s%n",
                "", "rows", "actual", "estimated", "expected");
        for (Estimate row : rows) {
            System.out.printf("  %-4s %8d %10s %12d %12.1f%n",
                    row.name(), row.rows(),
                    row.actual() == null ? "-" : row.actual().toString(),
                    row.estimated(), row.expected());
        }
    }

    /**
     * The three figures the assignment asks for, following the arithmetic of
     * the reference study: "The drop is a substantial 42% ((66-38)/66). This
     * means an overall reduction of 20% ((66-38)/135)".
     *
     * Note what those expressions do: 66 is the ACTUAL buggy count of B+ and 38
     * is the ESTIMATED buggy count of B. The comparison therefore mixes ground
     * truth with a model output, and folds the model's own error on B+ - it
     * estimates 57 where the truth is 66 - into what is reported as the effect
     * of the smells.
     *
     * That formula is reported first, because it is what the milestone asks for.
     * The within-model figure, estimated(B+) - estimated(B), is reported beside
     * it: both sides come from the same model, so the model's bias cancels and
     * what remains is attributable to the manipulated feature alone.
     */
    private static void prevention(Estimate all, Estimate smelly, Estimate zeroed)
            throws IOException {

        int preventedHis = smelly.actual() - zeroed.estimated();
        int preventedModel = smelly.estimated() - zeroed.estimated();

        double proportionHis = 100.0 * preventedHis / all.actual();
        double outOfPreventableHis = 100.0 * preventedHis / smelly.actual();

        double proportionModel = 100.0 * preventedModel / all.estimated();
        double outOfPreventableModel = 100.0 * preventedModel / smelly.estimated();

        System.out.println();
        System.out.println("=== how many buggy classes could have been prevented? ===");
        System.out.printf("  in total                  %6d   (%d actual buggy in B+ "
                        + "minus %d estimated buggy in B)%n",
                preventedHis, smelly.actual(), zeroed.estimated());
        System.out.printf("  in proportion             %6.1f%%   of the %d buggy classes in A%n",
                proportionHis, all.actual());
        System.out.printf("  out of the preventable    %6.1f%%   of the %d buggy classes "
                        + "that have smells%n",
                outOfPreventableHis, smelly.actual());
        System.out.println();
        System.out.printf("  within-model comparison   %6d prevented, %.1f%% of preventable%n",
                preventedModel, outOfPreventableModel);

        try (Writer out = Files.newBufferedWriter(OUT_PREVENT, StandardCharsets.UTF_8)) {
            out.write("Question,Basis,Value,Numerator,Denominator\n");
            row(out, "In total", "actual B+ - estimated B",
                    String.valueOf(preventedHis), preventedHis, null);
            row(out, "In proportion", "of all buggy classes in A",
                    format(proportionHis), preventedHis, all.actual());
            row(out, "Out of the preventable ones", "of buggy classes with smells",
                    format(outOfPreventableHis), preventedHis, smelly.actual());
            row(out, "In total (within model)", "estimated B+ - estimated B",
                    String.valueOf(preventedModel), preventedModel, null);
            row(out, "In proportion (within model)", "of all estimated buggy in A",
                    format(proportionModel), preventedModel, all.estimated());
            row(out, "Out of the preventable (within model)", "of estimated buggy with smells",
                    format(outOfPreventableModel), preventedModel, smelly.estimated());
        }
    }

    private static void row(Writer out, String question, String basis, String value,
                            Integer numerator, Integer denominator) throws IOException {
        out.write(question);
        out.write(",");
        out.write(basis);
        out.write(",");
        out.write(value);
        out.write(",");
        out.write(numerator == null ? "" : numerator.toString());
        out.write(",");
        out.write(denominator == null ? "" : denominator.toString());
        out.write("\n");
    }

    /**
     * Beyond the requirement.
     *
     * "Zero smells everywhere" is not an engineering plan - it is the upper
     * bound of one. Milestone 4 removes smells from two classes with an
     * automated tool, and will not remove all of them. This traces the estimate
     * as progressively more of the smelliest classes are cleaned, so the
     * headline number has a curve behind it rather than a single point.
     *
     * Classes are cleaned in descending order of NSmells, which is the order a
     * team with finite time would work in - and, not incidentally, the ranking
     * Milestone 4 uses to choose its two classes.
     */
    private static void sensitivity(Classifier model, WhatIfDatasets datasets,
                                    Estimate baseline) throws Exception {

        Instances all = datasets.all();
        int smellsIndex = datasets.smellsIndex();

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < all.numInstances(); i++) {
            if (all.instance(i).value(smellsIndex) > 0.0) {
                order.add(i);
            }
        }
        order.sort(Comparator.comparingDouble(
                (Integer i) -> all.instance(i).value(smellsIndex)).reversed());

        double[] fractions = { 0.0, 0.10, 0.25, 0.50, 0.75, 1.0 };

        // Every scenario is evaluated first, because the "share of the full
        // effect" column divides by the last one. Computing it inside the loop
        // would divide the early rows by a value not yet known.
        List<int[]> results = new ArrayList<>();
        for (double fraction : fractions) {

            int cleaned = (int) Math.round(fraction * order.size());

            Instances scenario = new Instances(all, 0);
            for (int i = 0; i < all.numInstances(); i++) {
                scenario.add(all.instance(i));
            }
            for (int k = 0; k < cleaned; k++) {
                scenario.instance(order.get(k)).setValue(smellsIndex, 0.0);
            }

            int estimated = predict(model, datasets, scenario, "S", false).estimated();
            results.add(new int[] { cleaned, estimated });
        }

        int fullEffect = baseline.estimated() - results.get(results.size() - 1)[1];

        System.out.println();
        System.out.println("=== if only the smelliest classes were cleaned ===");
        System.out.printf("  %10s %10s %12s %12s %10s%n",
                "cleaned", "classes", "estimated", "prevented", "of full");

        try (Writer out = Files.newBufferedWriter(OUT_SENSE, StandardCharsets.UTF_8)) {

            out.write("FractionCleaned,ClassesCleaned,EstimatedBuggy,"
                    + "ReductionFromBaseline,PercentOfFullEffect\n");

            for (int i = 0; i < results.size(); i++) {
                int cleaned = results.get(i)[0];
                int estimated = results.get(i)[1];
                int reduction = baseline.estimated() - estimated;
                double share = fullEffect == 0 ? Double.NaN : 100.0 * reduction / fullEffect;

                System.out.printf("  %9.0f%% %10d %12d %12d %9s%%%n",
                        100 * fractions[i], cleaned, estimated, reduction, format(share));

                out.write(String.format(Locale.US, "%.2f,%d,%d,%d,%s%n",
                        fractions[i], cleaned, estimated, reduction, format(share)));
            }
        }
    }

    private static void writeTable(List<Estimate> rows) throws IOException {
        Files.createDirectories(OUT_TABLE.getParent());
        try (Writer out = Files.newBufferedWriter(OUT_TABLE, StandardCharsets.UTF_8)) {
            out.write("Project,Dataset,Rows,Actual,Estimated,ExpectedCount,Definition\n");
            String[] definitions = {
                    "the original dataset",
                    "portion of A with NSmells > 0",
                    "B+ with NSmells set to 0 - counterfactual, no ground truth",
                    "portion of A with NSmells = 0"
            };
            for (int i = 0; i < rows.size(); i++) {
                Estimate row = rows.get(i);
                out.write(String.format(Locale.US, "%s,%s,%d,%s,%d,%.1f,%s%n",
                        Config.PROJECT_KEY, row.name(), row.rows(),
                        row.actual() == null ? "" : row.actual().toString(),
                        row.estimated(), row.expected(), definitions[i]));
            }
        }
    }

    private static void writeProfile(List<FeatureProfile.Row> rows) throws IOException {
        Files.createDirectories(OUT_PROFILE.getParent());
        try (Writer out = Files.newBufferedWriter(OUT_PROFILE, StandardCharsets.UTF_8)) {
            out.write("Feature,MeanA,MeanBPlus,MeanC,SpearmanWithNSmells,"
                    + "SpearmanWithBuggy,Direction\n");
            for (FeatureProfile.Row row : rows) {
                out.write(String.format(Locale.US, "%s,%.3f,%.3f,%.3f,%s,%s,%s%n",
                        row.name(), row.meanAll(), row.meanSmelly(), row.meanClean(),
                        format(row.withSmells()), format(row.withBuggy()), row.direction()));
            }
        }
    }

    private static void reportProfile(List<FeatureProfile.Row> rows) {
        System.out.println("=== feature profile, strongest relationship with bugginess first ===");
        System.out.printf("  %-16s %10s %10s %10s %10s %10s%n",
                "feature", "mean A", "mean B+", "mean C", "r NSmells", "r Buggy");
        for (FeatureProfile.Row row : FeatureProfile.byStrength(rows)) {
            System.out.printf("  %-16s %10.1f %10.1f %10.1f %10s %10s%n",
                    row.name(), row.meanAll(), row.meanSmelly(), row.meanClean(),
                    format(row.withSmells()), format(row.withBuggy()));
        }
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.3f", value);
    }
}