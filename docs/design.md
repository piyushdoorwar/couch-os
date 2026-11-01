# Home screen

The launcher has a 48 dp horizontal margin, a clock in Poppins Black, a date line and a
single horizontal row. Favourites keep the exact package order in local config;
uninstalled or non-TV apps are skipped. All apps contains the remaining launchable
TV packages. Empty favourites still leaves All apps focused and usable.

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

Poppins 600, 800 and 900 come from the [Google Fonts repository](https://github.com/google/fonts/tree/main/ofl/poppins).
The font files keep their original names in their internal metadata and carry the
SIL Open Font License in `app/src/main/assets/fonts/OFL.txt`.

Minimum shipped contrast ratios (across the background and surface):

| Theme | Text / secondary | Focus ring |
| --- | --- | --- |
| Broadcast | 14.45:1 | 10.42:1 |
| Forest Crest | 7.28:1 | 6.44:1 |
| Midnight Mint | 10.44:1 | 13.27:1 |

The settings screen marks the active theme with a small "In use" badge inside the
surface tile, so the focus ring never sits against the badge colour. The badge uses
the theme's highlight with its ink (Forest Crest: 5.58:1), otherwise the accent fill
with normal text (Midnight Mint 6.24:1, Broadcast 6.65:1). The validator rejects a
theme whose badge text falls below 4.5:1.
