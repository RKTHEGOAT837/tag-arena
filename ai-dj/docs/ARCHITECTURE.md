# AI DJ architecture

## Modules

| Module | Language | Responsibility |
|---|---|---|
| `dj-core` | Kotlin (JVM, no Android deps) | Data model, source capabilities, audio analysis, party modes, harmonic rules, track selection, transition planning, automation contract, AI tool registry + validation, Claude tool loop, MCP protocol, offline voice grammar. Fully unit-testable. |
| `dj-audio-engine` | C++17 + Kotlin | Real-time engine (Oboe), JNI bridge, Kotlin facade, MediaCodec decoder. |
| `app` | Kotlin, Compose, GLES3 | Library/DB, discovery, brain (decision loop), Claude orchestration, MCP server, voice/TTS, foreground service, 3D stage, UI. |
| `backend` | Node (ESM) | Holds secrets, validates & forwards Claude turns via `@anthropic-ai/sdk`, proxies Jamendo. |
| `native-tests` | C++ | Host tests of the engine + execution of planner-exported plans. |

The requested module names map onto packages: ai-orchestrator → `app/brain` + `dj-core/ai`;
mcp-integration → `dj-core/ai/McpProtocol` + `app/brain/McpServer`; music-discovery → `app/discovery`;
music-library → `app/data`; audio-analysis → `dj-core/analysis`; dj-audio-engine → `dj-audio-engine`;
remix-engine → native `synth.h` + loop/automation features + `dj-core/planning`; virtual-dj / animation-engine →
`app/stage`; voice-assistant → `app/voice` + `dj-core/voice`; settings → `app/data/SettingsStore`.

## Real-time audio path

```
TrackBuffer (int16 stereo, native memory)
  └─ Deck reader: loops (declick crossfade), slip shadow position, per-sample source-position tag
       └─ WSOLA time-stretch (key lock; tempo = rate / pitch)       [only when key lock is needed]
            └─ 16-tap windowed-sinc resampler (pitch × trackSR/outputSR, or rate for vinyl mode)
                 └─ trim → 3-band LR4 isolator EQ → TPT-SVF DJ filter → fader → crossfader
                      → flanger (beat-synced LFO) → echo (beat-timed, filtered feedback) → Freeverb
  Σ decks + drum machine + riser + impact → master filter → master volume × duck → peak limiter + soft clip
  → meters (RMS, low/mid/high, 16-band visualiser) → Oboe (AAudio/OpenSL ES) → device route
```

- **Master clock.** A beat counter advancing at the master BPM. Synced decks run at `masterBPM/gridBPM` and a
  P-controller nudges their rate (±2 %) to hold phase. The phase error is low-passed so the controller doesn't
  chase WSOLA's per-segment position jitter. The master can be re-anchored to a deck (first track, unsynced entries).
- **Automation.** Ramps (param, deck, start/end beat, from/to, curve) and actions (start deck at cue, stop, loop,
  exit loop, brake, impact, sync on/off, drum pattern, anchor, seek) are stamped in **master beats**. The audio
  callback splits its buffer so each action fires on its exact frame and evaluates ramps every ≤64 frames.
- **Threading.** Control threads enqueue commands into a ring consumed at the start of each callback; the
  callback never allocates or locks. State (positions, rates, phase, meters, live mixer values) is published
  through a seqlock once per callback. Track memory is freed only after the audio thread has stopped
  referencing it (load sequence numbers + snapshot).
- **Route changes.** On `ErrorDisconnected` (headphones, Bluetooth), the stream is reopened; if the sample rate
  changes, DSP coefficients are rebuilt while deck positions and every parameter target are preserved.

## Decision loop (DjBrain)

```
playing ─► ~25 s in (or 100 s before the outro): prepare next
   1. rank library (tempo, key ±2 st, energy curve of the mode, genre/era steering, history, likes/skips)
   2. ask Claude (async, ≤30 s, decision window closes at the deadline) ─ else local top pick
   3. decode into the idle deck (native memory), analyse if needed
   4. plan: mix-out phrase in A (outro/section boundary), entry cue in B (so its groove/drop lands on the phrase),
      style from mode weights + feasibility (tempo gap, licences, grid confidence, key compatibility), key shift
   5. schedule on the master clock (can be cancelled/re-planned until it starts)
   ─► TransitionStarted → handover → TrackChanged → TransitionCompleted → history, preferences, announcements
```

Overrides (skip, play next, mode/genre/energy/era steering, transition style/length/drama, lock song, pause
decisions, AI off) cancel the not-yet-started plan and re-plan immediately.

## Claude integration

- The app builds `{purpose, system, messages, tools}`; the backend adds model, limits, effort and refusal
  fallbacks and calls `client.beta.messages.create` with the official SDK.
- The device runs the manual tool loop (`ClaudeAgent`): assistant turns are appended unchanged (thinking blocks
  preserved), every `tool_use` is validated against its closed schema and executed by `DjController`, all
  results return in one user turn, `refusal`/`max_tokens`/API errors are handled; failures trigger exponential
  backoff and the local AI continues.
- State sent to Claude is compact metadata (current track, section, outro timing, energy target, history,
  ranked candidates) — never audio.

## Event system

`Performer` derives events from engine state + analysis and publishes them on a shared flow:
`Beat`, `Bar`, `BassHit` (live low-band onsets), `SectionChanged`, `BuildupStarted`, `DropStarted`,
`BreakdownStarted`, `VocalStarted`, `EnergyChanged`, `TransitionStarted/Completed`, `TrackChanged`,
`TrackEnded`, `Gesture`, `Activity`. Consumers: the brain (history, recovery), the announcer, and the stage
(rig gestures, particles, camera, lights). The stage additionally reads the live snapshot every frame and
offsets the beat by the measured output latency so visuals match what you hear (important on Bluetooth).

## Virtual DJ

`DjRig` computes joint positions each frame from layers blended with critically damped springs:
groove (beat-locked bounce/sway/nod scaled by energy and section — buildups ramp, drops explode,
breakdowns breathe), work (hands placed by two-bone IK on the actual console controls chosen from the engine's
activity: EQ knobs during bass swaps, filter knob during sweeps, FX buttons for echo-outs, pads for loop rolls,
jog wheel for tempo nudges, one hand pressing a headphone cup to the ear while the next track is cued),
gestures (celebrate on drops, hype during buildups, point/hands-up crowd interaction every 16 bars at high
energy, Claude-triggered moves), and look-at/expressions (blinks, smile with energy, shouting on drops). Legs
use IK with planted feet. The console's knobs, faders and jog wheels render the engine's live values.
