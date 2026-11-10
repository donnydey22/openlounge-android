#include "OlAudioDeviceModule.h"

#include <android/log.h>
#include <time.h>
#include <unistd.h>

#include <chrono>
#include <cstring>

#include "api/make_ref_counted.h"

#define TAG "OlAudio.ADM"

namespace olcall {
namespace {

int64_t MonoNs(void*) {
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}
void SleepMs(void*, int64_t ms) { usleep(static_cast<useconds_t>(ms * 1000)); }

}  // namespace

OlAudioDeviceModule::OlAudioDeviceModule(StatusListener listener) : listener_(std::move(listener)) {
  for (int d = 0; d < 2; d++) {
    ol_ring_init(&ring_[d], ring_storage_[d], 1 << 14);
    io_.ring[d] = &ring_[d];
    std::memset(&report_[d], 0, sizeof(report_[d]));
  }
  aaudio_ = ol_aaudio_backend(&io_);
  opensl_ = ol_opensl_backend(&io_);
}

OlAudioDeviceModule::~OlAudioDeviceModule() { Terminate(); }

std::string OlAudioDeviceModule::LastReport(ol_direction dir) const {
  std::lock_guard<std::mutex> l(mu_);
  return report_json_[dir];
}

int32_t OlAudioDeviceModule::ActiveAudioLayer(AudioLayer* layer) const {
  std::lock_guard<std::mutex> l(mu_);
  const ol_backend* b = active_[OL_DIR_CAPTURE] ? active_[OL_DIR_CAPTURE] : active_[OL_DIR_PLAYOUT];
  *layer = (b == &opensl_) ? kAndroidOpenSLESAudio : kAndroidAAudioAudio;
  return 0;
}

int32_t OlAudioDeviceModule::RegisterAudioCallback(webrtc::AudioTransport* cb) {
  std::lock_guard<std::mutex> l(mu_);
  transport_ = cb;
  return 0;
}

int32_t OlAudioDeviceModule::Init() {
  initialized_ = true;
  return 0;
}

int32_t OlAudioDeviceModule::Terminate() {
  StopRecording();
  StopPlayout();
  run_ = false;
  if (driver_.joinable()) driver_.join();
  initialized_ = false;
  return 0;
}

bool OlAudioDeviceModule::Initialized() const { return initialized_; }

int32_t OlAudioDeviceModule::PlayoutDeviceName(uint16_t, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) {
  std::strncpy(name, "olcall-playout", webrtc::kAdmMaxDeviceNameSize - 1);
  guid[0] = 0;
  return 0;
}

int32_t OlAudioDeviceModule::RecordingDeviceName(uint16_t, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) {
  std::strncpy(name, "olcall-capture", webrtc::kAdmMaxDeviceNameSize - 1);
  guid[0] = 0;
  return 0;
}

// Default open, then 16 kHz alternate-backend retry with cleanup; the
// report (verbatim native statuses) always goes to the listener, success or
// not. Returns 0 or the LAST native status (never a synthesized code).
int32_t OlAudioDeviceModule::OpenDir(ol_direction dir) {
  ol_open_config cfg{kEngineRate, 1, 120, MonoNs, SleepMs, nullptr};
  ol_open_report rep;
  int ok = ol_open_with_fallback(&cfg, dir, &aaudio_, &opensl_, &rep);
  char json[2048];
  ol_open_report_json(&rep, dir, json, sizeof json);
  {
    std::lock_guard<std::mutex> l(mu_);
    report_[dir] = rep;
    report_json_[dir] = json;
    active_[dir] = ok ? (rep.backend_index == 0 ? &aaudio_ : &opensl_) : nullptr;
    if (ok) {
      ol_resampler* rs = dir == OL_DIR_CAPTURE ? &rs_capture_ : &rs_playout_;
      if (dir == OL_DIR_CAPTURE)
        ol_resampler_init(rs, rep.device_rate, kEngineRate);
      else
        ol_resampler_init(rs, kEngineRate, rep.device_rate);
    }
  }
  __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG, "open %s", json);
  if (listener_) listener_(json);
  return ok ? 0 : (rep.last_error_status != 0 ? rep.last_error_status : -1);
}

int32_t OlAudioDeviceModule::InitPlayout() {
  if (play_init_) return 0;
  int32_t r = OpenDir(OL_DIR_PLAYOUT);
  play_init_ = r == 0;
  return r;
}

