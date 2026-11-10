#!/usr/bin/env bash
# Compile the OL Android Kotlin sources standalone (no Gradle) with the
# Kotlin 2.1.21 compiler from the local Gradle cache, against android-36
# android.jar + OkHttp 4.12 + okio (the versions OL Android already uses).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
C=~/.gradle/caches/modules-2/files-2.1
j() { find "$C/$1" -name "$2" ! -name '*sources*' | head -1; }
KC="$(j org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(j org.jetbrains.kotlin/kotlin-stdlib/2.1.21 'kotlin-stdlib-2.1.21.jar'):$(j org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(j org.jetbrains.kotlin/kotlin-reflect/2.1.20 '*.jar'):$(j org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(j org.jetbrains.intellij.deps/trove4j '*.jar'):$(j org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2 '*.jar'):$(j org.jetbrains/annotations/23.0.0 '*.jar')"
CP="/workspace/android-sdk/platforms/android-36/android.jar:$(j com.squareup.okhttp3/okhttp/4.12.0 '*.jar'):$(j com.squareup.okio/okio-jvm/3.6.0 '*.jar'):$(j org.jetbrains.kotlin/kotlin-stdlib/2.1.21 'kotlin-stdlib-2.1.21.jar')"
out="${OUT_DIR:-$here/build}/kotlin"; rm -rf "$out"; mkdir -p "$out"
JAVA="${JAVA:-/workspace/jdk17/bin/java}"
"$JAVA" -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror \
  -cp "$CP" -d "$out" $(find "$here/kotlin" -name '*.kt')
find "$out" -name '*.class' | sed "s|$out/||" | sort
