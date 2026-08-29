# Changes to the code provided in class

Milestone 1 slide 7 provides Java code for **release identification** and **ticket
identification** (`getReleaseInfo.java`, `RetrieveTicketsID.java`). This project starts
from that code.

Rule applied: **change only what is provably necessary, and record every change here.**
"Provably necessary" means the original either cannot run on OPENJPA, or produces a
demonstrably wrong or non-reproducible result on OPENJPA. Anything else is left alone,
including things that could be written better.

This file is the source for the Methodology and Threats to validity sections.

---

## Changes made

### C1 - project key
| | |
|---|---|
| File | `getReleaseInfo.java`, `RetrieveTicketsID.java` |
| Original | `String projName = "QPID";` / `"ACCUMULO"` |
| Changed to | `"OPENJPA"` |
| Necessary because | The provided files are worked examples on other Apache projects. They cannot address our assigned project unchanged. |

### C2 - releases keyed by version id, not by release date
| | |
|---|---|
| File | `getReleaseInfo.java` |
| Original | `releaseNames.put(dateTime, name)` and `releaseID.put(dateTime, id)`, both `HashMap<LocalDateTime, String>`, with `if (!releases.contains(dateTime))` guarding the list |
| Changed to | keyed by JIRA version id, which is unique per version |
| Necessary because | Two OpenJPA versions share a release date, so the second overwrites the first and is dropped from the list entirely. |
| Evidence | `2.4.3` and `3.0.0` were both released on **2018-06-12** (2.4.x is a maintenance line, 3.0.0 opens a new major line; both were cut the same day). Date keying yields 41 releases instead of 42. |
| Consequence if not fixed | The 66% rule divides by the release count: 42 -> keep the first **14**; 41 -> keep the first **13**. A collision between two discarded 2018 releases would silently remove one early release from the dataset, changing every per-release count in Milestone 1. |

### C3 - deterministic ordering for equal release dates
| | |
|---|---|
| File | `getReleaseInfo.java` |
| Original | `Collections.sort(releases, comparator on LocalDateTime)` - date only |
| Changed to | date, then version number as tie-break |
| Necessary because | With date as the only sort key, the relative order of two same-date releases is whatever order the JIRA API returned them in. The output is therefore not reproducible across runs, and release order is what defines the interval `[IV, FV)` used for labelling. |
| Evidence | Same `2.4.3` / `3.0.0` pair as C2. |

### C4 - output path
| | |
|---|---|
| File | both |
| Original | `new FileWriter(projName + "VersionInfo.csv")` - current working directory |
| Changed to | written under `isw2/dataset-tool/data/` |
| Necessary because | Later pipeline stages consume this file. It needs a fixed, known location rather than one that depends on where the program was launched from. The `data/` folder is gitignored so results are regenerated, not versioned. |

### C5 - dates stored as `LocalDate`, not midnight `LocalDateTime`
| | |
|---|---|
| File | `getReleaseInfo.java` |
| Original | `LocalDate.parse(strDate).atStartOfDay()`, printed as `2006-12-01T00:00` |
| Changed to | the `LocalDate` itself, printed as `2006-08-26` |
| Necessary because | Not necessary; recorded for completeness. JIRA's `releaseDate` has no time component, so widening it to midnight adds a field that carries no information and makes the CSV harder to read. No downstream stage uses a time of day. Same information either way. |

---

### C6 - dependency versions

The provided `GetTicketID` pom declares `org.json:json:20240303` and no other dependency.
This project uses:

| Dependency | Provided pom | Here | Reason |
|---|---|---|---|
| `org.json:json` | `20240303` | `20260814` | **Not necessary.** Upgraded to the current release. The API used (`JSONObject`, `JSONArray`, `has`, `getString`, `optBoolean`) is unchanged between the two. |
| `org.eclipse.jgit` | not present | `7.2.1.202505142326-r` | Required: reading git history is step 4.1 of the workflow, for which no code is provided. Version chosen after the IDE's dependency checker reported a published security advisory against the 6.10.0 release originally selected. JGit 7.x requires Java 17+, which this project already targets. |

Note also that the provided pom is not valid XML as distributed: it contains the text
`(or latest version)` inside the `<dependency>` element, after `</version>`.

## Noticed and deliberately NOT changed

Recorded so the decisions are visible, and because several are worth discussing at the oral.

