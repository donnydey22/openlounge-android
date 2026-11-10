// OlAudioDeviceModule: custom webrtc::AudioDeviceModule for tgcalls on
// Android, injected via tgcalls::Descriptor::createAudioDeviceModule
// (tgcalls Instance.h:240). Opens each direction with
// ol_open_with_fallback(AAudio default -> cleanup -> OpenSL ES @16 kHz) and
// resamples device rate <-> 48 kHz engine rate. Every open report (verbatim
// native statuses) goes to the status listener (JNI -> Kotlin -> breadcrumbs).
#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

// Newer WebRTC moved the ADM interface to api/audio; Telegram-Android's WebRTC
// (the revision the Android SDK is built from) still has it in modules/.
#if __has_include("api/audio/audio_device.h")
#include "api/audio/audio_device.h"
#else
#include "modules/audio_device/include/audio_device.h"
#endif
#include "api/task_queue/task_queue_factory.h"

extern "C" {
#include "../../core/ol_audio.h"
#include "ol_android_backends.h"
}

namespace olcall {

using StatusListener = std::function<void(const std::string& reportJson)>;

class OlAudioDeviceModule : public webrtc::AudioDeviceModule {
 public:
  static constexpr int kEngineRate = 48000;
  explicit OlAudioDeviceModule(StatusListener listener);
  ~OlAudioDeviceModule() override;

  // Last open reports (JSON), for the app's call diagnostics.
  std::string LastReport(ol_direction dir) const;

  // webrtc::AudioDeviceModule
  int32_t ActiveAudioLayer(AudioLayer* layer) const override;
  int32_t RegisterAudioCallback(webrtc::AudioTransport* cb) override;
  int32_t Init() override;
  int32_t Terminate() override;
  bool Initialized() const override;
  int16_t PlayoutDevices() override { return 1; }
  int16_t RecordingDevices() override { return 1; }
  int32_t PlayoutDeviceName(uint16_t, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) override;
  int32_t RecordingDeviceName(uint16_t, char name[webrtc::kAdmMaxDeviceNameSize], char guid[webrtc::kAdmMaxGuidSize]) override;
  int32_t SetPlayoutDevice(uint16_t) override { return 0; }
  int32_t SetPlayoutDevice(WindowsDeviceType) override { return 0; }
  int32_t SetRecordingDevice(uint16_t) override { return 0; }
  int32_t SetRecordingDevice(WindowsDeviceType) override { return 0; }
  int32_t PlayoutIsAvailable(bool* a) override { *a = true; return 0; }
  int32_t InitPlayout() override;
  bool PlayoutIsInitialized() const override { return play_init_; }
  int32_t RecordingIsAvailable(bool* a) override { *a = true; return 0; }
  int32_t InitRecording() override;
  bool RecordingIsInitialized() const override { return rec_init_; }
  int32_t StartPlayout() override;
  int32_t StopPlayout() override;
  bool Playing() const override { return playing_; }
  int32_t StartRecording() override;
  int32_t StopRecording() override;
  bool Recording() const override { return recording_; }
  int32_t InitSpeaker() override { return 0; }
  bool SpeakerIsInitialized() const override { return true; }
  int32_t InitMicrophone() override { return 0; }
  bool MicrophoneIsInitialized() const override { return true; }
  int32_t SpeakerVolumeIsAvailable(bool* a) override { *a = false; return 0; }
  int32_t SetSpeakerVolume(uint32_t) override { return -1; }
  int32_t SpeakerVolume(uint32_t*) const override { return -1; }
  int32_t MaxSpeakerVolume(uint32_t*) const override { return -1; }
  int32_t MinSpeakerVolume(uint32_t*) const override { return -1; }
  int32_t MicrophoneVolumeIsAvailable(bool* a) override { *a = false; return 0; }
  int32_t SetMicrophoneVolume(uint32_t) override { return -1; }
  int32_t MicrophoneVolume(uint32_t*) const override { return -1; }
  int32_t MaxMicrophoneVolume(uint32_t*) const override { return -1; }
  int32_t MinMicrophoneVolume(uint32_t*) const override { return -1; }
  int32_t SpeakerMuteIsAvailable(bool* a) override { *a = false; return 0; }
  int32_t SetSpeakerMute(bool) override { return -1; }
  int32_t SpeakerMute(bool*) const override { return -1; }
  int32_t MicrophoneMuteIsAvailable(bool* a) override { *a = true; return 0; }
  int32_t SetMicrophoneMute(bool enable) override { mic_muted_ = enable; return 0; }
  int32_t MicrophoneMute(bool* e) const override { *e = mic_muted_; return 0; }
  int32_t StereoPlayoutIsAvailable(bool* a) const override { *a = false; return 0; }
  int32_t SetStereoPlayout(bool e) override { return e ? -1 : 0; }
  int32_t StereoPlayout(bool* e) const override { *e = false; return 0; }
  int32_t StereoRecordingIsAvailable(bool* a) const override { *a = false; return 0; }
  int32_t SetStereoRecording(bool e) override { return e ? -1 : 0; }
  int32_t StereoRecording(bool* e) const override { *e = false; return 0; }
  int32_t PlayoutDelay(uint16_t* ms) const override { *ms = 40; return 0; }
  // Platform AEC/NS come from the VOICE_COMMUNICATION preset on both
  // backends; report "not available" so WebRTC keeps its software APM on
  // (safer across Samsung HAL variance; revisit with device data).
  bool BuiltInAECIsAvailable() const override { return false; }
  bool BuiltInAGCIsAvailable() const override { return false; }
  bool BuiltInNSIsAvailable() const override { return false; }
  int32_t EnableBuiltInAEC(bool) override { return -1; }
  int32_t EnableBuiltInAGC(bool) override { return -1; }
  int32_t EnableBuiltInNS(bool) override { return -1; }

 private:
  int32_t OpenDir(ol_direction dir);
  void DriverLoop();
  void EnsureDriver();
  void StopDriverIfIdle();

  StatusListener listener_;
  mutable std::mutex mu_;
  webrtc::AudioTransport* transport_ = nullptr;
  bool initialized_ = false;
  bool play_init_ = false, rec_init_ = false;
  std::atomic<bool> playing_{false}, recording_{false}, mic_muted_{false};
  std::atomic<bool> run_{false};
  std::thread driver_;

  int16_t ring_storage_[2][1 << 14];
  ol_ring ring_[2];
  ol_android_io io_;
  ol_backend aaudio_, opensl_;
  const ol_backend* active_[2] = {nullptr, nullptr};
  ol_open_report report_[2];
  ol_resampler rs_capture_, rs_playout_;
  std::string report_json_[2];
};

// Factory matching tgcalls Descriptor::createAudioDeviceModule's signature.
webrtc::scoped_refptr<webrtc::AudioDeviceModule> CreateOlAudioDeviceModule(webrtc::TaskQueueFactory* tq, StatusListener listener);

}  // namespace olcall
