#include "ol_rtc_audio.h"
#include <jni.h>
#include <atomic>
#include <mutex>
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OLCallAudio", __VA_ARGS__)

#ifdef OL_HAVE_WEBRTC
#include "api/audio/audio_device.h"
#include "api/environment/environment_factory.h"
#include "modules/audio_device/include/audio_device_factory.h"
#endif

namespace ol { namespace rtc_audio {
static std::mutex mu;
static std::atomic<int> actual_rate{0};
static std::atomic<int32_t> cap_cb{0}, ren_cb{0};
#ifdef OL_HAVE_WEBRTC
static webrtc::scoped_refptr<webrtc::AudioDeviceModule> adm;
bool Available() { return true; }
int Open(int in, int out, const int* rates, int n) {
  std::lock_guard<std::mutex> l(mu);
  for (int i = 0; i < n; i++) {
    // TODO(OL): AAudio first, then OpenSL ES via CreateAndroidAudioDeviceModule(kind); honor device ids via
    // AudioManager.setCommunicationDevice (Java) — WebRTC Android ADM follows the communication route.
    adm = webrtc::CreateAudioDeviceModule(webrtc::CreateEnvironment(), webrtc::AudioDeviceModule::kAndroidAAudioAudio);
    if (!adm || adm->Init() != 0) { LOGI("adm init fail rate=%d", rates[i]); adm = nullptr; continue; }
    if (adm->InitRecording() == 0 && adm->InitPlayout() == 0 && adm->StartRecording() == 0 && adm->StartPlayout() == 0) {
      actual_rate = rates[i]; return 0; }
    adm->Terminate(); adm = nullptr;
  }
  return -2;
}
void SetMute(bool m) { std::lock_guard<std::mutex> l(mu); if (adm) adm->SetMicrophoneMute(m); }
void Close() { std::lock_guard<std::mutex> l(mu); if (adm) { adm->StopRecording(); adm->StopPlayout(); adm->Terminate(); adm = nullptr; } actual_rate = 0; }
#else
// Built without the WebRTC fork: engine reports unavailable (typed DEVICE failure upstream, never network).
bool Available() { return false; }
int Open(int, int, const int*, int) { return -1000; }
void SetMute(bool) {}
void Close() { actual_rate = 0; }
#endif
int ActualRate() { return actual_rate; }
void Stats(int32_t* c, int32_t* r) { *c = cap_cb; *r = ren_cb; }
}}

#define J(n) Java_com_donnydey_openlounge_audio_CallAudioEngine_##n
extern "C" {
JNIEXPORT jboolean JNICALL J(nativeAvailable)(JNIEnv*, jclass) { return ol::rtc_audio::Available(); }
JNIEXPORT jint JNICALL J(nativeOpen)(JNIEnv* e, jclass, jint in, jint out, jintArray rates) {
  jsize n = e->GetArrayLength(rates); jint* p = e->GetIntArrayElements(rates, nullptr);
  int r = ol::rtc_audio::Open(in, out, p, n); e->ReleaseIntArrayElements(rates, p, JNI_ABORT); return r; }
JNIEXPORT jint JNICALL J(nativeActualRate)(JNIEnv*, jclass) { return ol::rtc_audio::ActualRate(); }
JNIEXPORT void JNICALL J(nativeSetMute)(JNIEnv*, jclass, jboolean m) { ol::rtc_audio::SetMute(m); }
JNIEXPORT jintArray JNICALL J(nativeStats)(JNIEnv* e, jclass) { int32_t s[2]; ol::rtc_audio::Stats(&s[0], &s[1]);
  jintArray a = e->NewIntArray(2); e->SetIntArrayRegion(a, 0, 2, s); return a; }
JNIEXPORT void JNICALL J(nativeClose)(JNIEnv*, jclass) { ol::rtc_audio::Close(); }
}
