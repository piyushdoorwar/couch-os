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
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 -m unittest discover -s tests -v
```

The generic debug APK is `app/build/outputs/apk/debug/app-debug.apk`. CI builds
and uploads the same APK without personal configuration. Keep the debug signing
key local and stable for TV updates; a CI debug key may differ between builds.

See [the laptop runbook](docs/local-runbook.md) before doing anything to the TV.
Turn debugging off and revoke debugging authorisations when finished.

## Licence

Project code is MIT. Poppins is separately licensed under SIL OFL 1.1; see
[font notices](app/src/main/res/font/OFL.txt). Weather: [Open-Meteo](https://open-meteo.com/),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
