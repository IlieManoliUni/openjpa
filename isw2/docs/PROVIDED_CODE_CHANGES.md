# Changes to the code provided in class

The Milestone 1 assignment provides Java code for **release identification** and **ticket
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
| The `released` boolean on a JIRA version is ignored; only presence of `releaseDate` is checked | **Verified equivalent on OPENJPA.** Of 51 versions, 42 carry a `releaseDate` and 42 have `released == true`; versions dated-but-not-released = 0, released-but-undated = 0. The two filters select the same set, so the provided check is not a source of error here. No longer a threat, a measured fact. |
| `readAll()` reads the HTTP response one character at a time | Slower than reading in bulk, but correct. Not a correctness issue. |
| No handling of HTTP errors - a non-JSON error response fails with an opaque exception | Apache JIRA is public and stable. Not necessary. |
| Two parallel `HashMap`s instead of one release object | Style, not correctness. |
| Pre-release versions are treated as releases | Six of the 42 dated versions are pre-releases of 2.0.0: `2.0.0-M1`, `-M2`, `-M3`, `-beta`, `-beta2`, `-beta3`. JIRA marks all six `released: true` with a real date, so the provided filter accepts them. Excluding them would be a filter we invented, and it would change the release count from 42 to 36 and the 66% cut from 14 kept releases to 12. **Decision: follow the provided code and include them.** The provided sample output the provided sample output confirms this is the reference practice: its first three rows are `M1`, `M2` and `M2.1` - milestone builds listed as ordinary releases. Consequence recorded as a threat: three snapshots of the same in-development 2.0.0 fall inside the kept third (indices 10, 12, 13), only weeks apart, so those rows are strongly correlated - which compounds the known problem that 10x10 cross-validation mixes releases of the same class. |

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

The justification is the course material on snoring, which exists to motivate exactly this rule:

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

Two example files are provided with the assignment. Neither is code; both define an expected format.

### the provided example dataset - expected M1 dataset

16,339 rows over 10 versions, 20 columns:

```
Version, File Name, Method Name, LOC, LOC_touched, NR, NFix, NAuth, LOC_added,
MAX_LOC_added, AVG_LOC_added, Churn, MAX_Churn, AVG_Churn, ChgSetSize, MAX_ChgSet,
AVG_ChgSet, Age, WeightedAge, Buggy
```

Two ways it differs from the Milestone 1 assignment, and what we do about each:

| Difference | Decision |
|---|---|
| The example has **no NSmells column** and no project-name column; the assignment requires both (NSmells is needed by M3 and M4). | Follow the **assignment**. The example predates this year's NSmells requirement. |
| 5,046 of its 16,339 rows are **not Java files** (`.gitignore`, `LICENSE`, `.travis.yml`, `CHANGELOG.asciidoc`, `DISCLAIMER`). The assignment says "For each java class". | Follow the **assignment**: `.java` only. Recorded because it explains why our row counts do not scale like the reference. |
| Its `Method Name` column is present but empty in every row. | Class-level granularity, as the assignment specifies. |

**Adopted from it:** the exact metric column names (`LOC_touched`, `NR`, `NFix`, `NAuth`,
`MAX_LOC_added`, `AVG_LOC_added`, `MAX_Churn`, `AVG_Churn`, `ChgSetSize`, `MAX_ChgSet`,
`AVG_ChgSet`, `Age`, `WeightedAge`), so our output is directly comparable to the reference.

Its values are synthetic - it reports `.gitignore` as having 1.6e7 lines of code - so it is a
format template, not reference data.

### the provided example output - expected M2 results table

```
Dataset,Classifier,FS,Balancing,Precision,Recall,AUC,Kappa,NPofB20
```

`FS` and `Balancing` are **Yes/No**, so the table is 3 classifiers x 2 x 2 = **12 rows**.
Balancing deck 13_1 presents three techniques (SpreadSubsample, Resample, SMOTE); the
expected output has room for one. We therefore report the required 12-row table with a documented
choice for "Balancing = Yes", and give the three-way comparison as an additional table.

### Confirmation from the provided example dataset

the provided example dataset is built on Apache **TinkerPop** (identifiable from
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
argues for, reached from the reference practice rather than its stated rationale.

**The proportion is a separate question, and the example does not confirm it.** TinkerPop
has 77 dated releases in JIRA today; ten were kept, which is 13%, not 33%. Two factors
explain the gap. The example was built years ago, when the denominator was smaller. And -
see below - the provided date-keyed code collapses same-date releases, which shrinks the numerator.
What binds this project is the assignment text ("Ignore last 66% of releases"), applied literally:
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
when the `-incubating` suffix was dropped). The reference dataset shows `DISCLAIMER` - the file Apache
requires of incubating projects - in versions **1-6** and absent from **7** onward, matching
that collapsed numbering exactly. Had both members of each pair been kept, version 7 would
still be `3.2.0-incubating` and would still carry the `DISCLAIMER`.

Its ten versions therefore span thirteen actual TinkerPop releases. This is strong
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

---

## Ticket retrieval, and unreleased affected versions

`TicketExtractor` is based on the provided `RetrieveTicketsID.java`. Kept unchanged: the JQL
query, the paging loop (`j = i + 1000`, `startAt`/`maxResults`, `i % 1000` page indexing) and
the field list `key,resolutiondate,versions,created`. Changed of necessity: the provided
version prints ticket keys to the console; Proportion needs the three date/version fields
persisted, so this writes `data/tickets.csv`.

Note on the field list: JIRA's `versions` is **Affects Version/s**, not Fix Version/s (which
is `fixVersions`). The provided code requests exactly the three fields Proportion needs -
`created` gives OV, `resolutiondate` gives FV, `versions` gives IV where recorded.

Result: **1,133 tickets** match the query (`type=Bug`, `status in {closed,resolved}`,
`resolution=fixed`).

### Decision: affected versions that are not dated releases are ignored

OpenJPA's JIRA holds 51 versions, of which **9 have no `releaseDate` and all 9 have
`released=false`** - versions that were planned and never shipped:

```
0.9.7-r547073 (archived), 1.3.0, 2.0.2, 2.1.2, 2.2.1.1, 2.2.3, 2.4.4, 3.2.3, 4.2.0
```

Five of them are referenced as affected versions by tickets:

| unreleased version | mentions |
|---|---|
| `1.3.0` | 64 |
| `2.1.2` | 21 |
| `2.2.3` | 19 |
| `2.2.1.1` | 16 |
| `2.0.2` | 14 |
| **total** | **134** |

(Counts produced by `TicketStats`, which splits the field on `;` and compares names exactly.)

IV is defined as the **minimum** affected version, and "minimum" presupposes a position in
release order. A version that never shipped has no release date and therefore no position:
there is no fact of the matter about whether `1.3.0` precedes or follows `2.0.0`.

**Rule adopted:** an affected-version entry that does not match a dated release is ignored.
A ticket retains a usable IV if at least one of its affected versions is a dated release.

Effect, measured:

| | tickets |
|---|---|
| total | 1,133 |
| no affected version recorded | 189 |
| affected versions, all dated releases | 831 |
| affected versions, partly undated - minimum still available | 101 |
| affected versions, none dated - unusable | 12 |
| **usable IV taken directly from affected versions** | **932** |
| **IV must be estimated via P** | **201** |

The rule costs **12 tickets**, not 134: most tickets referencing an unreleased version also
reference a released one, so the minimum is still available.

These counts are regenerated by `isw2.openjpa.TicketStats`, which also writes
`data/ticket_stats.csv`. It asserts that the buckets sum to the ticket total, so the figures
quoted in the report cannot silently drift from the data.

---

## Proportion (Total) — no provided code

Formulas are taken verbatim from the Proportion material:

```
P            = (FV - IV) / (FV - OV)
predicted IV = FV - (FV - OV) * P
```

and the consistency rule from the same deck, RQ1: *"is the AV consistent, i.e., is the
oldest AV not after the OV?"* — that is, `IV <= OV`.

The deck names three ways to compute P (Cold Start, Increment, Moving Window); none is
"Total". "Total" comes from the Milestone 1 assignment, which defines it: *"compute and use P on
all tickets"* — one P over the whole ticket set.

`P >= 1` is structural, not an anomaly: consistency forces `IV <= OV`, hence
`FV - IV >= FV - OV`. The deck's baseline method "Simple" is `IV = OV`, i.e. `P = 1`, so P
measures how much earlier than the opening version defects are injected.

### Decision: how OV and FV are derived

JIRA gives dates, not versions (the provided field list is `created`, `resolutiondate`,
`versions`; `fixVersions` is not requested). Both OV and FV must be derived from a date, and
each admits two readings. All four combinations were computed on the real data:

| OV rule | FV rule | tickets usable for P | P | FV==OV discarded | inconsistent |
|---|---|---|---|---|---|
| first release >= created | first release >= resolved | 262 | 2.365 | 458 | 207 |
| first release >= created | last release <= resolved | 152 | 2.046 | 90 | 685 |
| **last release <= created** | **first release >= resolved** | **676** | **2.109** | **2** | **249** |
| last release <= created | last release <= resolved | 220 | 2.080 | 200 | 512 |

**Adopted: OV = last release on or before the report date; FV = first release on or after the
resolution date.**

Reasons:

1. **Semantics.** OV is the version the defect was opened against. A reporter runs *released*
   software; a version that has not shipped cannot be the one in which a bug was observed.
   FV is the first release that actually contains the fix.
2. **It removes an artefact.** Under `first >= created`, a defect reported and fixed between
   releases N and N+1 gets OV = FV = N+1, so `FV - OV = 0` and the ticket is unusable. That
   discards 458 tickets (40%) for a reason that is an artefact of the definition rather than a
   property of the data. Under the adopted rule the same defect has OV = N, FV = N+1, and the
   count falls to 2.
3. **It makes the professor's consistency rule meaningful.** Affected versions are always
   released versions. If OV were a future release, `IV <= OV` would hold almost automatically
   and the check would test nothing. With OV as the current released version the rule rejects
   249 tickets - it does real work.

### Result on OPENJPA

```
tickets                                    1133
  no OV (reported before the first release)  17
  no FV (resolved after the last release)     5
  AV present but inconsistent               249
  FV == OV (P undefined)                      2
  clean AV tickets used to compute P        676

P = 2.109

IV taken from AV                            678
IV predicted via P                          433
unusable (no OV or no FV)                    22
```

Other decisions recorded for Threats to validity:

