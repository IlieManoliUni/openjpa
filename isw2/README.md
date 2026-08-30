# ISW2 — Apache OpenJPA

Coursework for *Ingegneria del Software 2*, Università di Roma Tor Vergata,
carried out on this fork of Apache OpenJPA.

| Module | Professor | Subject |
|---|---|---|
| Final project | Falessi | dataset construction, defect prediction, smells, automated refactoring |
| Software testing | De Angelis | test design, generation, adequacy, mutation, reliability |

Project assignment: last-name initial **M** → 13 mod 6 = 1 → **OpenJPA**.

---

## Requirements

- JDK 21 (build and run)
- JDK 11 (EvoSuite and Randoop only, added later)
- Maven 3.9.x

## Build

```
cd isw2/dataset-tool
mvn -B "-DskipTests" package
```

## Regenerating the results

Each stage is a class with its own `main`, run in this order. Output goes to
`isw2/dataset-tool/data/`, which is deliberately **not** versioned — the tool is the
deliverable and the data is reproducible by running it.

| Stage | Class | Produces |
|---|---|---|
| Releases and dates from JIRA | `isw2.openjpa.m1.ReleaseExtractor` | `data/OPENJPAVersionInfo.csv` |
| 66% cut + release-to-commit mapping | `isw2.openjpa.m1.ReleaseResolver` | `data/releases.csv` |
| Fixed bug tickets from JIRA | `isw2.openjpa.m1.TicketExtractor` | `data/tickets.csv` |
| Ticket / affected-version diagnostics | `isw2.openjpa.m1.TicketStats` | `data/ticket_stats.csv` |
| Proportion (Total): OV, FV, IV per ticket | `isw2.openjpa.m1.Proportion` | `data/proportion.csv` |
| NSmells for the last release (SonarCloud API) | `isw2.openjpa.m1.SonarSmells` | `data/smells.csv` |
| 16 git-history metrics per (class, release) | `isw2.openjpa.m1.MetricsComputer` | `data/metrics.csv` |
| NSmells per release (PMD over each checkout) | `isw2.openjpa.m1.PmdSmells` | `data/pmd_smells.csv` |
| SZZ labelling: buggy (class, release) pairs | `isw2.openjpa.m1.Labeler` | `data/buggy.csv` |
| **Join: the Milestone 1 dataset** | `isw2.openjpa.m1.DatasetBuilder` | `data/dataset.csv`, `data/dataset.arff` |

*(further stages are added as the project progresses)*

`SonarSmells` is listed under Milestone 1 because it collects data, but its consumer is
Milestone 4: the dataset's `NSmells` column comes from PMD, measured per release.

### Package layout

```
isw2.openjpa          Config - shared constants
isw2.openjpa.util     Csv (RFC 4180 reader), JiraClient (HTTP + JSON)
isw2.openjpa.m1       Milestone 1: dataset construction
isw2.openjpa.m2       Milestone 2: classifier accuracy
isw2.openjpa.m3       Milestone 3: what-if zero smells
isw2.openjpa.m4       Milestone 4: automated refactoring
```

Packages follow the milestones rather than technical concerns, so the code maps onto the
report and the slides. Shared helpers live in `util` so that no milestone depends on another.

Run a stage from the `isw2/dataset-tool` directory, for example:

```
mvn -B compile
mvn -q dependency:build-classpath "-Dmdep.outputFile=data/cp.txt"
java -cp "target/classes;$(cat data/cp.txt)" isw2.openjpa.m1.ReleaseExtractor
```

`dependency:build-classpath` writes the full classpath, including transitive dependencies,
to `data/cp.txt`; regenerate it whenever the POM changes. Stages can also be run from an IDE.

Two stages need external configuration:

- `SonarSmells` requires a SonarCloud token, from `$SONAR_TOKEN` or a `sonar-token.txt`
  file kept outside this repository.
- `PmdSmells` requires PMD 7, from `$PMD_HOME` or a `tools/pmd-bin-*` folder outside this
  repository.

## Relationship to the code provided in class

Milestone 1 supplies Java code for release identification and ticket identification.
This project starts from that code rather than reimplementing it. Every deviation from
it is deliberate, minimal, and recorded, and is described in the report's Methodology
and Threats to validity sections.

## Layout

```
isw2/
├── README.md
├── .gitignore
└── dataset-tool/
    ├── pom.xml
    ├── src/main/java/isw2/openjpa/    (Config, util/, m1/, m2/ ...)
    └── data/                        (generated, not versioned)
```
