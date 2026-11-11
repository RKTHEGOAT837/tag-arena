// Oboe output stream host + JNI bridge for com.aidj.engine.NativeEngine.
#include <android/log.h>
#include <jni.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>

#include "engine.h"

#define LOG_TAG "AiDjEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace aidj;

namespace {

class Host : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Engine engine;

    bool start() {
        std::lock_guard<std::mutex> lk(mutex_);
        wantRunning_ = true;
        return openAndStartLocked();
    }

    void stop() {
        std::lock_guard<std::mutex> lk(mutex_);
        wantRunning_ = false;
        if (stream_) { stream_->stop(); stream_->close(); stream_.reset(); }
    }

    double latencyMs() {
        std::lock_guard<std::mutex> lk(mutex_);
        if (!stream_) return -1;
        auto r = stream_->calculateLatencyMillis();
        return r ? r.value() : -1;
    }

    int framesPerBurst() {
        std::lock_guard<std::mutex> lk(mutex_);
        return stream_ ? stream_->getFramesPerBurst() : 0;
    }

    bool running() { return running_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* data, int32_t frames) override {
        if (tuner_) tuner_->tune();
        engine.render(static_cast<float*>(data), frames);
        auto x = s->getXRunCount();
        if (x) engine.setXruns(x.value());
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        // Typically ErrorDisconnected: headphones unplugged, Bluetooth connected/disconnected, default device changed.
        LOGI("Stream closed (%s); reopening on the new route", oboe::convertToText(error));
        running_ = false;
        // try_lock: if stop()/start() holds the mutex it is already managing the stream (avoids a deadlock
        // where close() waits for this callback thread).
        std::unique_lock<std::mutex> lk(mutex_, std::try_to_lock);
        if (!lk.owns_lock()) return;
        stream_.reset();
        if (wantRunning_) openAndStartLocked();
    }

private:
    bool openAndStartLocked() {
        if (stream_) return true;
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        auto r = b.openStream(stream_);
        if (r != oboe::Result::OK) { LOGE("openStream failed: %s", oboe::convertToText(r)); stream_.reset(); return false; }
        // Safe: no callback is running for the new stream yet.
        engine.setSampleRate(stream_->getSampleRate());
        // Start with 2 bursts of buffering; the tuner grows it on underruns.
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        tuner_ = std::make_unique<oboe::LatencyTuner>(*stream_);
        r = stream_->requestStart();
        if (r != oboe::Result::OK) { LOGE("requestStart failed: %s", oboe::convertToText(r)); stream_->close(); stream_.reset(); return false; }
        LOGI("Audio stream started: %d Hz, burst %d, api %d", stream_->getSampleRate(), stream_->getFramesPerBurst(), (int)stream_->getAudioApi());
        running_ = true;
        return true;
    }

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<oboe::LatencyTuner> tuner_;
    bool wantRunning_ = false;
    std::atomic<bool> running_{false};
};

inline Host* H(jlong h) { return reinterpret_cast<Host*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_aidj_engine_NativeEngine_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Host());
}

JNIEXPORT void JNICALL Java_com_aidj_engine_NativeEngine_nativeDestroy(JNIEnv*, jclass, jlong h) {
    H(h)->stop();
    delete H(h);
}

JNIEXPORT jboolean JNICALL Java_com_aidj_engine_NativeEngine_nativeStart(JNIEnv*, jclass, jlong h) { return H(h)->start(); }
JNIEXPORT void JNICALL Java_com_aidj_engine_NativeEngine_nativeStop(JNIEnv*, jclass, jlong h) { H(h)->stop(); }
JNIEXPORT jboolean JNICALL Java_com_aidj_engine_NativeEngine_nativeIsRunning(JNIEnv*, jclass, jlong h) { return H(h)->running(); }
JNIEXPORT jdouble JNICALL Java_com_aidj_engine_NativeEngine_nativeLatencyMs(JNIEnv*, jclass, jlong h) { return H(h)->latencyMs(); }
JNIEXPORT jint JNICALL Java_com_aidj_engine_NativeEngine_nativeFramesPerBurst(JNIEnv*, jclass, jlong h) { return H(h)->framesPerBurst(); }

JNIEXPORT jint JNICALL Java_com_aidj_engine_NativeEngine_nativeAllocTrack(JNIEnv*, jclass, jlong h, jlong frames, jint sr) {
    try {
        return H(h)->engine.allocTrack(frames, sr)->id;
    } catch (const std::bad_alloc&) {
        return -1;
    }
}

JNIEXPORT jobject JNICALL Java_com_aidj_engine_NativeEngine_nativeTrackBuffer(JNIEnv* env, jclass, jlong h, jint id) {
    auto t = H(h)->engine.getTrack(id);
    if (!t) return nullptr;
    return env->NewDirectByteBuffer(t->pcm.data(), (jlong)(t->pcm.size() * sizeof(int16_t)));
}

JNIEXPORT void JNICALL Java_com_aidj_engine_NativeEngine_nativeFinalizeTrack(JNIEnv*, jclass, jlong h, jint id, jlong frames) {
    auto t = H(h)->engine.getTrack(id);
    if (t) t->frames = std::min<int64_t>(frames, t->capacityFrames);
}

JNIEXPORT void JNICALL Java_com_aidj_engine_NativeEngine_nativeReleaseTrack(JNIEnv*, jclass, jlong h, jint id) {
    H(h)->engine.releaseTrack(id);
    H(h)->engine.collectGarbage();
}

JNIEXPORT void JNICALL Java_com_aidj_engine_NativeEngine_nativeCollectGarbage(JNIEnv*, jclass, jlong h) { H(h)->engine.collectGarbage(); }

JNIEXPORT jlong JNICALL Java_com_aidj_engine_NativeEngine_nativeLoad(JNIEnv*, jclass, jlong h, jint deck, jint id, jdouble bpm, jdouble firstBeat) {
    auto t = H(h)->engine.getTrack(id);
    if (!t) return -1;
    Command c; c.type = C_LOAD; c.deck = deck; c.track = t.get(); c.d0 = bpm; c.d1 = firstBeat;
    uint64_t seq = H(h)->engine.push(c);
    t->loadSeq = seq;
    return (jlong)seq;
}

JNIEXPORT jlong JNICALL Java_com_aidj_engine_NativeEngine_nativeCommand(JNIEnv*, jclass, jlong h, jint type, jint deck, jint i0, jint i1,
                                                                    jdouble d0, jdouble d1, jdouble d2, jdouble d3) {
    if (type == C_LOAD) return -1;  // must go through nativeLoad
    Command c; c.type = type; c.deck = deck; c.i0 = i0; c.i1 = i1; c.d0 = d0; c.d1 = d1; c.d2 = d2; c.d3 = d3;
    return (jlong)H(h)->engine.push(c);
}

JNIEXPORT void JNICALL Java_com_aidj_engine_NativeEngine_nativeReadState(JNIEnv* env, jclass, jlong h, jdoubleArray out) {
    double st[kStateSize];
    H(h)->engine.readState(st);
    env->SetDoubleArrayRegion(out, 0, kStateSize, st);
}

JNIEXPORT jint JNICALL Java_com_aidj_engine_NativeEngine_nativeSampleRate(JNIEnv*, jclass, jlong h) { return H(h)->engine.sampleRate(); }

}  // extern "C"
