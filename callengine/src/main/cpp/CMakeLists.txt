cmake_minimum_required(VERSION 3.22)
project(olpjsip C CXX)
find_package(pjproject REQUIRED CONFIG)
# Transport/video legs only (pending WebRTC transport cutover). No PJSIP sound device is ever opened for calls.
add_library(olpjsip SHARED olpjsip.c ol_sfu.c ol_sfu_video.c ol_p2p.c)
target_compile_definitions(olpjsip PRIVATE PJ_AUTOCONF=1)
target_link_libraries(olpjsip pjproject::pjproject log)
# Sole call audio engine. Set -DOL_WEBRTC_DIR=<signalapp/webrtc 8037b out dir> to link the BSD WebRTC ADM.
add_library(olrtcaudio SHARED ol_rtc_audio.cc ol_rtc_pc.cc)
set_target_properties(olrtcaudio PROPERTIES CXX_STANDARD 20)
target_link_libraries(olrtcaudio log)
if(DEFINED OL_WEBRTC_DIR)
  target_compile_definitions(olrtcaudio PRIVATE OL_HAVE_WEBRTC=1 WEBRTC_ANDROID=1 WEBRTC_POSIX=1)
  target_include_directories(olrtcaudio PRIVATE ${OL_WEBRTC_DIR}/include ${OL_WEBRTC_DIR}/include/third_party/abseil-cpp)
  target_link_libraries(olrtcaudio ${OL_WEBRTC_DIR}/lib/${ANDROID_ABI}/libwebrtc.a OpenSLES aaudio
    "-Wl,--allow-multiple-definition") # prebuilt bundles Chromium libc++abi exception types (std::__Cr); OL code stays on NDK libc++
endif()
