# Neon Controller (Amoled-Spotify)

A minimal pure-black Spotify remote for Android, built for AMOLED screens. Plain Java with Android framework views only: no third-party libraries.

## Features

- **Now playing**: large clock, optional album art, track, artist, device and battery. Controls are play/pause, previous/next, shuffle, repeat (off → all → one) and a seekable progress bar.
- **Portrait and landscape**: when you rotate the phone, track info moves to the left and the controls to the right.
- **Up next (♫)**: see the queue and tap a song to skip straight to it.
  - Swipe a song away to remove it. Spotify's API can't remove songs from the queue, so the app skips that song automatically when it comes up (only while the app is open).
- **Search (⌕)**: tap a result to play it, or hold it to add it to the queue. **MORE** loads further pages of 10.
- **Playlists (☷)**, with paging.
- **Devices (◎)**: switch playback to another Spotify Connect device. If you press play with no active device, the app reuses the last one automatically.
- **Settings (⚙)**:
  - **Sleep timer**: 15–90 minutes, or end of the current song.
  - **Highlight colour**: 7 solid colours or 4 gradients.
  - **Show album art**.
  - **Keep screen awake**.
  - **When idle**: nothing, dim, clock & song only, or both. Choose an idle time of 30 s, 1, 2 or 5 min.
  - **Normal and dimmed brightness** sliders. These change this app's window only, not your phone's brightness setting.
  - **Pixel shift**: burn-in protection.
  - **Spotify account**: check the account, change the Client ID, disconnect.
- **In-app setup**: paste your Spotify Client ID on first launch.

## Setup

1. Create an app at https://developer.spotify.com/dashboard. Tick **Web API**, and add the redirect URI `neonspotify://callback` exactly.
2. Under **User Management**, add the name and email of each Spotify account that will use the app (up to 5). The email must match the one shown at spotify.com/account. The app owner needs Spotify Premium.
3. Push to `main` (or run the workflow manually). Download the APK from the run's **Artifacts**.
4. Install it, paste your Client ID on the setup screen, and tap **SAVE AND CONNECT**.

Optional: add a repository variable `SPOTIFY_CLIENT_ID` to bake in a default Client ID. A Client ID typed in the app always wins.

## Signing (installing updates over old versions)

Android only installs an update over an app signed with the same key. Add these three **repository secrets** (Settings → Secrets and variables → Actions → Secrets):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the base64 of the `.p12` keystore |
| `SIGNING_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | the key alias |

With these secrets set, every build uses the same key and installs over the previous one, keeping your login and settings. Without them, the build falls back to a throwaway key, and you'll need to uninstall before each update. Never commit the keystore, and keep a backup: if you lose it, you'll have to uninstall once more and switch to a new key.

## Troubleshooting

| Message | Meaning |
|---|---|
| 403 *User may not be registered* | The account that logged in isn't allowlisted for the Client ID shown in Settings. Check User Management, then Settings → Disconnect and connect again. |
| No active playback device | Open Spotify on any device, or tap ◎ and pick one. |
| Rate limit reached | Spotify throttles Development Mode apps. The app slows its polling automatically. |

## Notes

- Playback control requires Spotify Premium and a Spotify Connect device. Spotify doesn't allow remote volume control on phones, so the app has no volume control.
- The sleep timer and queue auto-skip run inside the app. They're most reliable with **Keep screen awake** on.
- Login uses Authorization Code with PKCE (no client secret). Tokens are stored in app-private storage.
