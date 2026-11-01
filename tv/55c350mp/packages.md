# Toshiba 55C350MP package review

Status: **read-only inventory taken on 1 November 2026; nothing has been disabled.**
Facts from the owner's TV (no serials or personal data): Android TV 11, API 30,
security patch April 2026; built by Hisense on a MediaTek platform; 1920×1080 UI at
320 dpi; 123 preinstalled packages. HOME resolves to Google TV
(`com.google.android.apps.tv.launcherx`); the setup fallback
`com.google.android.tungsten.setupwraith` is present. Ambient mode is
`com.google.android.apps.tv.dreamx` (screensaver `…dreamx/.service.Backdrop`); there is no
`com.google.android.backdrop` and no `com.google.android.tvrecommendations`. The Assistant
voice service is `com.google.android.katniss`. The input picker is `com.vt.tv.input`,
reachable with `com.android.tv.action.VIEW_INPUTS`. Google TV's Apps only mode removes the
Movies/Shows tabs and recommendation rows but still shows a promotional carousel.

`policy.tsv` is the machine-readable source for candidates and keeps. The scripts also
require an explicit local verification list.

| Candidate package | What it may be | Why consider disabling | Evidence required |
| --- | --- | --- | --- |
| `com.google.android.apps.tv.launcherx` | Google TV Home | Stock home with promotions/recommendations | System app; resolved stock HOME before installing Couch OS; remote Home works after replacement |
| `com.google.android.tvlauncher` | Alternate Android TV Home | Same purpose on other firmware | Verify actual resolved HOME; never select two stock launchers by guessing |
| `com.google.android.tungsten.setupwraith` | Google TV setup fallback | May restore stock Home after boot/update | Inspect package/activities and system status; finish initial TV setup first |
| `com.google.android.tvrecommendations` | TV recommendation provider | Unwanted recommendation feed | Not installed on this unit |
| `com.hisense.tv.smartservice` | Suspected Hisense usage reporting | Logcat shows `LogReportUtils` sending app-run events with the device serial | Map the reporting PID to this package (runbook §6); then retest inputs, picture settings and voice |
| `com.hisense.storemode` | Retail demo mode | Not used at home | Confirm it is not running as part of normal picture modes |

No other ACR, vendor tracking or promo packages are guessed. When inventory
identifies one, record its exact preinstalled package, purpose, evidence and why
it is safe here; add a `candidate` row to `policy.tsv`; then opt in locally via
`verified.disable.packages`. Unknown packages stay enabled. A package with an
unclear role is not an ads-only candidate. Streaming, media-player and browser
apps stay even when preinstalled. Personal installed apps and favourites are
never copied into this document.

| Must keep | Known examples (unverified on this unit) | Reason |
| --- | --- | --- |
| Inputs / HDMI / tuner / Live TV | `com.android.tv`, `com.google.android.tv`, `com.android.providers.tv` | Input selection, HDMI-CEC, channels and tuner |
| Remote / Bluetooth | `com.android.bluetooth`; OEM remote services to identify | Remote pairing, D-pad and voice key |
| Chipset / framework | OEM MediaTek/Amlogic/Realtek/Qualcomm services to identify | Hardware, standby, decoding and display |
| Picture / sound / network settings | `com.android.tv.settings`; OEM equivalents to identify | TV controls, networking and system settings |
| Voice / Assistant | `com.google.android.katniss`, `com.google.android.googlequicksearchbox` | Voice key, Assistant and hotword integration |
| Ambient / Photos | `com.google.android.backdrop`, `com.google.android.apps.tv.dreamx`; OEM equivalents | Existing Google Photos ambient slideshow |
| Updates | OEM updater and Google system update services to identify | Security, firmware and recovery updates |
| Play Store / Google services | `com.android.vending`, `com.google.android.gms`, `com.google.android.gsf` | Store, app updates and Google integrations |

Critical package families are denied by the script even if someone adds them as
candidates. Put any additional exact OEM dependencies in local
`protected.packages`. The public examples do not replace the on-device review.

After the first TV run, replace the pending evidence with a sanitised table of
**preinstalled** packages only: firmware/API (no build serial), package purpose,
keep/disable decision, tested voice/ambient/input behaviour, and restore result.
Retain `captures/apply-state.json` privately for restore; it is the authority for
what the script actually changed, including an interrupted operation.
