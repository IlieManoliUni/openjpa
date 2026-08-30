package isw2.openjpa.m2;

import isw2.openjpa.util.Csv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;

/**
 * Walk-forward validation, the technique used in the RQ2 methodology of
 * 12_Npofb20.pdf ("Create Train and Test Datasets: Use the walk-forward
 * validation technique").
 *
 * For k = 1 .. releases-1:
 *     train on every row from releases 1..k
 *     test  on every row from release  k+1
 *
 * Thirteen steps on this dataset. Each one answers the question a team would
 * actually ask: "given everything released so far, which classes in the release
 * we are about to ship are defective?" No model is ever built from data that did
 * not exist at the moment its prediction would have been made.
 *
 * WHY THIS SITS BESIDE THE 10x10 CROSS VALIDATION RATHER THAN REPLACING IT
 *
 * Milestone 2 specifies 10 times 10-fold, so that is the deliverable. But random
 * folds ignore the ordering of the releases entirely: a model can be trained on
 * release 12 and tested on release 3. Measured on this dataset, a predictor
 * given nothing but the identity of the file - no metrics at all - reaches an
 * AUC of 0.913, because a file's label persists across 93.4% of consecutive
 * release pairs and each file appears in about eleven rows. Under random folds
 * roughly ten of a file's eleven rows sit in the training set while the eleventh
 * is being predicted, so any model able to recognise the file can look the
 * answer up rather than predict it.
 *
 * WHAT THIS DOES NOT FIX
 *
 * Between 95% and 99.9% of the files in release k+1 already existed in release
 * k, so a file recurs across the split here too. Walk-forward removes training
 * on the future, not file recurrence - the latter is inherent to any dataset
 * with one row per (class, release). The honest claim is "measured under
 * deployment conditions", not "leak-free".
 */
public final class WalkForward {

    /** Averages over the steps, and the per-step values behind them. */
    public static final class Result {

        private final Map<CrossValidator.Metric, double[]> perStep;
        private final int[] trainRows;
        private final int[] testRows;

        private Result(Map<CrossValidator.Metric, double[]> perStep,
                       int[] trainRows, int[] testRows) {
            this.perStep = perStep;
            this.trainRows = trainRows;
            this.testRows = testRows;
        }

        /** Mean over the steps, ignoring any step whose value is undefined. */
        public double mean(CrossValidator.Metric metric) {
            double sum = 0.0;
            int count = 0;
            for (double value : perStep.get(metric)) {
                if (!Double.isNaN(value)) {
                    sum += value;
                    count++;
                }
            }
            return count == 0 ? Double.NaN : sum / count;
        }

        public double stdDev(CrossValidator.Metric metric) {
            double mean = mean(metric);
            double sum = 0.0;
            int count = 0;
            for (double value : perStep.get(metric)) {
                if (!Double.isNaN(value)) {
                    sum += (value - mean) * (value - mean);
                    count++;
                }
            }
            return count < 2 ? 0.0 : Math.sqrt(sum / (count - 1));
        }

        public double[] values(CrossValidator.Metric metric) {
            return perStep.get(metric).clone();
        }

        public int steps() {
            return trainRows.length;
        }

        public int trainRows(int step) {
            return trainRows[step];
        }

        public int testRows(int step) {
            return testRows[step];
        }
    }

    private final Instances all;
    private final int[] version;
    private final int releases;
    private final int positiveIndex;
    private final int sizeIndex;
    private boolean verbose = true;

    private WalkForward(Instances all, int[] version, int releases) {
        this.all = all;
        this.version = version;
        this.releases = releases;
        this.positiveIndex = all.classAttribute().indexOfValue("yes");
        this.sizeIndex = all.attribute("LOC").index();
    }

