// Live-remix sound generators: a 6-voice drum machine synced to the master clock, a riser and an impact.
// These are synthesised in real time (no samples), so they can be layered over any remix-licensed track.
#pragma once

#include "dsp.h"

namespace aidj {

enum DrumVoice { kKick = 0, kClap, kHat, kOpenHat, kPerc, kBass, kNumVoices };

struct DrumPattern {
    float steps[kNumVoices][16] = {};
    float swing = 0;
};

struct DrumMachine {
    static constexpr int kMaxPatterns = 16;
    DrumPattern patterns[kMaxPatterns];
    int active = 0;
    float sr = 48000;
    int rootMidi = 45; // A2
    long lastStep = -1;
    Noise noise;
    // Voice states
    float kickPhase = 0, kickT = 1e9f, kickVel = 0;
    float clapT = 1e9f, clapVel = 0;
    float hatT = 1e9f, hatVel = 0, ohatT = 1e9f, ohatVel = 0;
    float percPhase = 0, percT = 1e9f, percVel = 0;
    float bassPhase = 0, bassT = 1e9f, bassVel = 0, bassLen = 0.1f;
    Biquad clapBp, hatHp, ohatHp, bassLp;
    Smoother level, bassLevel;

    void init(float rate) {
        sr = rate;
        clapBp.setBandpass(sr, 1200, 1.2f); hatHp.setHighpass(sr, 7500, 0.7f); ohatHp.setHighpass(sr, 6500, 0.7f);
        bassLp.setLowpass(sr, 380, 0.9f);
        level.setTime(0.02f, sr); level.reset(0); bassLevel.setTime(0.02f, sr); bassLevel.reset(0);
    }

    void trigger(int voice, float vel, double bpm) {
        switch (voice) {
            case kKick: kickT = 0; kickVel = vel; kickPhase = 0; break;
            case kClap: clapT = 0; clapVel = vel; break;
            case kHat: hatT = 0; hatVel = vel; ohatVel *= 0.2f; break;
            case kOpenHat: ohatT = 0; ohatVel = vel; break;
            case kPerc: percT = 0; percVel = vel; percPhase = 0; break;
            case kBass: bassT = 0; bassVel = vel; bassLen = (float)(60.0 / bpm / 4.0 * 0.85); break;
        }
    }

