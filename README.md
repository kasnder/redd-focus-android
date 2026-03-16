# ReDD Focus for Android (Beta)

> Formerly known as GMWay or GreaseMilkyway.

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" alt="ReDD Focus Logo" width="200"/>
</div>

ReDD Focus is an Android accessibility tool for people with ADHD and related attention difficulties. It helps users make supported apps feel calmer and easier to navigate by reducing selected sources of visual clutter and overstimulation on their own device.

<a href='https://play.google.com/store/apps/details?id=net.kollnig.greasemilkyway'><img height=70 alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png'/></a>
<a href='https://github.com/kasnder/GreaseMilkyway/releases/latest'><img height=70 alt='Get it on Github' src='https://raw.githubusercontent.com/TrackerControl/tracker-control-android/master/images/get-it-on-github.png'/></a>
<a href='https://apt.izzysoft.de/fdroid/index/apk/net.kollnig.greasemilkyway'><img height=70 alt='Get it on IzzyOnDroid' src='https://raw.githubusercontent.com/TrackerControl/tracker-control-android/master/images/get-it-on-izzy.png'/></a>

## What the App Does

ReDD Focus is designed for people who may find some app interfaces hard to manage because they are visually busy, highly stimulating, or difficult to ignore.

The app can help users:
- reduce distracting interface sections in supported apps
- make high-stimulation screens feel simpler and calmer
- add a little friction before changing focus settings
- temporarily pause a rule instead of turning it off completely
- create custom rules for specific interface elements

The app works locally on the device through Android's accessibility framework. It does not proxy traffic, inspect web browsing, or rely on remote servers.

## How It Works

ReDD Focus ships with a set of optional interface rules. Users can enable the rules that help them and leave the rest off. These rules target specific on-screen elements in supported apps so the interface can be made less overwhelming for the individual user.

The app also includes:
- a configurable friction gate for opening settings
- a default pause duration for temporarily relaxing a rule
- an advanced custom-rules editor for users who want finer control

## Privacy

ReDD Focus:
- runs entirely on-device
- does not require an account
- does not collect or transmit personal data
- uses accessibility access only to detect and hide configured interface elements

## Documentation

- General project overview: this README
- Advanced rule syntax and examples: [`docs/CUSTOM_RULES.md`](docs/CUSTOM_RULES.md)
- Built-in starter rules: [`app/src/main/assets/distraction_rules.txt`](app/src/main/assets/distraction_rules.txt)

## Contributing

Contributions are welcome.

If you want to improve the built-in rules:
1. Test the rule carefully on-device.
2. Add a clear comment explaining which interface element it affects.
3. Submit a pull request.

For rule syntax and advanced guidance, see [`docs/CUSTOM_RULES.md`](docs/CUSTOM_RULES.md).

## License

This project is licensed under the GNU General Public License v3.0. See [`LICENSE`](LICENSE).