    /**
     * Loads the ARFF the cross validation used, and takes the release number of
     * each row from dataset.csv.
     *
     * The release is deliberately NOT an attribute: it must partition the data
     * without ever being visible to a classifier, or the model could learn "rows
     * from release 10 are buggy" instead of learning from the metrics.
     *
     * DatasetBuilder writes dataset.csv and dataset.arff in the same loop, so
     * row i of one is row i of the other. That is an assumption, so it is
     * checked rather than trusted: every row's label must agree between the two
     * files, and a single mismatch aborts. If M1 is ever re-run in a way that
     * reorders one file and not the other, this fails loudly instead of
     * silently attaching the wrong release to every row.
     */
    public static WalkForward load(Path arff, Path csv) throws Exception {

        DataSource source = new DataSource(arff.toString());
        Instances data = source.getDataSet();
        if (data == null) {
            throw new IOException("could not read " + arff.toAbsolutePath());
        }
        data.setClassIndex(data.numAttributes() - 1);

        List<List<String>> rows = Csv.read(csv);
        if (rows.isEmpty()) {
            throw new IOException("empty " + csv.toAbsolutePath());
        }

        List<String> header = rows.get(0);
        int versionColumn = header.indexOf("Version");
        int buggyColumn = header.indexOf("Buggy");
        if (versionColumn < 0 || buggyColumn < 0) {
            throw new IOException("dataset.csv has no Version/Buggy column: " + header);
        }

        if (rows.size() - 1 != data.numInstances()) {
            throw new IllegalStateException(String.format(
                    "row count mismatch: %d in the csv, %d in the arff",
                    rows.size() - 1, data.numInstances()));
        }

        int[] version = new int[data.numInstances()];
        int releases = 0;

        for (int i = 0; i < data.numInstances(); i++) {

            List<String> row = rows.get(i + 1);

            String fromCsv = row.get(buggyColumn).trim();
            String fromArff = data.instance(i).stringValue(data.classIndex());
            if (!fromCsv.equals(fromArff)) {
                throw new IllegalStateException(String.format(
                        "row %d disagrees: csv says \"%s\", arff says \"%s\" - "
                                + "the two files are not in the same order",
                        i, fromCsv, fromArff));
            }

            version[i] = Integer.parseInt(row.get(versionColumn).trim());
            releases = Math.max(releases, version[i]);
        }

        return new WalkForward(data, version, releases);
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public Instances dataset() {
        return all;
    }

    public int releases() {
        return releases;
    }

    /** Runs all steps for one cell of the experiment matrix. */
    public Result run(ClassifierKind kind, boolean featureSelection, Balancing balancing)
            throws Exception {

        int steps = releases - 1;

        Map<CrossValidator.Metric, double[]> collected =
                new EnumMap<>(CrossValidator.Metric.class);
        for (CrossValidator.Metric metric : CrossValidator.Metric.values()) {
            collected.put(metric, new double[steps]);
        }
        int[] trainRows = new int[steps];
        int[] testRows = new int[steps];

        for (int k = 1; k <= steps; k++) {

            Instances train = subset(1, k);
            Instances test = subset(k + 1, k + 1);

            int step = k - 1;
            trainRows[step] = train.numInstances();
            testRows[step] = test.numInstances();

            Classifier model =
                    CrossValidator.buildModel(kind, featureSelection, balancing, train);

            // A fresh Evaluation per step. Unlike cross validation, the steps
            // are not folds of one partition - each is a separate experiment
            // with a different amount of training data, and pooling them would
            // hide exactly the trend this table exists to show.
            Evaluation evaluation = new Evaluation(train);
            List<Npofb.Entry> predictions = new ArrayList<>(test.numInstances());

            for (int i = 0; i < test.numInstances(); i++) {

                Instance instance = test.instance(i);

                Instance masked = (Instance) instance.copy();
                masked.setDataset(instance.dataset());
                masked.setClassMissing();

                double[] distribution = model.distributionForInstance(masked);
                evaluation.evaluateModelOnceAndRecordPrediction(distribution, instance);

                predictions.add(new Npofb.Entry(
                        distribution[positiveIndex],
                        instance.value(sizeIndex),
                        (int) instance.classValue() == positiveIndex));
            }

            collected.get(CrossValidator.Metric.PRECISION)[step] =
                    evaluation.precision(positiveIndex);
            collected.get(CrossValidator.Metric.RECALL)[step] =
                    evaluation.recall(positiveIndex);
            collected.get(CrossValidator.Metric.AUC)[step] =
                    evaluation.areaUnderROC(positiveIndex);
            collected.get(CrossValidator.Metric.KAPPA)[step] = evaluation.kappa();

            // NPofB20 per step is more meaningful here than it is under cross
            // validation: the test set IS one complete release, so "20% of the
            // lines" is 20% of a real codebase at a real point in time.
            collected.get(CrossValidator.Metric.NPOFB20)[step] =
                    Npofb.nPofB20(predictions);
            collected.get(CrossValidator.Metric.POFB20)[step] =
                    Npofb.pofB20(predictions);

            if (verbose) {
                System.out.printf(
                        "    train 1..%-2d (%5d rows) -> test %-2d (%4d rows)   "
                                + "prec %.2f  rec %.2f  AUC %.2f  kappa %.2f  NPofB20 %.2f%n",
                        k, train.numInstances(), k + 1, test.numInstances(),
                        collected.get(CrossValidator.Metric.PRECISION)[step],
                        collected.get(CrossValidator.Metric.RECALL)[step],
                        collected.get(CrossValidator.Metric.AUC)[step],
                        collected.get(CrossValidator.Metric.KAPPA)[step],
                        collected.get(CrossValidator.Metric.NPOFB20)[step]);
            }
        }

        return new Result(collected, trainRows, testRows);
    }

    /** Every row whose release lies in [from, to], sharing the ARFF header. */
    private Instances subset(int from, int to) {
        Instances subset = new Instances(all, 0);
        for (int i = 0; i < all.numInstances(); i++) {
            if (version[i] >= from && version[i] <= to) {
                subset.add(all.instance(i));
            }
        }
        return subset;
    }
}