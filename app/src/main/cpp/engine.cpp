// OpenSoundboard native audio engine: Oboe low-latency float output, polyphonic sample mixer,
// level metering and spectrum analysis. Source samples are kept as 32-bit float (lossless for
// up to 24-bit sources) and are only resampled when the stream rate differs from the file rate.
#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include <complex>
#include "decoder.h"

#define TAG "OsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kMaxSounds = 8192;
constexpr int kMaxVoices = 48;
constexpr int kRing = 8192;  // spectrum ring buffer
constexpr int kFft = 2048;

enum CmdType : int { kPlay, kStop, kStopAll };
struct Cmd {
    int type = 0;
    int id = 0;
    int mode = 0;  // 0 one-shot, 1 loop, 2 hold
    float gain = 1.f;
    int64_t start = 0, end = 0;
    Sound* snd = nullptr;
};

struct Voice {
    bool active = false;
    int id = 0;
    int mode = 0;
    Sound* snd = nullptr;
    double pos = 0, step = 1;
    int64_t start = 0, end = 0;
    float gain = 1.f;
    int fadeIn = 0;      // frames of remaining fade-in
    int releasing = 0;   // >0: frames of fade-out remaining
    int releaseLen = 1;
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    // ---- control thread API ----
    bool start(int deviceId, int rate, bool exclusive) {
        std::lock_guard<std::mutex> g(streamMutex_);
        reqDevice_ = deviceId; reqRate_ = rate; reqExclusive_ = exclusive;
        return openLocked();
    }
    void stop() {
        std::lock_guard<std::mutex> g(streamMutex_);
        closing_ = true;
        if (stream_) { stream_->stop(); stream_->close(); stream_.reset(); }
        closing_ = false;
    }
    void post(const Cmd& c) {
        std::lock_guard<std::mutex> g(cmdMutex_);
        uint32_t w = wr_.load(std::memory_order_relaxed);
        if (w - rd_.load(std::memory_order_acquire) >= kCmds) return;
        cmds_[w % kCmds] = c;
        wr_.store(w + 1, std::memory_order_release);
    }
    Sound* get(int id) { return (id >= 0 && id < kMaxSounds) ? sounds_[id].load() : nullptr; }
    void put(int id, Sound* s) {
        if (id < 0 || id >= kMaxSounds) { delete s; return; }
        Sound* old = sounds_[id].exchange(s);
        if (old) { Cmd c; c.type = kStop; c.id = id; post(c); retire(old); }
    }
    void remove(int id) { put(id, nullptr); }
    void retire(Sound* s) {  // freed later so the audio thread can never touch freed memory
        std::lock_guard<std::mutex> g(graveMutex_);
        grave_.push_back({s, std::chrono::steady_clock::now()});
        auto now = std::chrono::steady_clock::now();
        for (size_t i = 0; i < grave_.size();) {
            if (now - grave_[i].second > std::chrono::seconds(5) && grave_[i].first != s) {
                delete grave_[i].first; grave_.erase(grave_.begin() + i);
            } else i++;
        }
    }
    int streamRate() const { return rate_.load(); }
    bool isPlaying(int id) const { return id >= 0 && id < kMaxSounds && playing_[id].load() > 0; }
    void levels(float* out) {
        out[0] = peakL_.exchange(0.f); out[1] = peakR_.exchange(0.f);
        out[2] = std::sqrt(rmsL_.load()); out[3] = std::sqrt(rmsR_.load());
    }
    void streamInfo(double* out) {
        std::lock_guard<std::mutex> g(streamMutex_);
        for (int i = 0; i < 10; i++) out[i] = 0;
        if (!stream_) return;
        out[0] = stream_->getSampleRate();
        out[1] = stream_->getFramesPerBurst();
        out[2] = stream_->getBufferSizeInFrames();
        out[3] = stream_->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0;
        out[4] = (int)stream_->getFormat();
        out[5] = stream_->getDeviceId();
        out[6] = oboe::OboeExtensions::isMMapUsed(stream_.get()) ? 1 : 0;
        out[7] = stream_->getChannelCount();
        auto lat = stream_->calculateLatencyMillis();
        out[8] = lat ? lat.value() : -1.0;
        out[9] = xruns_.load();
    }
    void spectrum(float* out, int bands) {
        static float win[kFft]; static bool init = false;
        if (!init) { for (int i = 0; i < kFft; i++) win[i] = 0.5f - 0.5f * cosf(2.f * (float)M_PI * i / (kFft - 1)); init = true; }
        std::vector<std::complex<float>> buf(kFft);
        uint32_t w = ringW_.load(std::memory_order_acquire);
        for (int i = 0; i < kFft; i++) buf[i] = ring_[(w - kFft + i) & (kRing - 1)] * win[i];
        fft(buf);
        float sr = (float)std::max(rate_.load(), 8000);
        float fmin = 30.f, fmax = std::min(18000.f, sr / 2.f - 1.f);
        for (int b = 0; b < bands; b++) {
            float f0 = fmin * powf(fmax / fmin, (float)b / bands), f1 = fmin * powf(fmax / fmin, (float)(b + 1) / bands);
            int k0 = std::max(1, (int)(f0 * kFft / sr)), k1 = std::max(k0 + 1, (int)(f1 * kFft / sr));
            float m = 0.f;
            for (int k = k0; k < k1 && k < kFft / 2; k++) m = std::max(m, std::abs(buf[k]));
            float db = 20.f * log10f(m / (kFft / 4.f) + 1e-7f);
            out[b] = std::min(1.f, std::max(0.f, (db + 80.f) / 80.f));
        }
    }
    void setMasterGain(float g) { master_.store(g); }

