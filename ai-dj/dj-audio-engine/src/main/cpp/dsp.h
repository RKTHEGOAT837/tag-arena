// Real-time DSP building blocks for the AI DJ engine.
// All process() functions are allocation-free and safe to call from the audio callback.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace aidj {

constexpr float kPi = 3.14159265358979323846f;

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }

// One-pole parameter smoother (avoids zipper noise when knobs/automation move).
struct Smoother {
    float value = 0, target = 0, coef = 0.0f;
    void setTime(float seconds, float sr) { coef = std::exp(-1.0f / std::max(1.0f, seconds * sr)); }
    void reset(float v) { value = target = v; }
    inline float next() { value = target + (value - target) * coef; return value; }
    bool settled() const { return std::fabs(value - target) < 1e-5f; }
};

// RBJ biquad, transposed direct form II, stereo.
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1[2] = {0, 0}, z2[2] = {0, 0};
    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
    void setLowpass(float sr, float f, float q) { design(sr, f, q, 0); }
    void setHighpass(float sr, float f, float q) { design(sr, f, q, 1); }
    void setBandpass(float sr, float f, float q) { design(sr, f, q, 2); }
    void design(float sr, float f, float q, int type) {
        f = clampf(f, 5.0f, sr * 0.49f);
        float w0 = 2 * kPi * f / sr, cs = std::cos(w0), sn = std::sin(w0), alpha = sn / (2 * q);
        float a0;
        if (type == 0) { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = (1 - cs) / 2; }
        else if (type == 1) { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2; }
        else { b0 = alpha; b1 = 0; b2 = -alpha; }
        a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha;
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
    }
    inline float process(float x, int ch) {
        float y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return y;
    }
};

// Linkwitz-Riley 4th order (two cascaded Butterworth biquads).
struct LR4 {
    Biquad s1, s2;
    void set(float sr, float f, bool high) {
        if (high) { s1.setHighpass(sr, f, 0.70710678f); s2.setHighpass(sr, f, 0.70710678f); }
        else { s1.setLowpass(sr, f, 0.70710678f); s2.setLowpass(sr, f, 0.70710678f); }
    }
    void reset() { s1.reset(); s2.reset(); }
    inline float process(float x, int ch) { return s2.process(s1.process(x, ch), ch); }
};

// DJ-style 3-band isolator EQ: LR4 splits at 300 Hz and 4 kHz; band gains 0 (kill) .. 1.6.
// The three bands sum to a flat magnitude response at unity gain.
struct Eq3 {
    LR4 lowLp, lowHp, highLp, highHp;
    Smoother gl, gm, gh;
    void init(float sr) {
        lowLp.set(sr, 300, false); lowHp.set(sr, 300, true);
        highLp.set(sr, 4000, false); highHp.set(sr, 4000, true);
        for (auto* s : {&gl, &gm, &gh}) { s->setTime(0.01f, sr); s->reset(1); }
    }
    void reset() { lowLp.reset(); lowHp.reset(); highLp.reset(); highHp.reset(); }
    inline void process(float& l, float& r) {
        float g0 = gl.next(), g1 = gm.next(), g2 = gh.next();
        float in[2] = {l, r};
        for (int c = 0; c < 2; ++c) {
            float lo = lowLp.process(in[c], c);
            float rest = lowHp.process(in[c], c);
            float mid = highLp.process(rest, c);
            float hi = highHp.process(rest, c);
            in[c] = g0 * lo + g1 * mid + g2 * hi;
        }
        l = in[0]; r = in[1];
    }
};

// Topology-preserving-transform state variable filter (stable under fast modulation).
struct Svf {
    float ic1[2] = {0, 0}, ic2[2] = {0, 0};
    float g = 0, k = 1, a1 = 0, a2 = 0, a3 = 0;
    void reset() { ic1[0] = ic1[1] = ic2[0] = ic2[1] = 0; }
    void set(float sr, float cutoff, float q) {
        cutoff = clampf(cutoff, 10.0f, sr * 0.45f);
        g = std::tan(kPi * cutoff / sr);
        k = 1.0f / q;
        a1 = 1.0f / (1.0f + g * (g + k)); a2 = g * a1; a3 = g * a2;
    }
    // mode: 0 low, 1 high, 2 band
    inline float process(float v0, int ch, int mode) {
        float v3 = v0 - ic2[ch];
        float v1 = a1 * ic1[ch] + a2 * v3;
        float v2 = ic2[ch] + a2 * ic1[ch] + a3 * v3;
        ic1[ch] = 2 * v1 - ic1[ch];
        ic2[ch] = 2 * v2 - ic2[ch];
        if (mode == 0) return v2;
        if (mode == 1) return v0 - k * v1 - v2;
        return v1;
    }
};

