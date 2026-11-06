// Open Lounge WebRTC audio engine (clean-room). Links signalapp/webrtc (BSD-3) AudioDeviceModule when OL_HAVE_WEBRTC.
#pragma once
#include <cstdint>
namespace ol { namespace rtc_audio {
bool Available();
int Open(int input_id, int output_id, const int* rates, int n_rates); // 0 ok, <0 engine code
int ActualRate();
void SetMute(bool muted);
void Stats(int32_t* capture_cb, int32_t* render_cb);
void Close();
}}
