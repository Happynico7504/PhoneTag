# PhoneTag

Laser tag for Android phones. Every phone tracks every other player with **GPS + compass**:
you aim by pointing your phone's camera at another player and hitting **FIRE**. The
authoritative game server checks whether the other player was inside your line of fire.

- **Wi-Fi Direct first.** One phone hosts and the others join it directly. No router or internet needed.
- **Dedicated server fallback.** If players are too far apart for Wi-Fi Direct, run
  `phonetag-server` somewhere reachable and play over mobile data or any Wi-Fi.
- **Lobbies are capped at 16 players.** The cap is enforced on the server and can't be raised by a host.

## How it works

```
 ┌──────────── Wi-Fi Direct group ────────────┐        ┌──────── Online ────────┐
 │  Host phone (group owner, 192.168.49.1)    │        │  phonetag-server       │
 │   ├─ GameServer (single lobby, port 47474) │        │   ├─ many lobbies      │
 │   └─ GameClient → 127.0.0.1                │        │   │  (5-char codes,    │
 │  Joiner phones ── GameClient → host:47474  │        │   │   16 players each) │
 └────────────────────────────────────────────┘        │  Phones ── TCP 47474   │
                                                        └────────────────────────┘
```

Both modes use exactly the same code (`core/`): the same `GameServer`, `Lobby` engine and
newline-delimited JSON protocol over TCP. The host phone just runs the server in-process.

### Tracking & hit detection
- Each phone sends its pose (lat/lon, GPS accuracy, compass azimuth corrected to true north)
  5× per second. The server broadcasts everyone's positions, health and scores 5× per second.
  That data drives the in-game **radar**: heading-up, with the outer ring at max weapon range.
- A shot is a ray from the shooter along the compass heading. A target counts as hit if the
  bearing to it is inside a cone sized for a body radius plus part of both players' GPS
  error. The cone gets narrower with distance and is clamped between 2° and 30°. The closest,
  most centred target takes the hit, so the player in front blocks the one behind.
  See `core/.../HitResolver.kt`.
- The client runs the same check locally to turn the crosshair red when someone is in your sights.
- Rules: 100 HP, 25 damage per hit (4 hits = tagged out), 8 s respawn, 3 s spawn protection,
  350 ms fire cooldown, 60 m range by default. The server enforces all of them. Free-for-all
  or Red vs Blue teams (no friendly fire). Matches last 5, 10 or 15 min.

### Camera tracking & shared AR arena
On phones with ARCore, the camera also tracks the phone in 3D, accurate to a few centimetres.
Phones then **sync a shared coordinate frame (the "arena") with each other, offline**, with no
cloud anchors and no internet:

1. The lobby names a **reference phone**: the first AR-capable phone by default, and the host
   can pick another. Its ARCore world at the moment it starts tracking defines the arena.
2. A synced player opens **AR sync → Show my marker**. Their screen shows a generated image
   target at an exact physical size (5 cm) and keeps broadcasting the screen's pose in arena
   coordinates through the game server.
3. Another player points their camera at that screen from 20–40 cm away. ARCore measures the
   marker. Since `marker_in_my_world = arena_to_my_world × screen_in_arena`, the phone solves
   for `arena_to_my_world`. It averages 15 consistent samples, keeps only yaw and translation
   (both worlds are gravity-aligned), and stores the result as an ARCore anchor that keeps
   refining. Any synced phone can sync the next one.

How hits are decided (`core/.../HitResolver.kt`):

| Shooter / target | Method |
|---|---|
| Both synced to the same arena epoch | **3D ray cast** from the shooter's camera against a body-sized capsule around the target's phone. Tolerance is 0.5 m plus 2 cm per metre of distance. |
| Otherwise | **GPS + compass cone**, as before |
| ...plus on-device **person detection** (ML Kit pose detection, offline) | A person under the crosshair at a plausible distance **widens** the GPS cone. A clearly empty crosshair within 20 m **narrows** it, which also makes shooting through walls harder. |

AR markers in the view use the true 3D positions of synced players ("AR" in their label).
The detected person gets corner brackets. The host can restart the arena at any time,
which starts a new epoch and everyone re-syncs. Phones without ARCore still take part using
CameraX, GPS, compass and person detection.

