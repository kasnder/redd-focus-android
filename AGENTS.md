# ReDD Focus agent guide

**Only what the code cannot tell you belongs here.** You can read the tree; assume the next agent can too. Module layout, class responsibilities, call paths, which method does what, what a field is named — all of that is a tool call away and is deliberately *not* recorded here, because a description of the code is worse than the code: it goes stale silently and gets believed anyway.

What survives that test is roughly three things: an invariant that is load-bearing but invisible at the call site, a hazard that has already caused a bug, and a judgement call whose reasoning left no trace in the source. Before adding a line, ask whether an agent with this repo in front of it would get this wrong without being told. If it would not, delete the line. If it would, say the *why* in one sentence and trust it to find the *what*.

The rule format is documented for users in [docs/CUSTOM_RULES.md](docs/CUSTOM_RULES.md) and defined by `FilterRuleParser` — read one of those rather than trusting a copy.

## Who this serves

ReDD Focus (formerly GMWay / GreaseMilkyway; Gradle root project still `GreaseMilkyway`, package `net.kollnig.greasemilkyway`) is for people with ADHD and related attention difficulties who are trying to resist their own compulsions, on their own device. Two consequences run through everything:

- **Everything stays on-device.** No network layer, no account, no analytics — a proposal that needs any of them is wrong before it is discussed.
- **An overlay that will not go away is worse than one that never appears.** A missed block is a distraction the user can still choose to ignore; a stuck block makes the app itself the thing they cannot get rid of. Where a lifecycle decision is genuinely ambiguous, fail towards removing overlays.

Friction is a feature — that is what the friction gate is — but it is friction the user opted into, over a surface they can always leave.

## Working style

- Java only (Java 11 source/target). No Kotlin, no Compose — Views, XML layouts, `RecyclerView`.
- Where logic is unit-testable (rule parsing, config and saved state, adapters), write the failing test first and confirm it fails. Never weaken or delete a test to make it pass; if a test is wrong, fixing it is its own change, called out as such.
- Be honest about what was verified. Unit tests do not exercise `AccessibilityNodeInfo`, overlays or the service lifecycle, and there are no instrumented tests at all. If a change touches those, say it is unverified on-device instead of reporting green tests as verification.
- Ask before anything that destroys state on a connected device. Uninstalling, clearing app data or resetting the accessibility service wipes every rule and pause the user has set up.

## Build and test

Only the `standard` flavour needs building or testing. `gmwaylite` (`applicationIdSuffix .lite`) shares 100% of the Java source and differs only in resources — `assets/distraction_rules.txt`, a few strings and icons, and the three flags in `res/values/bools.xml`. Flavour-conditional behaviour is expressed through resource overrides, never Java branching, so `standard` exercises every code path. Do not run gmwaylite tasks unless the change is to a lite-only resource.

```bash
./gradlew testStandardDebugUnitTest :distractionlib:testDebugUnitTest
```

```bash
./gradlew assembleStandardDebug
```

```bash
./gradlew lintStandardDebug
```

Narrow to one class with `--tests 'net.kollnig.distractionlib.FilterRuleParserTest'`. HTML reports land in `*/build/reports/tests/`. Tests are JUnit 4 + Robolectric + Mockito. CI (`.github/workflows/ci.yml`) runs plain `./gradlew test` on JDK 21, which covers both flavours.

`tools/check_rule.py` answers whether a rule covers a screen from a `uiautomator` dump, without building or installing; `tools/dump_tree.py` prints the same dump as a tree. Run them over dumps of *several* screens of the same app — a rule that also swallows the neighbouring screens is the usual defect. Their docstrings state where they diverge from the running service; when matching semantics change in Java, check whether the script still mirrors them.

## Rules and saved state

