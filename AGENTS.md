# AGENTS.md

This file provides guidance to coding agents (including Claude Code, via `CLAUDE.md`) when working with code in this repository.

## Project

ReDD Focus (formerly GMWay / GreaseMilkyway, Gradle root project name still `GreaseMilkyway`, package `net.kollnig.greasemilkyway`) is an Android accessibility tool that hides distracting UI elements in other apps by drawing opaque overlays on top of them. Everything runs on-device; there is no network layer, no account, no analytics.

Java only (Java 11 source/target), no Kotlin, no Compose. Views + XML layouts + `RecyclerView`.

## Build flavours — only test `standard`

Two product flavours on dimension `variant`:

- **`standard`** — the full app. **This is the only flavour that needs building, testing, or verifying.**
- **`gmwaylite`** — a cut-down "GMWay Lite" build (`applicationIdSuffix .lite`). It only overrides `assets/distraction_rules.txt` (WhatsApp rules only), a few strings, icons, and three feature flags in `res/values/bools.xml` (`show_help_button`, `show_footer_branding`, `show_custom_rules_fab`). It shares 100% of the Java source with `standard`.

Unless a change specifically touches lite-only resources, **do not run gmwaylite tasks** — testing/building `standard` is sufficient. Flavour-conditional behaviour is expressed only through resource overrides (`R.bool.*`, strings, assets), never through Java branching, so `standard` exercises all the code paths.

## Commands

```bash
./gradlew testStandardDebugUnitTest :distractionlib:testDebugUnitTest
```

```bash
./gradlew assembleStandardDebug
```

```bash
./gradlew lintStandardDebug
```

Run a single test class or method:

```bash
./gradlew :distractionlib:testDebugUnitTest --tests 'net.kollnig.distractionlib.FilterRuleParserTest'
```

```bash
./gradlew testStandardDebugUnitTest --tests 'net.kollnig.greasemilkyway.ServiceConfigTest.*'
```

CI (`.github/workflows/ci.yml`) runs plain `./gradlew test` on JDK 21, which covers both flavours; locally the `standard` variant is what matters. HTML reports land in `*/build/reports/tests/`.

Tests are JUnit 4 + Robolectric + Mockito, all local unit tests — there are no instrumented (`androidTest`) tests. Anything touching `AccessibilityNodeInfo` or overlays realistically requires on-device manual testing.

## Module split

- **`:distractionlib`** (`net.kollnig.distractionlib`) — reusable, app-agnostic core: rule model/parsing, the abstract accessibility service, overlay window management, the element picker, and the friction gate. Knows nothing about SharedPreferences, notifications, or the UI.
- **`:app`** (`net.kollnig.greasemilkyway`) — the concrete app: persistence, notifications, activities, and the concrete `AccessibilityService` subclass that wires the library to them.

The seam is `BaseDistractionControlService` (abstract, in the lib) ← `DistractionControlService` (in the app). The library asks the app for rules and for permission to act via abstract hooks (`loadRules()`, `shouldProcessRules()`, `onServiceReady/Teardown()`, `onPauseNotificationShouldShow/Cancel()`); the app never reaches into the overlay machinery directly, it only calls the `protected final` helpers (`reloadRulesFromSource()`, `reevaluateBlockingState()`, `clearCurrentOverlays()`).

## Rule pipeline

A rule is a single line of ad-block-style text. `FilterRule.equals`/`hashCode` are defined purely on `ruleString`, but saved state is *not*: `ServiceConfig` keys enabled/paused prefs on `FilterRule.identity()`, built only from the matching fields (`viewId`, `desc`, `path`, `className`, `text`, `blockTouches`) plus the package. So `comment`, `category` and `color` are free to edit — **but changing a matching field silently orphans every user's saved state for that rule.**

The legacy scheme (prefs keyed on `ruleString.hashCode()`) is migrated in `ServiceConfig.migrateRuleKeys()`. Because legacy keys hash the *text*, they can only be recomputed from the text that shipped, so `assets/legacy_rules_v0.txt` holds a frozen copy of the 0.9.1 rules per flavour. **Never edit those snapshots**; if `PREFS_VERSION` is bumped again, add a new one. `ServiceConfigTest.everySnapshotRuleStillExistsInTheBundledRules` fails if a bundled rule's matching fields drift away from the snapshot.

