// Host-side tests for the native DJ engine (same sources as the Android build, minus Oboe/JNI).
// Build & run: native-tests/run.sh
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <limits>
#include <string>
#include <vector>

#include "../dj-audio-engine/src/main/cpp/engine.h"

using namespace aidj;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("  FAIL: " __VA_ARGS__); std::printf("\n"); ++failures; } } while (0)

static const int kSr = 48000;

struct Out {
    std::vector<float> l, r;
    std::vector<double> beatAtFrame;  // master beat at the first frame of each 256-frame block
};

static void renderSeconds(Engine& e, double secs, Out* out = nullptr) {
    int total = (int)(secs * kSr);
    std::vector<float> buf(256 * 2);
    double st[kStateSize];
    for (int done = 0; done < total; done += 256) {
        if (out) { e.readState(st); out->beatAtFrame.push_back(st[0]); }
        e.render(buf.data(), 256);
        if (out) for (int i = 0; i < 256; ++i) { out->l.push_back(buf[2 * i]); out->r.push_back(buf[2 * i + 1]); }
    }
}

// A synthetic drum track: kick on every beat (short sine burst), optional tone.
static std::shared_ptr<TrackBuffer> makeKickTrack(Engine& e, double bpm, double firstBeat, double secs, int sr = 44100, double toneHz = 0) {
    int64_t frames = (int64_t)(secs * sr);
    auto t = e.allocTrack(frames, sr);
    t->frames = frames;
    double period = 60.0 / bpm;
    for (int64_t i = 0; i < frames; ++i) {
        double ts = (double)i / sr;
        double v = 0;
        double k = (ts - firstBeat) / period;
        if (k >= 0) {
            double dt = (k - std::floor(k)) * period;
            if (dt < 0.08) v += 0.8 * std::sin(2 * M_PI * 60 * dt) * std::exp(-dt * 40);
        }
        if (toneHz > 0) v += 0.5 * std::sin(2 * M_PI * toneHz * ts);
        int16_t s = (int16_t)std::max(-32767.0, std::min(32767.0, v * 32767));
        t->pcm[2 * i] = s; t->pcm[2 * i + 1] = s;
    }
    return t;
}

static Command cmd(int type, int deck = 0) { Command c; c.type = type; c.deck = deck; return c; }

static void load(Engine& e, int deck, const std::shared_ptr<TrackBuffer>& t, double bpm, double first) {
    Command c = cmd(C_LOAD, deck); c.track = t.get(); c.d0 = bpm; c.d1 = first; t->loadSeq = e.push(c);
}
static void param(Engine& e, int p, int deck, double v) { Command c = cmd(C_SET_PARAM, deck); c.i0 = p; c.d0 = v; e.push(c); }
static void action(Engine& e, int type, int deck, double beat, double a0 = 0, double a1 = 0) {
    Command c = cmd(C_ADD_ACTION, deck); c.i0 = type; c.d0 = beat; c.d1 = a0; c.d2 = a1; e.push(c);
}
static void ramp(Engine& e, int p, int deck, double s, double en, double to, double from = std::numeric_limits<double>::quiet_NaN(), int curve = 0) {
    Command c = cmd(C_ADD_RAMP, deck); c.i0 = p; c.d0 = s; c.d1 = en; c.d2 = from; c.d3 = to; c.i1 = curve; e.push(c);
}

// Estimate frequency by counting positive zero crossings over a window.
static double freqOf(const std::vector<float>& x, size_t from, size_t len) {
    int cross = 0; size_t first = 0, last = 0;
    for (size_t i = from + 1; i < from + len && i < x.size(); ++i) {
        if (x[i - 1] <= 0 && x[i] > 0) { if (!cross) first = i; last = i; ++cross; }
    }
    if (cross < 2) return 0;
    return (double)(cross - 1) * kSr / (double)(last - first);
}

