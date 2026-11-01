# Laptop handover: Toshiba 55C350MP

This brief is for the local Claude Code session on the same Wi-Fi as the TV,
with the owner watching. The cloud session has not connected to or tested the
TV. Complete the stages in order. The project targets Android TV 11+; the exact
Indian firmware version is still unknown.

## 1. Keep private information local

Run `./scripts/setup-dev.sh`. Edit only `config/local.properties` for real inputs.
It uses plain UTF-8 `key=value`: no quotes, `export`, interpolation or backslash
escapes. Never source it in a shell. The Python helper reads it as data.

Keep pairing codes, endpoints, SSIDs, serial/MAC identifiers and weather location
out of commits and chat messages. Favourites and installed-app inventories are
personal. Raw logcat, dumps and screenshots can expose accounts/watch history;
put them under ignored `captures/`, with owner-only permissions. Do not copy
third-party logos out of the TV. There is no need to take an account screenshot.

Before every commit/push:

```sh
git diff --cached --stat
python3 scripts/public_guard.py --staged
python3 scripts/public_guard.py --history
```

Inspect the actual diff locally too. The guard blocks private/generated paths,
unapproved binary files, likely credentials and populated personal settings; it
cannot identify every possible secret. If sensitive material is staged, STOP.
Tell the owner to make the repository private before any commit/push containing
it. This project authorises local work; do not push without the owner's request.

## 2. Prepare the connection

Install JDK 17, Python 3.10+, Android SDK platform-tools (current `adb`), platform
35 and build-tools 35.0.0. Set `JAVA_HOME` / `ANDROID_HOME` in your shell. The
launcher needs API 30 on the TV, independently of its build SDK.

On the TV, open Settings > System > About and tap the Android TV OS build entry
until Developer options appears. Menu wording can vary. Enable debugging only
for this maintenance session. If the TV offers Wireless debugging, use its
pairing screen. Otherwise use the firmware's network ADB mode and approve the
connection on-screen; do not assume a fixed port.

Fill `tv.host` and `adb.connect.port` from the TV screen. If wireless pairing is
offered, also fill the current `adb.pair.port` and six-digit `adb.pair.code`.
The pairing port and connection port can differ and change after reboot.

```sh
./scripts/connect.sh
./scripts/inventory.sh
```

The scripts select one endpoint explicitly and never operate on a default ADB
device. Inventory saves exact model/API, original HOME and classified packages
to `captures/inventory.json`; it prints only counts. Copy the exact model value
into `tv.expected.model`. Reject API < 30. Clear pairing code/port in local
config after successful pairing. Do not publish the raw inventory.

## 3. Establish a baseline before changing HOME

With the owner, test the physical Home key, D-pad, long Back behaviour, Settings,
Wi-Fi, Input key and each HDMI input, Live TV/tuner, volume/picture controls,
Bluetooth remote, Play Store and firmware-update menu. Trigger the existing
Ambient mode and confirm the same Google Photos album still appears.

Test both hands-free "Ok Google" and the physical remote voice key, from stock
HOME and from a running app. Confirm whether hands-free recognition was actually
working in the original setup. Record pass/fail privately, without recording
spoken personal queries or account screens.

```sh
./scripts/diagnose.sh
```

This read-only helper stores voice interaction state, microphone/audio state,
secure Assistant components, input services, HOME resolution and recent logcat
under `captures/diagnostics/`. Inspect it only locally. It deliberately does not
change settings or activate any microphone.

Review `captures/inventory.json`, `tv/55c350mp/packages.md` and `policy.tsv`.
Identify the actual stock launcher and setup fallback from device evidence.
Candidate names are hints, not verified facts. Confirm a candidate is a system
package and inspect its services/activities and dependencies. Never disable
setup while initial TV setup, provisioning, account setup or updates are in
progress. Never disable Ambient/Photos, Assistant, tuner/HDMI, remote/Bluetooth,
chipset services, settings, updates, Google services or Play Store.

Record the exact stock and fallback packages in local config. If inspection proves
there is no setup fallback on this firmware, leave `fallback.package` blank and
set `fallback.verified.absent=true`; the script still refuses that claim if a
known fallback appears in inventory. Add discovered
critical OEM dependencies to `protected.packages`. This list must be nonempty.
Choose only proven ads/tracking candidates in `verified.disable.packages`;
start with the stock/fallback pair. Unknown packages stay. Nothing the owner
might use is removed. Any newly discovered ACR/promo candidate needs a sanitised
reason and public policy row before it can be selected locally.

