#!/usr/bin/env bash
set -euo pipefail
preload_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$preload_root"
mkdir -p build/preload-tests
preload_init=$(mktemp "$preload_root/build/preload-tests/init.XXXXXX.gradle")
preload_cleanup() {
    rm -f "$preload_init"
    rm -rf "$preload_root/build/preload-tests/tmp"
}
trap preload_cleanup EXIT
# Limit discovery as well as execution: unrelated legacy sample classes can fail
# Java 17 class loading before Gradle applies --tests filters.
cat > "$preload_init" <<'GRADLE'
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        include '**/services/preload/*Test.class'
        def scratch = new File(rootProject.projectDir, 'build/preload-tests/tmp')
        scratch.mkdirs()
        systemProperty 'java.io.tmpdir', scratch.absolutePath
    }
}
GRADLE
./gradlew -I "$preload_init" :app:testWeaselfinDebugUnitTest \
    --tests '*PacerFallbackTest' --tests '*PreloadFallbackIntegrationTest' \
    -PWeaselPlexTargetAbi=arm64-v8a
mkdir -p build/preload-tests/fallback
cp app/build/test-results/testWeaselfinDebugUnitTest/TEST-*preload*.xml build/preload-tests/fallback/
./gradlew -I "$preload_init" :app:testWeaselfinDebugUnitTest \
    --tests '*PreloadSessionTest' --tests '*PreloadManagerEnabledTest' -PWeaselPlexTargetAbi=arm64-v8a
mkdir -p build/preload-tests/enabled
cp app/build/test-results/testWeaselfinDebugUnitTest/TEST-*preload*.xml build/preload-tests/enabled/