### AR view & sound
- **Player markers in the camera image.** Each player's GPS bearing is projected into the viewfinder
  using the real camera's field of view (read from the lens focal length and sensor size).
  Each marker shows a name, distance and health bar. Enemies are diamonds, teammates are
  chevrons, and tagged-out players are grey X's. Players outside the view appear as stacked
  arrows on the screen edge. Markers are smoothed so GPS jitter doesn't make them jump.
- **Effects:** laser beam and muzzle flash on fire, spinning lock-on brackets, a hit marker
  with sparks, a shockwave and a "+1 TAG" banner on tag-outs, a red damage vignette with
  screen shake and an arrow pointing at whoever shot you, a heartbeat vignette at low health,
  a blue spawn-shield glow, a "signal lost" glitch while tagged out, and a radar sweep.
  See `app/.../ui/ArOverlay.kt`.
- **Sound effects** are synthesized in code (`app/.../sfx/Synth.kt`), so the app ships no audio
  files. They're rendered to WAV once and played through `SoundPool`: laser, hit confirm,
  tag, damage, tagged out, respawn, lock-on, match start/end and a low-health heartbeat.
  You can mute them from the in-game HUD.

### Limitations to know about
- **Without AR sync, GPS is the precision limit.** Phone GPS is typically good to ±3–10 m outdoors and much
  worse indoors. Play outside with open sky, and spread out more than a few metres. The app
  shows your current GPS accuracy, and the hit cone widens to compensate.
- **AR sync accuracy** depends mostly on how still both phones are while scanning. A 1° heading
  error moves a target 50 cm at 30 m. If the arena drifts after a long match, the host can
  restart it. Pausing the camera, for example by leaving the app, can make ARCore lose its map;
  re-sync if the lobby stops showing you as "AR synced".
- **Compass calibration matters.** If the app says the compass is unreliable, wave the phone in a figure 8.
- **Wi-Fi Direct client limits vary by phone.** The protocol allows 16 players, but some
  phones only accept about 8 clients in a Wi-Fi Direct group. If people can't join, use the
  dedicated server. Wi-Fi Direct range is roughly 50–100 m outdoors.
- Depending on the phone, the host may see a system "Invitation to connect" prompt for each joiner.
- The host phone runs the server, so it must keep PhoneTag open. The app keeps the screen on.
  If the host leaves, the Wi-Fi Direct game ends. On the dedicated server, the host role passes
  to the next player.

## Project layout

| Module    | What                                                                                          |
|-----------|-----------------------------------------------------------------------------------------------|
| `core/`   | Pure Kotlin/JVM: protocol, `Lobby` game engine, `HitResolver`, `GameServer`, `GameClient`. Unit + socket tests. |
| `server/` | Dedicated server CLI (`phonetag-server`) + Dockerfile.                                        |
| `app/`    | Android app (Kotlin, Jetpack Compose, CameraX, fused location, rotation-vector compass, Wi-Fi P2P). |

## Building

Requires JDK 17+ and, for the app, the Android SDK (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew :core:test            # engine + networking tests (incl. 17th player rejected)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :server:installDist   # server/build/install/phonetag-server/bin/phonetag-server
```

The GitHub Actions workflow builds all three and uploads the debug APK as an artifact.

## Running the dedicated server

```bash
server/build/install/phonetag-server/bin/phonetag-server --port 47474
# or
docker build -f server/Dockerfile -t phonetag-server .
docker run -p 47474:47474 phonetag-server
```

Open TCP port 47474 in your firewall. In the app, go to **Play online**, enter `host:47474`,
then either create a lobby and share its 5-letter code, or join from the open-lobby list.

## Playing

1. Everyone installs the APK and grants location, nearby-devices and camera permissions.
2. **Nearby:** one player taps **Host a game**. The others tap **Join a nearby game** and pick it.
   **Far apart:** use **Play online** with a dedicated server.
3. The host picks the mode, time and range, then taps **Start match**.
4. Hold the phone upright like a camera, find players on the radar, put them in the crosshair and **FIRE**.