| Decision | Rationale |
|---|---|
| Dates compared at day granularity (`substring(0,10)`) | JIRA returns an instant with a UTC offset; releases carry a bare date. Comparing at day granularity discards time-of-day, which a release does not have anyway. |
| Tickets with `FV == OV` are excluded from P but still receive an IV | `FV - OV = 0` makes P undefined. The prediction degenerates to `IV = FV`, which is correct, so only the learning of P is affected. |
| The prediction is clamped to `[1, OV]` | Rounding can place the estimate outside the valid range. Clamping enforces the same consistency rule used to filter the training pool. |
| Tickets with no OV or no FV get no IV | 17 were reported before the first release, 5 resolved after the last. Fabricating a version for them would invent data. |

---

## NSmells: two tools, each for the job it suits

The Milestone 1 workflow says *"Compute NSmells via SonarCloud **or PMD or similar**"*. Both
are used here, for different milestones, because neither alone can answer both questions.

| Use | Tool | Reason |
|---|---|---|
| M1 dataset (and therefore M3) | **PMD**, per release checkout | Only method that measures each class *as it was at that release* |
| M4 class ranking + refactoring prompt | **SonarCloud**, last release | The M4 prompt template requires *"(report SonarCloud diagnostic)"*, and the last release is modern code that builds |

### Why SonarCloud cannot produce the per-release column

A SonarCloud analysis measures the code as it is now. Applying today's counts to releases
from 2006-2010 records a class that became smelly in 2015 as smelly in 2007 - the feature
would be measured *after* the bugginess it is meant to predict.

Running SonarCloud on each release checkout is not possible: its Java analyser needs
compiled classes, so each release would have to build. Release 1 (`0.9.0`) is from 2006;
its POMs reference two-decade-old plugins and its source targets Java 1.4. It cannot be
built with Maven 3.9 and JDK 21.

**PMD analyses source directly and compiles nothing**, so it runs against any checkout
regardless of whether its build still works. That property is what makes a per-release
NSmells column achievable.

### Feasibility spike (PMD 7.27.0 on release 1, `dc1f0bf204`, 2006-08-26)