- Rules are **opt-in**: `isPackageDisabled` defaults to `true` and `isRuleEnabled` to `false`. Nothing is blocked until the user asks for it.
- Saved state is keyed on `FilterRule.identity()` — the matching fields (`viewId`, `desc`, `path`, `className`, `text`, `blockTouches`) plus the package — not on the rule text, even though `FilterRule.equals`/`hashCode` are. So `comment`, `category` and `color` are free to edit, but **changing a matching field of a shipped rule silently orphans every user's saved state for it**, switching their block off with no error. Treat it as a migration, not an edit.
- `assets/legacy_rules_v0.txt` is a frozen copy of the 0.9.1 rules per flavour, needed because pre-migration keys (`ServiceConfig.migrateRuleKeys()`) hash the rule *text* and can only be recomputed from the text that shipped. **Never edit a snapshot.** If `PREFS_VERSION` is bumped again, add a new one. `ServiceConfigTest.everySnapshotRuleStillExistsInTheBundledRules` is the guard.
- Rules in one package sharing a `comment` render as a single row (`RulesAdapter.mergeRules`) and are enabled, paused and disabled as a unit — that is how one user-facing switch is backed by several sibling rules. Merging ignores `category` and never merges comment-less rules, nor across the custom/built-in or navigation/blocking boundaries.
- Anchor a rule in structure, not in words. `viewId` and `path` survive; `desc`, `text` and `className` are translated or re-themed and break for every user outside the locale the rule was written in. Contributed rules should not depend on them.
- A pause is a timestamp, not a flag. `ServiceConfig.getRules()` resolves expired pauses as a side effect (re-enabling the rule and writing back), and `DistractionControlService.scheduleNextRuleUpdate()` posts a reload for the nearest expiry so blocking resumes without the user acting. Both halves matter: without the reload, a pause quietly never ends.
- `RulesAdapter.java` contains a literal NUL byte in the merge key's separator, so `file` reports the source as binary and **`grep` silently matches nothing in it** — use `Read` or `grep -a`, or you will conclude a symbol is absent when it is right there.

## Navigation rules ("Open on launch")

Where the distraction *is* the landing screen there is nothing to cover, so entering the app clicks an element instead. Two habits from the blocking pipeline are actively wrong here:

- **A wrong match has side effects.** A blocking rule that stops matching fails visibly and harmlessly — the content reappears. A navigation rule that matches the wrong element taps it. So refusing to act beats acting on a guess: the matcher accepts only `viewId`, `viewId`+`childPath` and `desc`, and rule generation returns null rather than fall back past them. A navigation rule written with `text`, `path` or `className` parses fine and then silently never fires.
- **Labels beat structure here**, inverting the guidance above. A `desc` that stops matching produces no click; a path whose index has shifted produces the *wrong* one.
- A navigation rule must never reach the overlay pipeline — it would paint a box over the control it needs to click. The two rule sets meet only in the display list.
- **Firing once per visit is the point.** Navigating on every pass would throw the user out of the feed every time they deliberately went back to it. Hence also that the service adopts the foreground app on connect *without* arming: the service reconnecting is not the user opening the app.
- Navigation retries on a timer because the target does not exist for the first few frames after launch, and clicks walk up a few ancestors because apps label the icon but attach the listener to a wrapper above it. Both look redundant and are not.
- Testing hazard: `uiautomator dump` takes over accessibility and restarts this service — which *is* the mid-session jump you would be trying to observe. Prefer `screencap` for anything spanning a visit. `adb shell am force-stop` on this app disables the accessibility service, and only the user can switch it back on.

## Accessibility service invariants

The service is the fiddly part of the codebase, and most of the commit history is overlay lifecycle fixes. Each of these encodes a bug that has already happened:

