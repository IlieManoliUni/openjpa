package isw2.openjpa.m3;

import java.io.IOException;
import java.nio.file.Path;

import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;

/**
 * The four datasets of the what-if analysis, as defined in the reference
 * study and in step 5 of the Milestone 3 assignment:
 *
 *   A   the original dataset
 *   B+  the portion of A with NSmells > 0        - the classes that have smells
 *   C   the portion of A with NSmells = 0        - the classes that do not
 *   B   B+ with the NSmells feature set to 0     - the counterfactual
 *
 * B is the whole point. It asks: take exactly the classes that do have smells,
 * and tell the model they have none. Everything else about them - their size,
 * their change history, their authors - stays as it is. The difference between
 * what the model predicts for B+ and what it predicts for B is the model's
 * estimate of what the smells were costing.
 *
 * B has no ground truth and never can. It describes code that was never written.
 * That is why the results table has an "actual" column for A, B+ and C but only
 * an "estimated" column for B.
 */
public final class WhatIfDatasets {

    private final Instances all;      // A
    private final Instances smelly;   // B+
    private final Instances zeroed;   // B
    private final Instances clean;    // C
    private final int smellsIndex;
    private final int positiveIndex;

    private WhatIfDatasets(Instances all, Instances smelly, Instances zeroed,
                           Instances clean, int smellsIndex, int positiveIndex) {
        this.all = all;
        this.smelly = smelly;
        this.zeroed = zeroed;
        this.clean = clean;
        this.smellsIndex = smellsIndex;
        this.positiveIndex = positiveIndex;
    }

    public static WhatIfDatasets load(Path arff) throws Exception {

        DataSource source = new DataSource(arff.toString());
        Instances all = source.getDataSet();
        if (all == null) {
            throw new IOException("could not read " + arff.toAbsolutePath());
        }
        all.setClassIndex(all.numAttributes() - 1);

        if (all.attribute("NSmells") == null) {
            throw new IllegalArgumentException("dataset has no NSmells attribute");
        }
        int smellsIndex = all.attribute("NSmells").index();

        int positiveIndex = all.classAttribute().indexOfValue("yes");
        if (positiveIndex < 0) {
            throw new IllegalArgumentException("class attribute has no value \"yes\"");
        }

        // B+ and C partition A on the smell count.
        Instances smelly = new Instances(all, 0);
        Instances clean = new Instances(all, 0);
        for (int i = 0; i < all.numInstances(); i++) {
            if (all.instance(i).value(smellsIndex) > 0.0) {
                smelly.add(all.instance(i));
            } else {
                clean.add(all.instance(i));
            }
        }

        // B is B+ with NSmells zeroed. Instances.add() copies the instance and
        // reattaches it to the receiving dataset, so writing to the copies here
        // cannot reach back into B+. The copy constructor would NOT be safe:
        // new Instances(smelly) shares the underlying Instance objects, and
        // zeroing them would silently destroy B+ as well.
        Instances zeroed = new Instances(smelly, 0);
        for (int i = 0; i < smelly.numInstances(); i++) {
            zeroed.add(smelly.instance(i));
        }
        for (int i = 0; i < zeroed.numInstances(); i++) {
            zeroed.instance(i).setValue(smellsIndex, 0.0);
        }

        WhatIfDatasets datasets =
                new WhatIfDatasets(all, smelly, zeroed, clean, smellsIndex, positiveIndex);
        datasets.check();
        return datasets;
    }

    /**
     * The invariants that must hold if the split is right. B+ and C partition A,
     * so their sizes and their buggy counts must both add up; B must have the
     * same size as B+ and no smells left; and B+ must still have its smells,
     * which is the assertion that catches the aliasing mistake described above.
     */
    private void check() {

        if (smelly.numInstances() + clean.numInstances() != all.numInstances()) {
            throw new IllegalStateException(String.format(
                    "B+ (%d) + C (%d) != A (%d)",
                    smelly.numInstances(), clean.numInstances(), all.numInstances()));
        }
        if (buggy(smelly) + buggy(clean) != buggy(all)) {
            throw new IllegalStateException("buggy counts of B+ and C do not sum to A");
        }
        if (zeroed.numInstances() != smelly.numInstances()) {
            throw new IllegalStateException("B and B+ differ in size");
        }
        for (int i = 0; i < zeroed.numInstances(); i++) {
            if (zeroed.instance(i).value(smellsIndex) != 0.0) {
                throw new IllegalStateException("B still has a smell at row " + i);
            }
        }
        for (int i = 0; i < smelly.numInstances(); i++) {
            if (smelly.instance(i).value(smellsIndex) <= 0.0) {
                throw new IllegalStateException(
                        "B+ lost its smells at row " + i + " - B was built by aliasing");
            }
        }
    }

    /** Number of rows actually labelled buggy. Undefined for B by construction. */
    public int buggy(Instances instances) {
        int count = 0;
        for (int i = 0; i < instances.numInstances(); i++) {
            if ((int) instances.instance(i).classValue() == positiveIndex) {
                count++;
            }
        }
        return count;
    }

    public Instances all()    { return all; }
    public Instances smelly() { return smelly; }
    public Instances zeroed() { return zeroed; }
    public Instances clean()  { return clean; }

    public int smellsIndex()   { return smellsIndex; }
    public int positiveIndex() { return positiveIndex; }

    public String describe() {
        return String.format(
                "A  %5d rows  %4d buggy (%.1f%%)%n"
                        + "B+ %5d rows  %4d buggy (%.1f%%)   NSmells > 0%n"
                        + "C  %5d rows  %4d buggy (%.1f%%)   NSmells = 0%n"
                        + "B  %5d rows     - counterfactual   B+ with NSmells set to 0",
                all.numInstances(), buggy(all), 100.0 * buggy(all) / all.numInstances(),
                smelly.numInstances(), buggy(smelly),
                100.0 * buggy(smelly) / smelly.numInstances(),
                clean.numInstances(), buggy(clean),
                100.0 * buggy(clean) / clean.numInstances(),
                zeroed.numInstances());
    }
}