Rules in the same package that share a `comment` are rendered as a **single row** in the UI (`RulesAdapter.mergeRules`) and enabled/paused/disabled as a unit — that's how one user-facing switch is backed by several rules (e.g. Instagram's feed needs two sibling containers). Merging ignores `category` and never merges comment-less rules.

Format (see `docs/CUSTOM_RULES.md` for user-facing docs):

```
package.name##viewId=…##desc=a|b##path=Class[0]>Class[*]##category=…##comment=…##color=#RRGGBB##blockTouches=true
```

Flow:

1. `ServiceConfig.getRules()` reads built-in rules from `assets/distraction_rules.txt` (flavour-specific) plus user custom rules from SharedPreferences, parses them via `FilterRuleParser`, then layers on persisted enabled/paused state. Rules are **opt-in**: `isPackageDisabled` defaults to `true` and `isRuleEnabled` to `false`. Expired pauses are resolved here as a side effect (pause expiry re-enables the rule and writes back to prefs).
2. `BaseDistractionControlService` matches enabled rules against the active window. Matching precedence in `applyRule`: `path` → `viewId` → recursive scan (`desc` / `className` / `text`).
3. Each match's screen bounds get a `TYPE_ACCESSIBILITY_OVERLAY` window keyed by `packageName::ruleString[::matchIndex]`; overlays that no longer match on a pass are removed. `OverlayManager` is main-thread-only and synchronous by design — see its class comment before changing it.

## Accessibility service invariants

This is the fiddly part of the codebase; the recent commit history is largely overlay lifecycle bug fixes. Be careful with:

- **Node recycling.** Every `getChild()` / `findAccessibilityNodeInfosByViewId()` / `getRootInActiveWindow()` result is recycled in a `finally`, except when the node *is* the root. Preserve this.
- **Frame-batched processing.** Events schedule work through `Choreographer.postFrameCallback` with a `processEventScheduled` guard, not `Handler.post`. Do not add unthrottled processing.
- **Sentinel package filtering.** Normally the service subscribes only to packages that have enabled rules. The moment an overlay is attached it re-subscribes to *all* packages (`configureAccessibilityService(true)`) so any foreground change can tear overlays down, and reverts once no overlays remain (`removeSentinelsIfIdle`). Both directions must stay in sync.
- **Delayed clearing.** Foreign-window events (SystemUI, launcher) do not clear overlays immediately; they post `pendingClear` after `CLEAR_OVERLAYS_DELAY_MS` (150ms), which re-inspects the active root window first. This exists specifically to stop the notification shade from flickering overlays — removing the delay reintroduces the bug.
- **Screen off** cancels all pending work and force-clears overlays; nothing should process while `screenOn` is false.

## Other pieces

- **Friction gate** (`FrictionGateActivity`) — optional typing challenge before settings/unblocking actions; word count from `ServiceConfig.getFrictionWordCount()`, invoked via `MainActivity.runWithFrictionGate(...)`.
- **Pause** — a rule or a whole package can be paused until a timestamp instead of disabled. `DistractionControlService.scheduleNextRuleUpdate()` posts a delayed reload for the nearest expiry so blocking resumes without user action. `PauseNotification` exposes this from the notification shade for the foreground app.
- **Element picker** (`ElementPickerOverlay` + `ElementPickerRuleGenerator`) — user taps a UI element in another app and gets a generated rule; generation priority is `viewId` > `path` > `text` > `className`. While the picker is active, `shouldProcessRules()` returns false so no blocking overlays interfere.
- **`LayoutDumper`** — debug-only hierarchy dumper, hard-disabled via `ENABLED = false`.

## Conventions

- Built-in rules go in `app/src/main/assets/distraction_rules.txt`, one per line, always with a `category` and a `comment` — both are shown in the UI (`RulesAdapter` groups by package, then category).
- New rule target packages must be added to `<queries>` in `AndroidManifest.xml`, otherwise package label lookup fails on API 30+.
- User-visible strings belong in `res/values/strings.xml`; if a string names the app, the `gmwaylite` flavour needs an override too.
- Version bumps live in `app/build.gradle` (`versionCode`/`versionName`); store metadata is in `fastlane/metadata/android/`.
- GPLv3.