// Single-knob DJ filter: -1 = low-pass closed, 0 = bypass, +1 = high-pass closed.
struct DjFilter {
    Svf svf;
    Smoother knob;
    float sr = 48000;
    int counter = 0;
    float lastCut = -1, lastMode = -1;
    void init(float rate) { sr = rate; knob.setTime(0.02f, sr); knob.reset(0); svf.reset(); }
    inline void process(float& l, float& r) {
        float p = knob.next();
        float ap = std::fabs(p);
        if (ap < 0.002f) { if (ap == 0.0f) svf.reset(); return; }
        if ((counter++ & 15) == 0) {
            float cut = p < 0 ? 20000.0f * std::pow(60.0f / 20000.0f, ap) : 20.0f * std::pow(10000.0f / 20.0f, ap);
            svf.set(sr, cut, 0.75f + 0.5f * ap);
        }
        int mode = p < 0 ? 0 : 1;
        float wet = clampf(ap / 0.04f, 0, 1);
        float fl = svf.process(l, 0, mode), fr = svf.process(r, 1, mode);
        l = l + wet * (fl - l); r = r + wet * (fr - r);
    }
};

// Stereo delay line with fractional (linear) reads.
struct DelayLine {
    std::vector<float> buf[2];
    int size = 0, w = 0;
    void init(int frames) { size = frames; buf[0].assign(frames, 0); buf[1].assign(frames, 0); w = 0; }
    void clear() { std::fill(buf[0].begin(), buf[0].end(), 0.f); std::fill(buf[1].begin(), buf[1].end(), 0.f); }
    inline void write(float l, float r) { buf[0][w] = l; buf[1][w] = r; if (++w >= size) w = 0; }
    inline float read(int ch, float delay) const {
        float pos = (float)w - delay - 1.0f;
        while (pos < 0) pos += (float)size;
        int i = (int)pos; float fr = pos - (float)i;
        int j = i + 1; if (j >= size) j -= size;
        return buf[ch][i] * (1 - fr) + buf[ch][j] * fr;
    }
};

// Beat-synced echo with filtered feedback (classic DJ "echo out").
struct Echo {
    DelayLine line;
    Smoother mix, feedback, delaySamples;
    Biquad fbLp, fbHp;
    float sr = 48000;
    float tailEnergy = 0;
    void init(float rate) {
        sr = rate;
        line.init((int)(rate * 4.2f));
        mix.setTime(0.02f, sr); feedback.setTime(0.05f, sr); delaySamples.setTime(0.08f, sr);
        mix.reset(0); feedback.reset(0.5f); delaySamples.reset(sr * 0.375f);
        fbLp.setLowpass(sr, 5500, 0.707f); fbHp.setHighpass(sr, 140, 0.707f);
    }
    void setBeats(float beats, double bpm) {
        float d = (float)(beats * 60.0 / std::max(30.0, bpm) * sr);
        delaySamples.target = clampf(d, 16.0f, (float)line.size - 4);
    }
    bool active() const { return mix.value > 1e-4f || mix.target > 0 || tailEnergy > 1e-7f; }
    inline void process(float& l, float& r) {
        float m = mix.next(), fb = feedback.next(), d = delaySamples.next();
        if (m <= 1e-5f && mix.target <= 0) { tailEnergy = 0; line.write(0, 0); return; }
        float el = line.read(0, d), er = line.read(1, d);
        float fl = fbHp.process(fbLp.process(el, 0), 0);
        float fr = fbHp.process(fbLp.process(er, 1), 1);
        // Input to the delay is the send (scaled by mix) so the tail survives the dry signal being cut.
        line.write(l * m + fl * fb, r * m + fr * fb);
        tailEnergy = 0.999f * tailEnergy + 0.001f * (el * el + er * er);
        l += el; r += er;
    }
};