| Question | Result |
|---|---|
| Parse errors on 2006 Java? | **none** - `--use-version` not required |
| Violations found | 14,333 across 744 files |
| `.java` files in the checkout | 966 total; **932 under `src/main`** |
| Runtime | under a minute |
| Processing errors | 2 - rule `UseStandardCharsets` crashing on `TimeUnit.java` (divide-by-zero in PMD's constant folder). Other rules still ran on that file. |

932 `src/main` files matches exactly the class count recorded for release 1 by a separate
earlier implementation, confirming that `src/main` is the correct scope.

Decisions:

| Decision | Rationale |
|---|---|
| Rulesets `design`, `errorprone`, `bestpractices` | Closest analogue to SonarCloud's maintainability rules. Balanced in practice: 5,985 / 3,969 / 4,379 violations. |
| `src/main` only | Test code is not in the dataset. Confirmed by the 932-file match. |
| The `UseStandardCharsets` crash is tolerated, not excluded | Excluding the rule would change the smell count of every class to work around a bug affecting one file. Bounded, documented limitation. |
| PMD CSV must be parsed quote-aware | 2,272 of 14,333 rows (16%) contain commas inside the quoted `Description` field and would be corrupted by a naive split. |

### SonarCloud analysis, as run

`mvn ... sonar:sonar` on the full reactor, with:

- `SONAR_SCANNER_JAVA_OPTS=-Xmx4g` - the scanner engine has its own JVM; `MAVEN_OPTS` does
  not reach it. A first attempt failed after 90 minutes with `OutOfMemoryError` inside the
  taint-analysis engine (`com.sonar.security`) at 1.8 GB, i.e. on the default heap.
- `sonar.scanner.skipJreProvisioning=true` - the scanner otherwise downloads its own JRE.
- exclusions: `**/src/test/**`, `**/openjpa-examples/**`, `**/openjpa-integration/**`,
  `**/openjpa-tools/**`, `**/openjpa-project/**`.

The exclusions are methodological, not merely pragmatic: tests, sample applications,
integration harnesses, build tooling and documentation are not product classes and do not
belong in the dataset or in the M4 ranking.

Result: **1,515 Java files, 10,664 code smells, 532 files with zero smells.**

Cross-check against a separate earlier run: `ExpressionImpl` = 1 smell in both.
`BrokerImpl` = 184 here against 152 previously, consistent with OpenJPA having moved from
4.1.2-SNAPSHOT to 4.2.0-SNAPSHOT in the interim.

---

## Metric definitions resolved from the provided example dataset

The Milestone 1 metric list defines two metrics with identical wording:

> **LOC Touched***: sum over revisions of LOC added and deleted.
> **Churn***: sum over revisions of added and deleted LOC.

Read literally these are the same quantity. The provided example dataset
(the provided example dataset) shows they are not:

```
Churn == 2*LOC_added - LOC_touched    holds for 16,242 / 16,339 rows (99.4%)
rows with negative Churn              567
rows with Churn > LOC_touched         0
```

Rearranging `Churn = 2·added − touched` gives `touched = added + deleted` and
`Churn = added − deleted`. The 567 negative values confirm it: a sum of two
non-negative quantities cannot be negative. The 97 rows that miss are large values
where the reference rounding to three significant figures exceeds the tolerance.

**Adopted:** `LOC_touched = Σ(added + deleted)`, `Churn = Σ(added − deleted)`.

Two further ambiguities resolved from the same file:

| Metric | Assignment text | What the data shows | Adopted |
|---|---|---|---|
| `Age` | "age of release" | 328 distinct values among the 1,369 rows of version 1 | age of the **class** at the release, not of the release |
| `WeightedAge` | "age of release weighted by LOC touched" | 731 distinct values in version 1, minimum 0 | `Σ(age_i · touched_i) / Σ(touched_i)` over revisions |

### The 16 metrics as implemented

| # | Column | Definition |
|---|---|---|
| 1 | `LOC` | lines in the file at the release commit |
| 2 | `LOC_touched` | `Σ (added + deleted)` over revisions |
| 3 | `NR` | number of revisions |
| 4 | `NFix` | revisions that fix a defect |
| 5 | `NAuth` | distinct authors |
| 6 | `LOC_added` | `Σ added` |
| 7 | `MAX_LOC_added` | `max added` over revisions |
| 8 | `AVG_LOC_added` | `LOC_added / NR` |
| 9 | `Churn` | `Σ (added − deleted)` |
| 10 | `MAX_Churn` | `max (added − deleted)` |
| 11 | `AVG_Churn` | `Churn / NR` |
| 12 | `ChgSetSize` | `Σ` files committed alongside, over revisions |
| 13 | `MAX_ChgSet` | max files in one commit |
| 14 | `AVG_ChgSet` | `ChgSetSize / NR` |
| 15 | `Age` | release date − first commit touching the class |
| 16 | `WeightedAge` | `Σ(age_i · touched_i) / Σ(touched_i)` |

Scope decisions:

| Decision | Rationale |
|---|---|
| Cumulative **from release 0**, not per-interval | The metric list marks these `* = within the release or from release 0`. Only this reading makes `Age` and `WeightedAge` meaningful. |
| `NFix` from the **actual fixed-ticket set**, not a keyword heuristic | A revision counts as a fix when its message references a ticket key present in `tickets.csv`, i.e. an issue that is genuinely `type=Bug, resolution=Fixed`. A keyword search for "fix" would count refactorings and typo corrections. |
| Rename detection **on** | A class moved between packages keeps its history. Truncating every class's history at a package reorganisation is the larger distortion. |
| `src/main/**/*.java` only | Confirmed by the PMD spike: 932 such files at release 1, matching the class count from a separate implementation. |

---

## Release ancestry: releases are not a chain

Measured with `git merge-base --is-ancestor` over the 14 kept release commits
(row = ancestor, column = descendant):

```
      1  2  3  4  5  6  7  8  9 10 11 12 13 14
 1    .  Y  Y  Y  Y  Y  Y  Y  Y  Y  Y  Y  Y  Y
 2    -  .  -  -  -  -  -  -  -  -  -  -  -  -
 3    -  -  .  -  -  -  -  -  -  -  -  -  -  -
 4..9 -  -  -  .  -  -  -  -  -  -  -  -  -  -   (each an ancestor of nothing)
10    -  -  -  -  -  -  -  -  -  .  -  Y  Y  -
11    -  -  -  -  -  -  -  -  -  -  .  -  -  Y
12    -  -  -  -  -  -  -  -  -  -  -  .  Y  -
13    -  -  -  -  -  -  -  -  -  -  -  -  .  -
```

Release 1 is an ancestor of every other release. **Releases 2 through 9 are ancestors of
nothing.** The only further chains are `10 -> 12 -> 13` (trunk, the 2.0.0 line) and
`11 -> 14` (the 1.2.x maintenance line).

Every release from 0.9.6 to 1.2.0 was cut on its own branch and never merged back into the
line the next release came from. "Release 3 follows release 2" is therefore true by date and
false by code lineage: they are siblings, not parent and child.

Consequences, all of which are already visible in this project's results:

| Consequence | Where it shows |
|---|---|
| A single history walk cannot compute metrics for all releases | `MetricsComputer` aggregates each release over its own ancestor set; a walk from the newest release would miss most of another release's history |
| Renames must be applied per release | A rename performed on trunk must not be applied when aggregating a 1.2.x release. Applying them globally left 53 classes with `NR = 0` |
| Metrics need not increase with release *index* | They increase only along true ancestor edges. Verified: over all 17 real ancestor pairs, zero classes show a decrease in `Age`, `NR` or `NFix` |
| Ordering releases by date is an approximation | This is the mechanism behind the ~20% of tickets rejected as inconsistent by Proportion's validity filter: `OV` and `FV` are derived from a date-ordered release list that does not reflect lineage |

### Validation of the metrics stage

```
rows written                     14,769   (14 releases)
classes at release 1                932   (= the PMD spike's src/main count)
classes at release 13             1,290
NR == 0                               0
Age < 0                               0
NFix > NR                             0
Churn > LOC_touched                   0
Age/NR/NFix decreasing along
  any real ancestor edge              0   (17 pairs checked)
```

Two defects found and fixed during development, both recorded here because each changed the
numbers:

1. **Age measured from JIRA's release date** rather than the release commit's timestamp gave
   4 classes a negative age: JIRA's date is midnight UTC and the tagged commit can fall later
   the same day. The age baseline is now the commit.
2. **Renames applied globally** rather than per release attributed pre-rename changes to paths
   that did not yet exist, leaving 53 classes with no history at all - concentrated in the
   early releases, where the `openjpa-*-5` Java-5 modules were later folded into the main
   modules. Renames are now filtered to the release's ancestry and applied in commit-time
   order, the latter so the result does not depend on hash iteration order.

---

## SZZ labelling

A commit fixes ticket K when its message references K *and* K is one of the 1,133 confirmed
`type=Bug, resolution=Fixed` tickets. The `src/main` `.java` files that commit changes are
the buggy classes, labelled buggy for every release in `[IV, FV)` - half-open, because FV is
the release that contains the fix.

All refs are walked, not just `master`: the ancestry matrix shows releases scattered across
maintenance branches, so a fix landing on `1.2.x` is invisible from trunk.

```
commits scanned                7,951
fixing commits                 2,712
tickets matched                  888   (unmatched 222)
tickets contributing labels      511   (the rest have [IV,FV) outside releases 1-14)
buggy (class, release) pairs   3,851
  ... matching a metrics row   2,994
  ... with no metrics row        857
buggy rate                     20.3%
```

### The 857 unmatched pairs are an SZZ property, not a defect

Proportion can place IV earlier than the class's own creation. Example: `DBIdentifier.java`
is labelled buggy at release 7, but that class was introduced in OpenJPA 2.x and does not
exist in 1.1.0. The join discards such pairs because no `(class, release)` row exists for
them. Recorded as a threat: the estimated injection version is not constrained by the class's
lifetime.

### Buggy rate per release

```
v 1 11.7%   v 2 21.8%   v 3 21.0%   v 4 14.5%   v 5 13.8%
v 6 16.0%   v 7 26.3%   v 8 20.9%   v 9 26.0%   v10 32.3%
v11 28.4%   v12 17.6%   v13 15.7%   v14 16.6%
```

The fall at releases 12 and 13 - the newest kept - is **snoring** visible in this project's
own data: defects in recent releases have not yet been discovered and fixed, so SZZ cannot
label them and those releases appear cleaner than they are. This is the effect the 66% rule
exists to limit, and it argues that the rule could be applied more aggressively still.

Comparison with a separate earlier implementation: 6,006 pairs and 17.3% buggy, against 3,851
and 20.3% here. The difference follows from the corrected `OV` definition, which changed every
`IV` and therefore the length of every `[IV, FV)` interval.

---

# Milestone 1 — results

`dataset.csv`: one row per (class, release), 14 releases, **14,769 rows**.

```
Project,Version,FileName,LOC,LOC_touched,NR,NFix,NAuth,LOC_added,MAX_LOC_added,
AVG_LOC_added,Churn,MAX_Churn,AVG_Churn,ChgSetSize,MAX_ChgSet,AVG_ChgSet,Age,
WeightedAge,NSmells,Buggy
```

`dataset.arff` carries the 17 features and the label only. `Project`, `Version` and
`FileName` are deliberately excluded from the ARFF: a path would be memorised as a nominal
attribute with 14,769 values, and `Version` would let a classifier learn "later release =
buggier", which is an artefact of snoring rather than a property of the code. `Buggy` is
declared `{no,yes}` so that `yes` is the positive class for Weka's `areaUnderROC(1)`, the
call used in the provided evaluation example.

### How many classes per release, and are they stable?

```
v 1   932    v 2   951    v 3   952    v 4  1002    v 5  1003
v 6  1008    v 7  1049    v 8  1008    v 9  1055    v10  1166
v11  1055    v12  1243    v13  1290    v14  1055
```

Monotonic growth along each branch, 932 to 1,290, with no discontinuities. Releases 8, 11
and 14 are smaller than their date-neighbours because they sit on the 1.0.x and 1.2.x
maintenance branches rather than trunk.

### What fraction are buggy?

**2,994 of 14,769 = 20.3%** - a realistic, imbalanced defect distribution.

Per release the rate rises through the middle of the window and falls at releases 12 and 13,
the newest kept. That fall is snoring in this project's own data.

### Do smells relate to bugs?

| | classes | buggy | rate |
|---|---|---|---|
| NSmells > 0 | 10,990 | 2,580 | **23.5%** |
| NSmells = 0 | 3,779 | 414 | **11.0%** |
| | | | **2.14x** |

NSmells ranges 0 to 430, median 3, with 3,779 classes (25.6%) carrying none. That clean
quarter is what makes Milestone 3's "what if zero smells" a real counterfactual rather than a
vacuous one.

A separate earlier implementation reported 22.2% versus 8.2% (2.7x) using SonarCloud counts
from current code projected onto old releases. The figures here are measured per release by
PMD on each release's own source, so the association is established on temporally correct
data rather than on a proxy.

### Pipeline summary

| Stage | Class | Output | Key figure |
|---|---|---|---|
| Releases from JIRA | `ReleaseExtractor` | `OPENJPAVersionInfo.csv` | 42 releases |
| 66% cut + commit mapping | `ReleaseResolver` | `releases.csv` | 14 kept, 0 unresolved |
| Fixed bug tickets | `TicketExtractor` | `tickets.csv` | 1,133 |
| AV diagnostics | `TicketStats` | `ticket_stats.csv` | 932 usable IV |
| Proportion (Total) | `Proportion` | `proportion.csv` | P = 2.109 |
| 16 git metrics | `MetricsComputer` | `metrics.csv` | 14,769 rows |
| NSmells per release | `PmdSmells` | `pmd_smells.csv` | PMD, 14 checkouts |
| NSmells last release | `SonarSmells` | `smells.csv` | 10,664 smells |
| SZZ labelling | `Labeler` | `buggy.csv` | 2,994 matched pairs |
| Join | `DatasetBuilder` | `dataset.csv`, `.arff` | 14,769 rows, 20.3% buggy |

---

## Feature set: what is included, and what is not

The Milestone 1 assignment asks for *"About 20 features + NSmells"* and then enumerates **16**
class metrics. All 16 are implemented, under the specified column names, plus NSmells - **17
features**. The gap between "about 20" and 17 is the assignment's phrasing, not an omission: every metric
he names is present.

### The Kamei commit metrics are deliberately not included

The assignment also lists an optional commit-metric set - `NS, ND, NF, Entropy, LA, LD, LT, FIX,
NDEV, AGE, NUC, EXP, REXP, SEXP` - with the instruction *"Include only what you use; justify."*
They are not included, for three reasons:

1. **Wrong unit of analysis.** They describe a *change*, not a *(class, release)* snapshot.
   The dataset's row is a class at a release; a commit metric would have to be aggregated over
   every revision of that class, at which point it duplicates metrics already present
   (`NF` becomes `ChgSetSize`, `LA`/`LD` become `LOC_added` and the deleted half of
   `LOC_touched`, `NDEV` becomes `NAuth`, `AGE` becomes `Age`, `FIX` becomes `NFix`).
2. **Redundancy inflates the feature space** without adding signal, which matters for
   Milestone 2's feature selection: near-duplicate features distort a CFS subset evaluation.
3. **The three that are genuinely new** - `Entropy`, `NUC` and the developer-experience trio
   `EXP`/`REXP`/`SEXP` - would need a developer-history model the assignment does not ask for.

The set actually used is the one the provided example dataset uses, which contains exactly these
16 columns and no commit metrics.

## Milestone 1 completeness check

| Requirement | Status |
|---|---|
| Project via the last-name algorithm | OPENJPA |
| Columns: project, class, release, features + NSmells, bugginess | all present |
| The 16 named class metrics | 16/16, the specified column names |
| Kamei commit metrics | omitted, justified above |
| Releases and dates | 42 |
| Ignore the last 66% | 14 kept (oldest third) |
| Last commit of each release | 42/42 resolved |
| Check out the code at that revision | JGit tree walk |
| Per class: name, features, NSmells | 14,769 rows |
| Assume all classes not buggy initially | default `no` |
| Tickets: Bug and Closed/Resolved and Fixed | 1,133 |
| Proportion (Total) with SZZ | P = 2.109 |
| Reuse the provided Java code | both classes; deviations C1-C6 |
| Results: classes per release, stability | 932 -> 1,290, monotonic |
| Results: fraction buggy | 20.3% |

ARFF validation: 18 attributes, 14,769 data rows, 0 malformed rows, 0 missing values, class
attribute last and declared `{no,yes}`.

---

# Milestone 2 — classifier accuracy

The Milestone 2 assignment states:

> Compare l'accuratezza (Precision/Recall/AUC/Kappa/NPofB20), di tre classificatori
> (RandomForest / NaiveBayes / Ibk), sul progetto selezionato precedentemente,
> utilizzando la tecnica di validazione 10 times 10-folds. Utilizzare filtra come feature
> selection e balancing.

Milestone 2 provides no Java program to adapt. What it provides is three short worked
examples on Weka's `breast-cancer` sample data, plus one expected output format.

| Provided file | What it shows | How it is used here |
|---|---|---|
| the provided evaluation example | load ARFF, set class index, build, evaluate, read `areaUnderROC(1)` and `kappa()` | the evaluation idiom, including `setClassIndex(numAttributes() - 1)` and the positive-class index `1` |
| the provided sampling example | `SpreadSubsample` / `Resample` / `SMOTE` inside a `FilteredClassifier` | the balancing wiring, adopted as-is |
| the provided feature-selection example | `CfsSubsetEval` + `GreedyStepwise(backwards)` via the `AttributeSelection` filter | the feature-selection wiring, with the change in M2-C2 |
| the provided example output | the required column set and row order | reproduced exactly; its numbers are placeholders (e.g. AUC 0.73 with Kappa 0.72) and are not targets |

Same rule as Milestone 1: **change only what is provably necessary, and record it here.**

---

## Changes made

### M2-C1 - `Resample -Z` and `SMOTE -P`: the formula in the balancing material sits under the wrong heading

| | |
|---|---|
| Source | the balancing material |
| Printed | "**Oversampling** - noReplacement=false, biasToUniformClass=1.0, and sampleSizePercent=Y, `Y = 100 * (majority - minority)/minority`. Example for the diabetes data: `Resample -B 1.0 -Z 130.3`" |
| Problem | For Weka's `diabetes.arff` (768 rows: 500 negative, 268 positive) the printed formula gives `100*(500-268)/268 = 86.6`, not the 130.3 of the material's own worked example. The two disagree. |
| Resolution | The example is correct for `Resample`; the formula is correct for `SMOTE`. It has been typed one bullet too high. |

`Resample -Z` is *sample size as a percentage of the input*. To end up with `majority` rows
of each of the `k` classes, the output must hold `k * majority` rows:

```
Z = 100 * k * majority / total
diabetes: 100 * 2 * 500 / 768 = 130.2      matches the stated 130.3
OPENJPA : 100 * 2 * 11775 / 14769 = 159.46
```

`SMOTE -P` is *how many new minority rows to synthesise, as a percentage of the minority
class*. To close the gap exactly:

```
P = 100 * (majority - minority) / minority
diabetes: 100 * (500-268)/268 = 86.6   ->  268 + 232 = 500 = majority, exactly balanced
OPENJPA : 100 * 8781 / 2994 = 293.29   -> 2994 + 8781 = 11775 = majority
```

That is the printed formula, and it is exact. It belongs to the SMOTE bullet one line below.

Why the example rather than the formula was followed: with `-B 1.0` **every** value of `-Z`
produces a balanced result, because `-Z` only sets the total size. The discriminator is the
section's own heading. On diabetes, `-Z 130.2` keeps all 500 majority rows (pure
oversampling); `-Z 86.6` produces 665 rows, 332 per class, silently discarding 168 majority
rows - a hybrid over/under sample, which is the *other* bullet's job. On OPENJPA the
printed reading would have discarded 5,380 real non-buggy classes while the report called
it oversampling.

