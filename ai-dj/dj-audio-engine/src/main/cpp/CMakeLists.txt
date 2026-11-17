cmake_minimum_required(VERSION 3.22.1)
project(aidj_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(aidj_engine SHARED engine.cpp jni_bridge.cpp)
# No -ffast-math: the automation code relies on NaN checks.
target_compile_options(aidj_engine PRIVATE -O3 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(aidj_engine PRIVATE oboe::oboe log android)
# 16 KB page-size support: Android 15+ devices with 16 KB pages refuse to load 4 KB-aligned libraries
# (NDK r27 still defaults to 4 KB), which would leave the DJ engine unable to start on those phones.
target_link_options(aidj_engine PRIVATE "-Wl,-z,max-page-size=16384")
