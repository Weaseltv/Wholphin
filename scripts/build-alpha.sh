#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/build-alpha.sh [--abi ABI] [--install ADB_SERIAL]

Build WeaselPlex Alpha for your Shield without tagging or publishing a release.
Defaults to arm64-v8a. Supported ABIs: arm64-v8a, armeabi-v7a, x86_64.
--install replaces the Alpha app on an already-connected ADB device.
EOF
}

alpha_abi=arm64-v8a
alpha_device=
while (($#)); do
    case "$1" in
        --abi|--install)
            if (($# < 2)) || [[ -z "$2" || "$2" == --* ]]; then
                echo "Missing value for $1" >&2
                exit 2
            fi
            if [[ "$1" == --abi ]]; then alpha_abi=$2; else alpha_device=$2; fi
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
case "$alpha_abi" in
    arm64-v8a|armeabi-v7a|x86_64) ;;
    *) echo "Unsupported ABI: $alpha_abi" >&2; exit 2 ;;
esac

alpha_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$alpha_root"
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
fi
command -v python3 >/dev/null || { echo "python3 is required to read APK metadata" >&2; exit 1; }
if [[ -n "$alpha_device" ]]; then
    command -v adb >/dev/null || { echo "adb must be on PATH for --install" >&2; exit 1; }
    adb -s "$alpha_device" get-state >/dev/null
fi

# Keep Gradle's incremental outputs between iterations; don't run clean here.
./gradlew :app:assembleWeaselfinAlpha "-PWeaselPlexTargetAbi=$alpha_abi"
alpha_dir="$alpha_root/app/build/outputs/apk/weaselfin/alpha"
alpha_source=$(python3 - "$alpha_dir/output-metadata.json" "$alpha_abi" <<'PY'
import json
import sys
from pathlib import Path

metadata_path = Path(sys.argv[1])
metadata = json.loads(metadata_path.read_text())
if metadata['applicationId'] != 'tv.theweasel.weaselplex.alpha':
    raise SystemExit('Refusing to install an APK outside the Alpha package')
matches = [item for item in metadata['elements'] if any(
    entry['filterType'] == 'ABI' and entry['value'] == sys.argv[2]
    for entry in item['filters']
)]
if len(matches) != 1:
    raise SystemExit('Expected exactly one Alpha APK for the requested ABI')
print(metadata_path.parent / matches[0]['outputFile'])
PY
)
alpha_apk="$alpha_dir/WeaselPlex-alpha-$alpha_abi.apk"
cp -- "$alpha_source" "$alpha_apk"
{
    printf 'commit=%s\n' "$(git rev-parse HEAD)"
    printf 'built_at=%s\n' "$(date -u +%FT%TZ)"
    printf 'abi=%s\n' "$alpha_abi"
    printf 'working_tree_status:\n'
    git status --short
} > "$alpha_apk.build.txt"
printf '\nAlpha APK: %s\nBuild record: %s.build.txt\n' "$alpha_apk" "$alpha_apk"
if [[ -n "$alpha_device" ]]; then
    adb -s "$alpha_device" install -r "$alpha_apk"
fi