int32_t OlAudioDeviceModule::InitRecording() {
  if (rec_init_) return 0;
  int32_t r = OpenDir(OL_DIR_CAPTURE);
  rec_init_ = r == 0;
  return r;
}

// ol_open_with_fallback already started the streams (open+start count as one
// attempt so a start failure also triggers the fallback); Start* only gates
// data flow to/from WebRTC.
int32_t OlAudioDeviceModule::StartPlayout() {
  if (!play_init_) return -1;
  playing_ = true;
  EnsureDriver();
  return 0;
}

int32_t OlAudioDeviceModule::StopPlayout() {
  playing_ = false;
  std::lock_guard<std::mutex> l(mu_);
  if (active_[OL_DIR_PLAYOUT]) active_[OL_DIR_PLAYOUT]->close(active_[OL_DIR_PLAYOUT]->ctx, OL_DIR_PLAYOUT);
  active_[OL_DIR_PLAYOUT] = nullptr;
  play_init_ = false;
  return 0;
}

int32_t OlAudioDeviceModule::StartRecording() {
  if (!rec_init_) return -1;
  recording_ = true;
  EnsureDriver();
  return 0;
}

int32_t OlAudioDeviceModule::StopRecording() {
  recording_ = false;
  std::lock_guard<std::mutex> l(mu_);
  if (active_[OL_DIR_CAPTURE]) active_[OL_DIR_CAPTURE]->close(active_[OL_DIR_CAPTURE]->ctx, OL_DIR_CAPTURE);
  active_[OL_DIR_CAPTURE] = nullptr;
  rec_init_ = false;
  return 0;
}

void OlAudioDeviceModule::EnsureDriver() {
  if (run_.exchange(true)) return;
  if (driver_.joinable()) driver_.join();
  driver_ = std::thread([this] { DriverLoop(); });
}

// 10 ms engine tick: capture ring (device rate) -> resample -> WebRTC;
// WebRTC playout (48 kHz) -> resample -> playout ring (device rate).
void OlAudioDeviceModule::DriverLoop() {
  constexpr size_t kEngineFrames = kEngineRate / 100;
  int16_t dev[kEngineFrames * 6];
  int16_t eng[kEngineFrames + 8];
  auto next = std::chrono::steady_clock::now();
  while (run_ && (playing_ || recording_)) {
    next += std::chrono::milliseconds(10);
    webrtc::AudioTransport* t;
    int capRate, playRate;
    {
      std::lock_guard<std::mutex> l(mu_);
      t = transport_;
      capRate = report_[OL_DIR_CAPTURE].device_rate;
      playRate = report_[OL_DIR_PLAYOUT].device_rate;
    }
    if (t && recording_ && capRate > 0) {
      uint32_t n = static_cast<uint32_t>(capRate / 100);
      if (ol_ring_avail(&ring_[OL_DIR_CAPTURE]) >= n) {
        ol_ring_read(&ring_[OL_DIR_CAPTURE], dev, n);
        int m = ol_resampler_process(&rs_capture_, dev, static_cast<int>(n), eng);
        if (mic_muted_) std::memset(eng, 0, sizeof(int16_t) * static_cast<size_t>(m));
        uint32_t newMic = 0;
        t->RecordedDataIsAvailable(eng, static_cast<size_t>(m), 2, 1, kEngineRate, 40, 0, 0, false, newMic);
      }
    }
    if (t && playing_ && playRate > 0) {
      size_t got = 0;
      int64_t elapsed = 0, ntp = 0;
      t->NeedMorePlayData(kEngineFrames, 2, 1, kEngineRate, eng, got, &elapsed, &ntp);
      if (got < kEngineFrames) std::memset(eng + got, 0, (kEngineFrames - got) * sizeof(int16_t));
      int m = ol_resampler_process(&rs_playout_, eng, static_cast<int>(kEngineFrames), dev);
      ol_ring_write(&ring_[OL_DIR_PLAYOUT], dev, static_cast<uint32_t>(m));
    }
    std::this_thread::sleep_until(next);
  }
  run_ = false;
}

webrtc::scoped_refptr<webrtc::AudioDeviceModule> CreateOlAudioDeviceModule(webrtc::TaskQueueFactory*, StatusListener listener) {
#if __has_include("api/audio/audio_device.h")
  return webrtc::make_ref_counted<OlAudioDeviceModule>(std::move(listener));
#else  // Telegram-Android WebRTC: rtc:: namespace
  return rtc::make_ref_counted<OlAudioDeviceModule>(std::move(listener));
#endif
}

}  // namespace olcall
