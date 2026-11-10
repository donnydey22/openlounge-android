# Classes and members reached from native code by name (JNI). R8 must keep them.
-keep class org.telegram.messenger.voip.VideoCameraCapturer { *; }
-keep class com.donnydey.openlounge.olcall.OlTgcallsEngine {
    native <methods>;
    private void onNative*(...);
}
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
