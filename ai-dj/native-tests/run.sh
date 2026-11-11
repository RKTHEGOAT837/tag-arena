#!/usr/bin/env bash
# Builds the native engine for the host and runs its checks.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
${CXX:-g++} -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -o build/test_engine test_engine.cpp ../dj-audio-engine/src/main/cpp/engine.cpp -lpthread
./build/test_engine
