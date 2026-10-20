#!/usr/bin/env bash
# Builds the Media3 ffmpeg audio decoder with WeaselPlex's channel-change fix and
# writes app/libs/lib-decoder-ffmpeg-release.aar, which the app build prefers
# over the prebuilt wholphin-extensions artifact.
#
# Same inputs as wholphin-extensions 0.2.4 (Media3 1.11.1, FFmpeg n9.0, the same
# decoders), plus channel-changes.patch: broadcast AC-3/E-AC-3 that switches
# between 5.1 and 2.0 mid-stream crashed the stock decoder (SIGSEGV in
# swr_convert), because its resampler was never rebuilt for the new layout.
#
# Usage: scripts/ffmpeg-decoder/build.sh [work dir, default /tmp/weaselplex-ffmpeg]
set -euo pipefail

MEDIA3_VERSION="1.11.1"
FFMPEG_BRANCH="n9.0"
ANDROID_ABI=21
ENABLED_DECODERS=(dca ac3 eac3 mlp truehd flac alac pcm_mulaw pcm_alaw mp3)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK_DIR="${1:-/tmp/weaselplex-ffmpeg}"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_PATH="${ANDROID_NDK_HOME:-$(ls -d "$SDK_DIR"/ndk/* | sort -V | tail -1)}"
HOST_PLATFORM="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"
[[ -d media ]] || git clone -q --depth 1 -b "$MEDIA3_VERSION" https://github.com/androidx/media.git media
[[ -d ffmpeg ]] || git clone -q --depth 1 -b "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg ffmpeg

cd media
git checkout -q -- .
git apply "$SCRIPT_DIR/channel-changes.patch"
# FFmpeg 8+ no longer has libpostproc
sed -i '/--disable-postproc/d' libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh
echo "sdk.dir=$SDK_DIR" > local.properties

JNI_DIR="$WORK_DIR/media/libraries/decoder_ffmpeg/src/main/jni"
ln -sfn "$WORK_DIR/ffmpeg" "$JNI_DIR/ffmpeg"
(cd "$JNI_DIR" && ./build_ffmpeg.sh "$WORK_DIR/media/libraries/decoder_ffmpeg/src/main" \
    "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_ABI" "${ENABLED_DECODERS[@]}")

./gradlew -q --no-daemon :lib-decoder-ffmpeg:assembleRelease
mkdir -p "$REPO_ROOT/app/libs"
cp libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar "$REPO_ROOT/app/libs/"
echo "Wrote $REPO_ROOT/app/libs/lib-decoder-ffmpeg-release.aar"
