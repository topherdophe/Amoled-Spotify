# Neon Controller (Amoled-Spotify)

A minimal pure-black Spotify remote for Android, built for AMOLED screens. Plain Java with Android framework views only: no third-party libraries.

## Features

- **Now playing**: large clock, optional album art and date, track, artist, device and battery. Shuffle and repeat (off → all → one) sit on the left of the song, one above the other, with a seekable progress bar below. There are no play or skip buttons:
  - **Swipe right** to skip, **swipe left** to go back, **two-finger swipe down** to play or pause. Swipes also work in clock-only idle mode without waking the screen.
  - **+** next to the song opens Add to playlist, Save to Liked Songs and Play artist radio.
- **Portrait and landscape**: when you rotate the phone, track info moves to the left and the controls to the right.
- **Up next (♫)**: see the queue and tap a song to skip straight to it.
  - Swipe a song away to remove it. Spotify's API can't remove songs from the queue, so the app skips that song automatically when it comes up (only while the app is open).
- **Search (⌕)**: songs, artists, albums or playlists. **MORE** loads further pages of 10.
  - An artist page has play and shuffle, their songs and their albums. Album and playlist pages play from any song.
- **Song menu (+)**: next to songs everywhere:
  - **Add to queue**.
  - **Add to playlist**: choose one of your own (or collaborative) playlists, or make a new one.
  - **Save to Liked Songs**.
  - **Play artist radio**: plays the song, then queues a shuffled mix of other songs by the same artist. Spotify closed its recommendations API to new apps in 2024, so real song radio isn't available to this app.
- **Select several songs**: hold a song to start selecting, tap more, then **+ PLAYLIST**, **+ QUEUE** or (in your own playlists) **REMOVE**.
- **Playlists (☷)**: **Recently played** (your last 50 songs) at the top, then your playlists. **+ NEW PLAYLIST** makes a private playlist.
  - In your own playlists, swipe a song away to remove it. The app asks first.
  - Spotify only lets this app list the songs in playlists you own or collaborate on. Others can still be played or shuffled.
- **Settings (⚙)**:
  - **Sleep timer**: tap it to show the choices: 15–90 minutes, or end of the current song. Timed sleeps use an Android alarm, so Spotify pauses on time even if the phone is asleep or the app is closed.
  - **Play on**: switch playback to another Spotify Connect device. If you press play with no active device, the app reuses the last one automatically.
  - **Highlight colour**: **One tone** opens a colour picker; **Gradient** opens two (start and end colour). Each picker has a colour slider and a white slider, with a preview.
  - **Show album art**.
  - **Date and time ›**: its own page with clock style (thin, regular, bold, mono), size, 12/24-hour, seconds and date.
  - **Keep screen awake** (hidden while **Lock phone when idle** is on, which decides that itself).
  - **Show over lock screen**: if the app is open when the screen turns off (power button or timeout), the app is what you see when you wake the phone, instead of the lock screen. The phone stays locked: leaving the app asks for your PIN as usual. With **Keep screen awake** on, the screen never turns off, so this only matters when that's off.
  - **Lock phone when idle, keep app on screen**: the phone locks itself when its own screen timeout runs out (Settings → Display), and the app switches the screen straight back on, showing itself over the lock screen. The screen then stays on. Using the app doesn't need unlocking; anything else asks for your PIN. No special permission is needed. Pressing the power button still turns the screen off as normal. For best results, set the phone to lock immediately after the screen timeout; the app waits up to 30 seconds for the lock.
  - **When idle**: nothing, dim, clock & song only, or both. In clock & song mode the clock and song drift slowly up and down the screen, which protects against burn-in. Choose an idle time of 30 s, 1, 2 or 5 min, or **Screen lock time** (your phone's own screen timeout).
  - **Normal and dimmed brightness** sliders. These change this app's window only, not your phone's brightness setting.
  - **Background activity**: shows whether Android limits the app in the background, and opens the app's settings so you can allow it (Battery → Unrestricted).
  - **Account**: who's connected, change the Client ID, disconnect.
- **Gradients** cover all coloured text, including the clock, song title, artist and device line.
- **Messages**: only problems show at the bottom of the screen, and they disappear after a few seconds. Routine notes like "Next song" aren't shown.
- **In-app setup**: paste your Spotify Client ID on first launch.
- **Accessibility**: every button has a screen-reader label (TalkBack), and text follows the phone's font size. Symbol buttons and the clock keep a fixed size so they still fit with large fonts.

## Setup

1. Create an app at https://developer.spotify.com/dashboard. Tick **Web API**, and add the redirect URI `neonspotify://callback` exactly.
2. Under **User Management**, add the name and email of each Spotify account that will use the app (up to 5). The email must match the one shown at spotify.com/account. The app owner needs Spotify Premium.
3. Push to `main` (or run the workflow manually). Download the APK from the run's **Artifacts**.
4. Install it, paste your Client ID on the setup screen, and tap **SAVE AND CONNECT**.

Optional: add a repository variable `SPOTIFY_CLIENT_ID` to bake in a default Client ID. A Client ID typed in the app always wins.

## New permissions

Playlist editing, Liked Songs and Recently played need Spotify permissions that older logins don't have. If a message says Spotify needs new permissions, go to Settings → Account → Disconnect, then connect again.

## Package name

The app's ID is `com.topherdophe.neoncontroller`. Earlier builds used `com.topherdophe.neon` (and before that `com.example.neonspotify`), which Android treats as a different app: uninstall the old one, then enter your Client ID and log in once in the new one.

## Signing (installing updates over old versions)

Android only installs an update over an app signed with the same key. Add these three **repository secrets** (Settings → Secrets and variables → Actions → Secrets):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the base64 of the `.p12` keystore |
| `SIGNING_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | the key alias |

With these secrets set, every build uses the same key and installs over the previous one, keeping your login and settings. Without them, the build falls back to a throwaway key, and you'll need to uninstall before each update. Never commit the keystore, and keep a backup: if you lose it, you'll have to uninstall once more and switch to a new key.

## Code checks

Every GitHub build also runs Android lint. Lint errors fail the build and show up as annotations on the run, pointing at the file and line. Warnings are counted in a notice but don't fail the build. The full report is in `app/build/reports/` when building locally.

## Troubleshooting

| Message | Meaning |
|---|---|
| 403 *User may not be registered* | The account that logged in isn't allowlisted for the Client ID shown in Settings. Check User Management, then Settings → Disconnect and connect again. |
| No active playback device | Open Spotify on any device, or pick one in Settings → Play on. |
| Rate limit reached | Spotify throttles Development Mode apps. The app slows its polling automatically. |

## Notes

- Playback control requires Spotify Premium and a Spotify Connect device. Spotify doesn't allow remote volume control on phones, so the app has no volume control.
- Queue auto-skip and "sleep after this song" run inside the app, so they need it open. They're most reliable with **Keep screen awake** on.
- Login uses Authorization Code with PKCE (no client secret). Tokens are encrypted with a key held in the Android Keystore, then saved in app-private storage.
