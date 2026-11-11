#include "engine.h"

#include <chrono>
#include <cmath>
#include <cstring>

namespace aidj {

namespace {
inline double wrapHalf(double x) { return x - std::round(x); }
inline float shape(int curve, float t) {
    t = clampf(t, 0, 1);
    switch (curve) {
        case CURVE_EASE_IN: return t * t;
        case CURVE_EASE_OUT: return 1 - (1 - t) * (1 - t);
        case CURVE_S: return t * t * (3 - 2 * t);
        case CURVE_STEP: return 1;
        default: return t;
    }
}
constexpr float kFadeStep = 1.0f / 96.0f;
}  // namespace

// ------------------------------------------------------------------------------------------ Deck

void Deck::init(float sr) {
    stretch.init(96000.0f);
    stretch.setRate(44100.0f);
    stretch.src = this;
    resampler.src = this;
    resampler.reset();
    volume.setTime(0.008f, sr); trim.setTime(0.02f, sr);
    eq.init(sr); filter.init(sr); echo.init(sr); flanger.init(sr); reverb.init(sr);
    resetParams();
}

void Deck::resetParams() {
    volume.reset(1); trim.reset(1);
    eq.gl.target = eq.gm.target = eq.gh.target = 1;
    filter.knob.target = 0;
    echo.mix.target = 0; echo.feedback.target = 0.5f; echoBeats = 0.75f;
    reverb.mix.target = 0; flanger.mix.target = 0;
    paramTarget[P_DECK_VOLUME] = 1; paramTarget[P_TRIM] = 1;
    paramTarget[P_EQ_LOW] = paramTarget[P_EQ_MID] = paramTarget[P_EQ_HIGH] = 1;
    paramTarget[P_FILTER] = 0; paramTarget[P_ECHO_MIX] = 0; paramTarget[P_ECHO_FEEDBACK] = 0.5f;
    paramTarget[P_ECHO_BEATS] = 0.75f; paramTarget[P_REVERB_MIX] = 0; paramTarget[P_FLANGER_MIX] = 0;
}

inline float Deck::sample(double p, int ch) const {
    int64_t i = (int64_t)p;
    if (!track || i < 0 || i >= track->frames) return 0.0f;
    return track->pcm[(size_t)(i * 2 + ch)] * (1.0f / 32768.0f);
}

double Deck::beatAt(double sourceFrames) const {
    if (!hasGrid()) return 0;
    return (sourceFrames / track->sampleRate - firstBeatSec) * gridBpm / 60.0;
}

void Deck::pull(float* l, float* r, double* tag, int n) {
    for (int i = 0; i < n; ++i) {
        if (looping && loopLen > 0 && pos >= loopStart + loopLen) {
            xfadeOldPos = pos;
            xfadeLeft = kXfade;
            pos = loopStart + std::fmod(pos - loopStart, loopLen);
        }
        float sl = sample(pos, 0), sr = sample(pos, 1);
        if (xfadeLeft > 0) {
            float w = (float)xfadeLeft / kXfade;
            sl = sl * (1 - w) + sample(xfadeOldPos, 0) * w;
            sr = sr * (1 - w) + sample(xfadeOldPos, 1) * w;
            xfadeOldPos += 1; --xfadeLeft;
        }
        l[i] = sl; r[i] = sr;
        tag[i] = (looping && slip) ? shadowPos : pos;
        pos += 1; shadowPos += 1;
    }
}

void Deck::configurePipeline() {
    useStretch = keylock && !braking && (synced || pitchSemis != 0.0 || std::fabs(userRate - 1.0) > 1e-4);
    resampler.src = useStretch ? static_cast<FrameSource*>(&stretch) : static_cast<FrameSource*>(this);
    stretch.src = this;
}

void Deck::flushPipeline() {
    xfadeLeft = 0;
    stretch.reset();
    resampler.reset();
}

void Deck::requestReconfigure(double seekTo) {
    if (playing && fade > 0) {
        pendingReconfig = true;
        pendingSeekPos = seekTo;
        fadeDir = -1;
        stopAfterFade = false;
    } else {
        if (seekTo >= 0) { pos = seekTo; audible = seekTo; shadowPos = seekTo; }
        else { pos = std::round(audible); shadowPos = pos; }
        configurePipeline();
        flushPipeline();
    }
}

void Deck::finishReconfigure() {
    pendingReconfig = false;
    double p = pendingSeekPos >= 0 ? pendingSeekPos : std::round(audible);
    pendingSeekPos = -1;
    if (!(looping && slip)) { pos = p; shadowPos = p; }
    else pos = std::round(p);
    audible = p;
    configurePipeline();
    flushPipeline();
    fadeDir = +1;
}

// ------------------------------------------------------------------------------------------ Engine

Engine::Engine() { setSampleRate(48000); }

// Must only be called while no audio callback is running (before start / after the stream closed).
// Preserves deck state and parameter targets so playback resumes seamlessly after a route change
// (e.g. Bluetooth headphones at 44.1 kHz replacing the 48 kHz speaker).
void Engine::setSampleRate(int sr) {
    bool first = !initialised_;
    if (!first && sr == sr_) return;
    float saved[kNumDecks][16];
    for (int d = 0; d < kNumDecks; ++d) std::memcpy(saved[d], decks_[d].paramTarget, sizeof(saved[d]));
    sr_ = sr;
    for (auto& d : decks_) {
        if (first) d.init((float)sr);
        else {
            d.volume.setTime(0.008f, (float)sr); d.trim.setTime(0.02f, (float)sr);
            d.eq.init((float)sr); d.filter.init((float)sr); d.echo.init((float)sr); d.flanger.init((float)sr); d.reverb.init((float)sr);
            d.resampler.reset(); d.stretch.reset();
        }
    }
    masterVol_.setTime(0.02f, sr); masterVol_.reset(0.9f);
    duck_.setTime(0.12f, sr); duck_.reset(0);
    xfL_.setTime(0.01f, sr); xfL_.reset(1); xfR_.setTime(0.01f, sr); xfR_.reset(1);
    masterFilter_.init((float)sr);
    limiter_.init((float)sr);
    drums_.init((float)sr);
    riser_.init((float)sr);
    impact_.init((float)sr);
    meterCoef_ = 1.0f - std::exp(-1.0f / (0.03f * sr));
    lowF_.setLowpass((float)sr, 150, 0.707f);
    midHp_.setHighpass((float)sr, 150, 0.707f); midLp_.setLowpass((float)sr, 3000, 0.707f);
    highF_.setHighpass((float)sr, 4000, 0.707f);
    for (int b = 0; b < kNumBands; ++b) {
        float f = 40.0f * std::pow(14000.0f / 40.0f, (float)b / (kNumBands - 1));
        bandFilters_[b].setBandpass((float)sr, f, 2.0f);
    }
    if (!first) {
        for (int d = 0; d < kNumDecks; ++d) for (int p = 0; p < 16; ++p) setParam(p, d, saved[d][p]);
        // The volume smoother would otherwise ramp from 0.
        for (auto& d : decks_) { d.volume.reset(d.paramTarget[P_DECK_VOLUME]); }
        masterVol_.reset(masterVolT_); masterFilter_.knob.reset(masterFilterT_);
        drums_.level.reset(drumLevelT_); drums_.bassLevel.reset(drumBassT_);
        riser_.level.reset(riserLevelT_); riser_.pitch.reset(riserPitchT_);
    }
    initialised_ = true;
}

uint64_t Engine::push(Command c) {
    std::lock_guard<std::mutex> lk(pushMutex_);
    uint32_t tail = qTail_.load(std::memory_order_relaxed);
    uint32_t next = (tail + 1) % kQueue;
    if (next == qHead_.load(std::memory_order_acquire)) return 0;  // full: drop (caller may retry)
    c.seq = nextSeq_++;
    queue_[tail] = c;
    qTail_.store(next, std::memory_order_release);
    return c.seq;
}

void Engine::drainCommands() {
    uint32_t head = qHead_.load(std::memory_order_relaxed);
    uint32_t tail = qTail_.load(std::memory_order_acquire);
    while (head != tail) {
        apply(queue_[head]);
        appliedSeq_ = queue_[head].seq;
        head = (head + 1) % kQueue;
    }
    qHead_.store(head, std::memory_order_release);
}

void Engine::startDeck(Deck& d, double posSec) {
    if (!d.track) return;
    double p = std::round(std::max(0.0, posSec) * d.track->sampleRate);
    d.pos = p; d.shadowPos = p; d.audible = p;
    d.looping = false; d.ended = false; d.braking = false; d.brakeFactor = 1.0; d.phaseErrFilt = 0;
    d.pendingReconfig = false; d.pendingSeekPos = -1;
    d.configurePipeline();
    d.flushPipeline();
    d.playing = true;
    d.fade = 0; d.fadeDir = +1; d.stopAfterFade = false;
}

void Engine::anchorTo(int deck) {
    Deck& d = decks_[deck];
    if (!d.hasGrid()) return;
    double db = d.beatAt(d.audible);
    masterBeat_ += wrapHalf(db - masterBeat_);
    masterBpm_ = d.synced ? masterBpm_ : d.gridBpm * d.userRate;
}

void Engine::setParam(int param, int deckIdx, float v) {
    if (param < 16) {
        if (deckIdx < 0 || deckIdx >= kNumDecks) return;
        Deck& d = decks_[deckIdx];
        d.paramTarget[param] = v;
        switch (param) {
            case P_DECK_VOLUME: d.volume.target = clampf(v, 0, 1); break;
            case P_TRIM: d.trim.target = clampf(v, 0, 2); break;
            case P_EQ_LOW: d.eq.gl.target = clampf(v, 0, 1.6f); break;
            case P_EQ_MID: d.eq.gm.target = clampf(v, 0, 1.6f); break;
            case P_EQ_HIGH: d.eq.gh.target = clampf(v, 0, 1.6f); break;
            case P_FILTER: d.filter.knob.target = clampf(v, -1, 1); break;
            case P_ECHO_MIX: d.echo.mix.target = clampf(v, 0, 1); break;
            case P_ECHO_FEEDBACK: d.echo.feedback.target = clampf(v, 0, 0.92f); break;
            case P_ECHO_BEATS: d.echoBeats = clampf(v, 0.125f, 4); break;
            case P_REVERB_MIX: d.reverb.mix.target = clampf(v, 0, 1); break;
            case P_FLANGER_MIX: d.flanger.mix.target = clampf(v, 0, 1); break;
            default: break;
        }
        return;
    }
    switch (param) {
        case P_CROSSFADER: crossfader_ = clampf(v, -1, 1); break;
        case P_MASTER_BPM: masterBpm_ = std::min(250.0, std::max(40.0, (double)v)); break;
        case P_DRUM_LEVEL: drumLevelT_ = clampf(v, 0, 1); drums_.level.target = drumLevelT_; break;
        case P_DRUM_BASS_LEVEL: drumBassT_ = clampf(v, 0, 1); drums_.bassLevel.target = drumBassT_; break;
        case P_RISER_LEVEL: riserLevelT_ = clampf(v, 0, 1); riser_.level.target = riserLevelT_; break;
        case P_RISER_PITCH: riserPitchT_ = clampf(v, 0, 1); riser_.pitch.target = riserPitchT_; break;
        case P_MASTER_FILTER: masterFilterT_ = clampf(v, -1, 1); masterFilter_.knob.target = masterFilterT_; break;
        case P_MASTER_VOLUME: masterVolT_ = clampf(v, 0, 1); masterVol_.target = masterVolT_; break;
        case P_DUCK: duckT_ = clampf(v, 0, 1); duck_.target = duckT_; break;
        default: break;
    }
}

float Engine::getParam(int param, int deck) const {
    if (param < 16) return (deck >= 0 && deck < kNumDecks) ? decks_[deck].paramTarget[param] : 0;
    switch (param) {
        case P_CROSSFADER: return crossfader_;
        case P_MASTER_BPM: return (float)masterBpm_;
        case P_DRUM_LEVEL: return drumLevelT_;
        case P_DRUM_BASS_LEVEL: return drumBassT_;
        case P_RISER_LEVEL: return riserLevelT_;
        case P_RISER_PITCH: return riserPitchT_;
        case P_MASTER_FILTER: return masterFilterT_;
        case P_MASTER_VOLUME: return masterVolT_;
        case P_DUCK: return duckT_;
        default: return 0;
    }
}

void Engine::apply(const Command& c) {
    int di = (c.deck >= 0 && c.deck < kNumDecks) ? c.deck : 0;
    Deck& d = decks_[di];
    switch (c.type) {
        case C_LOAD:
            d.playing = false; d.fade = 0; d.fadeDir = 0;
            d.track = c.track;
            d.gridBpm = c.d0; d.firstBeatSec = c.d1;
            d.pos = d.audible = d.shadowPos = 0;
            d.ended = false; d.looping = false; d.braking = false; d.brakeFactor = 1;
            d.pitchSemis = 0; d.userRate = 1;
            if (c.track) d.stretch.setRate((float)c.track->sampleRate);
            d.configurePipeline(); d.flushPipeline();
            break;
        case C_UNLOAD: d.playing = false; d.track = nullptr; d.fade = 0; d.fadeDir = 0; break;
        case C_PLAY: {
            double at = c.i1 ? c.d0 : (d.track ? d.audible / d.track->sampleRate : 0);
            startDeck(d, at);
            bool alone = true;
            for (int k = 0; k < kNumDecks; ++k) if (k != di && decks_[k].playing) alone = false;
            if (c.i0 && alone) anchorTo(di);
            break;
        }
        case C_PAUSE: if (d.playing) { d.fadeDir = -1; d.stopAfterFade = true; } break;
        case C_SEEK: if (d.track) d.requestReconfigure(std::round(c.d0 * d.track->sampleRate)); break;
        case C_SET_PARAM: setParam(c.i0, c.deck, (float)c.d0); break;
        case C_SET_SYNC:
            if (d.synced != (c.i0 != 0)) { d.synced = c.i0 != 0; d.requestReconfigure(); }
            break;
        case C_SET_KEYLOCK:
            if (d.keylock != (c.i0 != 0)) { d.keylock = c.i0 != 0; d.requestReconfigure(); }
            break;
        case C_SET_PITCH: {
            double v = std::min(6.0, std::max(-6.0, c.d0));
            bool modeChange = (v == 0.0) != (d.pitchSemis == 0.0);
            d.pitchSemis = v;
            if (modeChange) d.requestReconfigure();
            break;
        }
        case C_SET_RATE: {
            double v = std::min(1.5, std::max(0.5, c.d0));
            bool modeChange = (std::fabs(v - 1) > 1e-4) != (std::fabs(d.userRate - 1) > 1e-4);
            d.userRate = v;
            if (modeChange && !d.synced) d.requestReconfigure();
            break;
        }
        case C_SET_GRID: d.gridBpm = c.d0; d.firstBeatSec = c.d1; break;
        case C_SET_LOOP: executeAction(ActionEvent{A_SET_LOOP, di, masterBeat_, c.d0, (double)c.i0, true}); break;
        case C_EXIT_LOOP: executeAction(ActionEvent{A_EXIT_LOOP, di, masterBeat_, 0, 0, true}); break;
        case C_BRAKE: executeAction(ActionEvent{A_BRAKE, di, masterBeat_, c.d0, 0, true}); break;
        case C_RESET_DECK: executeAction(ActionEvent{A_RESET_DECK, di, masterBeat_, 0, 0, true}); break;
        case C_IMPACT: impact_.trigger(); break;
        case C_ANCHOR: anchorTo(di); break;
        case C_ADD_RAMP:
            for (auto& r : ramps_) if (!r.active) {
                r.param = c.i0; r.deck = c.deck; r.start = c.d0; r.end = std::max(c.d0, c.d1);
                r.hasFrom = !std::isnan(c.d2); r.from = (float)c.d2; r.to = (float)c.d3; r.curve = c.i1;
                r.started = false; r.active = true;
                break;
            }
            break;
        case C_ADD_ACTION:
            for (auto& a : actions_) if (!a.active) {
                a.type = c.i0; a.deck = c.deck; a.beat = c.d0; a.a0 = c.d1; a.a1 = c.d2; a.active = true;
                break;
            }
            break;
        case C_CLEAR_AUTOMATION:
            for (auto& r : ramps_) r.active = false;
            for (auto& a : actions_) a.active = false;
            break;
        case C_CLEAR_DECK_AUTOMATION:
            for (auto& r : ramps_) if (r.deck == c.deck && r.param < 16) r.active = false;
            for (auto& a : actions_) if (a.deck == c.deck) a.active = false;
            break;
        case C_DRUM_STEP:
            if (c.i0 >= 0 && c.i0 < DrumMachine::kMaxPatterns && c.i1 >= 0 && c.i1 < kNumVoices) {
                int step = (int)c.d0;
                if (step >= 0 && step < 16) drums_.patterns[c.i0].steps[c.i1][step] = (float)c.d1;
            }
            break;
        case C_DRUM_SWING:
            if (c.i0 >= 0 && c.i0 < DrumMachine::kMaxPatterns) drums_.patterns[c.i0].swing = (float)c.d0;
            break;
        case C_DRUM_PATTERN: if (c.i0 >= 0 && c.i0 < DrumMachine::kMaxPatterns) drums_.active = c.i0; break;
        case C_DRUM_ROOT: drums_.rootMidi = c.i0; break;
        default: break;
    }
}

void Engine::executeAction(const ActionEvent& a) {
    int di = (a.deck >= 0 && a.deck < kNumDecks) ? a.deck : 0;
    Deck& d = decks_[di];
    switch (a.type) {
        case A_START: startDeck(d, a.a0); break;
        case A_STOP: if (d.playing) { d.fadeDir = -1; d.stopAfterFade = true; } break;
        case A_SEEK: if (d.track) d.requestReconfigure(std::round(a.a0 * d.track->sampleRate)); break;
        case A_SET_LOOP: {
            if (!d.track || a.a0 <= 0) break;
            double lenBeats = a.a0;
            double start;
            if (d.hasGrid()) {
                double q = std::min(1.0, lenBeats);
                double b = std::round(d.beatAt(d.audible) / q) * q;
                start = (d.firstBeatSec + b * 60.0 / d.gridBpm) * d.track->sampleRate;
                d.loopLen = lenBeats * d.beatPeriodFrames();
            } else {
                start = d.audible;
                d.loopLen = lenBeats * 60.0 / masterBpm_ * d.track->sampleRate;
            }
            bool slip = a.a1 > 0.5;
            if (!d.looping) { d.shadowPos = d.pos; }
            d.loopStart = std::round(std::max(0.0, start));
            d.loopLen = std::max(16.0, std::round(d.loopLen));
            d.slip = slip;
            d.looping = true;
            if (d.pos - d.loopStart > d.loopLen) {
                // Reader already beyond the loop (tiny loop): wrap with a declick crossfade.
                d.xfadeOldPos = d.pos; d.xfadeLeft = Deck::kXfade;
                d.pos = d.loopStart + std::fmod(d.pos - d.loopStart, d.loopLen);
            }
            break;
        }
        case A_EXIT_LOOP:
            if (d.looping) {
                d.looping = false;
                if (d.slip) { d.xfadeOldPos = d.pos; d.xfadeLeft = Deck::kXfade; d.pos = d.shadowPos; }
            }
            break;
        case A_BRAKE: {
            if (!d.playing) break;
            double frames = std::max(1.0, a.a0) * 60.0 / masterBpm_ * sr_;
            d.braking = true; d.brakeFactor = 1.0; d.brakeDecPerFrame = 1.0 / frames;
            d.synced = false;
            d.requestReconfigure();
            break;
        }
        case A_IMPACT: impact_.trigger(); break;
        case A_RESET_DECK: d.resetParams(); d.looping = false; d.pitchSemis = 0; break;
        case A_SET_SYNC: {
            bool s = a.a0 > 0.5;
            if (d.synced != s) { d.synced = s; d.requestReconfigure(); }
            break;
        }
        case A_DRUM_PATTERN: { int p = (int)a.a0; if (p >= 0 && p < DrumMachine::kMaxPatterns) drums_.active = p; break; }
        case A_ANCHOR: anchorTo(di); break;
        default: break;
    }
}

void Engine::evaluateRamps() {
    for (auto& r : ramps_) {
        if (!r.active || masterBeat_ < r.start) continue;
        if (!r.started) {
            r.started = true;
            if (!r.hasFrom) { r.from = getParam(r.param, r.deck); r.hasFrom = true; }
        }
        float t = r.end > r.start ? (float)((masterBeat_ - r.start) / (r.end - r.start)) : 1.0f;
        float v = r.from + (r.to - r.from) * shape(r.curve, t);
        setParam(r.param, r.deck, v);
        if (t >= 1.0f) r.active = false;
    }
}

void Engine::render(float* out, int frames) {
    auto t0 = std::chrono::steady_clock::now();
    drainCommands();
    int done = 0;
    while (done < frames) {
        int chunk = std::min(kChunk, frames - done);
        const double bpf = masterBpm_ / 60.0 / sr_;
        // Find the earliest due action; split the chunk so it fires on its exact frame.
        int fireIdx = -1; double fireBeat = 1e300;
        for (int i = 0; i < kMaxActions; ++i) {
            const auto& a = actions_[i];
            if (a.active && a.beat < fireBeat) { fireBeat = a.beat; fireIdx = i; }
        }
        if (fireIdx >= 0) {
            double until = (fireBeat - masterBeat_) / bpf;
            if (until <= 0.0) {
                ActionEvent ev = actions_[fireIdx];
                actions_[fireIdx].active = false;
                executeAction(ev);
                continue;
            }
            if (until < chunk) chunk = std::max(1, (int)std::ceil(until));
        }
        evaluateRamps();
        renderChunk(out + done * 2, chunk);
        done += chunk;
    }
    framesRendered_ += frames;
    auto el = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    float load = (float)(el / ((double)frames / sr_));
    cpuLoad_ = cpuLoad_ * 0.95f + load * 0.05f;
    publish();
}

void Engine::renderChunk(float* out, int n) {
    std::memset(out, 0, sizeof(float) * n * 2);
    // Crossfader ("dipless" curve: both decks full at centre).
    xfL_.target = std::min(1.0f, 1.0f - crossfader_);
    xfR_.target = std::min(1.0f, 1.0f + crossfader_);
    const double bpf = masterBpm_ / 60.0 / sr_;

    for (int di = 0; di < kNumDecks; ++di) {
        Deck& d = decks_[di];
        bool active = d.playing && d.track;
        if (active) {
            double base;
            if (d.synced && d.hasGrid()) {
                base = masterBpm_ / d.gridBpm;
                bool fractionalLoop = d.looping && !d.slip && d.loopLen < d.beatPeriodFrames() * 0.99;
                if (!fractionalLoop && !d.braking) {
                    double err = wrapHalf(d.beatAt(d.audible) - masterBeat_);
                    d.phaseErr = err;
                    // WSOLA picks each segment within a ±15 ms search window, so the instantaneous position tag
                    // jitters around the true timeline. Track the average (≈0.25 s time constant) and correct
                    // only that, with a small deadband, instead of chasing the jitter.
                    double a = std::min(1.0, (double)n / (0.25 * sr_));
                    if (std::fabs(err - d.phaseErrFilt) > 0.25) d.phaseErrFilt = err;  // re-seed after a jump/seek
                    else d.phaseErrFilt += (err - d.phaseErrFilt) * a;
                    double e = d.phaseErrFilt;
                    if (std::fabs(e) < 0.0015) e = 0;
                    // P-controller: nudge tempo by up to ±2% to pull the deck onto the master phase.
                    base *= 1.0 - std::min(0.02, std::max(-0.02, e * 0.8));
                }
            } else {
                base = d.userRate;
            }
            if (d.braking) base *= std::max(0.0, d.brakeFactor);
            d.contentRate = base;
            double srRatio = (double)d.track->sampleRate / sr_;
            double resRate;
            if (d.useStretch) {
                double p = std::pow(2.0, d.pitchSemis / 12.0);
                d.stretch.setTempo(base / p);
                resRate = p * srRatio;
            } else {
                resRate = std::max(1e-4, base * srRatio);
            }
            d.resampler.process(bufL_, bufR_, bufT_, n, resRate);
            d.audible = bufT_[n - 1];
            if (d.braking) {
                d.brakeFactor -= d.brakeDecPerFrame * n;
                if (d.brakeFactor <= 0.0) { d.braking = false; d.playing = false; d.fade = 0; d.fadeDir = 0; }
            }
            if (d.audible >= d.track->frames && !d.looping) { d.ended = true; d.fadeDir = -1; d.stopAfterFade = true; }
        } else {
            std::memset(bufL_, 0, sizeof(float) * n);
            std::memset(bufR_, 0, sizeof(float) * n);
        }

        float vuAcc = 0;
        float xf = 1;
        double mb = masterBeat_;
        d.echo.setBeats(d.echoBeats, masterBpm_);
        for (int i = 0; i < n; ++i) {
            float l = bufL_[i], r = bufR_[i];
            if (d.fadeDir != 0) {
                d.fade += d.fadeDir * kFadeStep;
                if (d.fade >= 1) { d.fade = 1; d.fadeDir = 0; }
                if (d.fade <= 0) {
                    d.fade = 0; d.fadeDir = 0;
                    if (d.stopAfterFade) { d.playing = false; d.stopAfterFade = false; }
                    if (d.pendingReconfig) d.finishReconfigure();
                }
            }
            float g = (active ? d.fade : 0.0f) * d.trim.next();
            l *= g; r *= g;
            if (active) { d.eq.process(l, r); d.filter.process(l, r); }
            else { d.eq.gl.next(); d.eq.gm.next(); d.eq.gh.next(); d.filter.knob.next(); }
            float vol = d.volume.next();
            xf = di == 0 ? xfL_.value : xfR_.value;
            l *= vol * xf; r *= vol * xf;
            vuAcc += l * l + r * r;
            d.flanger.process(l, r, mb);
            d.echo.process(l, r);
            d.reverb.process(l, r);
            out[2 * i] += l; out[2 * i + 1] += r;
            mb += bpf;
        }
        d.vu = d.vu * 0.8f + 0.2f * std::sqrt(vuAcc / (2 * n));
    }

    // Remix generators + master chain.
    for (int i = 0; i < n; ++i) {
        xfL_.next(); xfR_.next();
        float l = out[2 * i], r = out[2 * i + 1];
        float dl = 0, dr = 0;
        drums_.process(masterBeat_, masterBpm_, dl, dr);
        l += dl; r += dr;
        riser_.process(l, r);
        impact_.process(l, r);
        masterFilter_.process(l, r);
        float gain = masterVol_.next() * (1.0f - 0.75f * duck_.next());
        l *= gain; r *= gain;
        limiter_.process(l, r);
        out[2 * i] = l; out[2 * i + 1] = r;
        masterBeat_ += bpf;

        // Meters (mono).
        float m = 0.5f * (l + r);
        float c = meterCoef_;
        rmsEnv_ += c * (m * m - rmsEnv_);
        float pk = std::max(std::fabs(l), std::fabs(r));
        peak_ = pk > peak_ ? pk : peak_ * 0.9998f;
        float lo = lowF_.process(m, 0); lowEnv_ += c * (lo * lo - lowEnv_);
        float mi = midLp_.process(midHp_.process(m, 0), 0); midEnv_ += c * (mi * mi - midEnv_);
        float hi = highF_.process(m, 0); highEnv_ += c * (hi * hi - highEnv_);
        for (int b = 0; b < kNumBands; ++b) {
            float y = bandFilters_[b].process(m, 0);
            bandEnv_[b] += c * (y * y - bandEnv_[b]);
        }
    }
}

void Engine::publish() {
    uint32_t s = snapSeq_.load(std::memory_order_relaxed);
    snapSeq_.store(s + 1, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
    double* o = snap_;
    o[0] = masterBeat_; o[1] = masterBpm_; o[2] = (double)framesRendered_; o[3] = sr_; o[4] = cpuLoad_;
    o[5] = xruns_.load(std::memory_order_relaxed); o[6] = limiter_.gr; o[7] = std::sqrt(rmsEnv_); o[8] = peak_;
    o[9] = std::sqrt(lowEnv_); o[10] = std::sqrt(midEnv_); o[11] = std::sqrt(highEnv_);
    int pending = 0;
    for (auto& a : actions_) if (a.active) ++pending;
    for (auto& r : ramps_) if (r.active) ++pending;
    o[12] = pending; o[13] = (double)appliedSeq_;
    for (int b = 0; b < kNumBands; ++b) o[14 + b] = std::sqrt(bandEnv_[b]);
    for (int di = 0; di < kNumDecks; ++di) {
        const Deck& d = decks_[di];
        double* q = o + 32 + di * 16;
        double tsr = d.track ? d.track->sampleRate : 44100.0;
        q[0] = d.audible / tsr; q[1] = d.playing; q[2] = d.ended; q[3] = d.looping; q[4] = d.synced;
        q[5] = d.contentRate; q[6] = d.gridBpm * d.contentRate; q[7] = d.vu; q[8] = d.track ? d.track->id : 0;
        q[9] = d.track ? (double)d.track->frames / tsr : 0; q[10] = d.keylock; q[11] = d.pitchSemis;
        q[12] = (d.looping && d.hasGrid()) ? d.loopLen / d.beatPeriodFrames() : 0; q[13] = d.phaseErrFilt;
        q[14] = d.volume.value; q[15] = d.useStretch;
        double* x = o + 64 + di * 8;  // live mixer values (for the 3D console)
        x[0] = d.paramTarget[P_EQ_LOW]; x[1] = d.paramTarget[P_EQ_MID]; x[2] = d.paramTarget[P_EQ_HIGH];
        x[3] = d.paramTarget[P_FILTER]; x[4] = d.paramTarget[P_ECHO_MIX]; x[5] = d.paramTarget[P_REVERB_MIX];
        x[6] = d.paramTarget[P_FLANGER_MIX]; x[7] = d.paramTarget[P_DECK_VOLUME];
    }
    o[80] = crossfader_; o[81] = drumLevelT_; o[82] = riserLevelT_; o[83] = masterFilterT_; o[84] = masterVolT_; o[85] = duckT_;
    std::atomic_thread_fence(std::memory_order_release);
    snapSeq_.store(s + 2, std::memory_order_release);
}

void Engine::readState(double* out) {
    for (int attempt = 0; attempt < 64; ++attempt) {
        uint32_t a = snapSeq_.load(std::memory_order_acquire);
        if (a & 1u) continue;
        std::memcpy(out, snap_, sizeof(snap_));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (snapSeq_.load(std::memory_order_relaxed) == a) return;
    }
    std::memcpy(out, snap_, sizeof(snap_));  // best effort
}

std::shared_ptr<TrackBuffer> Engine::allocTrack(int64_t capacityFrames, int sampleRate) {
    auto t = std::make_shared<TrackBuffer>();
    t->pcm.assign((size_t)capacityFrames * 2, 0);
    t->capacityFrames = capacityFrames;
    t->sampleRate = sampleRate;
    std::lock_guard<std::mutex> lk(trackMutex_);
    t->id = nextTrackId_++;
    tracks_[t->id] = t;
    return t;
}

std::shared_ptr<TrackBuffer> Engine::getTrack(int id) {
    std::lock_guard<std::mutex> lk(trackMutex_);
    auto it = tracks_.find(id);
    return it == tracks_.end() ? nullptr : it->second;
}

void Engine::releaseTrack(int id) {
    std::lock_guard<std::mutex> lk(trackMutex_);
    pendingRelease_.push_back(id);
}

void Engine::collectGarbage() {
    double st[kStateSize];
    readState(st);
    uint64_t applied = (uint64_t)st[13];
    std::lock_guard<std::mutex> lk(trackMutex_);
    for (auto it = pendingRelease_.begin(); it != pendingRelease_.end();) {
        int id = *it;
        bool inUse = false;
        for (int di = 0; di < kNumDecks; ++di) if ((int)st[32 + di * 16 + 8] == id) inUse = true;
        // A load command referencing it may still be queued: only free once that command has been applied.
        auto t = tracks_.find(id);
        uint64_t loadSeq = t == tracks_.end() ? 0 : t->second->loadSeq;
        if (!inUse && applied >= loadSeq) { tracks_.erase(id); it = pendingRelease_.erase(it); }
        else ++it;
    }
}

}  // namespace aidj
