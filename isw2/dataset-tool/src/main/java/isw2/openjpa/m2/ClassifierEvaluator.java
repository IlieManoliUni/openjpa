package isw2.openjpa.m2;

import isw2.openjpa.Config;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;

/**
 * Milestone 2: compare the accuracy of RandomForest / NaiveBayes / Ibk on the
 * Milestone 1 dataset, using 10 times 10-fold cross validation, with and
 * without feature selection and with and without balancing.
 *
 * Produces four files under data/:
 *
 *   m2_results.csv     THE DELIVERABLE. Twelve rows, columns exactly as in
 *                      the provided example output, rows in its order.
 *
 *   m2_detailed.csv    The same cells with the standard deviation and all ten
 *                      per-repetition values, plus PofB20. This is what lets
 *                      you say whether a gap between two classifiers is larger
 *                      than the noise, rather than just quoting two means.
 *
 *   m2_balancing.csv   The improvement layer: no balancing vs SpreadSubsample
 *                      vs Resample vs SMOTE, the three techniques in the
 *                      balancing material, which the milestone collapses into a
 *                      single Yes/No column.
 *
 *   m2_features.csv    How often CFS kept each of the 17 metrics across the 100
 *                      training folds.
 *
 * Usage:
 *   no arguments          run everything
 *   RandomForest Ibk      run only the named classifiers (labels as in the CSV)
 *   --required-only       skip the balancing comparison and the CFS frequency
 *
 * Running a subset is safe: the writers only emit the cells that exist, and a
 * later run with different arguments overwrites the files with whatever that
 * run computed. To assemble the full deliverable from separate runs, run the
 * classifiers you skipped in the same invocation as the others.
 */
public class ClassifierEvaluator {

    private static final Path ARFF          = Path.of("data", "dataset.arff");
    private static final Path OUT_MAIN      = Path.of("data", "m2_results.csv");
    private static final Path OUT_DETAIL    = Path.of("data", "m2_detailed.csv");
    private static final Path OUT_BALANCING = Path.of("data", "m2_balancing.csv");
    private static final Path OUT_FEATURES  = Path.of("data", "m2_features.csv");

    /** One cell of the experiment matrix. Record, so it works as a map key. */
    private record Cell(ClassifierKind classifier, boolean featureSelection,
                        Balancing balancing) { }

    private static final Map<Cell, CrossValidator.Result> RESULTS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {

        List<String> flags = Arrays.asList(args);
        boolean requiredOnly = flags.contains("--required-only");

        List<ClassifierKind> classifiers = new ArrayList<>();
        for (ClassifierKind kind : ClassifierKind.values()) {
            if (flags.isEmpty() || flags.contains(kind.label())) {
                classifiers.add(kind);
            }
        }
        if (classifiers.isEmpty()) {
            classifiers.addAll(Arrays.asList(ClassifierKind.values()));
        }

        Instances data = load();
        CrossValidator validator = new CrossValidator(data);

        System.out.println();
        System.out.println("=== required matrix: 3 classifiers x FS x balancing ===");

        // Cell order copied from the provided example output: balancing is the
        // outer loop, then feature selection, then the classifier. That example
        // lists RandomForest/NaiveBayes/Ibk three at a time under each setting.
        for (Balancing balancing : List.of(Balancing.NONE, Balancing.OVERSAMPLING)) {
            for (boolean featureSelection : List.of(false, true)) {
                for (ClassifierKind kind : classifiers) {
                    evaluate(validator, new Cell(kind, featureSelection, balancing));
                }
            }
        }

        if (!requiredOnly) {

            System.out.println();
            System.out.println("=== balancing comparison (feature selection off) ===");

            for (Balancing balancing : List.of(Balancing.UNDERSAMPLING, Balancing.SMOTE)) {
                for (ClassifierKind kind : classifiers) {
                    // NONE and OVERSAMPLING with FS off were already computed
                    // above and are read straight out of the cache.
                    evaluate(validator, new Cell(kind, false, balancing));
                }
            }

            System.out.println();
            System.out.println("=== attribute selection frequency over the 100 folds ===");
            writeFeatures(validator.attributeSelectionFrequency());
        }

        System.out.println();
        System.out.println("done. files written under data/");
    }

