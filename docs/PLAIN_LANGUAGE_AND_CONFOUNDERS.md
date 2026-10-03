# Plain-language results and confounder surfacing

## Two deliberately separate surfaces

**Ambient differences to check** is descriptive. It appears with one matched event/control value in each group and ranks large normalized differences so an operator can notice obvious context changes early. Every row says it is a possible confounder or context difference and is **descriptive only, not adjusted evidence**. It does not produce p-values or claims.

**Association results** retain the minimum four matched captures per group, permutation test, bootstrap interval, split-half persistence, and Benjamini-Hochberg correction. Cards lead with a human sentence and keep the complete technical statement below it.

## Plain language

Binary features such as a locally hashed device presence report:

> device presence was present in 75% of event windows versus 25% of matched controls (3.0× as common).

Continuous features report event average, matched-control average, and the directional difference. Ratios are not used for signed or unit-sensitive values such as dBm.

When either group has fewer than ten matched captures, the result says **Interesting, not yet established; the sample is still small.** A corrected weak result says **Good news: this pattern doesn't hold up against your controls.** A refuted pre-registration begins with the same sentence and then states that the corrected direction opposed the registration.

## Boundaries

Ambient ranking is not multiple-comparison-adjusted and must not be cited as evidence. Association text still includes tested/eligible feature counts, adjusted probability, effect/interval, permutation seed/resolution, and the no-causation statement. Post-event samples remain excluded; dense values remain one capture; omitted channels do not become zero.