    // Advance one frame at master beat position [beat]; returns stereo sample.
    inline void process(double beat, double bpm, float& outL, float& outR) {
        float lv = level.next(), bl = bassLevel.next();
        const DrumPattern& p = patterns[active];
        // Swing delays odd 16ths.
        double stepPos = beat * 4.0;
        long step = (long)std::floor(stepPos);
        int idx = (int)(((step % 16) + 16) % 16);
        double within = stepPos - step;
        double swingDelay = (idx & 1) ? p.swing : 0.0;
        if (step != lastStep && within >= swingDelay) {
            lastStep = step;
            if (lv > 1e-4f || bl > 1e-4f)
                for (int v = 0; v < kNumVoices; ++v) if (p.steps[v][idx] > 0) trigger(v, p.steps[v][idx], bpm);
        }
        if (lv <= 1e-5f && bl <= 1e-5f) { outL = outR = 0; return; }
        const float dt = 1.0f / sr;
        float s = 0, sl = 0, srr = 0;
        if (kickT < 0.6f) {
            float f = 46.0f + 120.0f * std::exp(-kickT * 32.0f);
            kickPhase += 2 * kPi * f * dt;
            float env = std::exp(-kickT * 7.5f);
            float click = kickT < 0.004f ? (1 - kickT / 0.004f) * 0.4f * noise.next() : 0;
            s += kickVel * (0.95f * std::sin(kickPhase) * env + click);
            kickT += dt;
        }
        if (clapT < 0.4f) {
            float burst = 0;
            for (int k = 0; k < 3; ++k) { float t = clapT - k * 0.011f; if (t >= 0 && t < 0.011f) burst = std::exp(-t * 300.0f); }
            float tail = clapT > 0.022f ? std::exp(-(clapT - 0.022f) * 18.0f) : 0;
            float n = clapBp.process(noise.next(), 0);
            float v = clapVel * n * 2.2f * std::max(burst, tail);
            sl += v; srr += v * 0.9f;
            clapT += dt;
        }
        if (hatT < 0.15f) { float n = hatHp.process(noise.next(), 0); float v = hatVel * n * 0.55f * std::exp(-hatT * 55.0f); sl += v * 0.8f; srr += v; hatT += dt; }
        if (ohatT < 0.6f) { float n = ohatHp.process(noise.next(), 1); float v = ohatVel * n * 0.4f * std::exp(-ohatT * 9.0f); sl += v; srr += v * 0.8f; ohatT += dt; }
        if (percT < 0.4f) {
            // Dhol/tom-like: pitched sine with a falling pitch and a slap of noise.
            float f = 150.0f + 90.0f * std::exp(-percT * 25.0f);
            percPhase += 2 * kPi * f * dt;
            float v = percVel * (0.6f * std::sin(percPhase) * std::exp(-percT * 11.0f) + (percT < 0.01f ? 0.25f * noise.next() : 0));
            sl += v * 1.0f; srr += v * 0.7f;
            percT += dt;
        }
        float bassOut = 0;
        if (bassT < bassLen + 0.02f) {
            float f = 440.0f * std::pow(2.0f, (rootMidi - 69) / 12.0f);
            bassPhase += f * dt; if (bassPhase >= 1) bassPhase -= 1;
            float saw = 2 * bassPhase - 1;
            float env = bassT < bassLen ? std::min(1.0f, bassT / 0.003f) : std::max(0.0f, 1 - (bassT - bassLen) / 0.02f);
            bassOut = bassVel * env * bassLp.process(0.5f * saw + 0.6f * std::sin(2 * kPi * bassPhase), 0) * 0.8f;
            bassT += dt;
        }
        outL = lv * (s + sl) + bl * bassOut;
        outR = lv * (s + srr) + bl * bassOut;
    }
};

// Noise riser with a sweeping band-pass and a rising tone; level/pitch are automated by transitions.
struct Riser {
    Svf bp[2];
    Noise n1, n2;
    Smoother level, pitch;
    float sr = 48000, phase = 0;
    int counter = 0;
    void init(float rate) { sr = rate; level.setTime(0.03f, sr); level.reset(0); pitch.setTime(0.05f, sr); pitch.reset(0); n2.s = 0x9abcdef1u; }
    inline void process(float& l, float& r) {
        float lv = level.next(), p = pitch.next();
        if (lv <= 1e-5f && level.target <= 0) { return; }
        if ((counter++ & 31) == 0) { float fc = 300.0f * std::pow(28.0f, p * p); bp[0].set(sr, fc, 2.5f); bp[1].set(sr, fc * 1.03f, 2.5f); }
        float tone = 200.0f * std::pow(8.0f, p);
        phase += tone / sr; if (phase >= 1) phase -= 1;
        float t = 0.12f * (2 * phase - 1) * p;
        l += lv * (0.9f * bp[0].process(n1.next(), 0, 2) + t);
        r += lv * (0.9f * bp[1].process(n2.next(), 1, 2) + t);
    }
};

// One-shot impact (sub boom + noise crash) used on drops.
struct Impact {
    float t = 1e9f, phase = 0, sr = 48000;
    Biquad crashHp;
    Noise n;
    void init(float rate) { sr = rate; crashHp.setHighpass(sr, 2500, 0.7f); }
    void trigger() { t = 0; phase = 0; }
    inline void process(float& l, float& r) {
        if (t > 2.0f) return;
        float f = 30.0f + 40.0f * std::exp(-t * 6.0f);
        phase += 2 * kPi * f / sr;
        float boom = 0.7f * std::sin(phase) * std::exp(-t * 2.2f);
        float crash = 0.35f * crashHp.process(n.next(), 0) * std::exp(-t * 2.8f);
        l += boom + crash; r += boom + crash * 0.9f;
        t += 1.0f / sr;
    }
};

}  // namespace aidj