    // ---- audio thread ----
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* st, void* audio, int32_t n) override {
        auto* out = static_cast<float*>(audio);
        std::memset(out, 0, sizeof(float) * 2 * n);
        drain();
        for (auto& v : voices_) if (v.active) mix(v, out, n);
        float pl = 0, pr = 0, sl = 0, sr = 0, m = master_.load();
        uint32_t w = ringW_.load(std::memory_order_relaxed);
        for (int i = 0; i < n; i++) {
            float l = out[2 * i] * m, r = out[2 * i + 1] * m;
            if (l > 1.f) l = 1.f; else if (l < -1.f) l = -1.f;
            if (r > 1.f) r = 1.f; else if (r < -1.f) r = -1.f;
            out[2 * i] = l; out[2 * i + 1] = r;
            pl = std::max(pl, std::fabs(l)); pr = std::max(pr, std::fabs(r));
            sl += l * l; sr += r * r;
            ring_[(w + i) & (kRing - 1)] = 0.5f * (l + r);
        }
        ringW_.store(w + n, std::memory_order_release);
        if (pl > peakL_.load()) peakL_.store(pl);
        if (pr > peakR_.load()) peakR_.store(pr);
        rmsL_.store(sl / n); rmsR_.store(sr / n);
        int x = st->getXRunCount() ? st->getXRunCount().value() : 0; xruns_.store(x);
        return oboe::DataCallbackResult::Continue;
    }
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result err) override {
        LOGE("stream error %s", oboe::convertToText(err));
        if (closing_) return;
        std::thread([this] {
            std::lock_guard<std::mutex> g(streamMutex_);
            stream_.reset();
            openLocked();
        }).detach();
    }

