# native-call-ui (Android) — status

Done. Build 0.3.23 (40), release-signed (cert 62e04366…a04be05, same as 38/39), live on openlounge.app/download.
APK SHA-256 048dd640e4702ea4134e896519c766d0ea66bf3b205d9aecab90b31064621f29. 39 stays as the fallback entry.

- NativeCallScreen.kt: native overlay above the WebView. Flow/states/placement per Telegram
  (DrKLO/Telegram@3de37c8 VoIPService.java, VoIPFragment.java updateViewState/updateButtons); look = Open Lounge web
  call screen (CallOverlay.tsx, calls.module.css, Avatar.tsx). Page engine.ts on openlounge.app stays the only owner
  of call:* (no server change).
- Gate: Prefs.native_call_ui (default on). Video calls keep the native screen only once native tiles are up, else the
  web call screen (fallback) is used.
- Tests: 46 unit tests, 0 failures (incl. NativeCallFlowTest, Robolectric NativeCallScreenRenderTest rendering
  calling/connecting/active/speaker and checking End call -> call:hangup).
- Not done: a live native-to-web call on the emulator (no KVM; software emulator's system_server kept ANR-ing).