| Observation | Why left alone |
|---|---|
| `if (releases.size() < 6) return;` - unexplained magic number, silently produces no output | Harmless on OPENJPA (42 releases). Not necessary to change. |
| The `released` boolean on a JIRA version is ignored; only presence of `releaseDate` is checked | **Verified equivalent on OPENJPA.** Of 51 versions, 42 carry a `releaseDate` and 42 have `released == true`; versions dated-but-not-released = 0, released-but-undated = 0. The two filters select the same set, so his check is not a source of error here. No longer a threat, a measured fact. |
| `readAll()` reads the HTTP response one character at a time | Slower than reading in bulk, but correct. Not a correctness issue. |
| No handling of HTTP errors - a non-JSON error response fails with an opaque exception | Apache JIRA is public and stable. Not necessary. |
| Two parallel `HashMap`s instead of one release object | Style, not correctness. |
| Pre-release versions are treated as releases | Six of the 42 dated versions are pre-releases of 2.0.0: `2.0.0-M1`, `-M2`, `-M3`, `-beta`, `-beta2`, `-beta3`. JIRA marks all six `released: true` with a real date, so the provided filter accepts them. Excluding them would be a filter we invented, and it would change the release count from 42 to 36 and the 66% cut from 14 kept releases to 12. **Decision: follow the provided code and include them.** The provided sample output `QPIDVersionInfo.csv` confirms this is his own practice: its first three rows are `M1`, `M2` and `M2.1` - milestone builds listed as ordinary releases. Consequence recorded as a threat: three snapshots of the same in-development 2.0.0 fall inside the kept third (indices 10, 12, 13), only weeks apart, so those rows are strongly correlated - which compounds the known problem that 10x10 cross-validation mixes releases of the same class. |

---

## Written by us, not provided

No code was provided for these; they are ours and are described in full in the Methodology.

- Mapping each JIRA release to its **last git commit** (workflow step 4.1 - required to check
  out the code at that revision, but no provided code covers it).
- Applying the 66% rule.
- Everything downstream: metrics, Proportion, SZZ labelling, NSmells, dataset assembly.

---

## Verification of C2 / C3

`ReleaseExtractor` run against the live JIRA API, output `data/OPENJPAVersionInfo.csv`:

```
total JIRA versions      : 51
with releaseDate         : 42   (== released == true, see the table above)
rows written             : 42
duplicate version ids    : 0
dates sorted             : yes
```

The two same-date releases are both present and correctly ordered:

```
31,12338939,2.4.3,2018-06-12
32,12338127,3.0.0,2018-06-12
```

Under the original date-keyed implementation this file contains **41** rows and one of
those two lines is absent, which moves the 66% cut from 14 kept releases to 13.

---

## Interpretation of "ignore last 66% of releases"

The Milestone 1 workflow says *"Ignore last 66% of releases"*. "Last" means the most
**recent** 66%; the oldest third is kept. Releases 1-14 (2006-2010) are analysed,
releases 15-42 are discarded.

Justification is `10_Snoring.pdf`, which exists to motivate exactly this rule:

- *"It is possible that a defect is only discovered or fixed several releases after its
  introduction"* (sleeping defect / dormant bug).
- *"The existence of a defect cannot be known before the defect is fixed."*
- *"Datasets are biased (i.e., many FN) because the measurement upon which the datasets
  are computed (e.g., SZZ) cannot consider the bugs that exist and are not yet fixed."*

SZZ can only label a class buggy once a defect has been fixed and linked to a commit.
Recent releases have not had time for their defects to surface, so their classes are
labelled clean when they are not - false negatives, the "snoring" phenomenon. Older
releases have had years for defects to be found and fixed, so their labels are
comparatively trustworthy.

The direction is confirmed by the paper's own RQ5 design: *"Remove the **last** 1,2,3,4
releases from the TrS dataset"* - the most recent ones are removed, not the oldest.
Reported consequence of not doing so: *"The relative error in measuring the classifiers'
accuracy achieved by using a dataset with snoring is about 100% in all accuracy metrics
other than AUC."*

Keeping the newest third instead would therefore build a dataset composed almost entirely
of snoring classes.

---

## Provided example outputs, and how we follow them

Two example files ship with the slides. Neither is code; both define an expected format.

### `11_Milestone 1 - Dataset creation.csv` - expected M1 dataset

16,339 rows over 10 versions, 20 columns:

```
Version, File Name, Method Name, LOC, LOC_touched, NR, NFix, NAuth, LOC_added,
MAX_LOC_added, AVG_LOC_added, Churn, MAX_Churn, AVG_Churn, ChgSetSize, MAX_ChgSet,
AVG_ChgSet, Age, WeightedAge, Buggy
```

Two ways it differs from the Milestone 1 slide, and what we do about each:

| Difference | Decision |
|---|---|
| The example has **no NSmells column** and no project-name column; the slide requires both (NSmells is needed by M3 and M4). | Follow the **slide**. The example predates this year's NSmells requirement. |
| 5,046 of its 16,339 rows are **not Java files** (`.gitignore`, `LICENSE`, `.travis.yml`, `CHANGELOG.asciidoc`, `DISCLAIMER`). The slide workflow says "For each java class". | Follow the **slide**: `.java` only. Recorded because it explains why our row counts do not scale like his. |
| Its `Method Name` column is present but empty in every row. | Class-level granularity, as the slide specifies. |

**Adopted from it:** the exact metric column names (`LOC_touched`, `NR`, `NFix`, `NAuth`,
`MAX_LOC_added`, `AVG_LOC_added`, `MAX_Churn`, `AVG_Churn`, `ChgSetSize`, `MAX_ChgSet`,
`AVG_ChgSet`, `Age`, `WeightedAge`), so our output is directly comparable to his.

