# Quick VIBE capture

The five VIBE grades and the egress event are descriptive evidence. A single tap in the app or widget logs immediately, stays on the current surface, and preserves that tap timestamp while context enrichment follows asynchronously.

| Rating | Stored label | Egress |
| --- | --- | --- |
| 1 | Vibe good 🙂 | false |
| 2 | Tolerable 😐 | false |
| 3 | Bad 🙁 | false |
| 4 | Fucked 😖 | false |
| 5 | Fucky 😵‍💫 | false |
| 5 | FUCK THIS, I'M OUT | true |

In the app, press and hold any option to stamp the time first and then add an optional note. Canceling that dialog discards the pending entry.

## Android widget limitation

The widget exposes exactly six capture options: five VIBE grades plus the full-width red egress action. Stock Android's `RemoteViews` API provides click pending-intents but no app-widget long-press callback; launchers reserve long-press for widget move/resize controls. Apophenia therefore keeps widget taps immediate and navigation-free, and the tappable widget heading opens the app when notes are needed. It does not pretend a launcher-reserved long-press was captured.

This is a platform gap, not an editorial restriction. If Android adds a supported app-widget long-press callback, the intended behavior is to open the app with the selected grade and original press timestamp pre-stamped.
