// JNI bridge: Kotlin OlTgcallsEngine <-> tgcalls::Instance (InstanceV2Impl via
// tgcalls::Meta). Blobs are passed as byte[] verbatim in both directions; the
// Kotlin side ships them over the olcall WebSocket to the Go/Pion server.
// Callbacks arrive on tgcalls StaticThreads; we attach the thread to the JVM
// and post to Kotlin, which hops off to its own executor (never block here).
//
// Media path (proven on Linux with the same library, 14/14 matrix cells,
// out/tgpeer-20261109T193633Z.json): ICE host/srflx to the olcall server's
// Pion leg, DTLS-SRTP, Opus + VP8, SCTP data channel. That requires
// Config.enableP2P = true: with no reflectors configured, enableP2P=false
// leaves tgcalls without any candidate that reaches the server (the earlier
// version of this file had false; fixed 2026-11-09).
//
// Video: camera capture goes through tgcalls' own Android platform layer
// (platform/android/AndroidContext + VideoCameraCapturer), which calls the Java
// class org.telegram.messenger.voip.VideoCapturerDevice. OL ships a clean-room
// implementation of that class on the BSD org.olwrtc SDK
// (android/olcall/src/main/java/org/telegram/messenger/voip/VideoCapturerDevice.java).
// Incoming video is delivered to an org.olwrtc.VideoSink (SurfaceViewRenderer).
#include <jni.h>

#include <array>
#include <cstdarg>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Instance.h"
#include "StaticThreads.h"
#include "VideoCaptureInterface.h"
#include "v2/InstanceV2Impl.h"
#include "platform/android/AndroidContext.h"
#include "sdk/android/native_api/base/init.h"
#include "sdk/android/native_api/jni/jvm.h"
#include "sdk/android/native_api/video/wrapper.h"
#include "OlAudioDeviceModule.h"

namespace {

JavaVM* g_vm = nullptr;

// Holds a native sink as shared_ptr: Instance::setIncomingVideoOutput takes a
// weak_ptr, so the Bridge must own it for the call's lifetime.
struct Bridge {
  jobject owner = nullptr;  // global ref to OlTgcallsEngine
  std::shared_ptr<tgcalls::Instance> instance;
  std::shared_ptr<tgcalls::VideoCaptureInterface> capture;
  std::shared_ptr<rtc::VideoSinkInterface<webrtc::VideoFrame>> remoteSink;
  std::shared_ptr<rtc::VideoSinkInterface<webrtc::VideoFrame>> localSink;
  std::mutex mu;
};

JNIEnv* Env() {
  JNIEnv* env = nullptr;
  if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) g_vm->AttachCurrentThread(&env, nullptr);
  return env;
}

void CallVoid(jobject owner, const char* name, const char* sig, ...) {
  JNIEnv* env = Env();
  jclass cls = env->GetObjectClass(owner);
  jmethodID m = env->GetMethodID(cls, name, sig);
  va_list ap;
  va_start(ap, sig);
  env->CallVoidMethodV(owner, m, ap);
  va_end(ap);
  env->DeleteLocalRef(cls);
  if (env->ExceptionCheck()) env->ExceptionClear();
}

std::string JStr(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}

std::shared_ptr<rtc::VideoSinkInterface<webrtc::VideoFrame>> WrapSink(JNIEnv* env, jobject javaSink) {
  if (!javaSink) return nullptr;
  return std::shared_ptr<rtc::VideoSinkInterface<webrtc::VideoFrame>>(webrtc::JavaToNativeVideoSink(env, javaSink).release());
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  webrtc::InitAndroid(vm);  // org.olwrtc JNI class loader + JVM for tgcalls' Android platform layer
  tgcalls::Register<tgcalls::InstanceV2Impl>();
  return JNI_VERSION_1_6;
}

