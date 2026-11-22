#!/usr/bin/env bash
# Launch Slotify (desktop) from the project root.
#   ./run.sh          — normal launch (real library per settings)
#   SLOTIFY_DEMO=1 ./run.sh — demo mode (fixture library + demo playlist)
set -euo pipefail
cd "$(dirname "$0")"

# Toolchain env (JDK 21, Android SDK, Gradle) — see env.sh
source ./env.sh

exec ./gradlew :composeApp:run --console=plain "$@"
