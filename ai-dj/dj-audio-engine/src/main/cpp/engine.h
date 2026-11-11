// AI DJ native engine: two decks, master clock with phase-locked beat sync, beat-scheduled automation,
// per-deck EQ/filter/FX, live remix synths, master limiter and meters.
//
// Threading model:
//  - render() runs on the real-time audio thread. It never allocates, locks or blocks.
//  - All control calls enqueue Commands into a lock-protected (producer side only) single-consumer ring.
//  - State is published once per callback through a seqlock snapshot.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "dsp.h"
#include "synth.h"

namespace aidj {

enum ParamId {
    P_DECK_VOLUME = 0, P_EQ_LOW = 1, P_EQ_MID = 2, P_EQ_HIGH = 3, P_FILTER = 4, P_ECHO_MIX = 5,
    P_ECHO_FEEDBACK = 6, P_ECHO_BEATS = 7, P_REVERB_MIX = 8, P_FLANGER_MIX = 9, P_TRIM = 10,
    P_CROSSFADER = 20, P_MASTER_BPM = 21, P_DRUM_LEVEL = 22, P_RISER_LEVEL = 23, P_RISER_PITCH = 24,
    P_MASTER_FILTER = 25, P_MASTER_VOLUME = 26, P_DUCK = 27, P_DRUM_BASS_LEVEL = 28
};

enum ActionType {
    A_START = 0, A_STOP = 1, A_SET_LOOP = 2, A_EXIT_LOOP = 3, A_BRAKE = 4, A_IMPACT = 5, A_RESET_DECK = 6,
    A_SET_SYNC = 7, A_DRUM_PATTERN = 8, A_ANCHOR = 9, A_SEEK = 10
};

enum CurveId { CURVE_LINEAR = 0, CURVE_EASE_IN = 1, CURVE_EASE_OUT = 2, CURVE_S = 3, CURVE_STEP = 4 };

struct TrackBuffer {
    int id = 0;
    std::vector<int16_t> pcm;  // interleaved stereo
    int64_t capacityFrames = 0;
    int64_t frames = 0;
    int sampleRate = 44100;
    uint64_t loadSeq = 0;  // sequence number of the last load command referencing this buffer (control thread)
};

enum CmdType {
    C_LOAD, C_UNLOAD, C_PLAY, C_PAUSE, C_SEEK, C_SET_PARAM, C_SET_SYNC, C_SET_KEYLOCK, C_SET_PITCH, C_SET_GRID,
    C_SET_LOOP, C_EXIT_LOOP, C_BRAKE, C_ADD_RAMP, C_ADD_ACTION, C_CLEAR_AUTOMATION, C_CLEAR_DECK_AUTOMATION,
    C_ANCHOR, C_DRUM_STEP, C_DRUM_SWING, C_DRUM_PATTERN, C_DRUM_ROOT, C_IMPACT, C_SET_RATE, C_RESET_DECK
};

struct Command {
    int type = 0, deck = 0, i0 = 0, i1 = 0;
    double d0 = 0, d1 = 0, d2 = 0, d3 = 0;
    TrackBuffer* track = nullptr;
    uint64_t seq = 0;
};

struct Ramp {
    int param = 0, deck = 0, curve = 0;
    double start = 0, end = 0;
    float from = 0, to = 0;
    bool hasFrom = false, started = false, active = false;
};

struct ActionEvent {
    int type = 0, deck = 0;
    double beat = 0, a0 = 0, a1 = 0;
    bool active = false;
};

constexpr int kNumDecks = 2;
constexpr int kStateSize = 64;
constexpr int kNumBands = 16;

class Deck : public FrameSource {
public:
    TrackBuffer* track = nullptr;
    double pos = 0;          // reader position, source frames
    double shadowPos = 0;    // slip-mode position
    double audible = 0;      // source position of the last output frame (tag)
    bool playing = false, ended = false;
    // Loop
    bool looping = false, slip = false;
    double loopStart = 0, loopLen = 0;
    // Jump declick
    int xfadeLeft = 0;
    double xfadeOldPos = 0;
    static constexpr int kXfade = 192;
    // Config
    bool synced = false, keylock = true;
    double pitchSemis = 0, userRate = 1.0;
    double gridBpm = 0, firstBeatSec = 0;
    // Brake
    bool braking = false;
    double brakeFactor = 1.0, brakeDecPerFrame = 0;
    // Start/stop fade
    float fade = 0; int fadeDir = 0; // +1 fading in, -1 fading out (then stop)
    bool stopAfterFade = false;
    bool pendingReconfig = false;
    double pendingSeekPos = -1;
    // Pipeline
    TimeStretch stretch;
    Resampler resampler;
    bool useStretch = false;
    double contentRate = 1.0, phaseErr = 0;
    // Mixer + FX
    Smoother volume, trim;
    Eq3 eq;
    DjFilter filter;
    Echo echo;
    Flanger flanger;
    Reverb reverb;
    float echoBeats = 0.75f, vu = 0;
    float paramTarget[16] = {};

