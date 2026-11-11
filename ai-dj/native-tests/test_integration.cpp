// End-to-end: transition plans produced by the Kotlin planner (dj-core PlanExportTest) are executed in the
// real C++ engine exactly the way the app's Performer.schedule() does it, and the result is verified.
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <fstream>
#include <limits>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "../dj-audio-engine/src/main/cpp/engine.h"

using namespace aidj;
static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("    FAIL: " __VA_ARGS__); std::printf("\n"); ++failures; } } while (0)

struct TrackInfo { double bpm, first; int downbeat; double dur, trueOffset, trueBpm; };
struct RampL { int param, deck; double s, e, from, to; int curve; };
struct ActL { int type, deck; double beat, a0, a1; };
struct PlanL { std::string style, from, to; int startBeatOut; double inCue, len, handover; int keyShift, syncIn; double inSyncBpm; std::vector<RampL> ramps; std::vector<ActL> acts; };

static std::vector<float> readF32(const std::string& p) {
    std::ifstream f(p, std::ios::binary | std::ios::ate);
    size_t n = (size_t)f.tellg() / 4; f.seekg(0);
    std::vector<float> v(n); f.read(reinterpret_cast<char*>(v.data()), n * 4);
    return v;
}

static std::shared_ptr<TrackBuffer> loadTrack(Engine& e, const std::vector<float>& mono) {
    auto t = e.allocTrack((int64_t)mono.size(), 44100);
    t->frames = (int64_t)mono.size();
    for (size_t i = 0; i < mono.size(); ++i) {
        int16_t s = (int16_t)std::max(-32767.0f, std::min(32767.0f, mono[i] * 32767.0f));
        t->pcm[2 * i] = s; t->pcm[2 * i + 1] = s;
    }
    return t;
}