Both constants are recomputed per training fold in `Balancing.filterFor(Instances)` rather
than hard-coded, and the run prints the before/after class counts on the first fold:

```
fold 1 training set: 13292 rows (10597 no / 2695 yes, 20.3% buggy)
  after Oversampling: 21194 rows (10597 no / 10597 yes, 50.0% buggy)
```

`10597` unchanged on both lines is the evidence that this is oversampling and not the
hybrid. Reverting to the printed reading is one line in `Balancing.oversamplingPercent`.

### M2-C2 - feature selection is fitted inside `FilteredClassifier`, not on the dataset

| | |
|---|---|
| File | the provided feature-selection example |
| Original | `filter.setInputFormat(noFilterTraining); Instances filteredTraining = Filter.useFilter(noFilterTraining, filter); ... Instances testingFiltered = Filter.useFilter(testingNoFilter, filter);` |
| Changed to | `FilteredClassifier` wrapping the `AttributeSelection` filter, rebuilt per fold |
| Necessary because | The provided code is a **holdout** example, and as written for a holdout it is correct - the subset is learned from `noFilterTraining` and merely applied to the test set. The corresponding exercise, however, instructs "Perform 10-fold cross validation". Carrying the code over unchanged means fitting the subset once on all 14,769 rows and then cross-validating, so the 1,477 rows being predicted in each fold helped choose the attributes they are scored on. That is selection leakage. |

`FilteredClassifier` fits its filter on exactly the data passed to `buildClassifier` - the
training fold - and at prediction time applies the attribute filter to the incoming
instance while letting supervised *instance* filters pass it through untouched. One wrapper
therefore makes both preprocessing steps fold-local.

The balancing example, the provided sampling example, already uses `FilteredClassifier` and
needed no change. Only the feature-selection example did.

Nesting order used, outermost first:

```
FilteredClassifier(CfsSubsetEval + GreedyStepwise backwards)
  -> FilteredClassifier(Resample)
       -> base classifier
```

Feature selection sees the fold's real 20.3/79.7 distribution, and balancing sees the
reduced attribute set. CFS scores an attribute by its correlation with the class net of its
correlation with the other attributes; measuring those correlations on rows that are
duplicates of each other, against a class prior that was invented, would not be measuring
relevance. Relevance is a property of the real data; class exposure is a property of the
learner.

### M2-C3 - a fresh `Evaluation` per configuration

| | |
|---|---|
| File | the provided feature-selection example |
| Original | one `Evaluation evalClass` is created, used for the unfiltered model, then reused for the filtered model without being reset |
| Changed to | a new `Evaluation` per repetition (cross validation) and per step (walk-forward) |
| Necessary because | `Evaluation.evaluateModel` **accumulates**. In the provided example the line printed as "AUC filtered" is computed over both models' predictions pooled together, not over the filtered model alone. Any comparison drawn from those two printouts is between a model and a mixture containing itself. |

---

## Weka API traps encountered

### `evaluateModelOnceAndRecordPrediction` returns a label, not a distribution

`Evaluation` carries two overloads whose names are identical:

```java
double evaluateModelOnceAndRecordPrediction(Classifier c, Instance i)   // returns Utils.maxIndex(dist)
void   evaluateModelOnceAndRecordPrediction(double[] dist, Instance i)  // takes a distribution
```

The first returns the **predicted class index**, not `P(buggy)`. NPofB20 needs the
probability, so the first overload cannot supply it. The compiler catches the type
mismatch; the obvious way to silence it - wrapping the returned `double` in a one-element
array - compiles and then either throws `ArrayIndexOutOfBoundsException` on index 1 or,
worse, silently fills every `Npofb.Entry` with a hard 0.0/1.0. Every prediction would tie,
and NPofB20 would become an artefact of the tie-break rule with no error anywhere.

Resolution: call `distributionForInstance` directly and hand the result to the array
overload. One prediction serves both the `Evaluation` and NPofB20, which matters because
for IBk that prediction is the expensive part.

The class value is blanked on a copy of the instance first, mirroring Weka's own
implementation. No classifier reads the label it is predicting, but a `FilteredClassifier`
runs supervised filters over the instance on the way in, and those can touch the class
attribute. Masking makes leakage impossible by construction rather than by trust.

## Written by us, not provided

| Class | Why it exists |
|---|---|
| `Npofb` | Weka has no effort-aware measure. `Evaluation` provides precision, recall, AUC and kappa; PofB20 and NPofB20 had to be implemented from the published definitions. |
| `CrossValidator` | Weka's `Evaluation.crossValidateModel` does a single 10-fold pass and exposes no per-instance probabilities, so it can neither do "10 times" nor feed NPofB20. |
| `WalkForward` | Not required by the milestone; added as a second table, see M2-T1. |
| `Balancing`, `ClassifierKind` | The filter and classifier configurations named in the assignment, in one place each. |
| `ClassifierEvaluator`, `WalkForwardEvaluator` | Drive the experiment matrix and write the CSVs. |

### NPofB20 as implemented

Rank the cross-validated predictions by `P(buggy) / LOC` descending, walk down accumulating
LOC until 20% of the total is consumed, and report `bugs found / bugs present`. PofB20 is
the same walk ranked by `P(buggy)` alone. Decisions taken where the assignment is silent:

| Decision | Rationale |
|---|---|
| The class that crosses the budget is counted as inspected | A developer who opens a file reads it. The alternative - skip anything that would overshoot - lets one large class block every smaller class behind it. |
| Size is charged a minimum of 1 LOC | A zero-size class would have infinite density, rank first, and cost nothing. |
| Ties broken by smaller size first | Among equally promising candidates the cheaper one is the rational choice, and it makes the result reproducible instead of dependent on fold order. |
| Predictions pooled across the 10 folds of a repetition, computed per step in walk-forward | 20% of one fold is not a codebase. In walk-forward each test set *is* one complete release, so the budget is 20% of a real codebase at a real point in time. |
| `LOC` read from the unfiltered instance | Feature selection removes `LOC` in 79 folds out of 100. The effort proxy must survive that, so it is read outside the `FilteredClassifier`. |

## Noticed and deliberately NOT changed

| Observation | Left alone because |
|---|---|
| No classifier is tuned; all three use Weka defaults | The milestone asks which classifier is most accurate. Tuning one by hand would measure how much attention each received. Proper tuning needs an inner validation loop, which is a different experiment. |
| `IBk` runs with k=1, its default | The assignment names "Ibk" with no parameters. Consequences are recorded in M2-T3 rather than engineered away. |
| 10 times 10-fold cross validation, though it ignores release order | It is what the milestone specifies. Its consequence is measured and reported in M2-T1, and walk-forward is added beside it rather than in place of it. |
| `GreedyStepwise` searches backwards | `search.setSearchBackwards(true)` is what the provided example sets. |
| Balancing "Yes" is one technique, not three | The expected output has a single Yes/No column. The three-way comparison is a separate table. |
| The netlib/ARPACK warning at startup | `weka-stable` looks for a native BLAS, does not find one, and loads its bundled reference implementation. Nothing used here touches ARPACK. |

---

# Milestone 2 — results

Dataset: the Milestone 1 output, 14,769 rows, 17 features, 20.3% buggy.
Validation: 10 times 10-fold, seeds 1..10.

## The required table

`results/m2_results.csv`, columns and row order as in the provided example output.

| Balancing | FS | Classifier | Precision | Recall | AUC | Kappa | NPofB20 |
|---|---|---|---|---|---|---|---|
| No | No | RandomForest | 0.867 | 0.731 | 0.960 | 0.746 | 0.667 |
| No | No | NaiveBayes | 0.603 | 0.367 | 0.798 | 0.358 | 0.196 |
| No | No | Ibk | 0.605 | 0.581 | 0.760 | 0.492 | 0.430 |
| No | Yes | RandomForest | 0.817 | 0.720 | 0.952 | 0.710 | 0.656 |
| No | Yes | NaiveBayes | 0.602 | 0.370 | 0.787 | 0.359 | 0.207 |
| No | Yes | Ibk | 0.701 | 0.645 | 0.861 | 0.593 | 0.538 |
| Yes | No | RandomForest | 0.766 | 0.813 | 0.954 | 0.733 | 0.646 |
| Yes | No | NaiveBayes | 0.585 | 0.378 | 0.796 | 0.357 | 0.195 |
| Yes | No | Ibk | 0.540 | 0.635 | 0.761 | 0.467 | 0.436 |
| Yes | Yes | RandomForest | 0.717 | 0.814 | 0.943 | 0.697 | 0.637 |
| Yes | Yes | NaiveBayes | 0.579 | 0.393 | 0.786 | 0.364 | 0.215 |
| Yes | Yes | Ibk | 0.619 | 0.731 | 0.855 | 0.577 | 0.537 |

**Which classifier is most accurate?** RandomForest, on every metric in every one of the
four settings. The ordering is `RandomForest > NaiveBayes > Ibk` without feature selection
and `RandomForest > Ibk > NaiveBayes` with it, and it is identical under walk-forward.

**Does feature selection help? It depends entirely on the classifier.** AUC, no balancing:

```
RandomForest  0.960 -> 0.952   -0.008   already selects features at every split
NaiveBayes    0.798 -> 0.787   -0.011   a near-uniform likelihood multiplies through as ~1
Ibk           0.760 -> 0.861   +0.101   Euclidean distance is polluted by every junk axis
```

