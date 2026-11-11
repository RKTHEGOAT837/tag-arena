# AI DJ — an autonomous DJ on your Android phone

Press **START PARTY** and the AI takes over: it picks tracks, analyses them, beatmatches, key-matches,
plans phrase-aligned transitions, uses EQ/filters/echo/loops/drum layers with intent, controls energy over
the set, talks (rarely), takes voice requests, and performs on a 3D stage as an animated DJ whose hands
work the real mixer state.

Everything that makes sound is real: a native C++ engine (Oboe) plays two decks, time-stretches with key
lock, phase-locks beats to a master clock and executes beat-scheduled automation sample-accurately.
Claude is the high-level brain (through a small backend that holds the API key); without it, a local AI
makes every decision and the party keeps going offline.

> **Status, honestly:** the analysis, audio engine, transition planning, AI tool layer and backend are
> implemented **and covered by automated tests** (including executing real plans in the real engine).
> The Android app compiles and packages into an installable APK, **but this build environment has no
> Android device or emulator, so the app has not been run on hardware yet.** See the
> [feature checklist](#feature-checklist) for exactly what is verified and how.

---

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Claude + backend setup](#claude--backend-setup)
- [Music sources and licensing](#music-sources-and-licensing)
- [Using the app](#using-the-app)
- [MCP server](#mcp-server)
- [Building from source](#building-from-source)
- [Tests](#tests)
- [Architecture](#architecture) (details in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md))
- [Feature checklist](#feature-checklist)
- [Known limitations](#known-limitations)

---

## Install

`release/AI-DJ-1.0.3.apk` (arm64-v8a, armeabi-v7a, x86_64; Android 10+). SHA-256 in
`release/AI-DJ-1.0.3.apk.sha256`.

It is signed with the Android **debug** key so you can sideload it (enable "Install unknown apps").
For distribution, build with your own keystore (see [Building](#building-from-source)).

## Quick start

1. Open the app → **Library** → **Import files** (or **Scan device** to index your music, or
   **Discover** to download Creative Commons tracks).
2. Wait for analysis (progress bar in Library; ~2–6 s per track on a modern phone). At least two
   analysed tracks are needed.
3. Back on the stage, press **START PARTY**.

Optional: connect Claude (below). Without it, the "Local AI" does selection, planning and voice commands.

## Cloud AI + backend setup (Claude **or Gemini — free tier**)

The APK never contains an API key. The cloud brain is reached through `backend/`, a tiny Node service that
holds the key, authenticates the phone with a device token, validates and bounds every request, and talks to
the provider. Two interchangeable providers; the app doesn't know which one runs:

- **Gemini (cheap option)** — Google AI Studio keys have a free tier that comfortably covers the DJ's
  ~1 request per track. The backend translates to/from the same tool-calling shape the app speaks.
- **Claude** — via the official `@anthropic-ai/sdk`.

```bash
cd ai-dj/backend
npm ci
export LLM_PROVIDER=gemini
export GEMINI_API_KEY=AIza...                  # free at https://aistudio.google.com/apikey
# (or: export LLM_PROVIDER=claude; export ANTHROPIC_API_KEY=sk-ant-...)
export APP_TOKENS=$(openssl rand -hex 24)      # token(s) the phone will use; comma-separate several
export JAMENDO_CLIENT_ID=...                   # optional, enables Jamendo search through the backend
npm start                                      # listens on :8787
```

`GEMINI_MODEL` defaults to `gemini-2.5-flash`; set `gemini-2.5-flash-lite` for the largest free quota.

Deploy anywhere that runs Node 20+ or Docker (`backend/Dockerfile`; a Render blueprint is in
`backend/render.yaml`). Use HTTPS in production — the app only allows cleartext to emulator/localhost.

In the app: **Settings → Claude AI** → enter the backend URL (`https://your-host`) and the device token →
**Test connection**.

What the backend sends to Claude (when `LLM_PROVIDER=claude`): model `claude-opus-5-5`, adaptive thinking (always on for this model),
effort `low` for periodic DJ decisions and `medium` for user requests, `tool_choice: auto`, and
server-side refusal fallbacks (`fallbacks: "default"`). Override the model with `CLAUDE_MODEL`.
Only metadata and analysis numbers are sent — never audio.

**Cost control:** Claude is consulted about once per track (plus each voice/text request that isn't handled
locally), never per beat or frame. The backend rate-limits per device (`RATE_LIMIT_PER_MIN`, default 20).

## Your listening taste (Spotify & YouTube history)

Song selection can be steered by what you actually listen to. All taste data stays on the phone.

- **Spotify (live)** — Settings → *Your music taste* → enter your own free Spotify developer Client ID
  (create an app at developer.spotify.com, add redirect URI `aidj://spotify-callback`), then **Connect
  Spotify**. The app reads your top tracks, top artists and recently played via the official Web API
  (OAuth + PKCE, token stored in Keystore-encrypted prefs). Spotify *audio* is never touched — only taste.
- **YouTube / YouTube Music** — Google removed API access to watch history, so no app can read it live.
  Export it with [Google Takeout](https://takeout.google.com) (YouTube → history → JSON) and import the
  `watch-history.json` in the same Settings section. YouTube Music entries count full weight, plain
  YouTube views less; "Artist – Title" video names are parsed into artist/title pairs.
- **Spotify export files** — `StreamingHistory*.json` / `endsong*.json` from Spotify's account-data
  download import the same way.

Matching library tracks get a ranking boost (exact track match > artist match, log-scaled by play counts),
show "matches your listening history" in the queue's candidate list, and the cloud AI sees your top
artists in its state snapshot.

## Music sources and licensing

Every track carries a capability record — **playback / analysis / processing / remix / offline** — and
every DJ operation checks it (see [docs/LICENSING.md](docs/LICENSING.md)).

| Source | Status | What the app may do |
|---|---|---|
| Your files (import, device scan, share-to-app) | ✅ implemented | Everything (you're responsible for your files' rights) |
| Jamendo (Creative Commons) | ✅ implemented (needs a client id, via backend or Settings) | Per license: ND tracks play unmodified; BY/SA/NC allow mixing & remixing; only tracks the artist allows downloading |
| Internet Archive (CC / public domain items) | ✅ implemented (no key) | Per license |
| Spotify / YouTube Music / SoundCloud | ❌ intentionally not integrated | Their terms/SDKs don't allow decoding, re-processing or mixing streams in a third-party engine, and streams are DRM-protected. AI DJ never bypasses DRM. |

Formats: anything Android's MediaCodec decodes (MP3, AAC/M4A, FLAC, WAV, OGG/Opus…). DRM-protected files are rejected.

## Using the app

- **Main screen:** status (AI state, internet, mode, party name), the 3D stage with the DJ, current DJ
  activity and next-mix countdown, track info (art, BPM, key/Camelot, section, energy, remaining time),
  transport (previous / play-pause / skip / voice), **AI DJ ON/OFF**, **Lock song**, **Queue**,
  **DJ controls**, **Party mode**, master volume.
- **Party modes:** Auto, Club, Bollywood, Punjabi, Retro, Chill, Festival, Custom — each changes genre
  preference, BPM range, the energy curve over the set, blend length and which transition techniques are favoured.
- **DJ controls (manual override):** transition style/length/drama for the next mix, "mix now", energy
  nudge, genre steering, master BPM, FX pads (echo, reverb, flanger, low/high-pass, brake, impact), loops
  (1/4 … 8 beats, slip rolls), drum layer, riser, beat roll, extend section, pause AI decisions,
  disable automatic remixing. The AI re-plans immediately after any change.
- **Voice:** tap the mic. Works offline for: energy up/down, mode/genre switches, "mix Punjabi with EDM",
  eras ("songs from the 2000s"), dramatic/smooth transitions, "powerful drop", "something everyone can
  dance to", "remix this" / "stop remixing", skip/previous/pause/resume, lock/unlock, louder/quieter,
  faster/slower, effects, "play <title>".
  Anything else goes to Claude when connected.
- **Background:** a foreground media service keeps playing with the screen off; notification, lock-screen,
  headset and Bluetooth media buttons work; audio focus and "headphones unplugged" pause correctly;
  the output stream re-opens automatically when the audio route changes (e.g. Bluetooth connects).

## MCP server

**Settings → MCP server** exposes the DJ's tools to other MCP clients on the same Wi-Fi:
`POST http://<phone-ip>:8765/mcp` with `Authorization: Bearer <token shown in Settings>` (JSON-RPC,
the JSON-response form of the Streamable HTTP transport; `initialize`, `tools/list`, `tools/call`, `ping`).
Requests with an `Origin` header are refused (DNS-rebinding protection).

Tools (all implemented): `search_music`, `get_track_metadata`, `analyse_audio`, `detect_bpm`, `detect_key`,
`get_song_structure`, `select_next_track`, `set_bpm`, `sync_beats`, `apply_eq`, `apply_effect`,
`create_loop`, `start_transition`, `create_remix`, `control_virtual_dj`, `get_audio_analysis`,
`get_dj_state`, `set_party_mode`, `announce`. Every call is validated against a closed JSON schema with
bounded values before it touches the engine. The same registry is what Claude uses in-app.

## Building from source

Requirements: JDK 17+ (21 tested), Android SDK platform 35, build-tools 35, **NDK 27.2.12479018**,
CMake 3.22.1. Then:

```bash
cd ai-dj
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # minified; signed with debug key unless you pass a keystore:
./gradlew :app:assembleRelease -PaidjKeystore=/path/key.jks -PaidjKeystorePassword=... \
          -PaidjKeyAlias=... -PaidjKeyPassword=...
# Optional: bake a default backend URL into the build
./gradlew :app:assembleRelease -PaidjBackendUrl=https://your-backend
```

CI (`.github/workflows/ai-dj.yml`) runs all tests and builds the release APK as an artifact.

## Tests

```bash
cd ai-dj
./gradlew :dj-core:test        # analysis, selection, planning, licensing, tool validation, Claude loop, MCP, voice
native-tests/run.sh            # C++ engine on the host + execution of exported plans in the engine
(cd backend && npm test)       # backend: auth, validation, rate limits, exact SDK request (fake fetch, no API spend)
node tools/check-shaders.mjs   # compiles/links every stage shader with a GLES3-class compiler (needs Playwright)
```

Highlights of what the tests measure (on synthetic tracks with known ground truth):

- Tempo exact at 95/124/128/140 BPM; beat-grid phase error ≤ 1.5 ms; key correct (incl. Camelot); downbeat,
  intro/groove/breakdown/buildup/drop/outro boundaries all found at the right bars.
- Engine: a 128 BPM track synced into a 124 BPM master lands every kick within **< 2 ms** of the master
  beat; key-lock keeps 440 Hz at ±6 % tempo; ±semitone shifts are exact; limiter holds peaks ≤ 0.89;
  echo tails survive a deck stop; drum machine kicks within 0.2 ms; ~60–70× real time on a host CPU.
- Plan → engine: bass-swap, filter sweep, echo-out, loop-roll-into-drop, drum bridge (124→100 BPM) and cut
  plans from the Kotlin planner run in the C++ engine: the incoming deck enters within 0.004 beats of its
  slot at the right cue, stays phase-locked (≤ 0.004 beats average) through the blend, the outgoing deck
  stops on schedule, tempo glides to the new track, and remix generators are cleaned up.

## Architecture

```
┌────────────────────────── Android app (Kotlin, Compose) ──────────────────────────┐
│ UI (stage, library, discover, settings, sheets)   PartyService (media session/focus)│
│        │                                                                            │
│  DjBrain ── selection/planning (dj-core) ── ClaudeOrchestrator ──HTTPS──► backend ──► Claude
│     │            ▲ tools (MCP-style registry) ◄── McpServer (LAN, JSON-RPC)         │
│  Performer ── schedules plans on the master clock, live state + music events        │
│     │                    │                                                          │
│     │         events ───►  Stage (OpenGL ES 3: DJ rig/IK, console, lights, crowd)   │
│     ▼                                                                               │
│  NativeEngine (JNI) ──► C++ engine: 2 decks · WSOLA key-lock · sinc resampler ·     │
│                         phase-locked sync · 3-band EQ · filter · echo · reverb ·    │
│                         flanger · loops/slip · brake · drums · riser · limiter      │
│                         beat-scheduled ramps/actions ──► Oboe/AAudio ──► speaker/BT │
└────────────────────────────────────────────────────────────────────────────────────┘
```

Key rule: **nothing time-critical depends on Kotlin or the network.** Transitions are compiled into
beat-stamped automation that the audio thread executes against its own master clock; UI jank, GC pauses,
slow GPUs or Claude outages can't make the music skip.

## Feature checklist

Legend: ✅ implemented & verified by automated tests · 🟢 implemented, builds, not yet run on a device ·
🟡 partial / simplified · 🌐 needs an external service · ❌ not implemented

| Area | Feature | Status |
|---|---|---|
| Analysis | BPM, beat grid, downbeat, key/Camelot, energy curve, sections (intro/groove/buildup/drop/breakdown/outro), drops | ✅ (synthetic ground truth; real-music accuracy not benchmarked) |
| Analysis | Vocal detection | 🟡 spectral heuristic, not an ML model |
| Analysis | Genre / mood | 🟡 genre from file/catalogue tags; mood from key mode + energy |
| Analysis | Caching in SQLite, background queue | 🟢 |
| Selection | Tempo/key (with ±2 st shift)/energy-curve/genre/era/history/preference scoring, no repeats | ✅ |
| Selection | Listening-taste boost from Spotify/YouTube history (log-scaled artist+track affinity) | ✅ scoring · 🟢 imports |
| Taste | Spotify OAuth (PKCE) top tracks/artists/recently-played sync | 🟢 · 🌐 your free Spotify Client ID |
| Taste | Google Takeout YouTube/YouTube Music + Spotify export file import | 🟢 |
| Mixing | Two decks, beat sync with phase lock, tempo change with key lock, pitch shift, key matching | ✅ |
| Mixing | 3-band isolator EQ, bipolar filter, echo, reverb, flanger, volume automation, crossfader, bass swaps | ✅ |
| Mixing | Cue points (phrase-aligned entry cues), loops, slip loops/beat rolls, brake | ✅ |
| Mixing | Transition styles: bass swap, filter sweep, echo out, loop roll → drop, cut, brake, drum bridge, mashup layer, crossfade | ✅ (6 executed end-to-end in tests; all 9 unit-tested) |
| Mixing | Hot cues (user-set, per track) | ❌ (cues are chosen automatically from structure) |
| Remix | Intro extension by looping, section extension, drum layer (7 patterns incl. dhol & phonk cowbell/808), riser + impact, mashup layering | ✅ |
| Remix | Autonomous live remixing of the playing track (phrase-aligned dhol/phonk drum layers, risers into real drops, beat rolls, echo throws; flavour from tags/title/mode; licence- and intensity-aware) | 🟢 |
| Remix | Stem separation / generative AI audio | ❌ needs on-device separation models or a paid service; not faked |
| AI | Local decision loop: prepare next track early, plan phrase-aligned transition, execute, adapt to overrides | 🟢 (logic paths unit-tested in dj-core) |
| AI | Claude decision layer + tool loop (validated tools, thinking blocks preserved, refusal/timeout handling) | ✅ loop · 🌐 Claude |
| AI | Gemini provider on the backend (Messages-API ⇄ functionCall translation, free tier) | ✅ translation tested · 🌐 Gemini key |
| AI | Works offline / survives Claude failures (timeouts, backoff, local fallback) | 🟢 |
| MCP | Tool registry + JSON-RPC protocol | ✅ · LAN HTTP server 🟢 |
| Backend | Key custody, device tokens, validation, rate limiting, Jamendo proxy, official SDK | ✅ |
| Voice | Offline command grammar | ✅ · Android speech recognition + TTS with ducking 🟢 (recogniser availability depends on device) |
| Personality | Rare, context-timed announcements (3 personalities) | 🟢 |
| Discovery | Jamendo + Internet Archive search, licence-aware download | 🟢 · 🌐 Jamendo client id |
| Virtual DJ | 3D character, IK hands on real console controls, beat/section-driven body, gestures, crowd interaction, expressions, headphone cueing | 🟢 · 🟡 procedurally modelled stylised character, not a photoreal scanned/rigged model |
| Stage | Beat-synced moving heads/beams, LED wall visualiser (real band meters), floor LEDs, particles on drops, camera moves, themes | 🟢 (shaders ✅ compile) |
| Performance | Auto/Low/Medium/High quality, render scaling, battery-saver mode, GL decoupled from audio | 🟢 |
| Android | Foreground media service, notification/lock-screen/BT media buttons, audio focus, becoming-noisy, route-change stream reopen | 🟢 |
| Android | Crash recovery (offer to resume the party) | 🟢 |
| Testing | On-device: Bluetooth latency, battery drain, memory, long-session soak, real-world analysis accuracy | ❌ not possible here — needs devices |

## Known limitations

- **Not yet run on a phone.** Expect a round of device testing/tuning (audio buffer sizes on specific
  devices, GL performance, speech recogniser quirks).
- Beat grids assume a constant tempo (true for most produced dance/pop music). Live-drummer recordings with
  drifting tempo get a low grid confidence; the planner then avoids long beatmatched blends.
- The virtual DJ is built from procedural geometry (stylised, mannequin-like) — what's real is the
  behaviour: every move is driven by the engine's beat clock, sections, energy and the mixer state. A
  production version could swap in an artist-made rigged glTF character and keep the same rig logic.
- Unity/Godot weren't used: neither can be built in this environment, and a custom GLES3 renderer keeps
  the APK small and the render thread fully independent of audio.
- Superpowered (licensing) and Rubber Band (GPL/commercial) were evaluated and not used; the engine ships
  its own WSOLA time-stretcher and windowed-sinc resampler.