    void init(float sr);
    void pull(float* l, float* r, double* tag, int n) override;  // reader stage
    void flushPipeline();
    void configurePipeline();
    double beatAt(double sourceFrames) const;
    double beatPeriodFrames() const { return track ? 60.0 / gridBpm * track->sampleRate : 0; }
    bool hasGrid() const { return gridBpm > 1.0 && track; }
    void resetParams();
    void requestReconfigure(double seekTo = -1);
    void finishReconfigure();
    inline float sample(double p, int ch) const;
};

class Engine {
public:
    Engine();
    void setSampleRate(int sr);
    int sampleRate() const { return sr_; }

    // Real-time thread.
    void render(float* interleavedOut, int frames);

    // Control thread API.
    uint64_t push(Command c);
    void readState(double* out);
    std::shared_ptr<TrackBuffer> allocTrack(int64_t capacityFrames, int sampleRate);
    std::shared_ptr<TrackBuffer> getTrack(int id);
    void releaseTrack(int id);
    void collectGarbage();
    void setXruns(int x) { xruns_.store(x); }

private:
    // RT helpers
    void drainCommands();
    void apply(const Command& c);
    void executeAction(const ActionEvent& a);
    void evaluateRamps();
    void setParam(int param, int deck, float v);
    float getParam(int param, int deck) const;
    void renderChunk(float* out, int frames);
    void startDeck(Deck& d, double posSec);
    void anchorTo(int deck);
    void publish();

    int sr_ = 48000;
    bool initialised_ = false;
    Deck decks_[kNumDecks];
    double masterBeat_ = 0, masterBpm_ = 120;
    int64_t framesRendered_ = 0;
    float crossfader_ = 0;
    Smoother masterVol_, duck_, xfL_, xfR_;
    DjFilter masterFilter_;
    Limiter limiter_;
    DrumMachine drums_;
    Riser riser_;
    Impact impact_;
    float riserLevelT_ = 0, riserPitchT_ = 0, drumLevelT_ = 0, drumBassT_ = 0, masterFilterT_ = 0, masterVolT_ = 0.9f, duckT_ = 0;

    static constexpr int kMaxRamps = 512, kMaxActions = 256;
    Ramp ramps_[kMaxRamps];
    ActionEvent actions_[kMaxActions];

    // Meters
    Biquad bandFilters_[kNumBands];
    float bandEnv_[kNumBands] = {};
    Biquad lowF_, midHp_, midLp_, highF_;
    float rmsEnv_ = 0, peak_ = 0, lowEnv_ = 0, midEnv_ = 0, highEnv_ = 0, meterCoef_ = 0, cpuLoad_ = 0;

    // Scratch (preallocated)
    static constexpr int kChunk = 64;
    float bufL_[kChunk], bufR_[kChunk];
    double bufT_[kChunk];

    // Command queue
    static constexpr int kQueue = 2048;
    Command queue_[kQueue];
    std::atomic<uint32_t> qHead_{0}, qTail_{0};
    std::mutex pushMutex_;
    uint64_t nextSeq_ = 1;
    uint64_t appliedSeq_ = 0;

    // Snapshot (seqlock)
    std::atomic<uint32_t> snapSeq_{0};
    double snap_[kStateSize] = {};
    std::atomic<int> xruns_{0};

    // Track registry (control thread)
    std::mutex trackMutex_;
    std::unordered_map<int, std::shared_ptr<TrackBuffer>> tracks_;
    std::vector<int> pendingRelease_;
    int nextTrackId_ = 1;
};

}  // namespace aidj