IBk improves on all five metrics under both balancing settings. Every irrelevant attribute
contributes to the distance in the same units as a useful one, so deleting the seven CFS
rejects makes "who is my nearest neighbour" mean something.

**NaiveBayes is insensitive to balancing** - recall moves 0.367 to 0.378 under
oversampling, and no more under any other technique. It multiplies 17 Gaussian densities,
so the likelihood ratio is astronomically far from 1 and the class prior is a rounding
error against it. Moving the prior from 0.20/0.80 to 0.50/0.50 shifts a decision that was
never close.

**NPofB20 replicates RQ2 of the effort-aware metrics study.** For RandomForest, NPofB20 0.667 against
PofB20 0.234 - **2.85x** the bugs found for the same reading effort, by ranking on
`P/LOC` instead of `P`.

## Balancing comparison

`results/m2_balancing.csv`, feature selection off, all four settings.

| Classifier | Balancing | Precision | Recall | AUC | Kappa | NPofB20 |
|---|---|---|---|---|---|---|
| RandomForest | None | 0.867 | 0.731 | 0.960 | 0.746 | 0.667 |
| RandomForest | Undersampling | 0.601 | 0.876 | 0.939 | 0.622 | 0.585 |
| RandomForest | Oversampling | 0.766 | 0.813 | 0.954 | 0.733 | 0.646 |
| RandomForest | SMOTE | 0.819 | 0.801 | 0.962 | **0.762** | 0.661 |
| NaiveBayes | None | 0.603 | 0.367 | 0.798 | 0.358 | 0.196 |
| NaiveBayes | Undersampling | 0.580 | 0.381 | 0.794 | 0.356 | 0.196 |
| NaiveBayes | Oversampling | 0.585 | 0.378 | 0.796 | 0.357 | 0.195 |
| NaiveBayes | SMOTE | 0.581 | 0.382 | 0.800 | 0.358 | 0.199 |
| Ibk | None | 0.605 | 0.581 | 0.760 | 0.492 | 0.430 |
| Ibk | Undersampling | 0.435 | 0.751 | 0.758 | 0.396 | 0.444 |
| Ibk | Oversampling | 0.540 | 0.635 | 0.761 | 0.467 | 0.436 |
| Ibk | SMOTE | 0.559 | 0.659 | 0.778 | **0.494** | 0.449 |

Balancing behaves as the theory predicts: recall bought with precision. RandomForest gains
8 points of recall for 10 of precision under oversampling. Undersampling pushes recall to
0.876 but precision collapses to 0.601 and kappa to 0.622 - the cost of discarding 8,781
real non-buggy classes.

**SMOTE is the best of the three on kappa for two of the three classifiers**, and the only
technique that raises kappa above the unbalanced baseline for RandomForest (0.762 vs
0.746). It synthesises minority rows instead of duplicating them (Resample) or deleting
majority rows (SpreadSubsample). It is also by far the slowest: 2,868 s against 1,205 s for
Resample on the same configuration, because it runs a kNN search among the minority class
for each of the 8,781 rows it creates.

## Attribute selection stability

`results/m2_features.csv`, over all 100 training folds. CFS is a filter method - it never
consults the classifier, and balancing is applied after it - so the subset chosen for a
given fold is the same for all three classifiers and both balancing settings. Two
independent runs produced byte-identical tables, which confirms it empirically.

| Selected in every fold | Almost always | Unstable | Never |
|---|---|---|---|
| `NR` 100, `NFix` 100, `NAuth` 100, `AVG_Churn` 100, `AVG_ChgSet` 100, `NSmells` 100 | `LOC_added` 99, `MAX_ChgSet` 98, `MAX_Churn` 81 | `AVG_LOC_added` 44, `Age` 39, `LOC` 21, `Churn` 10, `LOC_touched` 7, `MAX_LOC_added` 6 | `ChgSetSize` 0, `WeightedAge` 0 |

Typical subset: 10 of 17 attributes.

Three observations for the report. **`NSmells` is selected in 100/100 folds** - the PMD
per-release smell count from Milestone 1 is a permanent member of the subset, which
justifies the effort that stage cost. **`LOC` survives only 21/100** - raw size is not the
signal; the process and change metrics are. **CFS's redundancy penalty is visible**: it
keeps `AVG_Churn` (100) and `MAX_Churn` (81) while dropping raw `Churn` (10), and keeps
`LOC_added` (99) while dropping the near-collinear `LOC_touched` (7).

## Walk-forward (not required by the milestone)

`results/m2_walkforward.csv`. Train on releases 1..k, test on release k+1, k = 1..13. The
technique used in the RQ2 methodology of the effort-aware metrics study; see M2-T1 for why it was added.

AUC, no feature selection, no balancing:

| Classifier | 10x10 CV | walk-forward | change |
|---|---|---|---|
| RandomForest | 0.960 | 0.883 | -0.077 |
| NaiveBayes | 0.798 | 0.788 | -0.010 |
| Ibk | 0.760 | 0.668 | -0.092 |

The ranking of classifiers is unchanged under both protocols, with and without feature
selection. NPofB20 against PofB20 for RandomForest is 0.529 vs 0.168 - **3.15x**, a
stronger replication of RQ2 than under cross validation.

---

# Milestone 2 — threats to validity

## M2-T1 The cross-validation figures are inflated by file identity

An AUC of 0.960 is far outside the 0.70-0.80 range reported in the defect-prediction
literature, including in the published literature. The cause was measured rather than assumed.

**Not label leakage.** If a metric encoded the label, that metric alone would predict it.
Single-feature AUC over all 14,769 rows tops out at `Churn` 0.746 and `LOC` 0.746;
`NFix` - the metric most likely to be contaminated, since it and `Buggy` both derive from
bug-fix commits - reaches only 0.665. The Milestone 1 dataset is clean.

**The cause is that a file recurs across the split.** The dataset has one row per (class,
release); 1,348 distinct files produce 14,769 rows, about 11 per file. Bugginess is
overwhelmingly a property of the file:

```
consecutive-release pairs of the same file with the SAME label:  12,538 / 13,421  (93.4%)
files never buggy in any release:  760 / 1,348
files buggy in every release:       69 / 1,348
```

A deliberately cheating predictor - for each row, ignore all 17 metrics and predict that
file's buggy rate computed from its **other** rows - scores **AUC 0.913**. Random 10-fold
folds place roughly ten of a file's eleven rows in the training set while the eleventh is
predicted, and since the metrics are cumulative and change slowly (13,761 distinct feature
vectors for 14,769 rows), a file's metric vector is effectively a fingerprint. A model able
to partition the space finely enough performs a lookup rather than a prediction.

**The evidence that this is what happens** is the behaviour of NaiveBayes. As a product of
17 independent marginal Gaussians it cannot represent "this combination of values is file
X", so it is structurally incapable of the lookup - and it is the one classifier that loses
nothing when the opportunity is removed (-0.010). RandomForest and IBk, both able to
memorise, lose 0.077 and 0.092. NaiveBayes acts as a control group.

**Consequence for the milestone's own question.** Under random folds, part of what
distinguishes the classifiers is memorisation capacity rather than defect-prediction skill.
The *ranking*, however, is identical under both protocols, so the answer "RandomForest is
the most accurate" is robust; the magnitudes are not.

## M2-T2 Walk-forward does not remove file recurrence

Between 95% and 99.9% of the files in release k+1 already exist in release k, so a file
still spans the split under walk-forward. What walk-forward removes is training on the
future: no model is built from data that did not exist when its prediction would have been
made, and no test release votes on the feature selection or balancing applied to the
releases before it. Removing time travel recovers roughly half the inflation
(0.960 -> 0.883), not all of it. Eliminating recurrence entirely would require grouping
folds by file, which is a third protocol and was not run.

## M2-T3 IBk's AUC is structurally different from the others

With k=1 the predicted probability is essentially 0 or 1, so the ROC curve has a single
interior point and AUC collapses to `(sensitivity + specificity)/2` - approximately 0.74
for the first row against the 0.760 measured. The excess comes from tied nearest
neighbours: Weka returns every instance tied at the boundary distance, and with 10.8% of
rows being exact duplicate feature vectors, ties at distance zero are common.

For the same reason IBk's PofB20 (0.419) sits close to its NPofB20 (0.430) while
RandomForest shows 0.234 against 0.667. Normalisation can only reorder what differs, and a
mostly-tied ranking leaves little to reorder. IBk's PofB20 is also unusually high in
absolute terms, because within a large tie block the tie-break rule (smaller first) makes
it an effort-aware ranking by accident. Any PofB comparison involving a classifier with
near-binary output is sensitive to the tie-break convention.

## M2-T4 Walk-forward step 10 crosses a branch boundary

Training on releases 1..10 and testing on release 11 fails for all three classifiers at
once - RandomForest AUC 0.64, NaiveBayes 0.62, IBk 0.36 with kappa -0.17, worse than
random. This is structural, not noise, and Milestone 1 explains it: the ancestry matrix
showed the releases are not a chain, and the only real lineages are `1 -> all`,
`10 -> 12 -> 13` and `11 -> 14`. Releases 11 and 14 sit on a parallel maintenance branch,
so step 10 predicts a maintenance line from mainline history, and step 13 (test release 14,
same branch) is IBk's second-worst at 0.55. Release order is by date, which is correct for
a temporal protocol, but date order and lineage diverge in this project.

## M2-T5 Other limitations

| Threat | Effect |
|---|---|
| No hyper-parameter tuning | The comparison is between the three algorithms at their defaults, not at their best. |
| One project | Every result is OPENJPA-specific; the question about the best classifier varying by dataset cannot be answered from one. |
| Snoring, inherited from M1 | Only the oldest 14 of 42 releases are used, so the labels themselves are the ones the 66% rule leaves. |
| NPofB20 uses `LOC` as the effort proxy | Lines are a proxy for inspection effort, not effort itself. |
| Precision/recall use Weka's 0.5 threshold | The threshold is not tuned; AUC and NPofB20 are threshold-free and are the more robust comparisons. |

---

# Milestone 2 completeness check

| Requirement | Status |
|---|---|
| Precision | 12/12 rows |
| Recall | 12/12 rows |
| AUC | 12/12 rows |
| Kappa | 12/12 rows |
| NPofB20 | 12/12 rows, implemented from the published definitions |
| RandomForest | Weka defaults |
| NaiveBayes | Weka defaults |
| Ibk | Weka defaults, k=1 |
| On the previously selected project | OPENJPA, the Milestone 1 dataset |
| 10 times 10-fold validation | seeds 1..10, stratified, pooled per repetition |
| Filter as feature selection | `CfsSubsetEval` + `GreedyStepwise(backwards)`, fold-local |
| Filter as balancing | `Resample -B 1.0 -Z 159.46`, fold-local |
| Output format | columns and row order of the provided example output |

