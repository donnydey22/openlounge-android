#!/usr/bin/env bash
# Cross-compile the OL Android audio layer with the NDK against WebRTC m152
# headers. Produces libolaudio_core.so (core + AAudio/OpenSL backends, fully
# linked) and libolaudio_adm.a (adds OlAudioDeviceModule; links with WebRTC).
# The tgcalls JNI bridge is syntax-checked only: linking it needs the pinned
# tgcalls + its vendored WebRTC built for Android (gate G1, see BUILD.md).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-/workspace/android-sdk/ndk/28.2.13676358}"
WEBRTC_INC="${WEBRTC_INC:-/workspace/webrtc-bsd/m152/include}"
TGCALLS="${TGCALLS:-$here/../../src/tgcalls/tgcalls}"
API=27
ABI="${ABI:-arm64-v8a}"
case "$ABI" in arm64-v8a) TRIPLE=aarch64-linux-android;; x86_64) TRIPLE=x86_64-linux-android;; armeabi-v7a) TRIPLE=armv7a-linux-androideabi;; esac
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/${TRIPLE}${API}-clang"; CXX="$TC/${TRIPLE}${API}-clang++"
out="${OUT_DIR:-$here/build}/$ABI"; mkdir -p "$out"
WFLAGS="-Wall -Wextra -Werror -Wno-unused-parameter"
DEFS="-DWEBRTC_POSIX -DWEBRTC_LINUX -DWEBRTC_ANDROID -DNDEBUG"
INCS="-I$WEBRTC_INC -I$WEBRTC_INC/third_party/abseil-cpp"
"$CC" -std=c11 -O2 -fPIC $WFLAGS -c "$here/../core/ol_audio_open.c" -o "$out/ol_audio_open.o"
"$CC" -std=c11 -O2 -fPIC $WFLAGS -c "$here/../core/ol_resampler.c" -o "$out/ol_resampler.o"
"$CC" -std=c11 -O2 -fPIC $WFLAGS -c "$here/../core/ol_session_policy.c" -o "$out/ol_session_policy.o"
"$CC" -std=c11 -O2 -fPIC $WFLAGS -D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__ -Werror=unguarded-availability -c "$here/cpp/aaudio_backend.c" -o "$out/aaudio_backend.o"
"$CC" -std=c11 -O2 -fPIC $WFLAGS -c "$here/cpp/opensl_backend.c" -o "$out/opensl_backend.o"
"$CXX" -std=c++20 -O2 -fPIC $WFLAGS $DEFS $INCS -c "$here/cpp/OlAudioDeviceModule.cpp" -o "$out/OlAudioDeviceModule.o"
# 1) core + backends: fully linked, no undefined symbols
"$CC" -shared -o "$out/libolaudio_core.so" "$out"/ol_audio_open.o "$out"/ol_resampler.o "$out"/ol_session_policy.o "$out"/aaudio_backend.o "$out"/opensl_backend.o -laaudio -lOpenSLES -llog -lm -Wl,--no-undefined -Wl,-z,max-page-size=16384
echo "built $out/libolaudio_core.so"
"$TC/llvm-readelf" -d "$out/libolaudio_core.so" | grep NEEDED
"$TC/llvm-nm" -D --defined-only "$out/libolaudio_core.so" | grep -E " T (ol_open_with_fallback|ol_aaudio_backend|ol_opensl_backend|ol_resampler_process|ol_session_on_set_active)$"
# 2) ADM: static archive, linked into the same .so as tgcalls+WebRTC in the app
"$TC/llvm-ar" rcs "$out/libolaudio_adm.a" "$out"/*.o
echo "built $out/libolaudio_adm.a"
echo "ADM undefined symbols outside libc/libc++/NDK (must be WebRTC only):"
"$TC/llvm-nm" -uC "$out/OlAudioDeviceModule.o" | grep -E "webrtc::" || true
if "$TC/llvm-nm" -uC "$out/OlAudioDeviceModule.o" | grep -vE "webrtc::|std::|__cxa|__gxx|operator (new|delete)|__stack_chk|__android_log|ol_|memset|memcpy|strncpy|clock_gettime|usleep|nanosleep|pthread|_Unwind|__dso_handle|__emutls|abort|errno|__errno|__cxxabiv1|__aarch64_|memmove|strlen" | grep -q .; then
  echo "unexpected undefined symbols:"; "$TC/llvm-nm" -uC "$out/OlAudioDeviceModule.o" | grep -vE "webrtc::|std::|__cxa|__gxx|operator (new|delete)|__stack_chk|__android_log|ol_|memset|memcpy|strncpy|clock_gettime|usleep|nanosleep|pthread|_Unwind|__dso_handle|__emutls|abort|errno|__errno|__cxxabiv1|__aarch64_|memmove|strlen"; exit 1
fi
# tgcalls JNI bridge: syntax check against the pinned tgcalls headers
"$CXX" -std=c++20 -fsyntax-only $DEFS $INCS -I"$TGCALLS" -I"$here/cpp" -Wno-deprecated-declarations "$here/cpp/ol_tgcalls_jni.cpp" && echo "ol_tgcalls_jni.cpp: syntax OK against tgcalls 38dcea1 + WebRTC m152 headers"
