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

---

## Noticed and deliberately NOT changed

Recorded so the decisions are visible, and because several are worth discussing at the oral.

| Observation | Why left alone |
|---|---|
| `if (releases.size() < 6) return;` - unexplained magic number, silently produces no output | Harmless on OPENJPA (42 releases). Not necessary to change. |
| The `released` boolean on a JIRA version is ignored; only presence of `releaseDate` is checked | **Verified equivalent on OPENJPA.** Of 51 versions, 42 carry a `releaseDate` and 42 have `released == true`; versions dated-but-not-released = 0, released-but-undated = 0. The two filters select the same set, so his check is not a source of error here. No longer a threat, a measured fact. |
| `readAll()` reads the HTTP response one character at a time | Slower than reading in bulk, but correct. Not a correctness issue. |
| No handling of HTTP errors - a non-JSON error response fails with an opaque exception | Apache JIRA is public and stable. Not necessary. |
| Two parallel `HashMap`s instead of one release object | Style, not correctness. |

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