static void testSyncAccuracy() {
    std::printf("[sync] beatmatch 128 BPM track to a 124 BPM master (key-locked) and check onset alignment\n");
    Engine e; e.setSampleRate(kSr);
    auto a = makeKickTrack(e, 124.0, 0.25, 60);
    auto b = makeKickTrack(e, 128.0, 0.61, 60);
    load(e, 0, a, 124.0, 0.25);
    load(e, 1, b, 128.0, 0.61);
    // Play A and anchor the master clock to it, then lock A to the clock.
    Command p = cmd(C_PLAY, 0); p.i0 = 1; p.i1 = 1; p.d0 = 0.25; e.push(p);
    Command s = cmd(C_SET_SYNC, 0); s.i0 = 1; e.push(s);
    renderSeconds(e, 2.0);
    double st[kStateSize]; e.readState(st);
    double startBeat = std::ceil(st[0]) + 4;
    // B enters synced on an exact master beat at its own beat 8; A is muted to observe B alone.
    action(e, A_SET_SYNC, 1, startBeat - 0.001, 1);
    action(e, A_START, 1, startBeat, 0.61 + 8 * 60.0 / 128.0);
    ramp(e, P_DECK_VOLUME, 0, startBeat, startBeat, 0.0, 1.0, CURVE_STEP);
    Out o;
    renderSeconds(e, 20.0, &o);
    e.readState(st);
    std::printf("  master bpm=%.3f deckB bpm=%.3f rate=%.4f phaseErr=%.5f beats stretch=%d\n", st[1], st[32 + 16 + 6], st[32 + 16 + 5], st[32 + 16 + 13], (int)st[32 + 16 + 15]);
    CHECK(std::fabs(st[32 + 16 + 6] - 124.0) < 0.3, "deck B effective bpm %.3f", st[32 + 16 + 6]);
    // Onset detection on output: rising edge of |x| above threshold after silence.
    std::vector<double> onsets;
    double env = 0; bool armed = true;
    for (size_t i = 0; i < o.l.size(); ++i) {
        double v = std::fabs(o.l[i]);
        env = std::max(v, env * 0.999);
        if (armed && v > 0.05) { onsets.push_back((double)i); armed = false; }
        if (!armed && env < 0.01) armed = true;
    }
    // Expected master beat frames: integer master beats within the window.
    double beat0 = o.beatAtFrame.front();
    double bpf = 124.0 / 60.0 / kSr;
    double worst = 0, sum = 0; int n = 0;
    for (double on : onsets) {
        double beat = beat0 + on * bpf;
        if (on < kSr * 2) continue;  // let the controller settle
        double frac = beat - std::round(beat);
        // Kick burst onset vs. beat: the sine starts at the beat (phase 0); threshold crossing lags slightly.
        double ms = frac / bpf / kSr * 1000.0;
        worst = std::max(worst, std::fabs(ms)); sum += ms; ++n;
    }
    std::printf("  %d onsets checked, mean offset %.2f ms, worst %.2f ms\n", n, n ? sum / n : 0, worst);
    CHECK(n > 25, "too few onsets (%d)", n);
    CHECK(worst < 3.0, "beat alignment worst error %.2f ms", worst);
}

static void testKeylockAndPitch() {
    std::printf("[keylock] tempo change must keep pitch; pitch shift must move it by semitones\n");
    struct Case { bool keylock; double rate; double semis; double expect; const char* name; };
    Case cases[] = {
        {true, 1.06, 0, 440.0, "keylock +6% tempo"},
        {true, 0.94, 0, 440.0, "keylock -6% tempo"},
        {true, 1.0, 2, 440.0 * std::pow(2.0, 2 / 12.0), "pitch +2 st"},
        {true, 1.03, -1, 440.0 * std::pow(2.0, -1 / 12.0), "tempo +3% & pitch -1 st"},
        {false, 1.06, 0, 440.0 * 1.06, "varispeed +6%"},
    };
    for (auto& c : cases) {
        Engine e; e.setSampleRate(kSr);
        auto t = makeKickTrack(e, 120, 1000.0, 30, 44100, 440.0);  // no kicks (first beat beyond end): pure tone
        load(e, 0, t, 120, 0);
        Command k = cmd(C_SET_KEYLOCK, 0); k.i0 = c.keylock; e.push(k);
        Command r = cmd(C_SET_RATE, 0); r.d0 = c.rate; e.push(r);
        Command ps = cmd(C_SET_PITCH, 0); ps.d0 = c.semis; e.push(ps);
        Command p = cmd(C_PLAY, 0); p.i1 = 1; p.d0 = 0; e.push(p);
        Out o; renderSeconds(e, 3.0, &o);
        double f = freqOf(o.l, kSr, kSr);
        double st[kStateSize]; e.readState(st);
        double posExpect = 3.0 * c.rate;
        std::printf("  %-26s freq=%.2f Hz (expect %.2f)  pos=%.3fs (expect ~%.3f)\n", c.name, f, c.expect, st[32], posExpect);
        CHECK(std::fabs(f - c.expect) / c.expect < 0.006, "%s frequency %.2f vs %.2f", c.name, f, c.expect);
        CHECK(std::fabs(st[32] - posExpect) < 0.08, "%s position %.3f vs %.3f", c.name, st[32], posExpect);
    }
}

