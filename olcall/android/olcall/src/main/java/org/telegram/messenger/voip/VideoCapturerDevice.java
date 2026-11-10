// Clean-room implementation (Open Lounge) of the Java class that tgcalls'
// Android platform layer calls by name. Written from the JNI contract in
// Telegram-Android's tgcalls (submodule 545f5ae, the revision
// android/native/build-tgcalls-android-sdk.sh builds); no Telegram-Android Java
// was copied:
//
//   tgcalls/platform/android/AndroidContext.cpp
//     FindClass("org/telegram/messenger/voip/VideoCapturerDevice")
//     <init>(Z)V                      (screencast) once per capture interface
//     onDestroy()V                    when the capture interface is destroyed
//   tgcalls/platform/android/VideoCameraCapturer.cpp
//     init(JLjava/lang/String;)V      (native capturer ptr, deviceId "front"/"back")
//     onStateChanged(JI)V             (ptr, tgcalls::VideoState: 0 Inactive, 1 Paused, 2 Active)
//     onAspectRatioRequested(F)V
//     static native nativeGetJavaVideoCapturerObserver(J)Lorg/olwrtc/CapturerObserver;
//   tgcalls/platform/android/AndroidInterface.cpp (encoder/decoder factories, every call)
//     static getSharedEGLContext()Lorg/olwrtc/EglBase$Context;
//   org/olwrtc/voiceengine/WebRtcAudioRecord.java (Telegram's patched org.olwrtc)
//     static getMediaProjection()Landroid/media/projection/MediaProjection;  (OL: none)
//
// Host fix 2026-11-09: was VideoCameraCapturer (init(JZ)V), the contract of the
// Telegram-iOS-pinned tgcalls; the Android SDK is built from Telegram-Android's
// tgcalls, whose contract is the one above.
package org.telegram.messenger.voip;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;

import org.olwrtc.Camera1Enumerator;
import org.olwrtc.Camera2Enumerator;
import org.olwrtc.CameraEnumerator;
import org.olwrtc.CameraVideoCapturer;
import org.olwrtc.CapturerObserver;
import org.olwrtc.ContextUtils;
import org.olwrtc.EglBase;
import org.olwrtc.SurfaceTextureHelper;

public class VideoCapturerDevice {
    // Capture format; tgcalls/WebRTC adapts resolution/fps to bandwidth from here.
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;

    private static EglBase sharedEgl;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper textureHelper;
    private long nativePtr;
    private boolean front = true;
    private boolean capturing;

    /** EGL context shared by tgcalls' codec factories and OL's SurfaceViewRenderers. */
    public static synchronized EglBase.Context getSharedEGLContext() {
        if (sharedEgl == null) sharedEgl = EglBase.create();
        return sharedEgl.getEglBaseContext();
    }

    /** OL has no screen sharing. */
    public static MediaProjection getMediaProjection() {
        return null;
    }

    private final boolean screencast;

    public VideoCapturerDevice(boolean screencast) {
        this.screencast = screencast;
    }

    // native: VideoCameraCapturer::VideoCameraCapturer(...)
    @SuppressWarnings("unused")
    public void init(long ptr, String deviceId) {
        nativePtr = ptr;
        front = deviceId == null || !deviceId.toLowerCase(java.util.Locale.ROOT).contains("back");
        // The observer must be fetched synchronously while the native object is alive.
        final CapturerObserver observer = nativeGetJavaVideoCapturerObserver(ptr);
        main.post(() -> open(observer));
    }

    // native: VideoCameraCapturer::setState
    @SuppressWarnings("unused")
    public void onStateChanged(long ptr, int state) {
        if (ptr != nativePtr) return;
        main.post(() -> {
            if (capturer == null) return;
            if (state == 2) {
                start();
            } else {
                stop();
            }
        });
    }

    // native: VideoCameraCapturer::setPreferredCaptureAspectRatio. Capture stays
    // 16:9; the encoder crops/scales per the far side's request.
    @SuppressWarnings("unused")
    public void onAspectRatioRequested(float aspectRatio) {
    }

    // native: AndroidContext::~AndroidContext
    @SuppressWarnings("unused")
    public void onDestroy() {
        nativePtr = 0;
        main.post(() -> {
            stop();
            if (capturer != null) {
                capturer.dispose();
                capturer = null;
            }
            if (textureHelper != null) {
                textureHelper.dispose();
                textureHelper = null;
            }
        });
    }

    /** Front/back switch requested through tgcalls switchToDevice reaches us as a new init(); this is the direct path. */
    public void switchCamera() {
        main.post(() -> {
            if (capturer != null) capturer.switchCamera(null);
        });
    }

    private void open(CapturerObserver observer) {
        Context ctx = ContextUtils.getApplicationContext();
        if (ctx == null || observer == null || screencast) return;
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(ctx)
                ? new Camera2Enumerator(ctx) : new Camera1Enumerator(false);
        String chosen = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name) == front) {
                chosen = name;
                break;
            }
        }
        if (chosen == null && enumerator.getDeviceNames().length > 0) chosen = enumerator.getDeviceNames()[0];
        if (chosen == null) return; // no camera: tgcalls keeps the channel, sends nothing
        capturer = enumerator.createCapturer(chosen, null);
        textureHelper = SurfaceTextureHelper.create("ol-camera", getSharedEGLContext());
        capturer.initialize(textureHelper, ctx, observer);
    }

    private void start() {
        if (capturer == null || capturing) return;
        capturer.startCapture(WIDTH, HEIGHT, FPS);
        capturing = true;
    }

    private void stop() {
        if (capturer == null || !capturing) return;
        try {
            capturer.stopCapture();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        capturing = false;
    }

    private static native CapturerObserver nativeGetJavaVideoCapturerObserver(long ptr);
}
