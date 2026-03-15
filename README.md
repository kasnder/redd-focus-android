# ReDD Focus for Android (Beta)

> **Note:** This app was formerly known as GMWay (or GreaseMilkyway). The functionality remains the same, only the name has changed.

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" alt="ReDD Focus Logo" width="200"/>
</div>

ReDD Focus is an Android accessibility service designed to help people with attention-related conditions (such as ADHD) manage their digital environment. By allowing users to block distracting content in apps, it helps create a more focused and less overwhelming digital experience.

<a href='https://play.google.com/store/apps/details?id=net.kollnig.greasemilkyway'><img height=70 alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png'/></a>
<a href='https://github.com/kasnder/GreaseMilkyway/releases/latest'><img height=70 alt='Get it on Github' src='https://raw.githubusercontent.com/TrackerControl/tracker-control-android/master/images/get-it-on-github.png'/></a>
<a href='https://apt.izzysoft.de/fdroid/index/apk/net.kollnig.greasemilkyway'><img height=70 alt='Get it on IzzyOnDroid' src='https://raw.githubusercontent.com/TrackerControl/tracker-control-android/master/images/get-it-on-izzy.png'/></a>

## Purpose

This app is specifically designed as an accessibility tool to help people who:
- Struggle with attention regulation
- Find certain app features overly stimulating or distracting
- Need help maintaining focus while using their devices
- Want to customise their digital environment to better suit their needs

ReDD Focus is designed to make digital spaces more accessible and manageable for people with attention-related conditions.

## Adding new Rules

Rules follow a simple syntax with key-value pairs separated by `##`:

```
<package-name>##viewId=<view-id>##comment=<title>
```

Lines starting with `//` are treated as comments.

### Components:

- `package-name`: The package name of the target app (e.g., `com.example.app`)
- **Targeting (use at least one)**:
  - `viewId`: (Recommended) The resource ID of the view to block (e.g., `com.example:id/button`)
  - `path`: CSS-like path to the element (e.g., `android.widget.FrameLayout[0]>android.widget.TextView[*]`). `[*]` matches all elements of that type at that level.
  - `text`: Exact text content to match
  - `className`: Exact Android class name to match (e.g., `android.widget.Button`)
  - `desc`: Pipe-separated list of content descriptions to match
- `comment`: Human-readable title/description for the rule (required)
- `color`: (Optional) Hex color for the overlay (defaults to white #FFFFFF)
- `blockTouches`: (Optional) Set to `true` (default) to block touches, or `false` to allow interaction

### Examples:

```
// Block YouTube Shorts button
com.google.android.youtube##path=androidx.drawerlayout.widget.DrawerLayout[0]>android.widget.FrameLayout[0]>android.widget.FrameLayout[0]>android.widget.HorizontalScrollView[0]>android.widget.LinearLayout[0]>android.widget.Button[1]##comment=Hide Shorts button

// Block YouTube next-up recommendations
com.google.android.youtube##viewId=com.google.android.youtube:id/watch_list##comment=Hide next-up video recommendations

// Block Instagram Stories
com.instagram.android##path=androidx.viewpager.widget.ViewPager[0]>android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.widget.LinearLayout[0]>androidx.recyclerview.widget.RecyclerView[0]##comment=Hide Stories

// Block WhatsApp AI button
com.whatsapp##viewId=com.whatsapp:id/extended_mini_fab##comment=Hide AI button

// Example of a rule that allows touches to pass through
com.example.app##viewId=com.example.app:id/some_view##color=FFFFFF##blockTouches=false##comment=Hide but allow interaction
```

## Creating Your Own Rules

New rules can be added directly from within the app using the built-in element picker. Open the app, tap the picker button, switch to the target app, and tap any element to create a rule for it.

### Tips for Creating Rules

1. **View ID rules** (recommended): If an element has a stable resource ID, `viewId` is the most reliable and concise way to target it
2. **Path-based rules**: Use the element picker to generate a `path` selector — useful when no stable view ID is available
3. **Touch Blocking**: Use `blockTouches=true` (default) to prevent interaction with blocked elements, or `blockTouches=false` to allow touches to pass through
4. **Testing**: After creating a rule, test it thoroughly and verify it doesn't block unintended elements

## Privacy

ReDD Focus:
- Runs entirely on your device
- Doesn't collect any data
- Doesn't require internet access
- Only uses the accessibility service to analyse and block content

## Contributing

Contributions are welcome! Feel free to submit issues and pull requests.

### Default Rules

The app comes with a set of default rules to help users get started. These rules are stored in [`app/src/main/assets/distraction_rules.txt`](app/src/main/assets/distraction_rules.txt). We welcome contributions to improve these rules! If you've found a way to block distracting content in an app, please consider:

1. Testing your rule thoroughly
2. Adding a clear comment explaining what the rule blocks
3. Submitting a pull request with your addition

Each rule should follow the format described in the [Adding new Rules](#adding-new-rules) section above.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](https://github.com/kasnder/GreaseMilkyway/blob/main/LICENSE) file for details. 
