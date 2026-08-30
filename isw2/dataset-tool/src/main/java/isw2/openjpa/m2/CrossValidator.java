package isw2.openjpa.m2;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.meta.FilteredClassifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AttributeSelection;

/**
 * The validation technique named in the Milestone 2 assignment:
 * "Utilizzando la tecnica di validazione 10 times 10-folds".
 *
 * Ten independent 10-fold cross validations, seeds 1..10. Within one repetition
 * the ten folds partition the dataset, so every row is predicted exactly once by
 * a model that never saw it; the ten fold results are pooled into a single
 * Evaluation. The ten repetitions are then averaged. Repeating with different
 * shuffles is what turns a single lucky or unlucky partition into an estimate
 * with a spread you can report.
 *
 * FAIRNESS - this is the point the feature-selection material raises and leaves
 * open ("Is the evaluation fair?").
 *
 * Both preprocessing steps are wrapped in FilteredClassifier rather than applied
 * to the dataset up front:
 *
 *   - Attribute selection fitted on the whole dataset would let the 1477 rows
 *     about to be tested vote on which attributes matter. That is selection
 *     leakage and it inflates AUC.
 *
 *   - Balancing applied before the split is worse. Resample draws WITH
 *     replacement, so the same buggy row can be copied into both the training
 *     and the test fold. IBk with k=1 then finds its own duplicate at distance
 *     zero and predicts it perfectly. Recall approaches 1.0 and the number is
 *     pure artefact.
 *
 * FilteredClassifier fits its filter on exactly the data handed to
 * buildClassifier - the training fold - and at prediction time applies the
 * attribute filter to the incoming instance while letting supervised INSTANCE
 * filters pass it through unchanged. So the test fold is never resampled and
 * never votes on its own attributes.
 */
public final class CrossValidator {

    public static final int FOLDS = 10;
    public static final int REPETITIONS = 10;

    /** The metrics the milestone asks for, plus PofB20 for the comparison. */
    public enum Metric {

        PRECISION("Precision"),
        RECALL("Recall"),
        AUC("AUC"),
        KAPPA("Kappa"),
        NPOFB20("NPofB20"),
        /** Not requested by the milestone. Carried so the report can show what
         *  the normalisation in NPofB20 actually bought - RQ2 of the published study. */
        POFB20("PofB20");

        private final String label;