- **Node recycling.** Every `getChild()` / `findAccessibilityNodeInfosByViewId()` / `getRootInActiveWindow()` result is recycled in a `finally`, except when the node *is* the root. Preserve this.
- **Frame-batched processing.** Events schedule work through `Choreographer.postFrameCallback` behind the `processEventScheduled` guard, not `Handler.post`. Unthrottled processing costs the user battery on every scroll.
- **Sentinel package filtering.** The service normally subscribes only to packages with enabled rules; the moment an overlay is attached it re-subscribes to *all* packages (`configureAccessibilityService(true)`) so any foreground change can tear overlays down, and reverts once none remain (`removeSentinelsIfIdle`). Both directions must stay in sync — losing the widening is exactly how an overlay gets stranded over another app.
- **Delayed clearing.** Foreign-window events (SystemUI, launcher) post `pendingClear` after `CLEAR_OVERLAYS_DELAY_MS` (150 ms) and re-inspect the active root first, rather than clearing immediately. This is what stops the notification shade flickering overlays; removing the delay reintroduces the bug.
- **Screen off** cancels pending work and force-clears overlays. Nothing processes while `screenOn` is false.
- `OverlayManager` is main-thread-only and synchronous by design — read its class comment before changing it.
- The element picker suppresses blocking (`shouldProcessRules()` returns false) while it is active, so the user can tap the element they mean. Any new mode that draws its own UI over another app needs the same.

The module seam is a rule, not just a layout. `:distractionlib` is app-agnostic and must not learn about SharedPreferences, notifications or activities: it asks the app for those through the hooks on `BaseDistractionControlService`, and the app drives it only through that class's `protected final` helpers. Both sets are declared together at the top of the class — read them there rather than trusting a list here. Reaching past the seam in either direction is the thing to avoid.

## Conventions and hazards

- Built-in rules live in `app/src/main/assets/distraction_rules.txt`, one per line, always with a `category` and a `comment` — both are shown in the UI, which groups by package and then category. Rules that *open* a screen rather than hide one go in `navigation_rules.txt` instead.
- A new rule's target package must be added to `<queries>` in `AndroidManifest.xml`, or the package label lookup silently fails on API 30+.
- Any new path that unblocks something or opens settings goes through `MainActivity.runWithFrictionGate(...)`; a path that skips it quietly defeats the feature for the users who turned it on.
- User-visible strings belong in `res/values/strings.xml`. If a string names the app, `gmwaylite` needs an override too.
- Version bumps live in `app/build.gradle` (`versionCode`/`versionName`); store metadata is in `fastlane/metadata/android/`.
- Never log or dump the contents of another app's screen, even behind a debug flag. The service can read every screen the user opens; a dumper existed once and was removed for this reason.
- Edit the worktree that is actually being built, not another checkout.
- GPLv3.

## Pull requests, issues and documentation

- Use the template in [.github/pull_request_template.md](.github/pull_request_template.md) for PRs.
- Raise PRs for new code you've written. When asked to review a PR, post findings to the original PR, and make the fixes directly on its branch if they are not too many; if it is a substantial change, make a stacked PR. When fixing a PR under review, push to its existing branch.
- A PR is one concern, small enough to review confidently as a single coherent change. Closely coupled implementation, tests and required documentation are one concern; unrelated cleanup is not. Judge size by coupling, risk and reviewability rather than a line count, and when the work stops reading as one focused change, split it and stack the branches. A second concern discovered mid-work goes to a follow-up issue or PR.
- Commit subjects are imperative and sentence-case ("Guard indexOf(-1) crashes…"), with the reasoning in the body.
- Explaining a change is the PR description's job. Motivation, investigation notes, evidence and rejected alternatives belong there, not in the tree: do not commit research notes, implementation diaries, change narratives or new guides unless the user explicitly asks for them. Update only the authoritative document the behaviour change actually affects, and link to existing detail instead of duplicating it.
- Open and future work lives in GitHub issues, never as backlogs or TODO lists in maintained files.
- Prefer editing an existing file over adding a new one, and create a PR when the work is done.
- Branches are sometimes stacked, one PR based on another PR's branch rather than on `main`. Land a fix on the branch that introduces the defect: fixing only the upper PR leaves a window where merging the lower one carries the defect into `main` alone.
- Add a code comment only for a non-obvious invariant, hazard or platform constraint, and keep it short. Never narrate the change, rejected alternatives or history in code; that context belongs in the PR description.
