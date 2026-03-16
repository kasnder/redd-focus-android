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
- `path`: match a view hierarchy path
- `comment`: add a human-readable note
- `color`: optional fallback color
- `blockTouches`: whether touches should pass through

## Examples

```text
com.example.app##viewId=com.example.app:id/distracting_element##comment=Hide distracting panel
com.example.app##desc=Recommended content##comment=Hide recommendation row
com.example.app##path=android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[*]##comment=Hide feed cards
```

## Built-In Rules

The starter rules included with the app live in [`app/src/main/assets/distraction_rules.txt`](../app/src/main/assets/distraction_rules.txt).

These are examples of interface-level adjustments that can help reduce clutter or overstimulation for some users. They are optional and can be enabled, disabled, or extended with custom rules.

## Using the Element Picker

The custom-rules screen includes an optional helper for identifying interface elements. After enabling the helper notification, you can inspect elements in another app and add a rule candidate more quickly.

Because Android interfaces change often, always test a custom rule after creating it.

## Tips

- Start with `viewId` when possible because it is usually the most stable.
- Use `desc` for elements that expose a reliable content description.
- Use `path` only when there is no better identifier.
- Keep comments clear so future edits are easier.
- Re-check custom rules after app updates because screen layouts may change.

## Contributing Rules

If you want to contribute a new built-in rule:
1. Verify that it works reliably.
2. Make sure the comment clearly explains the affected interface element.
3. Add the rule to [`app/src/main/assets/distraction_rules.txt`](../app/src/main/assets/distraction_rules.txt).
4. Open a pull request with a short explanation and test notes.