        Metric(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Averages over the ten repetitions, and the raw ten values behind them. */
    public static final class Result {

        private final Map<Metric, double[]> perRepetition;

        private Result(Map<Metric, double[]> perRepetition) {
            this.perRepetition = perRepetition;
        }

        public double mean(Metric metric) {
            double[] values = perRepetition.get(metric);
            double sum = 0.0;
            for (double value : values) {
                sum += value;
            }
            return sum / values.length;
        }

        /** Sample standard deviation across the ten repetitions. Not part of
         *  the required CSV, but it is how you answer "is this difference
         *  real?" when he asks which classifier is best. */
        public double stdDev(Metric metric) {
            double[] values = perRepetition.get(metric);
            if (values.length < 2) {
                return 0.0;
            }
            double mean = mean(metric);
            double sum = 0.0;
            for (double value : values) {
                sum += (value - mean) * (value - mean);
            }
            return Math.sqrt(sum / (values.length - 1));
        }

        public double[] values(Metric metric) {
            return perRepetition.get(metric).clone();
        }
    }

    private final Instances data;
    private final int positiveIndex;
    private final int sizeIndex;
    private boolean verbose = true;

    /**
     * @param data the M1 dataset, class attribute already set to Buggy
     */
    public CrossValidator(Instances data) {

        this.data = data;

        this.positiveIndex = data.classAttribute().indexOfValue("yes");
        if (positiveIndex < 0) {
            throw new IllegalArgumentException(
                    "class attribute has no value \"yes\": " + data.classAttribute());
        }

        // LOC is the effort proxy for NPofB20. It is read from the UNFILTERED
        // instance, so it stays available even when attribute selection has
        // removed LOC from the view the classifier is trained on.
        if (data.attribute("LOC") == null) {
            throw new IllegalArgumentException("dataset has no LOC attribute");
        }
        this.sizeIndex = data.attribute("LOC").index();
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Runs the full 10x10 for one cell of the experiment matrix.
     */
    public Result run(ClassifierKind kind, boolean featureSelection, Balancing balancing)
            throws Exception {

        Map<Metric, double[]> collected = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            collected.put(metric, new double[REPETITIONS]);
        }

        long start = System.currentTimeMillis();

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {

            // Seed = repetition + 1, so the ten shuffles are 1..10 and the whole
            // experiment reruns identically on any machine.
            Random random = new Random(repetition + 1L);

            Instances shuffled = new Instances(data);
            shuffled.randomize(random);
            shuffled.stratify(FOLDS);

            // One Evaluation per repetition, accumulated across the ten folds.
            // Pooling rather than averaging per-fold values is what Weka's own
            // crossValidateModel does, and it is the only way AUC is meaningful:
            // an ROC curve from 1477 predictions is far too coarse.
            Evaluation evaluation = new Evaluation(shuffled);

            // Every row's cross-validated prediction, for the effort-aware
            // metrics. Pooling the folds is essential here too: NPofB20 asks
            // "what does 20% of the codebase buy", and 20% of a single fold is
            // not a codebase.
            List<Npofb.Entry> predictions = new ArrayList<>(shuffled.numInstances());

            for (int fold = 0; fold < FOLDS; fold++) {

                Instances train = shuffled.trainCV(FOLDS, fold, random);
                Instances test = shuffled.testCV(FOLDS, fold);

                if (verbose && repetition == 0 && fold == 0) {
                    reportFirstFold(train, featureSelection, balancing);
                }

                Classifier model = buildModel(kind, featureSelection, balancing, train);

                for (int i = 0; i < test.numInstances(); i++) {
                    Instance instance = test.instance(i);

                    // Weka's evaluateModelOnceAndRecordPrediction(Classifier,
                    // Instance) returns the predicted LABEL, not the class
                    // distribution - so it cannot give us P(buggy). We ask the
                    // classifier for the distribution ourselves and hand it to
                    // the array overload of the same method, which is exactly
                    // what Weka does internally. One prediction serves both the
                    // Evaluation object and NPofB20; for IBk that prediction is
                    // the expensive part, so computing it twice would double the
                    // runtime of the slowest configuration.
                    //
                    // The class value is blanked on the copy first, mirroring
                    // Weka's own implementation. No classifier reads the label
                    // it is predicting, but a FilteredClassifier runs supervised
                    // filters over the instance on the way in, and those can
                    // touch the class attribute. Masking makes leakage
                    // impossible by construction rather than by trust.
                    Instance masked = (Instance) instance.copy();
                    masked.setDataset(instance.dataset());
                    masked.setClassMissing();

                    double[] distribution = model.distributionForInstance(masked);

                    // Records the prediction against the TRUE label, so
                    // precision / recall / AUC / kappa all see the real answer.
                    evaluation.evaluateModelOnceAndRecordPrediction(distribution, instance);

                    predictions.add(new Npofb.Entry(
                            distribution[positiveIndex],
                            instance.value(sizeIndex),
                            (int) instance.classValue() == positiveIndex));
                }
            }

            collected.get(Metric.PRECISION)[repetition] = evaluation.precision(positiveIndex);
            collected.get(Metric.RECALL)[repetition] = evaluation.recall(positiveIndex);
            collected.get(Metric.AUC)[repetition] = evaluation.areaUnderROC(positiveIndex);
            collected.get(Metric.KAPPA)[repetition] = evaluation.kappa();
            collected.get(Metric.NPOFB20)[repetition] = Npofb.nPofB20(predictions);
            collected.get(Metric.POFB20)[repetition] = Npofb.pofB20(predictions);

            if (verbose) {
                System.out.printf(
                        "    repetition %2d/%d  AUC %.4f  (%.1f s elapsed)%n",
                        repetition + 1, REPETITIONS,
                        collected.get(Metric.AUC)[repetition],
                        (System.currentTimeMillis() - start) / 1000.0);
            }
        }

        return new Result(collected);
    }

    /**
     * Assembles the classifier for one cell of the matrix.
     *
     * Nesting, outermost first:
     *     FilteredClassifier(attribute selection)
     *       -> FilteredClassifier(balancing)
     *            -> the base classifier
     *
     * so attribute selection sees the fold's real class distribution and the
     * balancing filter sees the reduced attribute set. Relevance is a property
     * of the real data; class exposure is a property of the learner.
     */
    static Classifier buildModel(ClassifierKind kind, boolean featureSelection,
                                  Balancing balancing, Instances train) throws Exception {

        Classifier model = kind.build();

        Filter balancingFilter = balancing.filterFor(train);
        if (balancingFilter != null) {
            FilteredClassifier balanced = new FilteredClassifier();
            balanced.setFilter(balancingFilter);
            balanced.setClassifier(model);
            model = balanced;
        }

        if (featureSelection) {
            FilteredClassifier selected = new FilteredClassifier();
            selected.setFilter(newSelectionFilter());
            selected.setClassifier(model);
            model = selected;
        }

        model.buildClassifier(train);
        return model;
    }

    /**
     * The filter approach from the feature-selection material, wired exactly
     * as in the provided example: CfsSubsetEval scores subsets by
     * correlation with the class minus redundancy between attributes, and
     * GreedyStepwise walks the subset space backwards - starting from all 17
     * attributes and dropping the least damaging one at a time.
     *
     * Backwards rather than forwards because that is what the provided example sets
     * (search.setSearchBackwards(true)), and because with only 17 attributes
     * the cost difference is irrelevant while backward search is better at
     * keeping attributes that are useful only in combination.
     *
     * A fresh instance per fold: a filter carries the subset it learned, so
     * reusing one across folds would leak the first fold's choice into all ten.
     */
    static Filter newSelectionFilter() {

        CfsSubsetEval evaluator = new CfsSubsetEval();

        GreedyStepwise search = new GreedyStepwise();
        search.setSearchBackwards(true);

        AttributeSelection selection = new AttributeSelection();
        selection.setEvaluator(evaluator);
        selection.setSearch(search);
        return selection;
    }

    /**
     * How often CFS keeps each attribute, across all 100 training folds.
     *
     * This is free to ask separately because CFS is a FILTER method: it never
     * consults the classifier, and balancing happens after it, so the subset
     * chosen for a given fold is the same for all three classifiers and both
     * balancing settings. One pass answers it for the whole experiment.
     *
     * The folds are regenerated with the same seeds and the same call order as
     * run(), so these are the actual subsets used in the results, not a
     * separate sample.
     */
    public Map<String, Integer> attributeSelectionFrequency() throws Exception {

        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (i != data.classIndex()) {
                frequency.put(data.attribute(i).name(), 0);
            }
        }

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {

            Random random = new Random(repetition + 1L);

            Instances shuffled = new Instances(data);
            shuffled.randomize(random);
            shuffled.stratify(FOLDS);

            for (int fold = 0; fold < FOLDS; fold++) {

                // trainCV consumes numbers from the Random, so calling it in
                // the same order as run() reproduces the same folds exactly.
                Instances train = shuffled.trainCV(FOLDS, fold, random);

                Filter selection = newSelectionFilter();
                selection.setInputFormat(train);
                Instances reduced = Filter.useFilter(train, selection);

                for (int i = 0; i < reduced.numAttributes(); i++) {
                    String name = reduced.attribute(i).name();
                    if (frequency.containsKey(name)) {
                        frequency.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        return frequency;
    }

    /**
     * Prints what the preprocessing actually did on the first fold, so the
     * balancing is something you can point at rather than assert.
     */
    private void reportFirstFold(Instances train, boolean featureSelection,
                                 Balancing balancing) throws Exception {

        System.out.printf("    fold 1 training set: %s%n", describe(train));

        if (featureSelection) {
            Filter selection = newSelectionFilter();
            selection.setInputFormat(train);
            Instances reduced = Filter.useFilter(train, selection);

            StringBuilder kept = new StringBuilder();
            for (int i = 0; i < reduced.numAttributes(); i++) {
                if (i != reduced.classIndex()) {
                    if (kept.length() > 0) {
                        kept.append(", ");
                    }
                    kept.append(reduced.attribute(i).name());
                }
            }
            System.out.printf("      after CFS: %d of %d attributes [%s]%n",
                    reduced.numAttributes() - 1, train.numAttributes() - 1, kept);
        }

        Filter balancingFilter = balancing.filterFor(train);
        if (balancingFilter != null) {
            balancingFilter.setInputFormat(train);
            Instances balanced = Filter.useFilter(train, balancingFilter);
            System.out.printf("      after %s: %s%n", balancing.label(), describe(balanced));
        }
    }

    private String describe(Instances instances) {
        int buggy = 0;
        for (int i = 0; i < instances.numInstances(); i++) {
            if ((int) instances.instance(i).classValue() == positiveIndex) {
                buggy++;
            }
        }
        return String.format("%d rows (%d no / %d yes, %.1f%% buggy)",
                instances.numInstances(),
                instances.numInstances() - buggy,
                buggy,
                100.0 * buggy / instances.numInstances());
    }
}