// Flanger with beat-synced LFO.
struct Flanger {
    DelayLine line;
    Smoother mix;
    float sr = 48000, fbState[2] = {0, 0};
    void init(float rate) { sr = rate; line.init((int)(rate * 0.02f)); mix.setTime(0.02f, sr); mix.reset(0); }
    inline void process(float& l, float& r, double masterBeat) {
        float m = mix.next();
        if (m <= 1e-5f && mix.target <= 0) { line.write(l, r); return; }
        float lfo = 0.5f + 0.5f * std::sin((float)(masterBeat / 8.0 * 2.0 * kPi)); // 2-bar sweep
        float d = (0.0007f + 0.0050f * lfo) * sr;
        float dl = line.read(0, d), dr = line.read(1, d * 1.02f);
        line.write(l + 0.6f * fbState[0], r + 0.6f * fbState[1]);
        fbState[0] = dl; fbState[1] = dr;
        l = l * (1 - 0.5f * m) + dl * m * 0.7f;
        r = r * (1 - 0.5f * m) + dr * m * 0.7f;
    }
};

// Freeverb (Jezar's public-domain Schroeder/Moorer reverb).
struct Reverb {
    struct Comb { std::vector<float> b; int i = 0; float store = 0;
        inline float process(float x, float fb, float damp) {
            float o = b[i]; store = o * (1 - damp) + store * damp; b[i] = x + store * fb; if (++i >= (int)b.size()) i = 0; return o; } };
    struct AllPass { std::vector<float> b; int i = 0;
        inline float process(float x) { float bo = b[i]; float o = -x + bo; b[i] = x + bo * 0.5f; if (++i >= (int)b.size()) i = 0; return o; } };
    Comb comb[2][8];
    AllPass ap[2][4];
    Smoother mix;
    float room = 0.84f, damp = 0.25f;
    void init(float sr) {
        static const int combT[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int apT[4] = {556, 441, 341, 225};
        float s = sr / 44100.0f;
        for (int c = 0; c < 2; ++c) {
            for (int k = 0; k < 8; ++k) { comb[c][k].b.assign((size_t)((combT[k] + 23 * c) * s), 0); comb[c][k].i = 0; comb[c][k].store = 0; }
            for (int k = 0; k < 4; ++k) { ap[c][k].b.assign((size_t)((apT[k] + 23 * c) * s), 0); ap[c][k].i = 0; }
        }
        mix.setTime(0.03f, sr); mix.reset(0);
    }
    inline void process(float& l, float& r) {
        float m = mix.next();
        if (m <= 1e-5f && mix.target <= 0) return;
        float in = (l + r) * 0.015f * m;
        float o[2] = {0, 0};
        for (int c = 0; c < 2; ++c) {
            float acc = 0;
            for (int k = 0; k < 8; ++k) acc += comb[c][k].process(in, room, damp);
            for (int k = 0; k < 4; ++k) acc = ap[c][k].process(acc);
            o[c] = acc;
        }
        l += o[0] * 3.0f; r += o[1] * 3.0f;
    }
};

// Peak limiter with instant attack, smooth release, plus a soft-clip safety stage.
struct Limiter {
    float gain = 1, release = 0, threshold = 0.89f, gr = 1;
    void init(float sr) { release = 1.0f - std::exp(-1.0f / (0.15f * sr)); }
    inline void process(float& l, float& r) {
        float peak = std::max(std::fabs(l), std::fabs(r));
        float target = peak > threshold ? threshold / peak : 1.0f;
        if (target < gain) gain = target; else gain += (target - gain) * release;
        gr = gain;
        l *= gain; r *= gain;
        l = softClip(l); r = softClip(r);
    }
    static inline float softClip(float x) {
        const float t = 0.95f;
        if (x > t) return t + (1 - t) * std::tanh((x - t) / (1 - t));
        if (x < -t) return -t + (1 - t) * std::tanh((x + t) / (1 - t));
        return x;
    }
};

// Windowed-sinc fractional resampler (16 taps, 512 phases, Blackman-Harris window).
struct SincTable {
    static constexpr int kTaps = 16, kHalf = 8, kPhases = 512;
    float table[(kPhases + 1) * kTaps];
    SincTable() {
        const double cutoff = 0.92;
        for (int p = 0; p <= kPhases; ++p) {
            double frac = (double)p / kPhases;
            double sum = 0;
            for (int t = 0; t < kTaps; ++t) {
                double x = (t - (kHalf - 1)) - frac;
                double s = std::fabs(x) < 1e-9 ? cutoff : std::sin(M_PI * cutoff * x) / (M_PI * x);
                double n = (x + kHalf) / (2.0 * kHalf);
                double w = (n <= 0 || n >= 1) ? 0 : 0.35875 - 0.48829 * std::cos(2 * M_PI * n) + 0.14128 * std::cos(4 * M_PI * n) - 0.01168 * std::cos(6 * M_PI * n);
                table[p * kTaps + t] = (float)(s * w);
                sum += s * w;
            }
            for (int t = 0; t < kTaps; ++t) table[p * kTaps + t] = (float)(table[p * kTaps + t] / sum);
        }
    }
    static const SincTable& get() { static SincTable t; return t; }
};

// Audio frame source with a per-sample source-position tag (lets us track exactly which part of the
// track is audible after time-stretching, which drives beat sync).
struct FrameSource {
    virtual ~FrameSource() = default;
    virtual void pull(float* l, float* r, double* tag, int n) = 0;
};

// Streaming resampler pulling from a FrameSource; rate = input frames consumed per output frame.
struct Resampler {
    static constexpr int kCap = 4096;
    float L[kCap], R[kCap];
    double T[kCap];
    int count = 0;
    double pos = SincTable::kHalf; // fractional read position in the buffer
    FrameSource* src = nullptr;
    void reset() { count = 0; pos = SincTable::kHalf - 1; primed = false; }
    bool primed = false;
    void fill(int needCount) {
        while (count < needCount) {
            int n = std::min(kCap - count, std::max(256, needCount - count));
            if (n <= 0) break;
            src->pull(L + count, R + count, T + count, n);
            count += n;
        }
    }
    void compact() {
        int drop = (int)pos - SincTable::kHalf;
        if (drop > 1024) {
            std::memmove(L, L + drop, sizeof(float) * (count - drop));
            std::memmove(R, R + drop, sizeof(float) * (count - drop));
            std::memmove(T, T + drop, sizeof(double) * (count - drop));
            count -= drop; pos -= drop;
        }
    }
    void process(float* outL, float* outR, double* outT, int n, double rate) {
        const SincTable& st = SincTable::get();
        if (!primed) {
            // Prime with silence history so the first output frame maps exactly to the first input frame.
            for (int i = 0; i < SincTable::kHalf - 1; ++i) { L[i] = R[i] = 0; T[i] = 0; }
            count = SincTable::kHalf - 1;
            pos = SincTable::kHalf - 1;
            fill(count + 1);
            for (int i = 0; i < SincTable::kHalf - 1; ++i) T[i] = T[SincTable::kHalf - 1];
            primed = true;
        }
        for (int i = 0; i < n; ++i) {
            int ip = (int)pos;
            double frac = pos - ip;
            if (ip + SincTable::kHalf + 1 >= count) {
                if (count + 512 > kCap) compact();
                fill(ip + SincTable::kHalf + 2);
            }
            int ph = (int)(frac * SincTable::kPhases);
            float pf = (float)(frac * SincTable::kPhases - ph);
            const float* c0 = st.table + ph * SincTable::kTaps;
            const float* c1 = c0 + SincTable::kTaps;
            float sl = 0, sr = 0;
            int base = ip - (SincTable::kHalf - 1);
            for (int t = 0; t < SincTable::kTaps; ++t) {
                float c = c0[t] + (c1[t] - c0[t]) * pf;
                sl += c * L[base + t]; sr += c * R[base + t];
            }
            outL[i] = sl; outR[i] = sr;
            outT[i] = T[ip] + (T[ip + 1] - T[ip]) * frac;
            pos += rate;
        }
        compact();
    }
};

// Pitch-preserving time stretcher (WSOLA / SoundTouch TDStretch style).
// tempo > 1 consumes input faster than it produces output.
struct TimeStretch : FrameSource {
    int seq = 0, seek = 0, overlap = 0;
    double tempo = 1.0, skipFract = 0;
    std::vector<float> inL, inR, mono, midL, midR, outL, outR;
    std::vector<double> inT, outT;
    int inCount = 0, outCount = 0, outRead = 0;
    bool first = true;
    FrameSource* src = nullptr;

