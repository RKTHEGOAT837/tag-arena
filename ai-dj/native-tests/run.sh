#!/usr/bin/env bash
# Builds the native engine for the host and runs its checks.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
${CXX:-g++} -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -o build/test_engine test_engine.cpp ../dj-audio-engine/src/main/cpp/engine.cpp -lpthread
./build/test_engine

# Plan -> engine integration (needs plans exported by `./gradlew :dj-core:test`).
if [ -f ../dj-core/build/integration/plans.txt ]; then
  ${CXX:-g++} -std=c++17 -O2 -o build/test_integration test_integration.cpp ../dj-audio-engine/src/main/cpp/engine.cpp -lpthread
  ./build/test_integration ../dj-core/build/integration
else
  echo "(skipping plan integration test: run ./gradlew :dj-core:test first)"
fi
