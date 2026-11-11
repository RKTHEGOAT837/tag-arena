# Music sources, rights and the capability layer

AI DJ records, for every track, what it is allowed to do:

| Capability | Meaning | Enforced where |
|---|---|---|
| `playback` | May be played | `Performer.load` refuses otherwise |
| `analysis` | May be decoded locally to compute BPM/key/structure | `AnalysisQueue`, `Performer.load` |
| `processing` | Tempo/pitch change, EQ, filters, effects, beatmatched blends | Planner (no sync/key shift/FX on that deck), `Performer.requireProcessing` for manual/AI controls |
| `remix` | Derivative operations: loops that extend sections, drum layers, risers over the track, mashups, beat rolls | Planner (`allowRemix`), `Performer.requireRemix` |
| `offline` | May be stored on the device | Discovery only downloads where the source allows it |

## Sources

- **User files** (import, MediaStore scan, share-to-app): all capabilities. The user is responsible for holding
  the rights to their files; DJ mixing for a private party is the intended use.
- **Jamendo**: per-track Creative Commons licence (`license_ccurl`). `ND` (No Derivatives) → playback +
  analysis only (played unmodified, unsynced, no effects). Other CC licences → processing + remix. Tracks are
  downloaded only when `audiodownload_allowed` is true. Note `NC` licences forbid commercial use — don't use
  them for paid events.
- **Internet Archive**: items filtered to CC/public-domain `licenseurl`; same mapping.
- **Not integrated:** Spotify, YouTube Music, SoundCloud. A search API does not grant the right to decode,
  re-time, mix or remix audio, their playback SDKs don't expose PCM for processing, and their streams are
  protected. The app never attempts to bypass DRM; MediaExtractor-detected DRM content is rejected.

## Public performance

Playing music at a public event usually requires performance licences (e.g. via your venue's PRO
agreements) regardless of the source. That is outside what an app can grant.