Its values are synthetic - it reports `.gitignore` as having 1.6e7 lines of code - so it is a
format template, not reference data.

### `15_2_ExampleOfOutputD2M2.csv` - expected M2 results table

```
Dataset,Classifier,FS,Balancing,Precision,Recall,AUC,Kappa,NPofB20
```

`FS` and `Balancing` are **Yes/No**, so the table is 3 classifiers x 2 x 2 = **12 rows**.
Balancing deck 13_1 presents three techniques (SpreadSubsample, Resample, SMOTE); the
expected output has room for one. We therefore report his 12-row table with a documented
choice for "Balancing = Yes", and give the three-way comparison as an additional table.

### Confirmation from the provided example dataset

`11_Milestone 1 - Dataset creation.csv` is built on Apache **TinkerPop** (identifiable from
paths such as `giraph-gremlin/src/main/java/org/apache/tinkerpop/...`). It contains 10
versions, renumbered 1..10. Three independent signals establish that version 1 is the
*oldest* release analysed, not the newest:

| Signal | Observation |
|---|---|
| `DISCLAIMER` - required by Apache while a project is incubating, removed at graduation. TinkerPop graduated in 2016. | present in versions **1-6**, absent from 7-10 |
| `.travis.yml` - added to the project later | absent from 1-4, present from **5** onward |
| `jsr223/` - the newer plugin API superseding `groovy/plugin/` | grows 24 -> 147 across versions 1 -> 10, while `groovy/plugin` stays flat at ~38 |

The codebase also grows monotonically, 1,369 -> 2,116 files.

The direction is therefore settled: index 1 is the oldest release analysed, so the rule is
**keep the oldest third, discard the most recent 66%** - the same reading the snoring paper
argues for, reached from his practice rather than his rationale.

**The proportion is a separate question, and the example does not confirm it.** TinkerPop
has 77 dated releases in JIRA today; ten were kept, which is 13%, not 33%. Two factors
explain the gap. The example was built years ago, when the denominator was smaller. And -
see below - his date-keyed code collapses same-date releases, which shrinks the numerator.
What binds this project is the slide text ("Ignore last 66% of releases"), applied literally:
42 OpenJPA releases, keep the first 14.

### The provided example dataset itself exhibits the C2 defect

TinkerPop's first twelve dated releases contain three same-date pairs, because 3.1.x and
3.2.x were maintained in parallel and cut on the same day:

```
 6  2016-04-08  3.1.2-incubating  |  7  2016-04-08  3.2.0-incubating
 8  2016-07-18  3.1.3             |  9  2016-07-18  3.2.1
10  2016-09-06  3.1.4             | 11  2016-09-06  3.2.2
```

Collapsing each pair to one entry, as date-keying does, makes version 6 the last
pre-graduation date (2016-04-08) and version 7 the first post-graduation one (2016-07-18,
when the `-incubating` suffix was dropped). His dataset shows `DISCLAIMER` - the file Apache
requires of incubating projects - in versions **1-6** and absent from **7** onward, matching
that collapsed numbering exactly. Had both members of each pair been kept, version 7 would
still be `3.2.0-incubating` and would still carry the `DISCLAIMER`.

His ten versions therefore span thirteen actual TinkerPop releases. This is strong
corroboration rather than proof - the alignment of the two boundaries is hard to attribute
to coincidence, but the example could in principle have been built another way.

---

## Release-to-commit mapping (no provided code)

Workflow step 4.1 requires the last commit of each release. JIRA supplies a name and a
date; git supplies commits. `ReleaseResolver` bridges the two, in this order:

1. a tag whose name equals the JIRA version name, peeled to a commit (`name^{commit}`);
2. failing that, `name-incubating` - OpenJPA was an Apache Incubator project until 2007;
3. failing that, the newest commit on Apache's `master` dated on or before the release day.

Result over the 42 releases: **37 by exact tag, 2 by `-incubating` tag, 3 by date fallback,
0 unresolved.** The three requiring the fallback are `0.9.0` (2006-08-26), `2.0.0-M1`
(2009-01-29) and `2.0.0-M2` (2009-06-03) - all three inside the kept 14.

Decisions recorded for Threats to validity:

| Decision | Rationale |
|---|---|
| Exact tag name is tried **before** any variant | The repository also carries tags such as `openjpa-parent-2.4.0`, produced by the Maven release plugin, which tag the parent POM rather than the release. Exact-first prevents matching those. |
| History is searched on `refs/remotes/upstream/master` | Apache's own branch. The local `master` carries this project's commits; searching upstream makes the result independent of anything we commit. |
| The cutoff is midnight UTC at the end of the release day | JIRA dates carry no time of day; git commit times are absolute instants. UTC is an assumption; a release published late on the release date in another timezone could in principle select the previous day's commit. |
| The date fallback is an approximation | For the three untagged releases there is no authoritative commit. The chosen commit is the newest that existed on the release date, which is the best available proxy for "the code as released". |

**Cross-validation:** all 14 kept releases resolve to commit SHAs identical to those produced
by a separate, independently written implementation, including the three obtained by the
date fallback.
