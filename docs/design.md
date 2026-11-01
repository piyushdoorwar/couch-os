# Home screen

The launcher has a 48 dp horizontal margin, a clock in Poppins Black, a date line and a
single horizontal row. Favourites keep the exact package order in local config;
uninstalled or non-TV apps are skipped. All apps contains the remaining launchable
TV packages. Empty favourites still leaves All apps focused and usable.

Every button uses the same D-pad focus treatment: a 4 dp ring, 1.045 scale and a
short damped spring. A solid surface inset separates the ring from arbitrary
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

Poppins 600, 800 and 900 come from the [Google Fonts repository](https://github.com/google/fonts/tree/main/ofl/poppins).
The font files keep their original names in their internal metadata and carry the
SIL Open Font License in `app/src/main/assets/fonts/OFL.txt`.

Minimum shipped contrast ratios (across the background and surface):

| Theme | Text / secondary | Focus ring |
| --- | --- | --- |
| Broadcast | 14.45:1 | 10.42:1 |
| Forest Crest | 7.28:1 | 6.44:1 |
| Midnight Mint | 10.44:1 | 13.27:1 |

Forest orange with its reserved dark ink passes 5.58:1.