private:
    static constexpr uint32_t kCmds = 512;

    bool openLocked() {
        if (stream_) { stream_->stop(); stream_->close(); stream_.reset(); }
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(reqExclusive_ ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Best)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (reqRate_ > 0) b.setSampleRate(reqRate_);
        if (reqDevice_ > 0) b.setDeviceId(reqDevice_);
        std::shared_ptr<oboe::AudioStream> s;
        oboe::Result r = b.openStream(s);
        if (r != oboe::Result::OK && reqExclusive_) {
            b.setSharingMode(oboe::SharingMode::Shared);
            r = b.openStream(s);
        }
        if (r != oboe::Result::OK) { LOGE("open failed %s", oboe::convertToText(r)); return false; }
        s->setBufferSizeInFrames(s->getFramesPerBurst() * 2);
        rate_.store(s->getSampleRate());
        stream_ = s;
        r = stream_->requestStart();
        LOGI("stream rate=%d burst=%d excl=%d mmap=%d", s->getSampleRate(), s->getFramesPerBurst(),
             (int)(s->getSharingMode() == oboe::SharingMode::Exclusive), (int)oboe::OboeExtensions::isMMapUsed(s.get()));
        return r == oboe::Result::OK;
    }

    void drain() {
        uint32_t r = rd_.load(std::memory_order_relaxed), w = wr_.load(std::memory_order_acquire);
        while (r != w) {
            const Cmd& c = cmds_[r % kCmds];
            if (c.type == kPlay && c.snd) startVoice(c);
            else if (c.type == kStop) release(c.id);
            else if (c.type == kStopAll) for (auto& v : voices_) if (v.active) beginRelease(v);
            r++;
        }
        rd_.store(r, std::memory_order_release);
    }
    void beginRelease(Voice& v) { if (!v.releasing) { v.releaseLen = v.releasing = 256; } }
    void release(int id) { for (auto& v : voices_) if (v.active && v.id == id) beginRelease(v); }
    void startVoice(const Cmd& c) {
        Voice* slot = nullptr;
        for (auto& v : voices_) if (!v.active) { slot = &v; break; }
        if (!slot) { slot = &voices_[0]; for (auto& v : voices_) if (v.pos > slot->pos) slot = &v; finish(*slot); }
        int64_t frames = c.snd->frames();
        slot->active = true; slot->id = c.id; slot->mode = c.mode; slot->snd = c.snd;
        slot->start = std::max<int64_t>(0, std::min(c.start, frames - 1));
        slot->end = std::max(slot->start + 1, std::min(c.end, frames));
        slot->pos = (double)slot->start;
        slot->step = (double)c.snd->sampleRate / (double)std::max(1, rate_.load());
        slot->gain = c.gain; slot->fadeIn = 64; slot->releasing = 0;
        playing_[c.id].fetch_add(1);
    }
    void finish(Voice& v) { if (v.active) { v.active = false; playing_[v.id].fetch_sub(1); } }

    static inline float sampleAt(const Sound* s, int64_t i, int ch, int64_t lo, int64_t hi, bool loop) {
        if (i >= hi) i = loop ? lo + (i - hi) % std::max<int64_t>(1, hi - lo) : hi - 1;
        if (i < lo) i = loop ? hi - 1 : lo;
        return s->data[(size_t)i * s->channels + ch];
    }

    void mix(Voice& v, float* out, int n) {
        const Sound* s = v.snd;
        const int chans = s->channels;
        const bool loop = v.mode == 1;
        const bool unity = std::fabs(v.step - 1.0) < 1e-9;
        for (int i = 0; i < n; i++) {
            if (v.pos >= (double)v.end) {
                if (loop) v.pos -= (double)(v.end - v.start); else { finish(v); return; }
            }
            int64_t i0 = (int64_t)v.pos;
            float L, R;
            if (unity) {
                L = s->data[(size_t)i0 * chans];
                R = chans > 1 ? s->data[(size_t)i0 * chans + 1] : L;
            } else {
                float t = (float)(v.pos - (double)i0);
                float l[4], r[4];
                for (int k = 0; k < 4; k++) {
                    int64_t idx = i0 - 1 + k;
                    l[k] = sampleAt(s, idx, 0, v.start, v.end, loop);
                    r[k] = chans > 1 ? sampleAt(s, idx, 1, v.start, v.end, loop) : l[k];
                }
                L = cubic(l, t); R = cubic(r, t);
            }
            float g = v.gain;
            if (v.fadeIn > 0) { g *= 1.f - (float)v.fadeIn / 64.f; v.fadeIn--; }
            if (v.releasing > 0) {
                g *= (float)v.releasing / (float)v.releaseLen;
                if (--v.releasing == 0) { finish(v); return; }
            }
            out[2 * i] += L * g; out[2 * i + 1] += R * g;
            v.pos += v.step;
        }
    }
    static inline float cubic(const float* p, float t) {
        return p[1] + 0.5f * t * (p[2] - p[0] + t * (2.f * p[0] - 5.f * p[1] + 4.f * p[2] - p[3] + t * (3.f * (p[1] - p[2]) + p[3] - p[0])));
    }
    static void fft(std::vector<std::complex<float>>& a) {
        int n = (int)a.size();
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) std::swap(a[i], a[j]);
        }
        for (int len = 2; len <= n; len <<= 1) {
            float ang = -2.f * (float)M_PI / len;
            std::complex<float> wl(cosf(ang), sinf(ang));
            for (int i = 0; i < n; i += len) {
                std::complex<float> w(1);
                for (int j = 0; j < len / 2; j++) {
                    auto u = a[i + j], v = a[i + j + len / 2] * w;
                    a[i + j] = u + v; a[i + j + len / 2] = u - v; w *= wl;
                }
            }
        }
    }

    std::mutex streamMutex_, cmdMutex_, graveMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    int reqDevice_ = 0, reqRate_ = 0; bool reqExclusive_ = true;
    std::atomic<bool> closing_{false};
    std::atomic<int> rate_{48000};
    std::atomic<Sound*> sounds_[kMaxSounds] = {};
    std::atomic<int> playing_[kMaxSounds] = {};
    std::vector<std::pair<Sound*, std::chrono::steady_clock::time_point>> grave_;
    Cmd cmds_[kCmds];
    std::atomic<uint32_t> wr_{0}, rd_{0};
    Voice voices_[kMaxVoices];
    std::atomic<float> peakL_{0}, peakR_{0}, rmsL_{0}, rmsR_{0}, master_{1.f};
    std::atomic<int> xruns_{0};
    float ring_[kRing] = {};
    std::atomic<uint32_t> ringW_{0};
};

Engine& E() { static Engine e; return e; }

}  // namespace

