# Human and machine analysis exports

Apophenia exports are designed to be opened directly by a person and ingested without reverse engineering by analysis software. Every artifact is prepared locally, listed in `manifest.json`, hashed with SHA-256, reopened, and verified before a route is offered.

## Format map

| Artifact | Read it with | What it is for |
| --- | --- | --- |
| `analysis/README.md` | any text editor or Markdown viewer | scope, counts, exclusions, and the statistical rules that must travel with the data |
| `data/apophenia-data.json` | Python, R, Julia, JavaScript, `jq`, notebooks | canonical nested representation of observations, context, controls, sessions, hypotheses, inventories, and audit rows |
| `analysis/*.csv` | spreadsheet software, Python/pandas, R, MATLAB, Julia, BI tools | flat UTF-8 tables for ordinary analysis and charting |
| `analysis/data-dictionary.json` | scripts, schema checks, notebooks | column names, null/timestamp conventions, canonical path, and analysis invariants |
| raw SQLite `.db` | SQLite CLI, DB Browser for SQLite, Datasette, BI tools | direct queries against the checkpointed documented schema |
| report HTML/PDF | any browser or PDF reader | human review of selected events, gaps, findings, and derived charts |
| report/dossier JSON, CSV, SVG | notebooks, scripts, vector viewers | scoped machine analysis and reusable charts |

## Default data-only ZIP

The default tier contains these ordinary-analysis files:

```text
manifest.json
data/apophenia-data.json
analysis/README.md
analysis/data-dictionary.json
analysis/observations.csv
analysis/context-samples.csv
analysis/hypotheses.csv
analysis/hypothesis-evaluations.csv
analysis/sessions.csv
analysis/session-events.csv
```

It does not read the encrypted `sensitive_context` table and does not include raw audio/video, Tier 2 plaintext, RF IQ, or inbound attachment bytes. Hashed device identifiers remain hashed exactly as stored.

CSV is UTF-8 with one header row, RFC 4180-style quoting for commas, quotes, and line breaks, Unix line endings, `true`/`false` booleans, and an empty cell for null or unavailable values. Unix timestamps are milliseconds.

The nested JSON remains canonical because it preserves relationships such as an observation with its context and a hypothesis with its evaluations. CSV is a convenience view and is not a replacement import format.

## Core joins

| From | To | Join |
| --- | --- | --- |
| observation | context sample | `observations.id = context_samples.observation_id` |
| one dense window | its rows | group by `context_samples.capture_id` |
| drive or flight session | context | `sessions.id = context_samples.session_id` |
| drive or flight session | event | `sessions.id = session_events.session_id` |
| preregistration | evaluation | `hypotheses.id = hypothesis_evaluations.hypothesis_id` |

`context_samples.observation_id` can be empty for controls and session-only rows. `is_control=true` identifies controls. `phase` is one of `INSTANT`, `PRE`, `POST`, or `CONTROL`.

## Required analysis boundaries

These are data semantics, not optional recommendations:

- `capture_id` groups dense samples. Do not count every row from one capture as an independent event.
- Exclude `phase=POST` from predictor calculations. Post-event rows remain valid reconstruction context.
- Controls must stay identifiable through `is_control=true` and `phase=CONTROL`.
- Hashed identifiers support local repeated-presence analysis but are invalidated by an intentional hash-salt rotation.
- Observation text is descriptive evidence. Preregistered hypotheses and stored evaluations remain separate.
- Multiple comparisons can generate convincing noise. Preserve the channel count and corrected result tier.
- An export contains what was stored; a missing row is not proof that a physical phenomenon was absent.

## Python example

```python
import pandas as pd

observations = pd.read_csv("analysis/observations.csv")
context = pd.read_csv("analysis/context-samples.csv")

predictor_context = context[context["phase"] != "POST"]
capture_level = (
    predictor_context
    .groupby(["capture_id", "metric", "is_control"], dropna=False)["value"]
    .mean()
    .reset_index()
)
```

The final `groupby` is the important part: it collapses dense rows to the capture window before comparison.

## R example

```r
observations <- read.csv("analysis/observations.csv", fileEncoding = "UTF-8")
context <- read.csv("analysis/context-samples.csv", fileEncoding = "UTF-8")
predictors <- subset(context, phase != "POST")
capture_level <- aggregate(value ~ capture_id + metric + is_control, predictors, mean)
```

## SQLite example

Use **Prepare raw SQLite snapshot** when the analysis tool understands SQLite and needs every stored application table without CSV flattening.

```sql
SELECT
  capture_id,
  metric,
  is_control,
  AVG(value) AS capture_mean
FROM context_samples
WHERE phase <> 'POST'
GROUP BY capture_id, metric, is_control;
```

The raw route performs `PRAGMA wal_checkpoint(FULL)`, copies the main database, reopens the copy read-only, requires `PRAGMA integrity_check=ok`, checks the schema version and required tables, and shows size, row counts, and SHA-256 before routing.

## Which export to choose

- Choose **data-only** for notebooks, spreadsheets, statistical work, and ordinary archival.
- Choose **raw SQLite** for direct SQL, reproducible database queries, or tools that preserve the relational schema.
- Choose a **selected-event report** when a person needs a readable HTML/PDF with the same scoped JSON/CSV beside it.
- Choose a **single-event dossier** when one event needs every available channel and retained raw evidence.
- Choose **full evidence** only when raw AV, protected contents, attachments, and complete inventories are required.
- Choose a **full backup** for verified restore, not as the most convenient analysis format.

Once routed, an exported copy is outside Apophenia's local boundary. Hashes prove byte integrity, not confidentiality or destination retention. Sharesheet handoff, document-write completion, and endpoint acknowledgement are deliberately recorded as different outcomes.