static void testLoopLimiterEchoAutomation() {
    std::printf("[loop/limiter/echo/automation]\n");
    Engine e; e.setSampleRate(kSr);
    auto a = makeKickTrack(e, 120, 0.0, 40, 44100, 220.0);
    auto b = makeKickTrack(e, 120, 0.0, 40, 44100, 330.0);
    load(e, 0, a, 120, 0); load(e, 1, b, 120, 0);
    param(e, P_TRIM, 0, 2.0); param(e, P_TRIM, 1, 2.0);
    Command p = cmd(C_PLAY, 0); p.i0 = 1; p.i1 = 1; p.d0 = 0; e.push(p);
    Command p2 = cmd(C_PLAY, 1); p2.i1 = 1; p2.d0 = 0; e.push(p2);
    Out o; renderSeconds(e, 2.0, &o);
    float peak = 0; bool finite = true;
    for (size_t i = 0; i < o.l.size(); ++i) { peak = std::max(peak, std::fabs(o.l[i])); finite &= std::isfinite(o.l[i]); }
    std::printf("  two hot decks summed: peak %.3f\n", peak);
    CHECK(finite, "non-finite output");
    CHECK(peak <= 1.0f, "limiter let through %.3f", peak);

    // Loop: 1-beat loop, position must stay inside the loop.
    Command l = cmd(C_SET_LOOP, 0); l.d0 = 1.0; l.i0 = 0; e.push(l);
    renderSeconds(e, 3.0);
    double st[kStateSize]; e.readState(st);
    double pos = st[32];
    std::printf("  after 3 s in a 1-beat loop: deck pos %.3fs, looping=%d, loopLen=%.2f beats\n", pos, (int)st[35], st[44]);
    CHECK(st[35] == 1 && pos >= 1.9 && pos <= 2.6, "loop position %.3f", pos);
    Command x = cmd(C_EXIT_LOOP, 0); e.push(x);
    renderSeconds(e, 1.0); e.readState(st);
    CHECK(st[35] == 0 && st[32] > 2.4, "loop exit pos %.3f", st[32]);

    // Echo tail survives the deck being stopped.
    param(e, P_ECHO_BEATS, 1, 0.5); param(e, P_ECHO_FEEDBACK, 1, 0.7); param(e, P_ECHO_MIX, 1, 0.8);
    param(e, P_DECK_VOLUME, 0, 0.0);
    renderSeconds(e, 1.0);
    Command stop = cmd(C_PAUSE, 1); e.push(stop);
    Out tail; renderSeconds(e, 1.0, &tail);
    double energy = 0; for (size_t i = kSr / 4; i < tail.l.size(); ++i) energy += tail.l[i] * tail.l[i];
    std::printf("  echo tail energy after stop: %.3f\n", energy);
    CHECK(energy > 1.0, "echo tail missing");

    // Beat-scheduled automation: fade deck 0 back in over 4 beats starting 2 beats from now.
    param(e, P_ECHO_MIX, 1, 0.0);
    e.readState(st);
    double now = st[0];
    ramp(e, P_DECK_VOLUME, 0, now + 2, now + 6, 1.0, 0.0, CURVE_LINEAR);
    Out f; renderSeconds(e, 4.0, &f);
    auto rmsAt = [&](double sec) { double s = 0; size_t i0 = (size_t)(sec * kSr); for (size_t i = i0; i < i0 + 2400 && i < f.l.size(); ++i) s += f.l[i] * f.l[i]; return std::sqrt(s / 2400); };
    double r0 = rmsAt(0.5), r1 = rmsAt(1.0 + 1.0), r2 = rmsAt(3.6);
    std::printf("  automation rms: before %.4f, mid %.4f, after %.4f\n", r0, r1, r2);
    CHECK(r0 < 0.02 && r2 > r1 && r1 > r0, "automation ramp not applied");
}