extern "C" {
#define FN(name) Java_io_github_linuxkernel44_opensoundboard_audio_NativeEngine_##name

JNIEXPORT jboolean JNICALL FN(start)(JNIEnv*, jobject, jint dev, jint rate, jboolean excl) { return E().start(dev, rate, excl); }
JNIEXPORT void JNICALL FN(stop)(JNIEnv*, jobject) { E().stop(); }

JNIEXPORT jintArray JNICALL FN(loadFile)(JNIEnv* env, jobject, jint id, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    Sound* s = decodeFile(p);
    env->ReleaseStringUTFChars(path, p);
    if (!s || s->data.empty()) { delete s; return nullptr; }
    jint info[4] = {s->channels, s->sampleRate, s->bitDepth, (jint)s->frames()};
    E().put(id, s);
    jintArray arr = env->NewIntArray(4);
    env->SetIntArrayRegion(arr, 0, 4, info);
    return arr;
}
JNIEXPORT jstring JNICALL FN(codecOf)(JNIEnv* env, jobject, jint id) {
    Sound* s = E().get(id);
    return env->NewStringUTF(s ? s->codec.c_str() : "");
}
JNIEXPORT jboolean JNICALL FN(loadPcm)(JNIEnv* env, jobject, jint id, jobject buf, jint frames, jint ch, jint rate, jstring codec) {
    auto* f = static_cast<float*>(env->GetDirectBufferAddress(buf));
    if (!f || frames <= 0 || ch <= 0) return JNI_FALSE;
    auto* s = new Sound();
    s->channels = ch; s->sampleRate = rate; s->bitDepth = 0;
    const char* c = env->GetStringUTFChars(codec, nullptr); s->codec = c; env->ReleaseStringUTFChars(codec, c);
    s->data.assign(f, f + (size_t)frames * ch);
    E().put(id, s);
    return JNI_TRUE;
}
JNIEXPORT void JNICALL FN(unload)(JNIEnv*, jobject, jint id) { E().remove(id); }
JNIEXPORT void JNICALL FN(play)(JNIEnv*, jobject, jint id, jint mode, jfloat gain, jfloat startFrac, jfloat endFrac) {
    Sound* s = E().get(id);
    if (!s) return;
    Cmd c; c.type = kPlay; c.id = id; c.mode = mode; c.gain = gain; c.snd = s;
    int64_t n = s->frames();
    c.start = (int64_t)(startFrac * n); c.end = (int64_t)(endFrac * n);
    if (mode != 0) { Cmd st; st.type = kStop; st.id = id; E().post(st); }  // loop/hold restart cleanly
    E().post(c);
}
JNIEXPORT void JNICALL FN(stopSound)(JNIEnv*, jobject, jint id) { Cmd c; c.type = kStop; c.id = id; E().post(c); }
JNIEXPORT void JNICALL FN(stopAll)(JNIEnv*, jobject) { Cmd c; c.type = kStopAll; E().post(c); }
JNIEXPORT jboolean JNICALL FN(isPlaying)(JNIEnv*, jobject, jint id) { return E().isPlaying(id); }
JNIEXPORT void JNICALL FN(setMasterGain)(JNIEnv*, jobject, jfloat g) { E().setMasterGain(g); }

JNIEXPORT jfloatArray JNICALL FN(levels)(JNIEnv* env, jobject) {
    float v[4]; E().levels(v);
    jfloatArray a = env->NewFloatArray(4); env->SetFloatArrayRegion(a, 0, 4, v); return a;
}
JNIEXPORT jdoubleArray JNICALL FN(streamInfo)(JNIEnv* env, jobject) {
    double v[10]; E().streamInfo(v);
    jdoubleArray a = env->NewDoubleArray(10); env->SetDoubleArrayRegion(a, 0, 10, v); return a;
}
JNIEXPORT jfloatArray JNICALL FN(spectrum)(JNIEnv* env, jobject, jint bands) {
    std::vector<float> v(bands); E().spectrum(v.data(), bands);
    jfloatArray a = env->NewFloatArray(bands); env->SetFloatArrayRegion(a, 0, bands, v.data()); return a;
}
JNIEXPORT jfloatArray JNICALL FN(peaks)(JNIEnv* env, jobject, jint id, jint buckets) {
    Sound* s = E().get(id);
    if (!s || buckets <= 0) return nullptr;
    int64_t n = s->frames();
    std::vector<float> v(buckets, 0.f);
    for (int b = 0; b < buckets; b++) {
        int64_t i0 = n * b / buckets, i1 = std::max(i0 + 1, n * (b + 1) / buckets);
        float m = 0;
        int64_t step = std::max<int64_t>(1, (i1 - i0) / 64);
        for (int64_t i = i0; i < i1 && i < n; i += step)
            for (int c = 0; c < s->channels; c++) m = std::max(m, std::fabs(s->data[(size_t)i * s->channels + c]));
        v[b] = m;
    }
    jfloatArray a = env->NewFloatArray(buckets); env->SetFloatArrayRegion(a, 0, buckets, v.data()); return a;
}
}
