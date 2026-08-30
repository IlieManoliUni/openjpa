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

/**
 * The walk-forward counterpart to ClassifierEvaluator: the same twelve cells,
 * evaluated the way the effort-aware metrics study evaluates them.
 *
 * Writes:
 *   m2_walkforward.csv        one row per cell, averaged over the 13 steps
 *   m2_walkforward_steps.csv  every step of every cell, so the learning curve
 *                             as training data accumulates is visible
 *
 * Much cheaper than the cross validation: 13 model builds per cell instead of
 * 100, so roughly an eighth of the runtime.
 *
 * Usage: same as ClassifierEvaluator - no arguments runs everything, or name
 * the classifiers to run (RandomForest / NaiveBayes / Ibk).
 */
public class WalkForwardEvaluator {

    private static final Path ARFF     = Path.of("data", "dataset.arff");
    private static final Path CSV      = Path.of("data", "dataset.csv");
    private static final Path OUT_MAIN = Path.of("data", "m2_walkforward.csv");
    private static final Path OUT_STEP = Path.of("data", "m2_walkforward_steps.csv");

    private record Cell(ClassifierKind classifier, boolean featureSelection,
                        Balancing balancing) { }

    private static final Map<Cell, WalkForward.Result> RESULTS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {

        List<String> flags = Arrays.asList(args);

        List<ClassifierKind> classifiers = new ArrayList<>();
        for (ClassifierKind kind : ClassifierKind.values()) {
            if (flags.isEmpty() || flags.contains(kind.label())) {
                classifiers.add(kind);
            }
        }
        if (classifiers.isEmpty()) {
            classifiers.addAll(Arrays.asList(ClassifierKind.values()));
        }

        WalkForward walkForward = WalkForward.load(ARFF, CSV);

        System.out.printf("dataset   : %d rows, %d releases%n",
                walkForward.dataset().numInstances(), walkForward.releases());
        System.out.printf("validation: walk-forward, %d steps "
                        + "(train 1..k, test k+1, k = 1..%d)%n",
                walkForward.releases() - 1, walkForward.releases() - 1);

        for (Balancing balancing : List.of(Balancing.NONE, Balancing.OVERSAMPLING)) {
            for (boolean featureSelection : List.of(false, true)) {
                for (ClassifierKind kind : classifiers) {

                    Cell cell = new Cell(kind, featureSelection, balancing);

                    System.out.printf("%n  %s | FS %s | balancing %s%n",
                            kind.label(),
                            featureSelection ? "yes" : "no",
                            balancing.label());

                    long start = System.currentTimeMillis();
                    WalkForward.Result result =
                            walkForward.run(kind, featureSelection, balancing);
                    RESULTS.put(cell, result);

                    System.out.printf(
                            "    -> mean over %d steps: precision %.2f  recall %.2f  "
                                    + "AUC %.2f  kappa %.2f  NPofB20 %.2f  (PofB20 %.2f)  [%.0f s]%n",
                            result.steps(),
                            result.mean(CrossValidator.Metric.PRECISION),
                            result.mean(CrossValidator.Metric.RECALL),
                            result.mean(CrossValidator.Metric.AUC),
                            result.mean(CrossValidator.Metric.KAPPA),
                            result.mean(CrossValidator.Metric.NPOFB20),
                            result.mean(CrossValidator.Metric.POFB20),
                            (System.currentTimeMillis() - start) / 1000.0);

                    write();
                }
            }
        }

        System.out.println();
        System.out.println("done. m2_walkforward.csv and m2_walkforward_steps.csv written");
    }

    private static void write() throws IOException {

        Files.createDirectories(OUT_MAIN.getParent());

        try (Writer out = Files.newBufferedWriter(OUT_MAIN, StandardCharsets.UTF_8)) {

            out.write("Dataset,Classifier,FS,Balancing,Steps,"
                    + "Precision,Recall,AUC,Kappa,NPofB20,PofB20,AUCStdDev\n");

            for (Map.Entry<Cell, WalkForward.Result> entry : RESULTS.entrySet()) {
                Cell cell = entry.getKey();
                WalkForward.Result result = entry.getValue();

                out.write(prefix(cell));
                out.write(",");
                out.write(String.valueOf(result.steps()));
                for (CrossValidator.Metric metric : CrossValidator.Metric.values()) {
                    out.write(",");
                    out.write(number(result.mean(metric)));
                }
                out.write(",");
                out.write(number(result.stdDev(CrossValidator.Metric.AUC)));
                out.write("\n");
            }
        }

        try (Writer out = Files.newBufferedWriter(OUT_STEP, StandardCharsets.UTF_8)) {

            out.write("Dataset,Classifier,FS,Balancing,Step,TrainReleases,TrainRows,"
                    + "TestRelease,TestRows,Precision,Recall,AUC,Kappa,NPofB20,PofB20\n");

            for (Map.Entry<Cell, WalkForward.Result> entry : RESULTS.entrySet()) {
                Cell cell = entry.getKey();
                WalkForward.Result result = entry.getValue();

                for (int step = 0; step < result.steps(); step++) {
                    out.write(prefix(cell));
                    out.write(String.format(Locale.US, ",%d,1-%d,%d,%d,%d",
                            step + 1, step + 1, result.trainRows(step),
                            step + 2, result.testRows(step)));
                    for (CrossValidator.Metric metric : CrossValidator.Metric.values()) {
                        out.write(",");
                        out.write(number(result.values(metric)[step]));
                    }
                    out.write("\n");
                }
            }
        }
    }

    private static String prefix(Cell cell) {
        return Config.PROJECT_KEY + ","
                + cell.classifier().label() + ","
                + (cell.featureSelection() ? "Yes" : "No") + ","
                + (cell.balancing() == Balancing.NONE ? "No" : "Yes");
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.4f", value);
    }
}