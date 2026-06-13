#!/usr/bin/env bash
# Build and run the Sega Mega Drive emulator without Gradle, using only a JDK 21+.
# Usage: ./run.sh [path/to/rom.md]
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/out"
mkdir -p "$OUT"
echo "Compiling..."
find "$DIR/src/main/java" -name '*.java' > "$OUT/sources.txt"
javac -d "$OUT" @"$OUT/sources.txt"
echo "Launching..."
java -cp "$OUT" com.segaemu.Main "$@"