Beyond the requirement: the three-way balancing comparison, attribute-selection stability
over the 100 folds, PofB20 alongside NPofB20, per-repetition standard deviations, and the
walk-forward table.

## Does the best classifier change with the number of releases?

The Milestone 2 assignment asks whether the best classifier varies by
dataset, number of releases, or metric. The number-of-releases part is measurable from the
walk-forward runs: step k trains on releases 1..k, so the thirteen steps are thirteen
dataset sizes, from one release (932 rows) to thirteen (13,714 rows). Counting which
classifier wins each metric at each size, feature selection and balancing both off:

| Metric | RandomForest | NaiveBayes | Ibk | Winner changes? |
|---|---|---|---|---|
| AUC | **13 / 13** | - | - | No |
| NPofB20 | **13 / 13** | - | - | No |
| Precision | 12 / 13 | 1 | - | Once |
| Kappa | 11 / 13 | 2 | - | Twice |
| Recall | 9 / 13 | 2 | 2 | **Yes** |

The answer is conditional on the metric. On the threshold-free metrics - AUC and NPofB20 -
the best classifier never changes at any training size. On the threshold-dependent metrics
it does: RandomForest wins recall in only nine of thirteen sizes.

The cause is that precision, recall and kappa are read at Weka's fixed 0.5 posterior
threshold. With little training data the classifiers' probabilities are poorly calibrated,
so where each sits relative to that fixed cut varies; AUC and NPofB20 rank rather than
threshold and are unaffected. Two of the four changes fall on step 10, the branch-crossing
step of M2-T4.

With feature selection enabled even AUC becomes unstable (RandomForest 10/13, Ibk 2,
NaiveBayes 1), because feature selection is precisely the intervention that lifts Ibk into
contention.

---

# Milestone 3 — what if there were no smells?

The Milestone 3 assignment, titled "How many buggy classes could have been prevented
by having zero smells?", sets out seven steps, of which the first two are Milestones 1 and 2:

```
3. Choose the best classifier, aka BClassifier
5. Create   B+ : portion of A with NSmells > 0
            C  : portion of A with NSmells = 0
            B  : B+ manipulated with feature NSmells set to 0
6. Train BClassifier on A, aka BClassifierA
7. Predict A, B, B+, C and create a Table like the reference one
```

No code is provided. The method is that of the "What if I had no smells?" study,
presented in the accompanying study; the table shape is taken from its results table.

## The datasets

| Dataset | Rows | Buggy | Rate | Definition |
|---|---|---|---|---|
| A | 14,769 | 2,994 | 20.3% | the Milestone 1 dataset, unmodified |
| B+ | 10,990 | 2,580 | 23.5% | the portion of A with `NSmells > 0` |
| C | 3,779 | 414 | 11.0% | the portion of A with `NSmells = 0` |
| B | 10,990 | - | - | B+ with `NSmells` set to 0 — counterfactual |

B+ and C partition A, and both the row counts and the buggy counts sum correctly. B has no
ground-truth column and never can: it describes code that was never written. That is why
the results table carries an "actual" column for A, B+ and C but only an "estimated" column
for B, exactly as in the reference table.

An implementation note that is a real trap rather than a detail: Weka's `new Instances(x)`
copy constructor **shares the underlying Instance objects**. Building B that way and then
zeroing `NSmells` would have zeroed B+ as well, so the analysis would have compared B
against itself and reported an effect of exactly zero — a number that looks like a finding.
B is built by `add()`, which copies, and `WhatIfDatasets.check()` asserts afterwards that
B+ still has its smells.

## BClassifier

Milestone 2 answered step 3: **RandomForest**, most accurate on every metric in all four
preprocessing settings, under both 10x10 cross validation and walk-forward.

It is used with no feature selection and no balancing — also its best cell in the Milestone
2 table. Both choices matter here for reasons beyond accuracy:

- **no feature selection**, because the analysis works by manipulating `NSmells`, so the
  model must be one that reads it. (CFS keeps `NSmells` in 100/100 folds, so this is belt
  and braces, but it removes the question.)
- **no balancing**, because the analysis *counts* predicted-buggy rows. Balancing shifts
  the decision boundary toward the minority class and would inflate every count in the
  table, putting the estimates on a different scale from the actual counts beside them.

## M3-C1 — the drop formula in the reference study mixes actual with estimated

The study states:

> The drop is a substantial 42% ((66-38)/66). This means an overall reduction of 20%
> ((66-38)/135) in the number of defective files on the whole dataset A.

In the reference table, 66 is the **actual** buggy count of B+ and 38 is the **estimated** buggy
count of B. The ratio therefore compares ground truth against a model output, and folds the
model's own error into what is reported as the effect of the smells — the reference model estimates
57 for B+ where the truth is 66, a 14% under-prediction that lands entirely inside the
"drop".

The within-model comparison, `estimated(B+) - estimated(B)`, takes both sides from the same
model so its bias cancels and what remains is attributable to the manipulated feature alone.

Both are computed. On OPENJPA they agree to within 0.1 percentage points, because
RandomForest reproduces B+ almost exactly (2,577 estimated against 2,580 actual), so the
ambiguity does not affect the reported result. That formula is reported as primary because
it is what the milestone asks for.

## Results

`results/m3_whatif.csv`, shaped as the reference table.

| | Rows | Actual | Estimated | Expected |
|---|---|---|---|---|
| A | 14,769 | 2,994 | 2,983 | 2,953.7 |
| B+ | 10,990 | 2,580 | 2,577 | 2,540.2 |
| B | 10,990 | - | **2,003** | 2,221.4 |
| C | 3,779 | 414 | 406 | 413.5 |

Additivity holds on both columns: 2,580 + 414 = 2,994 and 2,577 + 406 = 2,983, mirroring
the reference 66 + 69 = 135 and 57 + 63 = 120.

"Expected" is not in the reference table. It is the sum of the predicted probabilities rather than a
count of rows over the 0.5 threshold, so it does not jump when a class sits at 0.49 instead
of 0.51. It agrees closely with the thresholded count throughout, which is evidence that the
counts are not an artefact of where the threshold falls.

### How many buggy classes could have been prevented?

| Question | Value | Basis |
|---|---|---|
| In total | **577** | 2,580 actual buggy in B+ minus 2,003 estimated buggy in B |
| In proportion | **19.3%** | of the 2,994 buggy classes in A |
| Out of the preventable ones | **22.4%** | of the 2,580 buggy classes that have smells |
| (within-model) | 574, 22.3% | estimated B+ minus estimated B |

### Is BClassifier accurate?

The first of the two questions the assignment asks of the results. It must **not** be answered from the A row of
the table above: that row is resubstitution, the model scored on the data it was fitted on,
and its 2,983-against-2,994 agreement measures memorisation rather than accuracy.

The answer is the cross-validated performance of exactly this configuration - RandomForest,
no feature selection, no balancing - measured in Milestone 2:

| | Precision | Recall | AUC | Kappa | NPofB20 |
|---|---|---|---|---|---|
| 10 times 10-fold | 0.867 | 0.731 | **0.960** | 0.746 | 0.667 |
| walk-forward | 0.799 | 0.594 | **0.883** | 0.585 | 0.529 |

So: yes, and by a clear margin over the alternatives - RandomForest was the most accurate of
the three classifiers on every metric in all four preprocessing settings, under both
validation protocols, with a gap to NaiveBayes of roughly 116 standard deviations.

Two qualifications carry over from Milestone 2 and must travel with the figure. The 0.960 is
inflated by file recurrence (M2-T1): a predictor given only a file's identity scores AUC
0.913 on this dataset, and the walk-forward 0.883 is the more conservative estimate. And
`Ibk` aside, none of the three classifiers was tuned.

For the purpose of Milestone 3 the relevant point is narrower than "is it good": the model
is being used as an instrument to answer a counterfactual, so what matters is that it is
substantially better than chance and that it actually uses `NSmells`. It is - `NSmells`
correlates 0.310 with defectiveness, is selected by CFS in 100 of 100 folds, and zeroing it
moves 577 predictions.

### The finding the method exists to produce

The naive reading of the data and the what-if answer differ by a factor of 1.7.

| | Buggy rate | Implied effect of smells |
|---|---|---|
| B+ smelly classes, actual | 23.5% | |
| C clean classes, actual | 11.0% | naive: smells **2.14x** the defect rate |
| B same classes, smells removed, estimated | **18.2%** | what-if: smells **1.29x** |

Smelly classes are not only smellier, they are four and a half times larger — mean `LOC`
283.5 against 63.0, with `Spearman(LOC, NSmells) = 0.770`. Comparing B+ to C therefore
credits smells with an advantage that mostly belongs to size.

Of the 12.5-point gap between smelly and clean classes, the what-if attributes **5.2 points
(42%) to the smells themselves** and 7.3 points (58%) to size, churn and change history.
Holding every other feature constant is the entire purpose of constructing B, and it changes
the answer substantially.

## Feature profile

`results/m3_feature_profile.csv`. The what-if study records this step: "we
measured for each feature the average value and the correlation with NSmells and
Defectiveness". Spearman rank correlation is used throughout, because these metrics are
heavily skewed (`NSmells` runs 0 to 430 with a median of 3) and Pearson would be steered by
a handful of very large classes while additionally assuming a linearity nothing justifies.
Ties are given midranks — 3,779 rows are tied at `NSmells = 0` alone.

`NSmells` correlated with itself comes out at exactly 1.000, which is a free check that the
ranking code is correct.

| Feature | mean A | mean B+ | mean C | r NSmells | r Buggy |
|---|---|---|---|---|---|
| `Churn` | 226.9 | 283.3 | 62.9 | 0.769 | **0.343** |
| `LOC` | 227.1 | 283.5 | 63.0 | **0.770** | 0.342 |
| `MAX_Churn` | 212.4 | 265.0 | 59.1 | 0.756 | 0.334 |
| `MAX_LOC_added` | 218.7 | 273.0 | 60.6 | 0.761 | 0.331 |
| `AVG_Churn` | 29.8 | 34.9 | 14.9 | 0.571 | 0.325 |
| `AVG_LOC_added` | 48.1 | 56.5 | 23.4 | 0.639 | 0.320 |
| `NSmells` | 14.5 | 19.5 | 0.0 | 1.000 | 0.310 |
| `AVG_ChgSet` | 730.1 | 698.9 | 820.7 | −0.300 | **−0.303** |
| `NFix` | 0.7 | 0.8 | 0.2 | 0.371 | 0.300 |
| `LOC_added` | 450.1 | 563.5 | 120.6 | 0.718 | 0.290 |
| `LOC_touched` | 673.4 | 843.6 | 178.3 | 0.668 | 0.257 |
| `NAuth` | 2.8 | 3.0 | 2.3 | 0.367 | 0.204 |
| `NR` | 8.0 | 8.8 | 5.7 | 0.387 | 0.164 |
| `MAX_ChgSet` | 1727.9 | 1734.9 | 1707.6 | −0.001 | −0.072 |
| `WeightedAge` | 537.2 | 544.1 | 517.3 | 0.042 | −0.063 |
| `ChgSetSize` | 5887.0 | 5989.6 | 5588.5 | 0.135 | −0.050 |
| `Age` | 669.7 | 676.3 | 650.5 | 0.034 | −0.036 |

