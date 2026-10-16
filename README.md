# TikTok Time Limit

A customizable time-management plugin for TikTok that helps you reduce mindless scrolling and take control of your daily screen time.

Instead of simply tracking usage, the plugin introduces a daily time limit and displays a full-screen blocking dialog when that limit is reached. Emergency unlocks let you temporarily return to TikTok while keeping your usage restrictions in place.

## Features

- **Daily time limit** — configure how many minutes you can spend on TikTok each day.
- **Full-screen blocking** — displays a blocking screen when your daily limit is reached.
- **Emergency unlocks** — temporarily unlock TikTok for a configurable amount of time, with a daily usage cap.
- **Unlock countdown** — optionally require a waiting period before an emergency unlock becomes available.
- **Usage reminders** — receive a notification shortly before reaching your daily limit.
- **Customizable themes** — choose from six presets or create your own color scheme.
- **Color picker** — adjust colors using a color wheel, brightness slider, or HEX input.
- **Seven-day usage chart** — review your recent screen time, daily totals, and average usage.
- **Bilingual interface** — Russian and English, selected automatically based on your device language.
- **Persistent local settings** — preferences and usage data are stored locally using Android SharedPreferences.
- **Configurable tracking** — choose whether to count only TikTok's main activity or all tracked TikTok activities.

## Screenshots

Add screenshots of the settings screen, theme editor, usage chart, and blocking screen here.

## Configuration

The plugin provides the following settings:

| Setting | Default | Description |
|---|---:|---|
| Daily limit | 60 minutes | Maximum daily usage before blocking |
| Emergency unlock duration | 10 minutes | Duration of each temporary unlock |
| Emergency unlocks per day | 2 | Maximum number of unlocks per day |
| Reminder | 5 minutes | How long before the limit to show a reminder; `0` disables it |
| Unlock waiting period | 5 seconds | Delay before an emergency unlock becomes available |
| Main activity only | Enabled | Whether to count only the configured main TikTok activity |
| Background color | `#121212` | Background color of the blocking screen |
| Accent color | `#FE2C55` | Main accent and button color |

Values can be changed through the plugin's settings interface.

## Usage History

The plugin tracks daily usage and displays the last seven days in a bar chart.

- The dashed line represents your configured daily limit.
- Today's usage is updated live.
- Daily totals and the seven-day average are displayed below the chart.
- Historical data is stored locally on the device.

The history is collected from the time this version of the plugin starts recording usage; it does not import previous TikTok activity.

## How It Works

The plugin uses Android activity lifecycle callbacks to track foreground usage. It stores daily usage counters and settings in `SharedPreferences`, checks the configured limit, and displays a full-screen dialog when blocking is required.

Usage is measured using Android's monotonic elapsed-time clock, while daily rollover and emergency unlock expiration use the system wall clock.

No remote server or cloud account is required by the implementation.

## Compatibility and Limitations

- The plugin is built for the MargyPlugin API and requires a compatible host application.
- Activity tracking depends on the host's lifecycle callbacks and TikTok's internal activity structure.
- The main-screen-only option relies on the activity class name `com.ss.android.ugc.aweme.main.MainActivity`. Behavior may vary between TikTok versions.
- The blocking dialog is an in-app restriction, not an operating-system-level application lock. It cannot guarantee that TikTok will remain inaccessible in every situation.
- Audio focus is requested on a best-effort basis and may not stop video playback on every Android version.
- Usage data is stored locally and is not synchronized across devices.

## Privacy

The implementation stores settings, usage counters, emergency unlock state, and historical usage locally through `SharedPreferences`. It does not implement a remote analytics or synchronization service.

## Development

The plugin is written in Java and uses Android SDK components for its interface, dialogs, activity lifecycle tracking, and persistent settings.

To build or modify the project, use a development environment compatible with the MargyPlugin API version required by your host application. The complete project and its dependencies are needed to determine the exact build configuration.

## License

No license has been specified yet. If you want others to fork, modify, and redistribute the project, add a `LICENSE` file describing the permissions and conditions you choose.

---

*Built as a personal project to make endless scrolling a little less automatic.*