    // Allocates for the highest supported rate; setRate() then picks window sizes without allocating.
    void init(float maxSr = 96000.0f) {
        seq = (int)(0.040f * maxSr);
        seek = (int)(0.015f * maxSr);
        overlap = (int)(0.008f * maxSr);
        int cap = seq * 6 + seek * 4 + 8192;
        inL.assign(cap, 0); inR.assign(cap, 0); inT.assign(cap, 0); mono.assign(cap, 0);
        midL.assign(overlap, 0); midR.assign(overlap, 0);
        outL.assign(seq * 4, 0); outR.assign(seq * 4, 0); outT.assign(seq * 4, 0);
        reset();
    }
    void setRate(float sr) {
        seq = (int)(0.040f * sr);
        seek = (int)(0.015f * sr);
        overlap = (int)(0.008f * sr);
        reset();
    }
    void reset() { inCount = outCount = outRead = 0; skipFract = 0; first = true;
        std::fill(midL.begin(), midL.end(), 0.f); std::fill(midR.begin(), midR.end(), 0.f); }
    // Clamp tempo so a single iteration never needs more input than the buffers hold.
    void setTempo(double t) { tempo = std::min(2.5, std::max(0.25, t)); }

    int bestOffset() {
        // Normalised cross-correlation of the stored overlap tail against candidate positions.
        // Coarse search with stride 2, then refine around the best.
        auto corr = [&](int off) {
            double c = 0, nrm = 1e-9;
            const float* m = mono.data() + off;
            for (int i = 0; i < overlap; i += 2) {
                float ref = midL[i] + midR[i];
                c += ref * m[i]; nrm += m[i] * m[i];
            }
            return c / std::sqrt(nrm);
        };
        int best = 0; double bc = -1e30;
        for (int o = 0; o < seek; o += 3) { double c = corr(o); if (c > bc) { bc = c; best = o; } }
        int lo = std::max(0, best - 3), hi = std::min(seek - 1, best + 3);
        for (int o = lo; o <= hi; ++o) { double c = corr(o); if (c > bc) { bc = c; best = o; } }
        return best;
    }

