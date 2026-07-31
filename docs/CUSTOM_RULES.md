# Custom Rules README

This document covers the advanced custom-rules feature in ReDD Focus.

Custom rules are intended for people who want more control over which interface elements are reduced inside supported apps. This feature is optional and best suited to advanced users who are comfortable inspecting Android view identifiers and screen structure.

## What Custom Rules Are For

Custom rules let you describe a specific interface element that ReDD Focus should hide on your device. This can be useful when a built-in rule does not cover the part of the interface that feels distracting or overstimulating for you.

## Rule Format

Each rule goes on its own line:

```text
package.name##key=value##key=value
```

Common keys:
- `viewId`: match a specific Android view ID
- `desc`: match a content description
- `descMatch`: how `desc` is compared — `exact` (default), `prefix`, or `substring`. Use
  `prefix` when the description ends in something that changes: a description is what a screen
  reader announces, so it often carries a badge count or a selected state
  (`Unread filter, 25, unselected`). Matching that exactly means the rule stops working as soon
  as the count does. Store the steady part (`Unread filter`) with `descMatch=prefix` instead.
  Keep it as long as you can — a short value can quietly match its neighbours too
- `path`: match a view hierarchy path, counted from the window root
- `childPath`: match a view hierarchy path counted from the `viewId` above, instead of from the window root
- `hasThumbnail`: keep only matches that contain an image of at least a given width in dp,
  e.g. video cards
- `requiresViewId`: apply the rule only on screens showing this view
- `requiresSelected`: apply the rule only when a given view, such as a navigation tab, is selected
- `category`: group related rules in the rule list
- `comment`: the name shown in the rule list. Rules for the same app that share
  a comment appear as **one switch**, turned on and off together — useful when
  hiding one thing takes more than one rule
- `color`: optional fallback color
- `blockTouches`: whether touches should pass through

### Anchored Paths

A `path` describes every level between the window root and the element. Apps often add or
remove wrapper layouts around their screens, and each such change breaks every `path` rule for
that screen, even though the element itself is unchanged.

`childPath` avoids this. It is measured from the element named by `viewId`, so it only depends
on the few levels below a stable anchor:

```text
com.example.app##viewId=com.example.app:id/bottom_nav##childPath=android.widget.LinearLayout[0]>android.widget.Button[1]##comment=Hide the second tab
```

The element picker writes `childPath` rules automatically whenever the element has an ancestor
with a view ID.

### Matching by Shape

Some apps draw their content without view IDs, and label it with translated text, so neither
`viewId` nor `desc` can identify it in a way that also works in other languages.

`hasThumbnail` matches such elements by shape instead. It keeps only those matches whose
subtree contains an image at least a given width, which is what separates a video or feed card
from a row of text, buttons or small avatars. Write `true` for the default of 100dp, or give an
explicit width:

```text
com.example.app##viewId=com.example.app:id/list##childPath=android.view.ViewGroup[*]##hasThumbnail=true##comment=Hide video cards in the list
com.example.app##viewId=com.example.app:id/list##childPath=android.view.ViewGroup[*]##hasThumbnail=160dp##comment=Hide only large cards
```

The width is absolute, in dp, rather than a share of the row. That matters on tablets and in
landscape, where apps commonly switch to a compact card: the thumbnail keeps roughly the same
dp size while the row around it grows much wider. On YouTube a thumbnail measures about 360dp
on a phone and 380dp on a tablet, against 16dp to 32dp for avatars and action icons in both
layouts, so an absolute threshold holds across screen sizes where a relative one does not.

Only `hasThumbnail=false` turns the check off. A width that cannot be read falls back to the
default, because dropping the check would widen the rule to every element it is paired with.

Apps hide decorative views, thumbnails among them, from accessibility tools. While a rule with
`hasThumbnail` is enabled, ReDD Focus therefore asks Android for the full view tree, which uses
somewhat more battery than usual. Turning such rules off restores the smaller tree.

### Limiting a Rule to One Screen

Apps often reuse one list for several screens. On YouTube the home feed and the search results
sit in the same `results` list, so a rule written for the feed would blank search results too.

`requiresViewId` and `requiresSelected` narrow a rule to the screen it was written for, using
structure rather than any visible label:

- `requiresViewId=<view-id>` applies the rule only while that view is on screen. Use something
  the screen always shows, such as a logo or a header view.