int main(int argc, char** argv) {
    std::string dir = argc > 1 ? argv[1] : "../dj-core/build/integration";
    std::map<std::string, TrackInfo> tracks;
    { std::ifstream f(dir + "/tracks.txt"); std::string name; TrackInfo t;
      while (f >> name >> t.bpm >> t.first >> t.downbeat >> t.dur >> t.trueOffset >> t.trueBpm) tracks[name] = t; }
    if (tracks.empty()) { std::printf("No exported plans in %s (run :dj-core:test first)\n", dir.c_str()); return 2; }
    std::vector<PlanL> plans;
    { std::ifstream f(dir + "/plans.txt"); std::string line; PlanL cur;
      while (std::getline(f, line)) {
          std::istringstream ss(line); std::string tag; ss >> tag;
          if (tag == "PLAN") { cur = PlanL(); ss >> cur.style >> cur.from >> cur.to >> cur.startBeatOut >> cur.inCue >> cur.len >> cur.handover >> cur.keyShift >> cur.syncIn >> cur.inSyncBpm; }
          else if (tag == "R") { RampL r; std::string from; ss >> r.param >> r.deck >> r.s >> r.e >> from >> r.to >> r.curve; r.from = from == "nan" ? std::numeric_limits<double>::quiet_NaN() : std::stod(from); cur.ramps.push_back(r); }
          else if (tag == "A") { ActL a; ss >> a.type >> a.deck >> a.beat >> a.a0 >> a.a1; cur.acts.push_back(a); }
          else if (tag == "END") plans.push_back(cur);
      } }
    std::map<std::string, std::vector<float>> pcm;
    for (auto& [n, _] : tracks) pcm[n] = readF32(dir + "/" + n + ".f32");

    const int sr = 48000;
    for (auto& p : plans) {
        std::printf("[%s] %s -> %s\n", p.style.c_str(), p.from.c_str(), p.to.c_str());
        Engine e; e.setSampleRate(sr);
        const TrackInfo& A = tracks[p.from]; const TrackInfo& B = tracks[p.to];
        auto ta = loadTrack(e, pcm[p.from]); auto tb = loadTrack(e, pcm[p.to]);
        Command c;
        c = {}; c.type = C_LOAD; c.deck = 0; c.track = ta.get(); c.d0 = A.bpm; c.d1 = A.first; e.push(c);
        c = {}; c.type = C_LOAD; c.deck = 1; c.track = tb.get(); c.d0 = B.bpm; c.d1 = B.first; e.push(c);
        // Performer.playNow(): play unsynced, anchor the master clock, set its BPM, then lock the deck to it.
        c = {}; c.type = C_PLAY; c.deck = 0; c.i0 = 1; c.i1 = 1; c.d0 = 0; e.push(c);
        c = {}; c.type = C_ANCHOR; c.deck = 0; e.push(c);
        c = {}; c.type = C_SET_PARAM; c.i0 = P_MASTER_BPM; c.deck = -1; c.d0 = A.bpm; e.push(c);
        c = {}; c.type = C_SET_SYNC; c.deck = 0; c.i0 = 1; e.push(c);
        std::vector<float> buf(512);
        double st[kStateSize];
        // Play until 20 s into A (the plans were made at that position).
        for (;;) { e.render(buf.data(), 256); e.readState(st); if (st[32] >= 20.0) break; }
        // Performer.schedule(): convert plan beats (A's grid) to master beats.
        double deckBeatA = (st[32] - A.first) * A.bpm / 60.0;
        double offset = std::round(st[0] - deckBeatA);
        double masterStart = p.startBeatOut + offset;
        CHECK(masterStart > st[0], "plan start already passed");
        c = {}; c.type = C_SET_GRID; c.deck = 1; c.d0 = p.inSyncBpm; c.d1 = B.first; e.push(c);
        c = {}; c.type = C_SET_PITCH; c.deck = 1; c.d0 = p.keyShift; e.push(c);
        for (auto& r : p.ramps) { c = {}; c.type = C_ADD_RAMP; c.deck = r.deck; c.i0 = r.param; c.i1 = r.curve; c.d0 = r.s + masterStart; c.d1 = r.e + masterStart; c.d2 = r.from; c.d3 = r.to; e.push(c); }
        double bStart = 0;
        for (auto& a : p.acts) {
            c = {}; c.type = C_ADD_ACTION; c.deck = a.deck; c.i0 = a.type; c.d0 = a.beat + masterStart; c.d1 = a.a0; c.d2 = a.a1; e.push(c);
            if (a.type == A_START && a.deck == 1) bStart = a.beat;
        }
        // Render through the plan (+ the post-mix tempo glide), checking as we go.
        bool bStarted = false; double bStartErrBeats = 1e9, bCueErr = 1e9;
        double worstPhaseB = 0, worstPhaseA = 0; float peak = 0; bool finite = true;
        double endBeat = masterStart + p.len + 72;
        double aStoppedAt = -1;
        int blocks = 0;
        while (true) {
            e.render(buf.data(), 256); ++blocks;
            for (float v : buf) { peak = std::max(peak, std::fabs(v)); finite &= std::isfinite(v); }
            e.readState(st);
            double mb = st[0];
            bool aPlay = st[33] > 0.5, bPlay = st[49] > 0.5;
            if (bPlay && !bStarted) {
                bStarted = true;
                double rate = st[48 + 5];
                double blockBeats = 256.0 / sr * st[1] / 60.0;
                bStartErrBeats = std::fabs((mb - blockBeats) - (masterStart + bStart));
                bCueErr = std::fabs(st[48] - p.inCue) - 256.0 / sr * rate;
            }
            double rel = mb - masterStart;
            if (getenv("TRACE") && bPlay && (blocks % 40) == 0 && p.style == "BASS_SWAP") std::printf("      rel %.2f phaseB %.4f rate %.4f stretch %d\n", rel, st[48 + 13], st[48 + 5], (int)st[48 + 15]);
            if (bPlay && aPlay && rel > bStart + 2 && st[48 + 4] > 0.5) worstPhaseB = std::max(worstPhaseB, std::fabs(st[48 + 13]));
            if (aPlay && rel < 0 && rel > -16) worstPhaseA = std::max(worstPhaseA, std::fabs(st[32 + 13]));
            if (!aPlay && aStoppedAt < 0 && rel > 0) aStoppedAt = rel;
            if (mb > endBeat) break;
            if (blocks > sr * 600 / 256) break;
        }
        std::printf("    B entered %.3f beats from target, cue err %.3f s; worst phase A %.4f B %.4f beats; A stopped at plan beat %.1f; peak %.2f; master %.2f BPM\n",
                    bStartErrBeats, bCueErr, worstPhaseA, worstPhaseB, aStoppedAt, peak, st[1]);
        CHECK(bStarted, "B never started");
        CHECK(bStartErrBeats < 0.02, "B started %.3f beats off", bStartErrBeats);
        CHECK(bCueErr < 0.05, "B cue error %.3f s", bCueErr);
        CHECK(worstPhaseA < 0.02, "A drifted %.4f beats", worstPhaseA);
        if (p.syncIn) CHECK(worstPhaseB < 0.02, "B phase error %.4f beats during the blend", worstPhaseB);
        CHECK(st[33] < 0.5, "A still playing after the transition");
        CHECK(st[49] > 0.5, "B not playing after the transition");
        CHECK(std::fabs(st[64 + 8 + 7] - 1.0) < 1e-3 && std::fabs(st[64 + 8 + 0] - 1.0) < 1e-3 && std::fabs(st[64 + 8 + 3]) < 1e-3, "B not restored to neutral (vol %.2f low %.2f filter %.2f)", st[64 + 8 + 7], st[64 + 8], st[64 + 8 + 3]);
        CHECK(finite && peak <= 1.0f, "output not clean (peak %.2f)", peak);
        // After the mix the master tempo glides to B's own tempo (or the drum bridge carried it there).
        CHECK(std::fabs(st[1] - p.inSyncBpm) < 0.6 || !p.syncIn, "master tempo %.2f, expected ~%.2f", st[1], p.inSyncBpm);
        CHECK(st[81] < 0.01 && st[82] < 0.01, "remix generators left running (drums %.2f riser %.2f)", st[81], st[82]);
    }
    if (failures) { std::printf("\n%d integration check(s) FAILED\n", failures); return 1; }
    std::printf("\nAll %zu transition plans executed correctly in the engine\n", plans.size());
    return 0;
}
