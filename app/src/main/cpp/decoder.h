#pragma once
#include <vector>
#include <string>
#include <cstdint>

struct Sound {
    std::vector<float> data;  // interleaved
    int channels = 2;
    int sampleRate = 48000;
    int bitDepth = 16;        // source bit depth when known (0 = unknown / lossy)
    std::string codec;
    int64_t frames() const { return channels ? (int64_t)(data.size() / channels) : 0; }
};

// Returns nullptr if the format is not handled natively (caller falls back to MediaCodec).
Sound* decodeFile(const char* path);
