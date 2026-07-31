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

Rules in the same package that share a `comment` are rendered as a **single row** in the UI (`RulesAdapter.mergeRules`) and enabled/paused/disabled as a unit — that's how one user-facing switch is backed by several rules (e.g. Instagram's feed needs two sibling containers). Merging ignores `category` and never merges comment-less rules, nor across the custom/built-in or navigation/blocking boundaries.

`RulesAdapter.java` contains a literal NUL byte in that merge key's separator, so `file` reports the source as binary and **`grep` silently matches nothing in it** — use `Read` or `grep -a`, or you will conclude a symbol is absent when it is right there.

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

## Navigation rules ("Open on launch")

A second, much smaller pipeline for the case where the distraction *is* the landing screen, so there is nothing to cover: entering the app clicks an element instead. Instagram opens on its inbox rather than the feed; WhatsApp opens on a chat-list filter.

**The asymmetry that governs this whole area:** a blocking rule that stops matching fails visibly and harmlessly — the content reappears. A navigation rule that matches the *wrong* element taps it. Clicking has side effects; covering does not. So selectors here are held to a stricter standard than anywhere else in the codebase, and refusing to act is always preferred over acting on a guess.

- Bundled in `app/src/main/assets/navigation_rules.txt`, same `##` syntax, parsed by the same `FilterRuleParser`. The matcher (`findNavigationTarget`) understands exactly three forms: `viewId`, `viewId`+`childPath`, and `desc`. Nothing else — a rule using `text`, `path` or `className` will silently never fire.
- Loaded by `ServiceConfig.getNavigationRules()`, **never** by `getRules()`: a navigation rule in the overlay pipeline would paint a box over the control it needs to click. Keyed on `nav_rule_enabled_` + the same `ruleKeySuffix`, so navigation and blocking state for the same element stay independent. Opt-in, and deliberately not gated on `isPackageDisabled`.
- **Separate pipeline, one UI.** `MainActivity.loadSettings()` concatenates `getRules()` and `getNavigationRules()` for display only, so the user sees one list of what ReDD Focus does to an app. `RulesAdapter` dispatches each row back to its own store via `setRowEnabled` / `removeCustomRow`, keyed on `FilterRule.isNavigation`. Never merge the two lists anywhere else. Navigation rules get their own section (`getRuleGroup` forces the heading) and never merge into a blocking row, since a mixed row's switch would have to write to two stores at once.
- Because they appear under the app switch, navigation rules **obey `isPackageDisabled`** — an app switched off must do nothing at all. The picker's navigation path therefore enables the package, exactly as the blocking path does.
- Long-press to delete works for custom navigation rules through the same handler as custom blocking rules; there is no separate delete UI.
- Users add their own with the element picker's **Open** button; those are stored in `custom_navigation_rules`, separate from `custom_rules`. Adds are deduplicated by `hasNavigationRuleLike` (identity, not text), because `comment` is outside `identity()`: two rules differing only in their comment share one preference key and would show as two rows driven by a single switch. `ElementPickerRuleGenerator.navigationSelector()` ranks selectors `viewId` > `desc` > anchored path and **returns null rather than fall back further**. That ordering deliberately inverts the usual structure-over-labels preference: a description that stops matching produces no click, whereas a path whose index has shifted produces the *wrong* one. Same reason `className`, root-relative `path` and `text` are refused outright — see the asymmetry below.
- `AutoNavigator` (pure Java, unit-tested) decides *when*: entering an app arms it, and it disarms on the first successful click or after `ATTEMPT_WINDOW_MS`. **Firing once per visit is the whole point** — navigating on every pass would throw the user out of the feed every time they deliberately went back to it. Our own package and `com.android.systemui` are excluded from foreground tracking, so the notification shade does not end a visit.
- `onServiceConnected` calls `adoptCurrentForegroundPackage()`, which seeds the tracked package *without* arming. The service is reconnected whenever it is re-enabled, updated, or displaced by another accessibility client — notably `uiautomator dump`, which takes over accessibility and restarts every other service. Without adoption each reconnection starts blank and the next event reads as the user opening the app, jumping them to the target screen mid-session. Beware when testing on-device: dumping the hierarchy in a loop causes exactly the bounce you are trying to observe, so prefer `screencap` for anything that spans a visit. `adb shell am force-stop` on this app *disables the accessibility service* and only the user can switch it back on, so reach for it sparingly.
- `BaseDistractionControlService.attemptNavigation()` retries on a timer, because the tab bar does not exist for the first few frames after launch. `clickNodeOrAncestor` walks up to `MAX_CLICK_ANCESTRY` levels, since apps label the icon but attach the listener to a wrapper above it.
- Enabled navigation packages, plus the launcher, are added to the event filter in `configureAccessibilityService`. The launcher is what makes leaving an app observable; without it a second visit looks like a continuation of the first.

## Conventions

- Built-in rules go in `app/src/main/assets/distraction_rules.txt`, one per line, always with a `category` and a `comment` — both are shown in the UI (`RulesAdapter` groups by package, then category). Rules that *open* a screen rather than hide one go in `navigation_rules.txt` instead.
- New rule target packages must be added to `<queries>` in `AndroidManifest.xml`, otherwise package label lookup fails on API 30+.
- User-visible strings belong in `res/values/strings.xml`; if a string names the app, the `gmwaylite` flavour needs an override too.
- Version bumps live in `app/build.gradle` (`versionCode`/`versionName`); store metadata is in `fastlane/metadata/android/`.
- GPLv3.