    void processOne() {
        double nominalSkip = tempo * (seq - overlap);
        int need = std::max((int)(nominalSkip + 0.5) + overlap, seq) + seek;
        if (inCount < need) {
            int n = need - inCount;
            src->pull(inL.data() + inCount, inR.data() + inCount, inT.data() + inCount, n);
            for (int i = inCount; i < inCount + n; ++i) mono[i] = inL[i] + inR[i];
            inCount += n;
        }
        int off = first ? 0 : bestOffset();
        // Output buffer is consumed before processOne is called again; reset it.
        outCount = 0; outRead = 0;
        for (int i = 0; i < overlap; ++i) {
            float w = first ? 1.0f : (float)i / overlap;
            outL[outCount] = midL[i] * (1 - w) + inL[off + i] * w;
            outR[outCount] = midR[i] * (1 - w) + inR[off + i] * w;
            outT[outCount] = inT[off + i];
            ++outCount;
        }
        int mid = seq - 2 * overlap;
        for (int i = 0; i < mid; ++i) {
            outL[outCount] = inL[off + overlap + i]; outR[outCount] = inR[off + overlap + i];
            outT[outCount] = inT[off + overlap + i]; ++outCount;
        }
        for (int i = 0; i < overlap; ++i) { midL[i] = inL[off + seq - overlap + i]; midR[i] = inR[off + seq - overlap + i]; }
        first = false;
        skipFract += nominalSkip;
        int skip = (int)skipFract;
        skipFract -= skip;
        skip = std::min(skip, inCount);
        std::memmove(inL.data(), inL.data() + skip, sizeof(float) * (inCount - skip));
        std::memmove(inR.data(), inR.data() + skip, sizeof(float) * (inCount - skip));
        std::memmove(inT.data(), inT.data() + skip, sizeof(double) * (inCount - skip));
        std::memmove(mono.data(), mono.data() + skip, sizeof(float) * (inCount - skip));
        inCount -= skip;
    }

    void pull(float* l, float* r, double* tag, int n) override {
        int done = 0;
        while (done < n) {
            if (outRead >= outCount) processOne();
            int k = std::min(n - done, outCount - outRead);
            std::memcpy(l + done, outL.data() + outRead, sizeof(float) * k);
            std::memcpy(r + done, outR.data() + outRead, sizeof(float) * k);
            std::memcpy(tag + done, outT.data() + outRead, sizeof(double) * k);
            outRead += k; done += k;
        }
    }
};

// Simple white-noise generator (xorshift).
struct Noise {
    uint32_t s = 0x12345678u;
    inline float next() { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return (float)(int32_t)s * (1.0f / 2147483648.0f); }
};

}  // namespace aidj
