# Couch OS

A quiet Google TV home screen: a large clock and one row of favourite apps.
Kotlin, Compose for TV, Android TV 11+ (API 30). App artwork is read from the
installed packages at runtime. No streaming logos or franchise artwork ships here.

## Device and risk

Target / tested-only model: **Toshiba 55C350MP (India)**. **No physical TV has been
tested yet.** The exact Android release and OEM package names must be checked on
the device; the retail model name is not sufficient. Run at your own risk.
Install the launcher and set it as HOME before disabling anything. `restore.sh`
undoes everything that `apply.sh` changes: package enabled states are restored
from a local journal. It does not uninstall Couch OS or erase its settings.

## Public repository rules

Never commit TV IP addresses, ADB codes or ports, Wi-Fi names, device identifiers,
weather coordinates, API keys, keystores or passwords. Your installed-app inventory,
favourites and raw screenshots/logs are private too. Never include third-party
app artwork. An audited list of preinstalled packages and reasons is publishable.

All local inputs belong in `config/local.properties`. Start with:

```sh
./scripts/setup-dev.sh
```

This installs pre-commit and pre-push guards and creates the ignored config with
owner-only permissions. Hooks reject private paths and suspicious content; CI
runs the same guard. Git hooks can be bypassed, so review staged changes before
any push. If sensitive material is found, stop and make the repository private
before proceeding; do not publish it and try to delete it later.

## Build

Install JDK 17 and Android SDK platform 35 / build-tools 35.0.0. Set
`ANDROID_HOME` in your shell (SDK tooling only). Then:

```sh
./gradlew assembleRelease testDebugUnitTest lintDebug
python3 -m unittest discover -s tests -v
```

Use the optimized release APK on the TV: `app/build/outputs/apk/release/app-release.apk`
(R8, resource shrinking, baseline profile; about 1.8 MB against 26 MB for debug). It is
signed with this machine's debug key so it upgrades an earlier install in place. CI
builds both without personal configuration; a CI key differs from yours, so install
your own local build on the TV.

See [the laptop runbook](docs/local-runbook.md) before doing anything to the TV.
Turn debugging off and revoke debugging authorisations when finished.

## Licence

Project code is MIT. Google Sans Flex is separately licensed under SIL OFL 1.1; see
[font notices](app/src/main/assets/fonts/OFL.txt). Button icons are [Material Symbols](https://github.com/google/material-design-icons)
(Apache License 2.0). Weather: [Open-Meteo](https://open-meteo.com/),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

## Local delivery status

The launcher, three themes, weather, setup/restore tools and CI are implemented.
[Validation](docs/validation.md) records the checks and the physical-TV work
still pending. Start the laptop session with [the runbook](docs/local-runbook.md).
[Package notes](tv/55c350mp/packages.md) are deliberately unverified until that
session supplies device evidence. There is no automatic debloat default.

```sh
./scripts/connect.sh
./scripts/inventory.sh
# Review the runbook and fill the verified local config before continuing.
./scripts/install.sh --yes
./scripts/apply.sh                 # dry run
./scripts/apply.sh --yes
# Optional, reversible: halve system animation durations for a snappier feel.
./scripts/animations.sh            # dry run
./scripts/animations.sh --yes
# To recover:
./scripts/restore.sh --yes
./scripts/animations.sh --restore --yes
```

A stable local debug signing key supports repeated installs. The scripts never
uninstall an app to work around a signing mismatch. Preserve the ignored restore
journal in `captures/apply-state.json`; restore uses it even if policy changes.
After an update, run `./scripts/check-after-update.sh` to report changes without
silently applying them.
