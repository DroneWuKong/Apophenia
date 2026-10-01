# Data model

## Observation
A human timestamped report. The record is intentionally descriptive rather than interpretive.

Kinds: `OBSERVATION`, `COINCIDENCE`, `HYPOTHESIS_NOTE`, `WEIRD`.

A Garmin-originated observation keeps the watch timestamp, rather than replacing it with phone receive time.

## Context sample
A scalar measurement with timestamp, source, metric, value and unit. It may be attached to an observation or marked as a random control.

`capture_id` groups all values from the same control/event capture window so dense sampling cannot be mistaken for independent observations.

## Rolling sample
`rolling_samples` is a bounded scratch buffer, separate from durable event context. When an event occurs, the relevant window is copied into `context_samples`; old scratch samples are pruned.

- pre-event samples: durable and eligible as predictors
- post-event samples: durable for exploration, excluded from predictor calculations

## Hypothesis
Kept separate from observations so theories can change without rewriting evidence.

## Controls
Control samples are scheduled at jittered intervals and assigned a capture ID. They provide a baseline so common conditions are not mistaken for meaningful associations.
