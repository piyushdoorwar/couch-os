# Home screen

The baseline mirrors the Google TV home in Apps only mode, rebuilt in our own code: a
profile circle, a Home pill and a screensaver/quick-settings pill on the left; on the
right, where Google TV prints its name, the time, the network (Wi-Fi name when the owner
opts in) and live download speed. The promotional carousel is replaced by a greeting,
the date and the weather. "Your apps" is a five-column grid of 16:9 banners: favourites
first in the configured order, then every other TV app alphabetically. The default
Classic theme uses Google TV's dark greys, white focus ring and light-blue pill.

"Continue watching" comes first when the owner opts in (`history.media_sessions=true`):
every Android TV video app publishes a media session while playing (it is how "Ok Google,
pause" works). `MediaListener`, granted notification-listener access over adb, reads only
those sessions, never notifications, and records the last five distinct shows while they
are actually playing: app, title, subtitle and the session's artwork. The history lives in
app-private files, is excluded from backups, is never logged by title, and is hidden as
soon as access is revoked; Settings has "Clear watch history". OK resumes through the
app's live session when it still exists, otherwise opens the app. It only knows shows
played after it was switched on; on the 55C350MP the shared Watch Next list was empty.

"Jump back in" takes the carousel slot Google TV uses for promotions. Google TV's own
Continue watching lives inside Google services (many apps report it through Google's
Engage SDK, off the device). The carousel first reads Android TV's shared Watch Next list
(title, episode, poster, progress and deep link, exactly what apps exposed to the TV) and
shows those as "Continue watching". Deep links are confined to the publishing app with URI
grants stripped. Android normally limits a non-system home to its own Watch Next rows, so
the launcher logs only the visible row count ("Watch Next rows visible: N") for checking on
the TV. After shows come recently used apps: launches made from Couch OS,
plus Android's usage history when the owner opts in with `recent.usage_access=true`.
Up to five, newest first, from the last two weeks. It never advances by itself; Left/
Right flips while it has focus and OK opens the app. The history never leaves the TV.

Empty favourites are fine: every launchable TV app still appears in the grid, and
uninstalled favourites are skipped.

Every button uses the same D-pad focus treatment: a 3 dp ring, 1.08 scale (1.02 for
wide settings rows) and a short damped spring. A solid surface inset separates the ring from arbitrary
app artwork so its checked contrast is preserved. The glow is drawn only while
focused. No home element loops an animation. Banners and icons come from PackageManager and stay in memory.

Theme JSON lives in `app/src/main/assets/themes/`. The app validates colour
contrast on load; JVM tests validate all shipped themes. Text and secondary text
must pass 4.5:1 against both the background and the surface. Focus passes 3:1
against both. Accent is a fill token, never foreground text. Forest's orange
highlight reserves dark ink; it is not used as text on the green background.

Weather uses a 30-minute private cache and polls connection availability every
five seconds while visible. A missing location, failed refresh or unvalidated
network hides it. The HTTP request has eight-second timeouts. No background
worker wakes a sleeping TV. Open-Meteo receives the configured coordinates and
the TV's network address; there is no analytics endpoint. Cloud backup and device
transfer are excluded for app-private files and preferences. The app needs network
permissions but no location or microphone permission.

Live download speed is off by default. When switched on in settings, the date line
shows how fast the TV is downloading right now, from the kernel's device-wide byte
counters (`TrafficStats`), sampled every two seconds while HOME is visible. It sends no
traffic and needs no permission. Idle reads 0.0 Mbps.

The look follows tvOS: icon-only buttons (Inputs, Wi-Fi, Google account, Settings),
artwork-only tiles whose name appears beneath them while focused, and a settings list
with values on the right. App artwork is still read from each installed app at runtime.
"Monochrome" (default) desaturates it; light artwork is inverted so every tile is a
light logo on a dark plate. Nothing third-party is bundled. Button glyphs are Material
Symbols Rounded (Apache License 2.0), stored as vector drawables in `res/drawable`.

Google Sans Flex comes from the [Google Fonts repository](https://github.com/google/fonts/tree/main/ofl/googlesansflex)
under the SIL Open Font License (no Reserved Font Names); its licence is in
`app/src/main/assets/fonts/OFL.txt`. To keep the TV fast it is slimmed from 3.9 MB to
about 118 KB: other axes pinned to their defaults (opsz 18, wdth 100, ROND 0, slnt 0), the
weight axis limited to 400–700, and glyphs subset to Latin, Latin-1/Extended-A, general
punctuation, ₹ and arrows. Labels in other scripts fall back to the system font.
Reproduce with fonttools 4.55.3:

```sh
fonttools varLib.instancer "GoogleSansFlex[ROND,opsz,slnt,wdth,wght].ttf" opsz=18 wdth=100 ROND=0 slnt=0 wght=400:700 -o instanced.ttf
pyftsubset instanced.ttf --unicodes="U+0020-007E,U+00A0-017F,U+2000-206F,U+20B9,U+2190-2193,U+2122" \
  --layout-features='kern,liga,calt,ccmp,locl,mark,mkmk,tnum' --output-file=google_sans_flex.ttf
```

"Google Sans" is a Google trademark; Couch OS uses the font but not the name for anything
of its own, and implies no affiliation with Google.

Minimum shipped contrast ratios (across the background and surface):

| Theme | Text / secondary | Focus ring |
| --- | --- | --- |
| Classic | 7.93:1 | 14.35:1 |
| Broadcast | 14.45:1 | 10.42:1 |
| Forest Crest | 7.28:1 | 6.44:1 |
| Midnight Mint | 10.44:1 | 13.27:1 |

The settings screen marks the active theme with a small "In use" badge inside the
surface tile, so the focus ring never sits against the badge colour. The badge uses
the theme's highlight with its ink (Forest Crest: 5.58:1), otherwise the accent fill
with normal text (Midnight Mint 6.24:1, Broadcast 6.65:1). The validator rejects a
theme whose badge text falls below 4.5:1.