// Versions this build can create (InstanceV2Impl registers 7.0.0 .. 13.0.0).
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_donnydey_openlounge_olcall_OlTgcallsEngine_nativeVersions(JNIEnv* env, jclass) {
  const auto versions = tgcalls::Meta::Versions();
  jobjectArray out = env->NewObjectArray(static_cast<jsize>(versions.size()), env->FindClass("java/lang/String"), nullptr);
  for (size_t i = 0; i < versions.size(); ++i) {
    jstring s = env->NewStringUTF(versions[i].c_str());
    env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return out;
}

static std::vector<tgcalls::RtcServer> ReadRtcServers(JNIEnv* env, jobjectArray hosts, jintArray ports, jobjectArray logins,
                                                      jobjectArray passwords, jintArray flags) {
  std::vector<tgcalls::RtcServer> out;
  if (!hosts || !ports || !logins || !passwords || !flags) return out;
  const jsize n = env->GetArrayLength(hosts);
  if (env->GetArrayLength(ports) != n || env->GetArrayLength(logins) != n || env->GetArrayLength(passwords) != n ||
      env->GetArrayLength(flags) != n)
    return out;
  std::vector<jint> p(n), f(n);
  if (n > 0) {
    env->GetIntArrayRegion(ports, 0, n, p.data());
    env->GetIntArrayRegion(flags, 0, n, f.data());
  }
  for (jsize i = 0; i < n; ++i) {
    auto str = [&](jobjectArray a) {
      auto js = static_cast<jstring>(env->GetObjectArrayElement(a, i));
      std::string v = JStr(env, js);
      if (js) env->DeleteLocalRef(js);
      return v;
    };
    tgcalls::RtcServer r;
    r.id = 0;
    r.host = str(hosts);
    r.port = static_cast<uint16_t>(p[i]);
    r.login = str(logins);
    r.password = str(passwords);
    r.isTurn = (f[i] & 1) != 0;
    r.isTcp = (f[i] & 2) != 0;
    if (r.host.empty() || p[i] <= 0 || p[i] > 65535 || r.login == "reflector") continue;
    out.push_back(std::move(r));
  }
  return out;
}

// key: 256 bytes from the server's "joined" frame; isOutgoing as given.
// video: start with the front camera on. remoteSink/localSink: org.olwrtc.VideoSink or null.
extern "C" JNIEXPORT jlong JNICALL Java_com_donnydey_openlounge_olcall_OlTgcallsEngine_nativeCreate(
    JNIEnv* env, jobject self, jstring version, jbyteArray key, jboolean isOutgoing, jstring customParametersJson,
    jboolean video, jobject remoteSink, jobject localSink,
    jobjectArray rtcHosts, jintArray rtcPorts, jobjectArray rtcLogins, jobjectArray rtcPasswords, jintArray rtcFlags) {
  if (env->GetArrayLength(key) != tgcalls::EncryptionKey::kSize) return 0;
  auto* b = new Bridge();
  b->owner = env->NewGlobalRef(self);
  auto keyBytes = std::make_shared<std::array<uint8_t, tgcalls::EncryptionKey::kSize>>();
  env->GetByteArrayRegion(key, 0, tgcalls::EncryptionKey::kSize, reinterpret_cast<jbyte*>(keyBytes->data()));

  if (video == JNI_TRUE) {
    b->capture = tgcalls::VideoCaptureInterface::Create(tgcalls::StaticThreads::getThreads(), "front", false,
                                                        std::make_shared<tgcalls::AndroidContext>(env, nullptr, nullptr, false));
    b->localSink = WrapSink(env, localSink);
    if (b->capture && b->localSink) b->capture->setOutput(b->localSink);
  }
  b->remoteSink = WrapSink(env, remoteSink);

  tgcalls::Config config;
  config.initializationTimeout = 30.0;
  config.receiveTimeout = 20.0;
  config.enableP2P = true;  // the olcall server is reached through its host/srflx candidates
  config.enableAEC = true;  // Android: WebRTC APM does echo control (no platform VPIO as on iOS)
  config.enableNS = true;
  config.enableAGC = true;
  config.customParameters = JStr(env, customParametersJson);

  jobject owner = b->owner;
  tgcalls::Descriptor d{
      .version = JStr(env, version),
      .config = config,
      .encryptionKey = tgcalls::EncryptionKey(keyBytes, isOutgoing == JNI_TRUE),
      .videoCapture = b->capture,
      .stateUpdated = [owner](tgcalls::State s) { CallVoid(owner, "onNativeState", "(I)V", static_cast<jint>(s)); },
      .remoteMediaStateUpdated =
          [owner](tgcalls::AudioState a, tgcalls::VideoState v) {
            CallVoid(owner, "onNativeRemoteMedia", "(II)V", static_cast<jint>(a), static_cast<jint>(v));
          },
      .signalingDataEmitted =
          [owner](const std::vector<uint8_t>& data) {
            JNIEnv* e = Env();
            jbyteArray arr = e->NewByteArray(static_cast<jsize>(data.size()));
            e->SetByteArrayRegion(arr, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
            CallVoid(owner, "onNativeSignalingData", "([B)V", arr);
            e->DeleteLocalRef(arr);
          },
      .createAudioDeviceModule =
          [owner](webrtc::TaskQueueFactory* tq) {
            return olcall::CreateOlAudioDeviceModule(tq, [owner](const std::string& json) {
              JNIEnv* e = Env();
              jstring js = e->NewStringUTF(json.c_str());
              CallVoid(owner, "onNativeAudioOpenReport", "(Ljava/lang/String;)V", js);
              e->DeleteLocalRef(js);
            });
          },
  };
  // Relay list (OlRtcServers / olcall tgrtc rules): coturn STUN + TURN UDP/TCP with
  // the short-lived REST login. enableP2P stays true above, so host/srflx pairs are
  // preferred and TURN is the fallback. Non-"reflector" logins make tgcalls'
  // ReflectorRelayPortFactory create a standard cricket::TurnPort. Never logged.
  d.rtcServers = ReadRtcServers(env, rtcHosts, rtcPorts, rtcLogins, rtcPasswords, rtcFlags);
  // keyBytes is shared with EncryptionKey (shared_ptr): do NOT zero it here.
  b->instance = std::shared_ptr<tgcalls::Instance>(tgcalls::Meta::Create(d.version, std::move(d)).release());
  if (!b->instance) {
    env->DeleteGlobalRef(b->owner);
    delete b;
    return 0;
  }
  if (b->remoteSink) b->instance->setIncomingVideoOutput(b->remoteSink);
  return reinterpret_cast<jlong>(b);
}

extern "C" JNIEXPORT void JNICALL Java_com_donnydey_openlounge_olcall_OlTgcallsEngine_nativeReceiveSignaling(
    JNIEnv* env, jobject, jlong handle, jbyteArray data) {
  auto* b = reinterpret_cast<Bridge*>(handle);
  if (!b) return;
  std::vector<uint8_t> v(static_cast<size_t>(env->GetArrayLength(data)));
  env->GetByteArrayRegion(data, 0, static_cast<jsize>(v.size()), reinterpret_cast<jbyte*>(v.data()));
  std::lock_guard<std::mutex> l(b->mu);
  if (b->instance) b->instance->receiveSignalingData(v);
}

extern "C" JNIEXPORT void JNICALL Java_com_donnydey_openlounge_olcall_OlTgcallsEngine_nativeSetMuted(JNIEnv*, jobject, jlong handle, jboolean muted) {
  auto* b = reinterpret_cast<Bridge*>(handle);
  if (!b) return;
  std::lock_guard<std::mutex> l(b->mu);
  if (b->instance) b->instance->setMuteMicrophone(muted == JNI_TRUE);
}

// Camera on/off mid-call. Turning it on for the first time creates the capturer
// and hands it to the instance (tgcalls renegotiates its video channel).
extern "C" JNIEXPORT void JNICALL Java_com_donnydey_openlounge_olcall_OlTgcallsEngine_nativeSetVideoEnabled(
    JNIEnv* env, jobject, jlong handle, jboolean enabled, jobject localSink) {
  auto* b = reinterpret_cast<Bridge*>(handle);
  if (!b) return;
  std::lock_guard<std::mutex> l(b->mu);
  if (!b->instance) return;
  if (enabled == JNI_TRUE && !b->capture) {
    b->capture = tgcalls::VideoCaptureInterface::Create(tgcalls::StaticThreads::getThreads(), "front", false,
                                                        std::make_shared<tgcalls::AndroidContext>(env, nullptr, nullptr, false));
    b->localSink = WrapSink(env, localSink);
    if (b->capture && b->localSink) b->capture->setOutput(b->localSink);
    b->instance->setVideoCapture(b->capture);
    return;
  }
  if (b->capture) b->capture->setState(enabled == JNI_TRUE ? tgcalls::VideoState::Active : tgcalls::VideoState::Inactive);
}

extern "C" JNIEXPORT void JNICALL Java_com_donnydey_openlounge_olcall_OlTgcallsEngine_nativeSwitchCamera(
    JNIEnv*, jobject, jlong handle, jboolean front) {
  auto* b = reinterpret_cast<Bridge*>(handle);
  if (!b) return;
  std::lock_guard<std::mutex> l(b->mu);
  if (b->capture) b->capture->switchToDevice(front == JNI_TRUE ? "front" : "back", false);
}

extern "C" JNIEXPORT void JNICALL Java_com_donnydey_openlounge_olcall_OlTgcallsEngine_nativeStop(JNIEnv* env, jobject, jlong handle) {
  auto* b = reinterpret_cast<Bridge*>(handle);
  if (!b) return;
  {
    std::lock_guard<std::mutex> l(b->mu);
    if (b->capture) b->capture->setState(tgcalls::VideoState::Inactive);
    if (b->instance) {
      b->instance->stop([](tgcalls::FinalState) {});
      b->instance.reset();
    }
    b->capture.reset();
    b->remoteSink.reset();
    b->localSink.reset();
  }
  env->DeleteGlobalRef(b->owner);
  delete b;
}