    /**
     * Runs one cell unless it is already cached, then refreshes every output
     * file so a crash never costs more than the cell in flight.
     */
    private static void evaluate(CrossValidator validator, Cell cell) throws Exception {

        if (RESULTS.containsKey(cell)) {
            return;
        }

        System.out.printf("%n  %s | FS %s | balancing %s%n",
                cell.classifier().label(),
                cell.featureSelection() ? "yes" : "no",
                cell.balancing().label());

        long start = System.currentTimeMillis();
        CrossValidator.Result result =
                validator.run(cell.classifier(), cell.featureSelection(), cell.balancing());
        RESULTS.put(cell, result);

        System.out.printf(
                "    -> precision %.2f  recall %.2f  AUC %.2f  kappa %.2f  "
                        + "NPofB20 %.2f  (PofB20 %.2f)  [%.0f s]%n",
                result.mean(CrossValidator.Metric.PRECISION),
                result.mean(CrossValidator.Metric.RECALL),
                result.mean(CrossValidator.Metric.AUC),
                result.mean(CrossValidator.Metric.KAPPA),
                result.mean(CrossValidator.Metric.NPOFB20),
                result.mean(CrossValidator.Metric.POFB20),
                (System.currentTimeMillis() - start) / 1000.0);

        writeMain();
        writeDetail();
        writeBalancing();
    }

    /**
     * Loads the Milestone 1 ARFF and points Weka at the class attribute.
     *
     * The class is the last attribute, so setClassIndex(numAttributes() - 1) -
     * the same idiom as the provided evaluation example. Weka has no notion of a "class
     * column" until you say so; without this line every method that needs the
     * class throws UnassignedClassException.
     */
    private static Instances load() throws Exception {

        DataSource source = new DataSource(ARFF.toString());
        Instances data = source.getDataSet();
        if (data == null) {
            throw new IOException("could not read " + ARFF.toAbsolutePath());
        }
        data.setClassIndex(data.numAttributes() - 1);

        int buggy = 0;
        int positive = data.classAttribute().indexOfValue("yes");
        for (int i = 0; i < data.numInstances(); i++) {
            if ((int) data.instance(i).classValue() == positive) {
                buggy++;
            }
        }

        System.out.printf("dataset      : %s%n", ARFF.toAbsolutePath());
        System.out.printf("instances    : %d%n", data.numInstances());
        System.out.printf("attributes   : %d (+ class %s)%n",
                data.numAttributes() - 1, data.classAttribute().name());
        System.out.printf("class balance: %d no / %d yes (%.1f%% buggy)%n",
                data.numInstances() - buggy, buggy,
                100.0 * buggy / data.numInstances());
        System.out.printf("validation   : %d times %d-fold%n",
                CrossValidator.REPETITIONS, CrossValidator.FOLDS);
        System.out.printf("oversampling : Resample -B 1.0 -Z %.2f%n",
                Balancing.oversamplingPercent(data));
        System.out.printf("SMOTE        : SMOTE -P %.2f%n",
                Balancing.smotePercent(data));

        return data;
    }

    /** The twelve required cells, in the order of the provided example output. */
    private static List<Cell> requiredCells() {
        List<Cell> cells = new ArrayList<>();
        for (Balancing balancing : List.of(Balancing.NONE, Balancing.OVERSAMPLING)) {
            for (boolean featureSelection : List.of(false, true)) {
                for (ClassifierKind kind : ClassifierKind.values()) {
                    cells.add(new Cell(kind, featureSelection, balancing));
                }
            }
        }
        return cells;
    }

    /**
     * THE DELIVERABLE. Columns and their spelling are copied from
     * the provided example output and nothing extra is added - PofB20 and the
     * standard deviations live in the detailed file so this one stays exactly
     * the shape he asked for.
     *
     * FS and Balancing are written as "Yes"/"No", as in that example. Balancing
     * "Yes" means Resample; which technique that is belongs in the report and
     * in m2_balancing.csv, not in a column he defined as a boolean.
     */
    private static void writeMain() throws IOException {
        Files.createDirectories(OUT_MAIN.getParent());
        try (Writer out = Files.newBufferedWriter(OUT_MAIN, StandardCharsets.UTF_8)) {

            out.write("Dataset,Classifier,FS,Balancing,Precision,Recall,AUC,Kappa,NPofB20\n");

            for (Cell cell : requiredCells()) {
                CrossValidator.Result result = RESULTS.get(cell);
                if (result == null) {
                    continue;
                }
                out.write(Config.PROJECT_KEY);
                out.write(",");
                out.write(cell.classifier().label());
                out.write(",");
                out.write(cell.featureSelection() ? "Yes" : "No");
                out.write(",");
                out.write(cell.balancing() == Balancing.NONE ? "No" : "Yes");
                for (CrossValidator.Metric metric : List.of(
                        CrossValidator.Metric.PRECISION,
                        CrossValidator.Metric.RECALL,
                        CrossValidator.Metric.AUC,
                        CrossValidator.Metric.KAPPA,
                        CrossValidator.Metric.NPOFB20)) {
                    out.write(",");
                    out.write(number(result.mean(metric)));
                }
                out.write("\n");
            }
        }
    }

