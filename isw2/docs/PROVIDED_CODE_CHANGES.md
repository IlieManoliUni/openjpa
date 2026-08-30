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

Formulas are taken verbatim from `8_Presentation Proportion.pdf`:

```
P            = (FV - IV) / (FV - OV)
predicted IV = FV - (FV - OV) * P
```

and the consistency rule from the same deck, RQ1: *"is the AV consistent, i.e., is the
oldest AV not after the OV?"* — that is, `IV <= OV`.

The deck names three ways to compute P (Cold Start, Increment, Moving Window); none is
"Total". "Total" comes from the Milestone 1 slide, which defines it: *"compute and use P on
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
(`11_Milestone 1 - Dataset creation.csv`) shows they are not:

```
Churn == 2*LOC_added - LOC_touched    holds for 16,242 / 16,339 rows (99.4%)
rows with negative Churn              567
rows with Churn > LOC_touched         0
```

Rearranging `Churn = 2·added − touched` gives `touched = added + deleted` and
`Churn = added − deleted`. The 567 negative values confirm it: a sum of two
non-negative quantities cannot be negative. The 97 rows that miss are large values
where his rounding to three significant figures exceeds the tolerance.

**Adopted:** `LOC_touched = Σ(added + deleted)`, `Churn = Σ(added − deleted)`.

Two further ambiguities resolved from the same file:

| Metric | Slide text | What the data shows | Adopted |
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
