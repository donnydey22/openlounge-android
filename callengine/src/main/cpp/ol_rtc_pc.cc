// OL PeerConnection JNI (contract v1). Real implementation links signalapp/webrtc 8037b (BSD-3) when OL_HAVE_WEBRTC.
// Until the fork is built, every entry reports -1000 (unavailable) -> typed DEVICE failure in Kotlin, never relay.
#include <jni.h>
#define J(n) Java_com_donnydey_openlounge_rtc_OlPeerConnection_##n
#ifndef OL_HAVE_WEBRTC
extern "C" {
JNIEXPORT jint JNICALL J(nativeCreate)(JNIEnv*, jclass, jstring) { return -1000; }
JNIEXPORT jint JNICALL J(nativeSetConfiguration)(JNIEnv*, jclass, jstring) { return -1000; }
JNIEXPORT jint JNICALL J(nativeCreateOffer)(JNIEnv*, jclass, jboolean) { return -1000; }
JNIEXPORT jint JNICALL J(nativeCreateAnswer)(JNIEnv*, jclass) { return -1000; }
JNIEXPORT jint JNICALL J(nativeSetRemote)(JNIEnv*, jclass, jstring, jstring) { return -1000; }
JNIEXPORT jint JNICALL J(nativeAddCandidate)(JNIEnv*, jclass, jstring, jstring, jint) { return -1000; }
JNIEXPORT void JNICALL J(nativeSetMute)(JNIEnv*, jclass, jboolean) {}
JNIEXPORT jstring JNICALL J(nativeTake)(JNIEnv*, jclass) { return nullptr; }
JNIEXPORT void JNICALL J(nativeClose)(JNIEnv*, jclass) {}
}
#else
// TODO(OL, once libwebrtc.a exists): PeerConnectionFactory with ol::rtc_audio ADM; RTCConfiguration
// {iceTransportPolicy=all, bundle=max-bundle, rtcpMux=require}; one Opus audio track; observers push
// "sdp|cand|cand-end|ice|remote-set|error" strings into a locked queue drained by nativeTake.
#error "ol_rtc_pc.cc WebRTC implementation not written yet — build without OL_WEBRTC_DIR until it is"
#endif