Two things this table establishes.

**The confounding is severe.** Six features correlate with `NSmells` above 0.6. A class with
smells is, in this project, essentially a *large, heavily churned* class. Any comparison
between B+ and C is therefore a comparison between large and small classes as much as
between smelly and clean ones, which is what makes the B counterfactual necessary rather
than merely interesting.

**This is also the input to Milestone 4**, which asks whether any feature positively
correlated with bugginess is higher in the refactored class than in the original, and the
same for negatively correlated ones. Those two sets are, from the last column:

- **positive**: `Churn`, `LOC`, `MAX_Churn`, `MAX_LOC_added`, `AVG_Churn`, `AVG_LOC_added`,
  `NSmells`, `NFix`, `LOC_added`, `LOC_touched`, `NAuth`, `NR`
- **negative**: `AVG_ChgSet`, `MAX_ChgSet`, `WeightedAge`, `ChgSetSize`, `Age`

The negative group is weak — the largest magnitude is 0.303 and the rest are below 0.08 —
so a Milestone 4 conclusion resting on `Age` or `ChgSetSize` moving would be resting on
noise. Only `AVG_ChgSet` is strong enough to carry an argument.

## Beyond the requirement: partial cleaning

`results/m3_sensitivity.csv`. "Zero smells everywhere" is the upper bound of an engineering
plan, not a plan. Milestone 4 refactors two classes with an automated tool and will not
remove every smell. This traces the estimate as progressively more of the smelliest classes
are cleaned, in descending order of `NSmells` — the order a team with finite time would
work in, and the same ranking Milestone 4 uses to choose its classes.

| Cleaned | Classes | Estimated buggy | Prevented | Share of full effect |
|---|---|---|---|---|
| 0% | 0 | 2,983 | 0 | 0.0% |
| 10% | 1,099 | 2,924 | 59 | 10.3% |
| 25% | 2,748 | 2,646 | 337 | 58.7% |
| 50% | 5,495 | 2,419 | 564 | **98.3%** |
| 75% | 8,243 | 2,400 | 583 | 101.6% |
| 100% | 10,990 | 2,409 | 574 | 100.0% |

**Cleaning the smelliest half captures 98% of the entire benefit.** The other 5,495 classes
contribute essentially nothing. If the result is read as advice, it is that smell removal is
worth doing on the worst classes and not worth doing anywhere else.

The curve is also **not monotonic** — 75% cleaned prevents more (583) than 100% cleaned
(574). That is not noise; it is the counterfactual leaving the training distribution, and it
is quantified in M3-T1 below.

---

# Milestone 3 — threats to validity

## M3-T1 The counterfactual dataset B is partly outside the training distribution

This is the principal threat, and it is measurable rather than hypothetical.

`Spearman(LOC, NSmells) = 0.770`. In the real data, `NSmells = 0` overwhelmingly means
"small class": the largest class in the whole project that genuinely has zero smells is
**329 lines**, and the median is 53.

Setting `NSmells = 0` on a large class therefore manufactures a row unlike anything the
model was trained on:

```
rows of B larger than C's 95th percentile (126 lines)      5,800   52.8% of B
rows of B larger than C's 99th percentile (188 lines)      4,331   39.4% of B
rows of B larger than ANY real zero-smell class (329)      2,506   22.8% of B
```

Nearly a quarter of B describes code that has no analogue anywhere in the project: classes
of a size that, in reality, never occurs without smells. Worse, those 2,506 rows have a
**52.3% buggy rate** against 23.5% for B+ overall — so the manufactured, unprecedented rows
are exactly the high-risk ones on which the estimate most depends.

A tree ensemble asked to predict outside the region it was fitted on does not fail loudly;
it returns whatever the nearest leaves happen to say. That is the mechanism behind the
non-monotonic sensitivity curve: the last classes to be cleaned are the least smelly ones,
where the manufactured rows are least like anything real, and cleaning them moves the
estimate the wrong way.

Practical effect on the headline: the maximum is 583 at 75% cleaning against 574 at 100%, a
1.5% wobble, so the reported 577 is not materially disturbed. But the figure should be read
as an estimate with a directional uncertainty of a few percent, not as a count.

Mitigation that was not performed: restricting the analysis to classes whose size falls
inside the range where zero-smell classes actually exist would remove the extrapolation, at
the cost of discarding the 22.8% of B where most of the estimated effect lives.

## M3-T2 The model is evaluated on its own training data

Step 6 says to train BClassifier on A, and step 7 says to predict A. That is
resubstitution, and the A row of the results table shows it: 2,983 estimated against 2,994
actual, a 99.6% reproduction of the training labels.

That number must not be used to answer "Is BClassifier accurate?". The honest accuracy
figures are the cross-validated ones from Milestone 2 — AUC 0.960 under 10x10 cross
validation, 0.883 under walk-forward, both of which are themselves subject to the
file-recurrence caveat of M2-T1.

Training on all of A is nevertheless correct **for this purpose**. The model here is an
instrument for asking a counterfactual question, not a subject whose generalisation is being
measured, and it should be fitted on as much data as exists.

## M3-T3 `NSmells` is a count, not a description

Setting `NSmells` to 0 asserts that every smell in a class can be removed, that all smells
are equally consequential, and that removing them changes nothing else about the class. None
of the three is true: PMD reports smells of very different severity, and removing a God
Class genuinely changes `LOC` — which the manipulation holds fixed.

The estimate is therefore an answer to "what if the smell count were zero and nothing else
moved", which is a narrower question than "what if this code had been written well".
Milestone 4, which performs an actual refactoring and re-measures the features, is the
empirical check on this assumption.

## M3-T4 Inherited threats

Everything in Milestone 1 and Milestone 2 propagates: the 66% snoring cut, SZZ's assumptions
and Proportion's estimate for the injected version, PMD as the smell oracle, and the
file-recurrence inflation of M2-T1. The what-if result is conditional on all of them.

---

# Milestone 3 completeness check

| Requirement | Status |
|---|---|
| 3. Choose the best classifier (BClassifier) | RandomForest, from the Milestone 2 comparison |
| 5.1 Create B+ (portion of A with NSmells > 0) | 10,990 rows |
| 5.2 Create C (portion of A with NSmells = 0) | 3,779 rows |
| 5.3 Create B (B+ with NSmells set to 0) | 10,990 rows, verified not to alias B+ |
| 6. Train BClassifier on A | RandomForest on all 14,769 rows |
| 7. Predict A, B, B+, C and produce the table | `results/m3_whatif.csv`, shaped as the reference table |
| Is BClassifier accurate? | From Milestone 2, not from the A row — see M3-T2 |
| Prevented, in total | 577 |
| Prevented, in proportion | 19.3% |
| Prevented, out of the preventable ones | 22.4% |

Beyond the requirement: the feature profile (which Milestone 4 consumes), the
within-model comparison alongside the reference formula, the expected-count column, the partial
cleaning curve, and the quantification of the extrapolation in M3-T1.

---

# Milestone 4 — automated refactoring: class selection

The Milestone 4 assignment specifies:

```
8. Class Selection
   1. Rank all classes of the last release of the project based on Nsmells
   2. Filter out classes that are too small (e.g., few and simple methods).
   3. Select two classes based on your name
      1. Take the first letter of your first name (e.g., D for Davide)
      2. Take the number of the letter (e.g., D = 4)
      3. X = number mod 5 (e.g. X = 4)
      switch (X) { case 0: first and last; ... case 4: first +4 and last -4; }
```

## M4-C1 — the selection rule uses the FIRST name, the project rule uses the LAST name

The two selection algorithms in this course look almost identical and differ in one word.

| Rule | Which name | Modulo | Applied here |
|---|---|---|---|
| Project selection (M1, M3) | **last** name, "F for Falessi" | 6 | Manoli -> M = 13 -> 13 mod 6 = 1 -> **OPENJPA** |
| Class selection (M4) | **first** name, "D for Davide" | 5 | Ilie -> I = 9 -> 9 mod 5 = 4 -> **first +4 and last -4** |

The examples in the source confirm the distinction: the author uses his own surname for the
project rule and his own forename for the class rule.

Recorded as a change because a previous attempt at this project applied the surname initial
to both, obtaining X = 13 mod 5 = 3 and therefore case 3 (first +3, last -3) rather than
case 4. Both selected classes were consequently wrong.

## M4-C2 — "the last release of the project" is 4.1.1, not the last release of dataset A

Dataset A keeps only the oldest third of the releases under the 66% rule, so its final entry
is 1.2.2 (January 2010). That is the last release of the **dataset**, not of the **project**;
the project's last release is 4.1.1 (May 2025).

The wording is used elsewhere in the course material for a study spanning a project's entire
release history, where it can only mean the project's final release.

Three practical requirements agree with the literal reading, and none of them is satisfiable
against a 2010 maintenance branch:

- the assignment asks whether the refactored class compiles, alone or with the system;
- it requires SonarCloud diagnostics in the refactoring prompt, and SonarCloud cannot analyse
  2006-2010 era code whose Maven build no longer works - the reason PMD was used for the
  per-release NSmells column in Milestone 1;
- the prompt states that the refactored class must work with the other components of the
  system "as C_0 currently does".

Note also that 1.2.2 sits on the 1.2.x maintenance branch, the `11 -> 14` lineage identified
in the release-ancestry analysis, so it is not even on the project's main line.

## M4-C3 — SonarCloud is the ranking oracle, PMD is the cross-check