    /**
     * Mean, standard deviation and all ten repetition values for every metric
     * of every cell computed so far.
     *
     * The standard deviation is the point of this file. "RandomForest scored
     * AUC 0.78 and NaiveBayes 0.75" is not an answer to "which classifier is
     * more accurate" unless you also know that the ten repetitions of each sat
     * within a few thousandths of their mean.
     */
    private static void writeDetail() throws IOException {
        try (Writer out = Files.newBufferedWriter(OUT_DETAIL, StandardCharsets.UTF_8)) {

            out.write("Dataset,Classifier,FS,Balancing,Metric,Mean,StdDev");
            for (int i = 1; i <= CrossValidator.REPETITIONS; i++) {
                out.write(String.format(Locale.US, ",Rep%02d", i));
            }
            out.write("\n");

            for (Map.Entry<Cell, CrossValidator.Result> entry : RESULTS.entrySet()) {
                Cell cell = entry.getKey();
                CrossValidator.Result result = entry.getValue();

                for (CrossValidator.Metric metric : CrossValidator.Metric.values()) {
                    out.write(Config.PROJECT_KEY);
                    out.write(",");
                    out.write(cell.classifier().label());
                    out.write(",");
                    out.write(cell.featureSelection() ? "Yes" : "No");
                    out.write(",");
                    out.write(cell.balancing().label());
                    out.write(",");
                    out.write(metric.label());
                    out.write(",");
                    out.write(number(result.mean(metric)));
                    out.write(",");
                    out.write(number(result.stdDev(metric)));
                    for (double value : result.values(metric)) {
                        out.write(",");
                        out.write(number(value));
                    }
                    out.write("\n");
                }
            }
        }
    }

    /**
     * The improvement layer you asked for: all four balancing settings side by
     * side, feature selection held off so the only thing varying is the
     * sampling technique.
     */
    private static void writeBalancing() throws IOException {
        try (Writer out = Files.newBufferedWriter(OUT_BALANCING, StandardCharsets.UTF_8)) {

            out.write("Dataset,Classifier,Balancing,Precision,Recall,AUC,Kappa,NPofB20,PofB20\n");

            for (ClassifierKind kind : ClassifierKind.values()) {
                for (Balancing balancing : Balancing.values()) {

                    CrossValidator.Result result = RESULTS.get(new Cell(kind, false, balancing));
                    if (result == null) {
                        continue;
                    }
                    out.write(Config.PROJECT_KEY);
                    out.write(",");
                    out.write(kind.label());
                    out.write(",");
                    out.write(balancing.label());
                    for (CrossValidator.Metric metric : CrossValidator.Metric.values()) {
                        out.write(",");
                        out.write(number(result.mean(metric)));
                    }
                    out.write("\n");
                }
            }
        }
    }

    /**
     * How often CFS kept each metric, out of the 100 training folds.
     *
     * An attribute kept in 100/100 folds is a stable signal. One kept in 40/100
     * is being chosen by the shuffle rather than by the data, and any claim
     * about "the important metrics" that rests on it is a claim about noise.
     */
    private static void writeFeatures(Map<String, Integer> frequency) throws IOException {

        int folds = CrossValidator.REPETITIONS * CrossValidator.FOLDS;

        try (Writer out = Files.newBufferedWriter(OUT_FEATURES, StandardCharsets.UTF_8)) {
            out.write("Attribute,TimesSelected,TotalFolds,Frequency\n");
            for (Map.Entry<String, Integer> entry : frequency.entrySet()) {
                out.write(entry.getKey());
                out.write(",");
                out.write(String.valueOf(entry.getValue()));
                out.write(",");
                out.write(String.valueOf(folds));
                out.write(",");
                out.write(number((double) entry.getValue() / folds));
                out.write("\n");
                System.out.printf("  %-16s %3d / %d%n", entry.getKey(), entry.getValue(), folds);
            }
        }
    }

    /**
     * Four decimal places, Locale.US.
     *
     * The locale is not cosmetic. On an Italian Windows the default locale
     * formats 0.7312 as "0,7312", and a comma inside a CSV field silently
     * shifts every later column by one. The example shows two decimals, but
     * those numbers are placeholders; four keeps differences that live in the
     * third decimal visible, and rounding for presentation is the reader's job.
     */
    private static String number(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        return String.format(Locale.US, "%.4f", value);
    }
}