static void testBrakeAndCpu() {
    std::printf("[brake + cpu]\n");
    Engine e; e.setSampleRate(kSr);
    auto a = makeKickTrack(e, 126, 0.1, 200, 44100, 330);
    auto b = makeKickTrack(e, 128, 0.2, 200, 44100, 220);
    load(e, 0, a, 126, 0.1); load(e, 1, b, 128, 0.2);
    Command p = cmd(C_PLAY, 0); p.i0 = 1; p.i1 = 1; e.push(p);
    Command s0 = cmd(C_SET_SYNC, 0); s0.i0 = 1; e.push(s0);
    Command s1 = cmd(C_SET_SYNC, 1); s1.i0 = 1; e.push(s1);
    Command ps = cmd(C_SET_PITCH, 1); ps.d0 = 1; e.push(ps);
    Command p1 = cmd(C_PLAY, 1); p1.i1 = 1; p1.d0 = 0.2; e.push(p1);
    for (int d = 0; d < 2; ++d) { param(e, P_ECHO_MIX, d, 0.3); param(e, P_REVERB_MIX, d, 0.3); param(e, P_FLANGER_MIX, d, 0.3); param(e, P_FILTER, d, 0.3); }
    param(e, P_DRUM_LEVEL, 0, 0.8); param(e, P_RISER_LEVEL, 0, 0.3);
    auto t0 = std::chrono::steady_clock::now();
    renderSeconds(e, 60.0);
    double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("  60 s of audio (2 key-locked decks, all FX, drums, riser) rendered in %.2f s -> %.1fx real time (host CPU)\n", secs, 60.0 / secs);
    CHECK(secs < 30.0, "too slow");
    Command br = cmd(C_BRAKE, 0); br.d0 = 2; e.push(br);
    renderSeconds(e, 2.0);
    double st[kStateSize]; e.readState(st);
    CHECK(st[33] == 0, "brake should stop deck A");
    std::printf("  brake stopped deck A: %s\n", st[33] == 0 ? "yes" : "no");
}

static void testDrumMachineTiming() {
    std::printf("[drums] drum machine kicks land on master beats\n");
    Engine e; e.setSampleRate(kSr);
    for (int s = 0; s < 16; ++s) { Command c = cmd(C_DRUM_STEP); c.i0 = 0; c.i1 = kKick; c.d0 = s; c.d1 = (s % 4 == 0) ? 1.0 : 0.0; e.push(c); }
    param(e, P_MASTER_BPM, 0, 128); param(e, P_DRUM_LEVEL, 0, 1.0);
    Out o; renderSeconds(e, 4.0, &o);
    double bpf = 128.0 / 60.0 / kSr; double beat0 = o.beatAtFrame.front();
    int checked = 0; double worst = 0; long last = -100000;
    for (size_t i = kSr / 2; i < o.l.size(); ++i) {
        double v = std::fabs(o.l[i]);
        // Onset = first sample above threshold at least 200 ms after the previous onset.
        bool quietBefore = true;
        for (size_t k = i - 480; k < i - 24; ++k) if (std::fabs(o.l[k]) > 0.25) { quietBefore = false; break; }
        if (v > 0.3 && quietBefore && (long)i - last > kSr * 3 / 10) {
            double b = beat0 + i * bpf; double ms = (b - std::round(b)) / bpf / kSr * 1000;
worst = std::max(worst, std::fabs(ms)); ++checked; last = (long)i;
        }
    }
    std::printf("  %d kicks, worst offset %.2f ms\n", checked, worst);
    CHECK(checked >= 5 && worst < 2.0, "drum timing worst %.2f ms", worst);
}

int main() {
    testSyncAccuracy();
    testKeylockAndPitch();
    testLoopLimiterEchoAutomation();
    testBrakeAndCpu();
    testDrumMachineTiming();
    if (failures) { std::printf("\n%d check(s) FAILED\n", failures); return 1; }
    std::printf("\nAll native engine checks passed\n");
    return 0;
}
