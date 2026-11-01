# Validation record

Local checks on 1 November 2026. The Toshiba TV was not reachable from this
session. Do not interpret emulator results as a Toshiba compatibility claim.

| Check | Result |
| --- | --- |
| Generic debug APK | Builds with JDK 17, Gradle wrapper 8.11.1, AGP 8.9.2 and SDK 35 |
| Android unit tests | 4 pass: three-theme contrast, rejection of a failing theme, weather cache expiry/clock rollback, config defaults/range checks |
| Android lint | Pass, no errors; advisory warnings include newer dependency versions, TV landscape orientation, vector banner size and Kotlin style suggestions |
| Python scripts and public guard | 28 tests pass, including pending journal recovery, idempotence, exact-state restore, pre-disabled preservation, critical package denial, wrong-TV rejection, HOME/fallback gates and update reporting |
| Shell entrypoints and Git hooks | `bash -n` passes |
| APK contents | Four theme files and the Google Sans Flex OFL present; no local config file bundled |
| Git publication guard | Staged content and reachable commit history checked before final commit |
| TV emulator UI | 2 instrumented tests pass on Android TV API 30 x86: D-pad grid/Back, Settings/Wi-Fi/Inputs focus, theme persistence and HOME intent routing |
| TV emulator ADB | Real dry run, disable-user, returned-package detection and exact-state restore pass; original emulator stock state restored |
| Visual review | Account-free emulator HOME inspected at 1920×1080; clock/date, top buttons and focus ring fit within safe margins |
| Release build | R8 + resource shrinking + baseline profile; 1.78 MB APK; non-debuggable build launched, rendered, opened settings and showed the network name on the TV emulator with no crash |
| Private settings channel | `content write` over stdin accepted a valid config and rejected malformed input, keeping the previous file (emulator) |
| Remote navigation with carousel | UI tests pass with and without the "Jump back in" card |
| Release build on the 55C350MP | Installed as a normal app (HOME unchanged). After `cmd package compile -m speed-profile`: cold start 0.82 s (1.7 s before compile), 1.9% janky frames over a 14-key D-pad pass, 99th percentile 23 ms (150 ms before). install.sh now compiles right after installing |
| HOME on the 55C350MP | Role alone was not enough (stock priority 2). After the reviewed apply: Home key opens Couch OS; voice button, "Ok Google" and Google Photos Ambient confirmed by the owner; inputs and screensaver wake verified over adb |
| Toshiba model / OS version | Android TV 11, API 30 (read-only inventory, 1 Nov 2026) |
| Toshiba Inputs picker | `com.android.tv.action.VIEW_INPUTS` opens `com.vt.tv.input` (observed via the Input key) |
| Physical voice key / hands-free Assistant | Works after apply (owner). Reboot and standby/wake still to retest |
| Ambient / Google Photos | Works after apply (owner) |
| Physical apply / restore / OTA | Pending local runbook execution |

Reproduce the build and host-side checks with:

```sh
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
python3 -m unittest discover -s tests -v
for script in scripts/*.sh .githooks/*; do bash -n "$script"; done
python3 scripts/public_guard.py --history
```

For the UI tests, use a clean Android TV API 30 emulator without personal config
or accounts. Install the app and test APKs, then run the AndroidJUnitRunner for
`dev.couch.launcher.test`. The tests send physical-style D-pad events, test grid
entry/Back, top button navigation, theme selection/persistence and HOME intent
routing. Do not run them against a personalised TV without reviewing their
assumptions. They temporarily change the selected theme and restore it afterwards.

Keep screenshots and raw reports in ignored `captures/` or `app/build/`.
CI builds/tests/lints and uploads the generic debug APK; it has no TV access and
does not execute the on-device regression checklist.

The emulator exposed a stock HOME priority quirk: `set-home-activity` selected
Couch OS as the OS HOME role holder while resolution still preferred the
privileged stock app. An explicit local inspection gate now supports that case
only for the exact verified stock package and sole Couch OS HOME role holder;
post-apply resolution must point to Couch OS. This is not evidence the Toshiba
has the same quirk. The [runbook](local-runbook.md) describes the checks.

An opt-in real-ADB smoke test is in `tests/emulator_smoke.py`. Set `ANDROID_SERIAL`
to a clean TV emulator and run it after installing the debug APK. It refuses
physical devices by serial format and `ro.kernel.qemu`, exercises package-manager
commands, and restores both package state and the prior HOME selection in a
`finally` block. It never reads the owner's private TV config.
