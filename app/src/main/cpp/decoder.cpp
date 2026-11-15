#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"
#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"
#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"
extern "C" int stb_vorbis_decode_filename(const char* filename, int* channels, int* sample_rate, short** output);
#include "decoder.h"
#include <cstring>
#include <cstdlib>
#include <strings.h>

static bool endsWith(const char* s, const char* ext) {
    size_t n = strlen(s), m = strlen(ext);
    return n >= m && strcasecmp(s + n - m, ext) == 0;
}

Sound* decodeFile(const char* path) {
    if (endsWith(path, ".wav") || endsWith(path, ".wave")) {
        drwav wav;
        if (!drwav_init_file(&wav, path, nullptr)) return nullptr;
        auto* s = new Sound();
        s->channels = (int)wav.channels;
        s->sampleRate = (int)wav.sampleRate;
        s->bitDepth = (int)wav.bitsPerSample;
        s->codec = (wav.translatedFormatTag == DR_WAVE_FORMAT_IEEE_FLOAT) ? "WAV (float)" : "WAV (PCM)";
        s->data.resize((size_t)wav.totalPCMFrameCount * wav.channels);
        drwav_uint64 got = drwav_read_pcm_frames_f32(&wav, wav.totalPCMFrameCount, s->data.data());
        s->data.resize((size_t)got * wav.channels);
        drwav_uninit(&wav);
        return s;
    }
    if (endsWith(path, ".flac")) {
        drflac* f = drflac_open_file(path, nullptr);
        if (!f) return nullptr;
        auto* s = new Sound();
        s->channels = (int)f->channels;
        s->sampleRate = (int)f->sampleRate;
        s->bitDepth = (int)f->bitsPerSample;
        s->codec = "FLAC";
        s->data.resize((size_t)f->totalPCMFrameCount * f->channels);
        drflac_uint64 got = drflac_read_pcm_frames_f32(f, f->totalPCMFrameCount, s->data.data());
        s->data.resize((size_t)got * f->channels);
        drflac_close(f);
        return s;
    }
    if (endsWith(path, ".mp3")) {
        drmp3_config cfg;
        drmp3_uint64 frames = 0;
        float* pcm = drmp3_open_file_and_read_pcm_frames_f32(path, &cfg, &frames, nullptr);
        if (!pcm) return nullptr;
        auto* s = new Sound();
        s->channels = (int)cfg.channels;
        s->sampleRate = (int)cfg.sampleRate;
        s->bitDepth = 0;
        s->codec = "MP3";
        s->data.assign(pcm, pcm + (size_t)frames * cfg.channels);
        drmp3_free(pcm, nullptr);
        return s;
    }
    if (endsWith(path, ".ogg")) {
        int ch = 0, rate = 0;
        short* pcm = nullptr;
        int frames = stb_vorbis_decode_filename(path, &ch, &rate, &pcm);
        if (frames <= 0 || !pcm) return nullptr;
        auto* s = new Sound();
        s->channels = ch;
        s->sampleRate = rate;
        s->bitDepth = 0;
        s->codec = "Ogg Vorbis";
        s->data.resize((size_t)frames * ch);
        for (size_t i = 0; i < s->data.size(); i++) s->data[i] = pcm[i] / 32768.0f;
        free(pcm);
        return s;
    }
    return nullptr;
}
