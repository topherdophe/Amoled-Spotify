# Neon Controller (Amoled-Spotify)

A minimal pure-black (`#000000`) and neon-green (`#00FF00`) Spotify remote for Android, built for AMOLED screens. Plain Java with Android framework views only: no third-party libraries.

## Features

- **Now playing**: large clock, track, artist, device, battery.
- **Controls**: play/pause, previous/next, shuffle, repeat (off → all → one), a seekable progress bar, and a volume slider with −/+ buttons.
- **Device picker (◎)**: lists your Spotify Connect devices and switches playback to the one you tap. If you press play with no active device, the app reuses your last device (or the only one available) automatically. "Open Spotify on this phone" starts the Spotify app so the phone shows up as a device.
- **Search (⌕)** and **Playlists (☷)** with **MORE** paging. Spotify limits search to 10 results per page for Development Mode apps.
- **OLED care**:
  - The whole UI drifts a few dp every 30 s.
  - **AUTO-DIM** dims the app after 90 s without a touch. The first touch only wakes it, so it can't press a button by accident.
  - **NIGHT** keeps the app permanently dim.
  - **SCREEN ON** keeps the display awake.
  - Nothing polls or runs while the screen is off or the app is in the background.
- **Diagnostics (ⓘ)**: the Client ID baked into the build, the last request and its result, an account test, and disconnect/reconnect.

## Setup

1. Create an app at https://developer.spotify.com/dashboard. Tick **Web API**, and add the redirect URI `neonspotify://callback` exactly.
2. Under **User Management**, add the name and email of each Spotify account that will use the app (up to 5). The email must match the one shown at spotify.com/account. The app owner needs Spotify Premium.
3. In this GitHub repo, go to **Settings → Secrets and variables → Actions → Variables** and add `SPOTIFY_CLIENT_ID` with your app's Client ID.
4. Push to `main` (or run the workflow manually). Download the APK from the run's **Artifacts**.
5. Install it on your phone, tap **CONNECT TO SPOTIFY**, and check the account shown on Spotify's consent screen.

To build locally instead, run `gradle assembleDebug -PSPOTIFY_CLIENT_ID=your_id` (JDK 17, Android SDK 35).

> **Updating:** each GitHub build is signed with a fresh debug key, so Android refuses to install a new build over an old one. Uninstall the old app first.

## Troubleshooting

| Message | Meaning |
|---|---|
| 403 *User may not be registered* | The account that logged in isn't allowlisted for the Client ID shown under ⓘ. Compare that Client ID with the dashboard, check User Management, then use ⓘ → DISCONNECT AND RECONNECT. |
| No active playback device | Open Spotify on any device, or tap ◎ and pick one. |
| Rate limit reached | Spotify throttles Development Mode apps. The app slows its polling automatically. |

## Notes

- Playback control requires Spotify Premium and a Spotify Connect device. The audio itself plays in the Spotify app or on a speaker.
- Login uses Authorization Code with PKCE (no client secret). Tokens are stored in app-private storage.
