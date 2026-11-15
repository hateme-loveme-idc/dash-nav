# Dash Nav

**Real Google Maps on the Royal Enfield Himalayan 450 Tripper dash.** Android 15+, dash firmware 11.61+.

Google Maps runs on a hidden screen inside your phone, and Dash Nav streams it to the dash over the dash's own
Wi-Fi, with your music and calls on the dash's gold band. No Google API key, no account, no servers.

> **This repository is the Dash Nav app.** The APK on the [Releases](../../releases) page is built from the tagged
> commit in this repo: same name, same package (`nav.dash`), same code. See
> [Check that your APK came from here](#check-that-your-apk-came-from-here).

- [Install (about 10 minutes, once)](#install)
- [Ride](#ride)
- [Privacy](#privacy)
- [Known limits](#known-limits-beta)
- [Report a problem](#report-a-problem)
- [Build it yourself](#build-it-yourself)

## Install

You need an Android 15+ phone and a Himalayan 450 with the Tripper dash on firmware 11.61 or newer.

### 1. Shizuku

[Shizuku](https://shizuku.rikka.app/) gives Dash Nav the system access it needs to run Google Maps on a hidden
screen. It does not need root.

1. Install **Shizuku** from the [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).
2. Turn on Developer options: Settings → About phone → tap **Build number** 7 times.
3. Connect to your home Wi-Fi, then turn on Settings → Developer options → **Wireless debugging**.
4. Open Shizuku and tap **Pairing**. In Wireless debugging, tap **Pair device with pairing code** and type the
   code into Shizuku's notification.
5. In Shizuku, tap **Start**. It should say *Shizuku is running*.

> After every phone restart, open Shizuku and tap **Start** again. Do it at home, before you ride.
> Rooted phones: use *Start (for rooted devices)* instead.

### 2. Dash Nav

1. Download `dash-nav-<version>.apk` from [Releases](../../releases).
2. Open it. If asked, allow your browser to **install unknown apps**.
3. Google Play Protect may offer to scan it: let it, then install. (Dash Nav doesn't use notification access,
   so Play Protect doesn't block it.)

### 3. First start

Open Dash Nav and allow:

| Permission | Why |
|---|---|
| Location, Nearby devices | Android requires them to find and join the dash's Wi-Fi |
| Notifications | the "running on dash" notification with Stop |
| Contacts (optional) | to show the caller's name on the dash |
| Unrestricted battery | so Android doesn't stop it mid-ride |

Tap **Start on dash** and allow it when Shizuku asks.

## Ride

1. Turn the bike on.
2. In Google Maps choose two-wheeler mode, set your route and tap **Start**.
3. In Dash Nav tap **Start on dash**. The first time, pick the dash's `RE_…` Wi-Fi when Android asks (Cancel
   stops Dash Nav). After that it reconnects by itself.
4. The map moves to the dash in about 3 seconds.
5. **Stop** (in the app or its notification) brings Google Maps back to the phone.

You can also start Dash Nav first: until you start a route, the dash shows the time and your music.

**On the dash**

- **Map:** Google Maps' own map, centred on you, with the next turn and the ETA.
- **Gold band:** the song and the time; during a call, the caller's name, then the call duration. The map stays
  clear during calls.
- **Idle:** your wallpaper (Settings → Wallpaper, with a dash-shaped crop preview), or the time.

**Joystick**

| Press | Does |
|---|---|
| Down, Down | menu: play/pause · next · previous · phone screen on/off · close |
| In the menu | Down = next item · Right = select · Left = close (closes itself after 10 s) |
| Incoming call | Up = answer · Down = reject. In a call: Down = hang up |
| The dash's music player | Right / Left = next / previous track |

**Phone screen:** Dash Nav never turns your screen off by itself. Press power to turn the phone's screen off:
the phone stays awake underneath, so the dash keeps its map. Press power again to get it back.

## Privacy

- **None of your data leaves your phone.** The app's only network traffic is the video and control stream to your
  dash, over the dash's own Wi-Fi. There are no servers, no account, no analytics and no ads, and the only library
  is Shizuku's API. You can check: the code makes no HTTP calls.
- Contacts are only read to put a caller's name on the dash.
- Ride logs (`Download/Dash Nav/ride-*.txt`) contain app events only (link state, frame rate, joystick, call
  ringing/ended): no locations, contact names or phone numbers. They do include your dash's Wi-Fi name (`RE_…`).
- Ride videos (Settings → Save dash video) are **off** by default and stay on your phone. They show your real
  roads, so don't post them publicly.

## Known limits (beta)

- Your phone stays awake while the map is on the dash. Keep it charging on long rides.
- Shizuku needs to be restarted after every phone reboot.
- Set your route before you ride. While moving, use only the joystick.
- The frame rate adapts between 4 and 12 per second, depending on what your dash accepts.
- Not an official Royal Enfield or Google app. Built on community reverse-engineering of the dash.

## Report a problem

[Open an issue](../../issues) with:

- phone model, Android version, dash firmware
- what you did, what you expected, what happened
- the ride log from `Download/Dash Nav/` (safe to share: see [Privacy](#privacy))

## Check that your APK came from here

Every release lists the commit it was built from, the APK's SHA-256 and the signing certificate:

```
package:      nav.dash
certificate:  CN=Dash Nav
cert SHA-256: 9e:30:70:2e:e5:4f:ee:fb:f1:2c:30:91:46:32:5e:14:ce:b4:04:14:44:14:42:97:87:60:bc:95:48:fc:8c:cb:9
```

- **On the phone:** the app's package is `nav.dash` (Settings → Apps → Dash Nav). Android only installs
  an update over it if the update carries the same certificate.
- **On a computer:** `sha256sum dash-nav-*.apk` should match the release's `SHA256SUMS`, and
  `apksigner verify --print-certs dash-nav-*.apk` should print the certificate above.
- **Build it yourself** (below) from the release's tag. Your build is signed with your own debug key, so it
  installs as a separate app; everything else is this repository's code.

## Build it yourself

```bash
cd app
./gradlew assembleDebug          # app/build/outputs/apk/debug/ (installs as nav.dash.debug)
./gradlew assembleRelease        # signed with ../keys/release.properties if present, else the debug key
hosttest/run.sh                  # JVM tests: Maps notification parsing, turn text, RTP packetiser
```

The [CI workflow](.github/workflows/build.yml) runs the same on every push and keeps the APK as an artifact.

## How it works

- **Hidden screen:** a Shizuku helper creates a trusted virtual display, moves Google Maps onto it and keeps it
  rendering. The app crops around your position and encodes it as H.264.
- **Dash link:** the dash's own Wi-Fi protocol (authentication, control messages, RTP video). The frame rate adapts
  to what the dash acknowledges.
- **Turn, ETA, music and calls:** the helper reads Google Maps' navigation notification (`dumpsys notification`),
  media sessions and the phone's call state, which is why the app needs no notification access.

## Developer notes

- `app/`: the Android app (Java, Gradle). Debug builds install as `nav.dash.debug` next to the release
  and accept adb test extras (`test_mode`, `record`, `fake_call`, `wifi_prefix`, `wallpaper_file`).
- `vdash/`: a virtual Tripper dash (`VDash.java`, plain Java: runs on the phone via `app_process` or on a JVM) and
  `cluster.py`, which renders the round dash from the live stream. `vdash/ride/`: mock-GPS rides (`ride.sh`),
  robustness tests (`robust.sh`), soak test (`soak.sh`) and the step-by-step analyzer (`analyze.py`).
- The release signing key lives outside git (`keys/`). Updates must use the same key.