- `requiresSelected=<view-id>[><path>]` applies the rule only while the named view is selected.
  This is how a navigation bar marks its active tab, so it tells apart screens that otherwise
  look identical in structure.

```text
com.example.app##viewId=com.example.app:id/list##childPath=android.view.ViewGroup[*]##hasThumbnail=true##requiresViewId=com.example.app:id/logo##requiresSelected=com.example.app:id/nav>android.widget.LinearLayout[0]>android.widget.Button[0]##comment=Hide feed cards on the first tab only
```

Both keys are positive conditions, and rules are applied only once their markers are found. If
an app update renames a marker, the rule therefore stops matching and that content becomes
visible again, rather than spreading to screens it was never meant to cover. Prefer this over
writing a rule that has to be right about which screens to skip.

## Examples

```text
com.example.app##category=Feed##viewId=com.example.app:id/distracting_element##comment=Hide distracting panel
com.example.app##category=Recommendations##desc=Recommended content##comment=Hide recommendation row
com.example.app##category=Feed##path=android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[*]##comment=Hide feed cards
```

Two rules presented as a single "Hide feed" switch, because they share a comment:

```text
com.example.app##category=Feed##path=androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[*]##comment=Hide feed
com.example.app##category=Feed##path=androidx.recyclerview.widget.RecyclerView[0]>android.widget.FrameLayout[*]##comment=Hide feed
```

## Built-In Rules

The starter rules included with the app live in [`app/src/main/assets/distraction_rules.txt`](../app/src/main/assets/distraction_rules.txt).

These are examples of interface-level adjustments that can help reduce clutter or overstimulation for some users. They are optional and can be enabled, disabled, or extended with custom rules.

## Using the Element Picker

The custom-rules screen includes an optional helper for identifying interface elements. After enabling the helper notification, you can inspect elements in another app and add a rule candidate more quickly.

Because Android interfaces change often, always test a custom rule after creating it.

## Checking a Rule Offline

Two scripts in [`tools/`](../tools) work on a screen dump, so a rule can be checked without
installing anything. They need Python 3 and `adb`, and nothing else.

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
python3 tools/dump_tree.py ui.xml 12
```

[`dump_tree.py`](../tools/dump_tree.py) prints the screen as an indented tree of classes,
view IDs and bounds, which is how you find an element and an ancestor to anchor on. The
optional second argument limits the depth.

[`check_rule.py`](../tools/check_rule.py) then reports what a rule would cover:

```bash
python3 tools/check_rule.py home.xml search.xml --density 2.0 \
  --rule 'com.example.app##viewId=com.example.app:id/list##childPath=android.view.ViewGroup[*]##hasThumbnail=true'
```

Pass a dump of every screen the rule could reach, not only the one it targets. A rule that
covers its own screen is only half of the answer, and the other half — that it leaves
neighbouring screens alone — is the part that is easy to get wrong, because apps reuse one
list across several screens. The script prints which screen markers held, which elements
were selected, and which of those the thumbnail check kept.

Get the density from `adb shell wm density`, divided by 160. It is only needed for
`hasThumbnail`, whose width is in dp while a dump is in pixels.

The scripts follow the same matching as the service, but they are an aid rather than an
authority: they cannot simulate rules based on `desc`, `text` or `className`, and a dump
has no dependable equivalent of "visible to the user". Confirm on a device before
contributing a rule.

## Tips

- Start with `viewId` when possible because it is usually the most stable.
- Prefer `viewId` plus `childPath` over a plain `path`, and keep the `childPath` short.
- Avoid `desc` and `text` in contributed rules: both are translated, so a rule that relies on
  them only works for users of one language.
- Use `path` only when there is no ancestor with a view ID to anchor on.
- Use `category` to group related rules. If omitted, custom rules appear under Custom rules.
- Keep comments clear so future edits are easier.
- Re-check custom rules after app updates because screen layouts may change.

## Contributing Rules

If you want to contribute a new built-in rule:
1. Verify that it works reliably, and that it does not depend on the language the app is
   displayed in.
2. Add a `category` that groups the rule with similar controls.
3. Make sure the comment clearly explains the affected interface element.
4. Add the rule to [`app/src/main/assets/distraction_rules.txt`](../app/src/main/assets/distraction_rules.txt).
5. Open a pull request with a short explanation and test notes.
