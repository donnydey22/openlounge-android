cmake_minimum_required(VERSION 3.22)
project(olpjsip C)
find_package(pjproject REQUIRED CONFIG)
# Transport/video legs only (pending WebRTC transport cutover). No PJSIP sound device is ever opened for calls.
add_library(olpjsip SHARED olpjsip.c ol_sfu.c ol_sfu_video.c ol_p2p.c)
target_compile_definitions(olpjsip PRIVATE PJ_AUTOCONF=1)
target_link_libraries(olpjsip pjproject::pjproject log)
# Call audio + PeerConnection: org.webrtc (BSD AAR) via Kotlin audio/CallAudioEngine.kt + rtc/OlPeerConnection.kt.
