# KitchenUI

A home-screen launcher for a kitchen TV / recipe screen. It's built for a Dell OptiPlex 3030 running Bliss OS at 1600×900, and scales to fit any landscape screen.

**Home screen:** clock, weather (Open-Meteo), Google Calendar agenda, kitchen timers, tonight's dinner, recipe book, shopping list and an app dock.
**Cook mode:** step-by-step recipes in large text, with ingredient checkboxes and a timer for each step.
**Watch:** a touch version of [Flux TV](https://github.com/ProfoundBandit86/fluxtv)'s streaming. Movies and shows from Plex, Jellyfin and Emby, Stremio add-ons with Real-Debrid, continue watching, a watchlist, search, and a player that resumes where you stopped.
**Live TV:** IPTV channels (Xtream Codes or M3U + XMLTV guide), sorted by group, showing what's on now.
**Paprika 3:** sign in, and your Paprika recipes (photos, categories, timers detected in the directions) fill the recipe book. A dinner planned in Paprika's meal planner for today becomes "Tonight's dinner".

The streaming code in `app/src/main/java/com/fluxtv/app/` is copied from Flux TV (servers, add-ons, Real-Debrid, IPTV). The touch screens are new, in `com/kitchenui/launcher/watch/`.

## Auto-update

1. Every push to `main` runs `.github/workflows/release.yml`. It builds a signed APK and publishes it as a GitHub Release tagged `v1.0.<build number>`.
2. The launcher checks the latest release every 3 hours (or via **Settings › Updates › Check now**).
3. When it finds a newer build, it downloads it. Once the kitchen is idle (home screen, no timers running), it installs it.
   - The first update asks once to **allow installs from this app**, then shows Android's "Update" confirmation.
   - On Android 12+ (Bliss OS 15+), later updates usually install without a prompt.
4. **Recipes** come from `app/src/main/assets/recipes.json` and download straight from `main`, so editing recipes needs no new APK.

The repo must be **public** for the app to see releases and recipes without a login.

### Signing

Updates only install over an APK signed with the same key. The workflow uses `signing/kitchenui.jks` (password `kitchenui`) unless you set these repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. If you switch keys later, uninstall the app once and install the new build by hand.

## First install on Bliss OS

1. Open the repo's **Releases** page in the browser on the OptiPlex and download the latest `KitchenUI-*.apk`.
2. Open it and allow the browser to install apps.
3. Press Home and choose **Kitchen**, then **Always**. You can also do this from **Settings › Choose home app**.
4. Open **Settings** in the dock:
   - Enter your city and tap **Find** to set the weather.
   - Paste your Google Calendar "Secret address in iCal format". In Google Calendar on a computer, it's under Settings › *your calendar* › Integrate calendar.

## Setting up Watch and Live TV

Open **Watch › Sources**:

- **Plex:** tap *sign in with code*, then enter the code at plex.tv/link on your phone. Every server on the account is added.
- **Jellyfin / Emby:** server address, username and password.
- **Stremio add-ons:** paste an add-on's install link. Cinemeta is already installed for titles and posters. Stream add-ons like Torrentio need *Configure* first; paste the new link after configuring.
- **Real-Debrid:** sign in with a code at real-debrid.com/device, or paste your API token.
- **IPTV:** an Xtream Codes login (server, username, password), or an M3U playlist with an optional guide link. Turn on *Provider movies & series* to add the provider's VOD to Watch.

Videos open **split screen**: the video on the left, and the kitchen on the right with a **Home / Recipe book** switch (weather, timers, tonight's dinner, shopping list, or your recipes with ingredients and steps). Tap the video or *Full screen* to fill the screen; *Split screen* or Back returns. Go Home and the video keeps playing in the now playing card in place of the recipe book. Videos play on a built-in VLC engine, so almost any codec works (HEVC, AV1, DTS, TrueHD, MKV, MPEG-TS). Audio defaults to English when the video has an English track, and subtitles start off; the *Audio* and *Subtitles* buttons change them. In full screen, tap the video to show or hide the controls. *Other player* hands the stream to VLC or another installed app.

## Using it

- **Paprika:** sign in from **Settings › Paprika 3 recipes** with your Paprika Cloud Sync email and password.
- **Recipe book:** the dock button opens every recipe with search (names and ingredients) and categories. Tap to start cooking. Press and hold to make that recipe tonight's dinner.
- **Voice search:** tap *Search or ask…* under the clock (or the mic in split screen, or the keyboard/remote search key). Say a command — "set a timer for 10 minutes", "add milk and eggs to the shopping list", "send the list to Sam", "open YouTube", "play Bluey" — or anything else to search recipes, apps, and movies and shows. Uses the device's speech recognizer (the Google app); you can also type.
- **Theme:** Settings › Display › Theme: Dark, Light, or Auto (light from 7 AM to 7 PM).
- **Now playing:** while anything plays (KitchenUI's own videos, or music and video from other apps once media controls are allowed), a now playing card replaces the recipe book on the home screen.
- **Emailing the shopping list:** in **Settings › Household**, add each person's name and email, then the email account to send from: enter the address and password and tap *Find settings* (works with Gmail, Outlook, Yahoo, iCloud and most providers; otherwise enter the SMTP and IMAP/POP3 servers yourself). Mail goes out over SMTP; IMAP or POP3 is used to test the sign-in, and with IMAP a copy is saved in your Sent folder. Providers with 2-step verification need an app password. The shopping list gets a *Send to NAME* button for each person; it emails what's still unchecked. Emails are flagged high priority and the subject always starts "KitchenUI shopping list", so a mail rule on that subject (never spam, mark important, star or flag) keeps them on top.
- **All apps:** press and hold an app to add it to the dock, hide it, or open its app info. *Hidden apps* lists hidden ones; hold one to show it again.
- **Timers:** tap a finished timer to silence it.

## Adding recipes

Edit `app/src/main/assets/recipes.json` on GitHub:

```json
{
  "id": "unique-id",
  "name": "Recipe name",
  "category": "Dinner",
  "prep": 15, "cook": 30, "serves": "4",
  "color": "#5A3328",
  "facts": [["Oven", "400°F / 200°C"]],
  "ingredients": [["1 cup", "rice"]],
  "steps": [{"text": "Do this.", "tip": "Optional tip", "timer": 10}]
}
```

`timer` is in minutes. It adds a "Start timer" button to that step.

## Building locally

Requires JDK 17, the Android SDK and Gradle 8.14: `gradle :app:assembleRelease`.
