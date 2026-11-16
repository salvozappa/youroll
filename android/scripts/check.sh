#!/bin/bash
# Full gate before a commit: compile, unit tests, lint-free build of the debug APK.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$YOUROLL_ROOT"
./gradlew --quiet assembleDebug testDebugUnitTest "$@"
# The stabilization motion estimator is the unchanged iOS C++ (../ThirdParty/YouRec/StabilizationMotion.cpp);
# its own tests (../ThirdParty/YouRec/StabilizationMotionTests.cpp) build and run on the host.
if command -v clang++ >/dev/null; then
  mkdir -p build/native
  clang++ -O2 -std=c++17 -I../ThirdParty/YouRec ../ThirdParty/YouRec/StabilizationMotionTests.cpp ../ThirdParty/YouRec/StabilizationMotion.cpp -o build/native/StabilizationMotionTests
  build/native/StabilizationMotionTests >/dev/null || { echo "StabilizationMotionTests failed" >&2; exit 1; }
fi
echo "check OK"
