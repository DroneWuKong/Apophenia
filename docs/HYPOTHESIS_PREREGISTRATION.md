# Hypothesis pre-registration

## Registration contract

A pre-registration is timestamped before the exact result is viewed. It contains:

- an event cohort: a named label, **Egress · bailed**, or **Bad vibes · stayed**;
- an exact context metric;
- expected direction: higher, lower, or either;
- window: instant, 0–10, 10–20, or 20–30 minutes before the event;
- a plain-language expected association.

Free-form legacy hypothesis notes remain supported but are labeled **HYPOTHESIS NOTE** and are not promoted to registrations.

## Anti-backdating and immutability

When Patterns opens a feature with enough matched event/control captures for a permutation result, the `analysis_views` ledger introduced in schema v9 records that cohort/feature. A later attempt to register that exact cohort, metric, and window is refused rather than mislabeled as prior prediction.

An earlier valid registration remains editable only until its first eligible evaluation. Recording that evaluation atomically sets `locked_at_ms`. Later data can create another append-only evaluation signature, but the cohort, metric, direction, window, expectation, and original timestamp cannot change. Insufficient data produces no evaluation and does not lock.

## Outcomes

The evaluator uses the same Benjamini-Hochberg-adjusted result shown in Patterns:

- **CONFIRMED**: adjusted p is at most 0.05 and delta matches the registered direction;
- **REFUTED**: adjusted p is at most 0.05 and delta points against the registered direction;
- **NOT YET SUPPORTED**: the corrected evidence does not reach that threshold.

An `ANY` registration confirms only when a nonzero direction meets the corrected threshold. Each evaluation preserves matched counts, adjusted p, delta, tested-feature count, deterministic analysis signature, and an association-not-causation disclosure.

## Validation boundary

JVM tests cover schema v2-to-current migration, pre-view insertion, post-view refusal, first-result locking, duplicate-signature rejection, window-to-feature mapping, and all three evaluation outcomes. Emulator tests cover the Compose surfaces. No statistical outcome proves a cause, diagnosis, hardware effect, or field phenomenon.
