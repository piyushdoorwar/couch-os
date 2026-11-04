# Home screen

The baseline mirrors the Google TV home in Apps only mode, rebuilt in our own code: a
profile circle, a Home pill and a screensaver/quick-settings pill on the left; on the
right, where Google TV prints its name, the time, the network (Wi-Fi name when the owner
opts in), followed by the optional bulb shortcut. The promotional carousel is replaced by a greeting,
the date and the weather when there is no title to show. With automatic watch
history enabled, Home also shows how to pin a title manually.

Continue Watching shows recent titles in the carousel. Up from the first row
moves to the card. Left and Right browse the titles, OK opens the title or its
app, and holding OK opens pin, hide and mark-watched actions. The carousel never
advances on its own. Hidden titles stay out until restored. A watched title can
return when the app reports a newer playback.

CouchOS combines visible Android TV Watch Next entries with playback metadata
from apps that expose it. When a session reports playback without a title,
its app and playback time can move the app’s newest published card up.
That signal does not identify an episode or restore a hidden or watched title.
Home also refreshes when the TV provider updates its rows.
The optional Android TV listings permission makes
searchable Watch Next rows visible. Google TV Home can also read rows protected
by a privileged permission. To enable automatic playback history, grant notification-listener
access through CouchOS Settings if the TV exposes that page, or set
`history.media_sessions=true` and run the configuration script when ready.
CouchOS reads media sessions only. The five most recent titles, available
progress and artwork are stored privately on the TV. Playback history is
excluded from backups. Revoking access hides this history, and Settings can
clear it.

Show artwork can come from a session bitmap or a poster or thumbnail URI.
On-device images are tried first. The Download show artwork setting under
Personalise allows CouchOS to fetch HTTPS images from the streaming app's
artwork URLs. It is off by default. Requests go to the image host and can
identify which artwork was requested. Images are cached in memory. Watch
history stays on the TV, and an unavailable image leaves a title card. Pressing OK uses an app-confined Watch Next link
when it applies to the current episode. Otherwise CouchOS looks for a matching
live session, then opens the provider app as a fallback. The title, artwork,
progress and playback actions available vary by streaming app and firmware.
In the Toshiba test, Netflix and Prime Video did not provide usable titles.

Hold OK on an app to pin it to favourites, move it to the front, hide it, or
enter a show title manually. All apps includes hidden apps so they can be
restored. Recently opened apps appear first there. Apps launched outside
CouchOS can be included with the optional `recent.usage_access=true` setting.
The first on-TV favourite edit takes precedence over the local config order.

Personalise in Settings contains the Inputs and Google Photos Ambient shortcuts
and the optional quiet evening layout. During the chosen evening hours (until
6 am), Home uses Midnight Mint and initially focuses Continue Watching when
a title is available.

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

The Wi-Fi fan fills from the bottom using the current received signal
strength. An unavailable reading leaves the fan dim.

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
