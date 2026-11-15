cmake_minimum_required(VERSION 3.22.1)
project(osbaudio CXX C)
find_package(oboe REQUIRED CONFIG)
add_library(osbaudio SHARED engine.cpp decoder.cpp third_party/stb_vorbis_impl.c)
target_include_directories(osbaudio PRIVATE third_party)
target_link_libraries(osbaudio oboe::oboe log android)
# 16 KB page size support for Android 15+
target_link_options(osbaudio PRIVATE -Wl,-z,max-page-size=16384)
