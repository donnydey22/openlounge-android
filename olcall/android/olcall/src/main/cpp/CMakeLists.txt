# libolcall_tgcalls.so = JNI bridge + OL audio device module + prebuilt
# tgcalls/WebRTC (android/native/build-tgcalls-android-sdk.sh output).
cmake_minimum_required(VERSION 3.22.1)
project(olcall_tgcalls C CXX)

if(NOT OLCALL_TGCALLS_SDK OR NOT EXISTS "${OLCALL_TGCALLS_SDK}/lib/${ANDROID_ABI}/libtgcalls_webrtc.a")
  message(FATAL_ERROR
    "OLCALL_TGCALLS_SDK must point at the output of android/native/build-tgcalls-android-sdk.sh "
    "(missing ${OLCALL_TGCALLS_SDK}/lib/${ANDROID_ABI}/libtgcalls_webrtc.a)")
endif()

# Sources live once in this package: <root>/audio (shared with the iOS bridge and tests).
get_filename_component(OL_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(OL_AUDIO "${OL_ROOT}/audio")

add_library(tgcalls_webrtc STATIC IMPORTED)
set_target_properties(tgcalls_webrtc PROPERTIES IMPORTED_LOCATION "${OLCALL_TGCALLS_SDK}/lib/${ANDROID_ABI}/libtgcalls_webrtc.a")
add_library(tgcalls_webrtc_jni STATIC IMPORTED)
set_target_properties(tgcalls_webrtc_jni PROPERTIES IMPORTED_LOCATION "${OLCALL_TGCALLS_SDK}/lib/${ANDROID_ABI}/libtgcalls_webrtc_jni.a")

add_library(olcall_tgcalls SHARED
  ${OL_AUDIO}/android/cpp/ol_tgcalls_jni.cpp
  ${OL_AUDIO}/android/cpp/OlAudioDeviceModule.cpp
  ${OL_AUDIO}/android/cpp/aaudio_backend.c
  ${OL_AUDIO}/android/cpp/opensl_backend.c
  ${OL_AUDIO}/core/ol_audio_open.c
  ${OL_AUDIO}/core/ol_resampler.c
  ${OL_AUDIO}/core/ol_session_policy.c
)

set(SDK_INC "${OLCALL_TGCALLS_SDK}/include")
target_include_directories(olcall_tgcalls PRIVATE
  ${OL_AUDIO}/android/cpp
  ${OL_AUDIO}/core
  ${SDK_INC}/tgcalls            # tgcalls/tgcalls (Instance.h, v2/, platform/android/)
  ${SDK_INC}/webrtc             # webrtc root (api/, sdk/android/native_api/, ...)
  ${SDK_INC}/webrtc/third_party/abseil-cpp
  ${SDK_INC}/absl
  ${SDK_INC}/libyuv/include
  ${SDK_INC}/libyuv              # build-tgcalls-android-sdk.sh copies libyuv/include here
)
target_compile_definitions(olcall_tgcalls PRIVATE
  WEBRTC_POSIX WEBRTC_LINUX WEBRTC_ANDROID RTC_ENABLE_VP9 NDEBUG
  __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
set_source_files_properties(
  ${OL_AUDIO}/android/cpp/aaudio_backend.c ${OL_AUDIO}/android/cpp/opensl_backend.c
  ${OL_AUDIO}/core/ol_audio_open.c ${OL_AUDIO}/core/ol_resampler.c ${OL_AUDIO}/core/ol_session_policy.c
  PROPERTIES COMPILE_OPTIONS "-std=c11")
target_compile_options(olcall_tgcalls PRIVATE -Wall -Wno-unused-parameter -fvisibility=hidden)

target_link_libraries(olcall_tgcalls
  # Same split as Telegram-Android's own link line: JNI/voip objects whole, the rest on demand.
  -Wl,--whole-archive tgcalls_webrtc_jni -Wl,--no-whole-archive
  tgcalls_webrtc
  aaudio OpenSLES android log EGL GLESv2 GLESv3 m dl z)
# -Bsymbolic: Telegram's prebuilt ffmpeg/vpx objects use non-PIC-safe references to
# their own default-visibility data (ff_tx_tab_*); Telegram links with the same flag.
target_link_options(olcall_tgcalls PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384
  -Wl,-z,common-page-size=16384 -Wl,--gc-sections -Wl,-Bsymbolic)
