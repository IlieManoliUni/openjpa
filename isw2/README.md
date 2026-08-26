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
| Releases and dates from JIRA | `isw2.openjpa.ReleaseExtractor` | `data/OPENJPAVersionInfo.csv` |

*(further stages are added as the project progresses)*

Run a stage from the `isw2/dataset-tool` directory, for example:

```
mvn -B "-DskipTests" compile
java -cp "target/classes;<org.json jar>" isw2.openjpa.ReleaseExtractor
```

or directly from an IDE.

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
    ├── src/main/java/isw2/openjpa/
    └── data/                        (generated, not versioned)
```