## 4. Build, install and prove HOME

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 -m unittest discover -s tests -v
./scripts/install.sh
./scripts/install.sh --yes
```

Install first performs a dry run unless `--yes` is supplied. It verifies the model
and API, installs the generic debug APK, provisions private settings and requests
Couch OS as HOME. A firmware refusal to select our HOME role stops the script.
If necessary use the TV's default-app chooser and select Couch OS. If resolution
already points to Couch OS, test the physical Home key before going further.

Some Android TV builds select the requested HOME role but still resolve their
privileged stock launcher first because of intent priority. This was observed on
the API 30 TV emulator. In that case install explicitly opens Couch OS and reports
the mismatch; it disables nothing. Inspect `captures/diagnostics/home-role.txt`
and `resolved-home.txt`. Only if user 0's sole HOME role holder is
`dev.couch.launcher` and resolution points to the exact verified stock package,
set local `verification.stock.home.priority.checked=true`. Apply still checks
the installed/enabled app, the OS role and that exact stock package before each
change, disables stock last, then requires real HOME resolution to Couch OS.
Without this explicit inspection gate the mismatch blocks apply. An unknown
role-dump format or a different resolved HOME also blocks it. Never bypass a
refusal to select Couch OS as the OS HOME role.

The local favourites list is comma-separated package names in desired order.
Use the inventory privately to select them. An empty list is valid; All apps is
still reachable. Optionally set decimal weather coordinates; both are required.
No API key is needed. Updating local config after install uses:

```sh
./scripts/configure.sh
./scripts/configure.sh --yes
```

Provisioning restarts only Couch OS and writes `local-config.json` through stdin
into its private files using `run-as`. Personal values never enter Gradle or the
APK. This requires a debuggable APK. Do not replace it with a release build
without implementing a private import method. Keep a stable local debug keystore:
CI debug builds may have a different signing key. If `adb install -r` reports a
signature mismatch, stop; uninstalling would delete TV settings. Rebuild with
the original local debug key or let the owner decide about data loss.

Use the physical remote to test Couch OS repeatedly and after standby. Where
stock intent priority still wins, open Couch OS from the app list for this
baseline and retest the actual Home key immediately after apply. Confirm a
large 12-hour clock, date, Celsius weather (or no temperature offline), favourites
order, artwork, All apps, and launch/return behaviour. Exercise every item with
D-pad only: top buttons, row, horizontally offscreen items, grid, theme selection,
and Back. Check Midnight Mint, Broadcast and Forest Crest, then reboot and
confirm the selected theme remains. Check large text/overscan at sofa distance.

## 5. Find the input picker on this firmware

The launcher tries the configured component/action first, then the common
`com.android.tv.action.SELECT_INPUT` action. It does not claim this action works
on this Toshiba. It never opens input *setup* as if that were an input picker.
If no picker opens, the launcher shows a message and the remote Input key remains
available.

Use the input services and exported-activity evidence saved by `diagnose.sh`.
Compare `logcat` while pressing the physical Input key. Find the launched OEM
component/action; confirm it is exported and opens the picker without changing
an input or entering setup automatically. Put that value in `inputs.component`
or `inputs.action`, run configure, and test the launcher's Inputs button with the
owner. Do not hardcode a guessed vendor component. A sanitised verified component
may later be documented publicly; the actual local input goes in config.

## 6. Apply a reviewed ads-only plan

Only after baseline and HOME tests pass, set these local gates to `true`:
`verification.complete` and `verification.voice.ambient.checked`. These record
the baseline; they do not replace the checks below.

```sh
./scripts/apply.sh
./scripts/apply.sh --yes
```

Review the dry-run package reasons before the second command. The script refuses
personal apps, unknown policy entries and protected families; requires the verified
fallback alongside stock HOME; checks working HOME or the explicitly inspected HOME-role/stock-priority case
before each change, then verifies resolved HOME after disabling stock; uses
`pm disable-user --user 0`; and never uninstalls anything. A previously disabled
package stays in its existing state and is not claimed as a Couch OS change.

Before every mutation the script atomically saves the original enabled state
and pending action to `captures/apply-state.json`. Keep this file until the TV is
restored. A TV identity digest prevents applying another TV's journal. Do not
copy an unrelated journal here. A lock prevents concurrent apply/restore runs.
After an interrupted process, first verify no script is running; only then remove
the empty `captures/.operation-lock` directory if it remains. Never delete the
journal to make a failed operation proceed.

Now repeat the complete remote/inputs/voice/Ambient checklist, and test a cold
boot plus standby/wake. Stop at the first regression and restore. Ambient and
Photos must look and behave exactly as before.

## 7. Investigate voice only if it regresses

Collect another private diagnosis after apply. Compare `dumpsys voiceinteraction`,
secure `assistant` / `voice_interaction_service`, and the active recording state
in `dumpsys audio`. Inspect local logcat around the physical voice key and a
hands-free attempt. Look for an absent Assistant service, permission denial,
failed bind or microphone recording client that changed when stock HOME stopped.
Recent logcat can contain queries/accounts; never paste it into a public issue.

The launcher asks for no microphone permission and starts no recording service.
Its opt-in helper handles ASSIST/VOICE_ASSIST keys **only if Android delivers them
to the focused launcher**, then delegates to the system `VOICE_COMMAND`/`ASSIST`
intent. To try this after evidence shows missing key dispatch, set local
`voice.helper.enabled=true`, configure, and retest from HOME and from another app.
It cannot recreate a privileged hotword service, intercept globally consumed keys,
or keep "Ok Google" alive by itself. Do not enable it speculatively.

If disabling the stock launcher breaks hotword listening on this firmware,
restore immediately and keep the stock launcher package enabled while using Couch
OS as default HOME. First preference is preserving the existing Assistant and
Photos behaviour. A different helper needs observed service/binding evidence
and a separate reviewed change; do not add an always-recording microphone loop,
accessibility hijack, signature-permission workaround or guessed service call.

## 8. Restore, then decide whether to reapply

```sh
./scripts/restore.sh
./scripts/restore.sh --yes
```

Restore uses the journal even if the selected package list or public policy has
changed. It restores exact default/enabled states, including pending operations,
and can be repeated. It leaves packages that were already disabled alone. It
does not uninstall Couch OS, erase its preferences, or change your chosen HOME.
To return completely to stock presentation, select the restored stock launcher
in the TV's default-HOME settings after restoring packages. Test physical Home
before uninstalling Couch OS. Debugging/pairing is separate and must be turned
off manually.

For an interrupted apply with no reachable launcher, keep ADB connected and run
restore from the laptop. If the TV is offline, reconnect using its current port
before retrying. Restore never depends on a disabled stock launcher's UI.

Prove restore at least once before settling on a final configuration. If you
choose to reapply, repeat the dry run and all regression checks.

## 9. After an OTA or app update

Temporarily enable debugging, update local connection details and run:

```sh
./scripts/connect.sh
./scripts/check-after-update.sh
```

The check is read-only. Exit 0 means the recorded disabled packages remain
disabled and HOME is Couch OS; exit 2 means returned/missing packages or changed
HOME; exit 1 means a connection/state validation failure. Re-inventory firmware
changes before reapplying. Newly introduced ad services are not silently disabled.
Retest voice, HDMI and Ambient/Photos. Keep system updates enabled.

## 10. Finish and publish only sanitised findings

Update `tv/55c350mp/packages.md` with preinstalled-package evidence and actual
Android/API version. Do not call a package "tested" until the physical test ran.
Keep personal installed apps/order, identifiers and raw logs private. Update
`docs/validation.md` with pass/fail observations without account information.

On the TV, turn off network/wireless/USB debugging and revoke authorisations.
Run `./scripts/disconnect.sh` on the laptop, clear stale pairing values from local config, and
keep the private restore journal safe. Check that Home, Assistant and the Google
Photos screensaver still work after debugging is off.

Reference APIs: [TV app setup](https://developer.android.com/training/tv/get-started/create),
[package visibility](https://developer.android.com/training/package-visibility),
[ADB wireless debugging](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi),
[Open-Meteo](https://open-meteo.com/en/docs). The
[Toshiba AU specification](https://toshiba-tv.com.au/wp-content/uploads/2023/05/55C350MP-Product-Specifications.pdf)
confirms Google TV for that regional model but does not establish the Android
release or package set on this Indian TV. Use on-device evidence.