The Milestone 1 material permits either tool ("compute NSmells via SonarCloud or PMD or
similar"), so the ranking could have used the cheaper PMD pass. It does not, and the reason
is internal consistency rather than preference.

The assignment fixes the detector elsewhere: the refactoring prompt must state the smells to
remove **as reported by SonarCloud**, and the results section asks whether the refactored
class still has smells and whether they are old or new. That before/after comparison is only
meaningful if both sides come from the same detector. Selecting with one tool and defining
the work with another would be incoherent.

A SonarCloud baseline on 4.1.1 was therefore required regardless of how the ranking was
produced, so ranking with it cost nothing additional.

PMD is run over the same release anyway, through the identical code path, rulesets and
counting used for dataset A. Its purpose is to answer "would the selection have been
different with another tool":

**Spearman(SonarCloud, PMD) = 0.768 over 1,487 classes.**

The ranking is a property of the code rather than of the detector.

## Scope alignment

The first PMD pass exported every `src/main/java` file - 1,633 - and ranked
`openbooks/tools/parser/JavaParser` first with 2,747 violations, with `JavaLexer` fifth.
Both are ANTLR-generated code inside the OpenBooks sample application, and SonarCloud does
not see either: its exclusions drop `openjpa-examples`, `openjpa-integration`,
`openjpa-tools` and `openjpa-project`.

That is a scope mismatch rather than a disagreement between detectors, and it would have made
the cross-check meaningless. The PMD pass now applies the same module exclusions:

```
exported                                             1,633
out of scope (examples/integration/tools/project)      146
in scope                                             1,487
SonarCloud java files                                1,487    exact match
classes measured by PMD but absent from SonarCloud       0
```

The exclusions are methodological, not convenient. Sample applications, integration
harnesses, build tooling and documentation are not product classes, and refactoring
generated code is meaningless because the next build regenerates it from its grammar.

## The size filter

The assignment says to filter out classes that are "too small (e.g., few and simple
methods)" without saying where the line falls. Three criteria are applied, and every
threshold is a median of this release rather than a chosen number, so all three are
properties of the codebase.

| Criterion | Threshold | Rationale |
|---|---|---|
| declared kind is `class` | - | Interfaces, annotations and enums have no method bodies to refactor. The unfiltered ranking's bottom ten was made entirely of them, three to four lines each with zero methods. |
| NCSS >= median | **76** | Size. |
| methods >= median | **8** | "Few methods". |
| NCSS / methods >= median | **8.00** | "Simple methods". |

```
1,487 in scope -> 1,103 concrete classes -> 291 eligible
```

### The third criterion, and why it was added after the fact

With only the first two, the low selection was `AbstractFieldManager`: twenty methods, each
a single `throw new InternalException()`, whose own javadoc reads *"Throws exceptions for all
methods"*. It scores zero smells because there is nothing in it to smell, and it passed a
size test because twenty one-line methods plus imports reach 86 NCSS.

That is a filter measuring "few methods" while ignoring "simple methods", which is half of
what the criterion says. The median class here is 8.0 NCSS per method;
`AbstractFieldManager` is 4.3.

Adding a criterion after seeing which class the previous one selected deserves scrutiny, so
the check that matters: **the high selection is identical with and without it.** Only the low
end moves, which is where the defect was. The change cannot have been chosen to reach a
particular class at the top.

There is also direct evidence that the criterion matters. A previous attempt at this project
used a filter that admitted its low class at 4.3 NCSS per method - the same signature - and
recorded that the language model hallucinated on it, claiming the class "declares no methods"
when it declares 102, and returned an empty class body that produced 34 compilation errors
across 17 files. Asking a model to refactor a class with no substance in it produces nothing.

The consequence is not confined to Milestone 4: both selected classes are also the input to
the testing module, and a class whose every method throws unconditionally admits no test
beyond asserting that it throws.

## The selection

Ordering is by SonarCloud smells descending, then NCSS descending, then path. The tie-break
is not decoration: many clean classes share a smell count of zero, so without a documented
secondary key the low selection would depend on file-system iteration order and would not
reproduce.

`first +4` is position 5 of 291; `last -4` is position 287 of 291.

| Role | Position | Class | Sonar | PMD | NCSS | Methods | NCSS/method |
|---|---|---|---|---|---|---|---|
| C_0 high | 5 | `org.apache.openjpa.jdbc.sql.SelectImpl` | 126 | 230 | 2,703 | 305 | 8.9 |
| C_0 low | 287 | `org.apache.openjpa.lib.util.StringDistance` | 1 | 3 | 92 | 8 | 11.5 |

Written to `isw2/classes.txt` in the format the testing module requires: one per line,
alphabetical, fully qualified.

Only three of the 291 eligible classes have zero SonarCloud smells and none falls at position
287, so the low selection has one smell rather than none. That is arguably the better
experiment: one smell going to zero is a checkable before/after result, whereas zero staying
zero would demonstrate nothing.

## Diagnostics for the two classes

The counts are not sufficient for the refactoring prompt, which must state the smells to be
removed. Individual issues come from a different endpoint - `api/issues/search` rather than
`measures/component_tree` - filtered to open issues of type CODE_SMELL so that the list is
comparable to the measure used for the ranking. The 10,000-issue cap that forced the
aggregate endpoint for the ranking is irrelevant for two files.

**Self-check.** The number of issues returned must equal the `code_smells` measure recorded
during selection. The two figures come from different endpoints computing the same quantity,
so a mismatch would mean one of them is filtered differently than assumed and neither could
be trusted.

```
SelectImpl        issues 126   measure 126   match
StringDistance    issues   1   measure   1   match
```

### `StringDistance` - one smell

`java:S1118` - add a private constructor to hide the implicit public one. A utility class of
static methods should not be instantiable. One line, no behavioural risk.

### `SelectImpl` - 126 smells, and what they actually are

Severity: 22 CRITICAL, 37 MAJOR, 67 MINOR. By rule, the largest groups:

| Rule | Count | What it asks for |
|---|---|---|
| `java:S116` | **50** | rename a field to match `^[a-z][a-zA-Z0-9]*$` |
| `java:S3776` | 14 | reduce cognitive complexity |
| `java:S6213` | 11 | rename a variable that matches a restricted identifier |
| `java:S1172` | 10 | remove an unused method parameter |
| `java:S1186` | 6 | empty method needs a comment or an exception |
| `java:S108` | 5 | empty block |

**49 of the 50 `S116` findings are leading-underscore private fields** - `_conf`, `_dict`,
`_aliases`, `_tables`, `_ordered`, `_preJoins` and so on. That is Apache OpenJPA's house
naming convention for private fields, applied consistently across all 1,487 classes in the
analysis.

So **39% of this class's measured technical debt is a project-wide style choice rather than a
defect**, and 48% of it is renaming of one kind or another once `S6213` is included. That
ceiling is set by the smell oracle's default rule set, not by the code, and it bounds what
any refactoring of this class can honestly be said to have achieved.

Whether the renames are *safe* was checked rather than assumed: `SelectImpl` is not
serializable - no `Serializable`, no `serialVersionUID`, no `writeObject` or `readObject` -
and all 49 fields are `private`, so renaming them breaks no external caller and no
serialized form. The objection to renaming is consistency with the codebase, not correctness.
This differs from the previous attempt at this project, where the model refused the same rule
on a class that *was* serializable and gave that as its reason.

## Build and analysis of the 4.1.1 release

The release is checked out as a git worktree beside the repository, so the working tree and
the coursework directory are untouched, and `isw2/` does not exist in 4.1.1 - which
guarantees the analysis contains only OpenJPA code.

| Step | Toolchain | Note |
|---|---|---|
| build | **JDK 11** | 4.1.1 targets Java 11 and performs bytecode enhancement during the build, which newer JDKs break |
| scan | **JDK 21** | current SonarCloud scanners require Java 17+ to run; `sonar:sonar` only reads the already-built classes |

The build is run with the report plugins disabled - `checkstyle.skip`, `rat.skip`,
`maven.javadoc.skip`, `pmd.skip`, `cpd.skip`. Milestone 4 needs compiled classes, not the
project's own quality gates, and SonarCloud performs its own analysis regardless. Checkstyle
9.3 reports 283 violations on `openjpa-lib` alone and fails the build by default.

A full-reactor build can also fail in `openjpa-examples/openbooks`, whose Ant script resolves
OpenJPA jars by file path inside the local Maven repository rather than through Maven's
dependency resolution. `mvn package` never writes to the local repository - only `install`
does - so the file it looks for may not be there. The documented reproduction therefore
excludes the same modules the analysis excludes, which makes the build deterministic and
compiles only what is measured.

The scanner needs `SONAR_SCANNER_JAVA_OPTS=-Xmx4g`; it runs in its own JVM that `MAVEN_OPTS`
does not reach, and the default heap exhausts inside the taint-analysis engine. The run took
one hour.

### Observation, not a conclusion

The 4.1.1 analysis reports 9,415 smells over 1,487 files; the Milestone 1 analysis of master
(4.2.0-SNAPSHOT) reported 10,664 over 1,515. Every class in the top six is lower in 4.1.1,
and the top two are ordered differently. That is consistent with 326 commits of drift, but
the two runs also differed in configuration and the effects cannot be separated from the data
available. It is recorded as an observation. Milestone 4 uses only the 4.1.1 analysis.

---

# Milestone 4 — threats to validity, class selection

## M4-T1 The smell oracle counts a naming convention as 39% of the debt

Fifty of `SelectImpl`'s 126 smells ask for private fields to be renamed away from the
project's own convention. A refactoring that correctly declines them cannot exceed roughly
61% smell removal, and one that accepts them makes the class inconsistent with every other
class in the project. Any percentage reported for this class must be read against that.

## M4-T2 The method count is a heuristic

Declared kind and method count come from a character-scanning source analysis, not from a
Java parser. It strips comments and literals in one pass and then matches method
declarations by pattern. It is deliberately approximate because it drives a coarse filter,
and the classes that survive to selection are inspected by hand - but the 8.00 median that
sets the third filter threshold rests on it, and a parser would give slightly different
numbers.

## M4-T3 One release, one project

The ranking, the filter thresholds and both selected classes are specific to OpenJPA 4.1.1.
Nothing here establishes that the same procedure on another project or another release would
behave similarly.

## M4-T4 The low class is small by construction

The selection rule takes a class from the clean end of the ranking, so the low class is
necessarily one with few smells. The testing module separately advises against classes whose
tests would trivially reach full coverage. `StringDistance` is small (92 NCSS, 8 methods),
though it contains a real dynamic-programming algorithm with nested loops, threshold logic
and clamping, so branch coverage and mutation score are not trivial even where statement
coverage is easy. The tension is inherent to combining the two requirements, and the testing
module explicitly defers the class choice to this selection algorithm.
