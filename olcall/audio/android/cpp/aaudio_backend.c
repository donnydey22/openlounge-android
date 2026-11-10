/* AAudio backend (API 26+; voice usage/preset applied on API 28+). Every
 * failure returns the raw aaudio_result_t. */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <stdlib.h>

#include "ol_android_backends.h"

#define TAG "OlAudio.AAudio"

typedef struct {
  ol_android_io *io;
  AAudioStream *s[2];
  int channels[2];
} aa_ctx;

static aaudio_data_callback_result_t data_cb(AAudioStream *st, void *user, void *audio, int32_t frames) {
  aa_ctx *c = (aa_ctx *)user;
  if (AAudioStream_getDirection(st) == AAUDIO_DIRECTION_INPUT) {
    ol_ring_write(c->io->ring[OL_DIR_CAPTURE], (const int16_t *)audio, (uint32_t)frames);
  } else {
    ol_ring_read(c->io->ring[OL_DIR_PLAYOUT], (int16_t *)audio, (uint32_t)frames);
  }
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void error_cb(AAudioStream *st, void *user, aaudio_result_t err) {
  (void)st; (void)user;
  /* Disconnect (e.g. headset route change): surfaced via log; the ADM
   * restart path re-runs ol_open_with_fallback. */
  __android_log_print(ANDROID_LOG_WARN, TAG, "stream error %d (%s)", err, AAudio_convertResultToText(err));
}

static void aa_close(void *ctx, ol_direction dir) {
  aa_ctx *c = (aa_ctx *)ctx;
  if (c->s[dir]) {
    AAudioStream_requestStop(c->s[dir]);
    AAudioStream_close(c->s[dir]);
    c->s[dir] = NULL;
  }
}

static int32_t aa_open(void *ctx, ol_direction dir, int rate, int channels, int *actual) {
  aa_ctx *c = (aa_ctx *)ctx;
  aa_close(ctx, dir);
  AAudioStreamBuilder *b = NULL;
  aaudio_result_t r = AAudio_createStreamBuilder(&b);
  if (r != AAUDIO_OK) return r;
  AAudioStreamBuilder_setDirection(b, dir == OL_DIR_CAPTURE ? AAUDIO_DIRECTION_INPUT : AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setChannelCount(b, channels);
  if (rate > 0) AAudioStreamBuilder_setSampleRate(b, rate);
  AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  /* usage/content/preset are API 28+; on API 26-27 AAudio opens without the
   * voice-communication routing hints (WebRTC software APM still runs). */
  if (__builtin_available(android 28, *)) {
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SPEECH);
    if (dir == OL_DIR_CAPTURE) AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
  }
  AAudioStreamBuilder_setDataCallback(b, data_cb, c);
  AAudioStreamBuilder_setErrorCallback(b, error_cb, c);
  r = AAudioStreamBuilder_openStream(b, &c->s[dir]);
  AAudioStreamBuilder_delete(b);
  if (r != AAUDIO_OK) {
    c->s[dir] = NULL;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "openStream dir=%d rate=%d -> %d (%s)", dir, rate, r, AAudio_convertResultToText(r));
    return r;
  }
  if (AAudioStream_getFormat(c->s[dir]) != AAUDIO_FORMAT_PCM_I16 || AAudioStream_getChannelCount(c->s[dir]) != channels) {
    aa_close(ctx, dir);
    return AAUDIO_ERROR_INVALID_FORMAT;
  }
  *actual = AAudioStream_getSampleRate(c->s[dir]);
  return AAUDIO_OK;
}

static int32_t aa_start(void *ctx, ol_direction dir) {
  aa_ctx *c = (aa_ctx *)ctx;
  if (!c->s[dir]) return AAUDIO_ERROR_INVALID_STATE;
  aaudio_result_t r = AAudioStream_requestStart(c->s[dir]);
  if (r != AAUDIO_OK) return r;
  aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
  r = AAudioStream_waitForStateChange(c->s[dir], AAUDIO_STREAM_STATE_STARTING, &next, 500 * 1000000LL);
  if (r != AAUDIO_OK) return r;
  return next == AAUDIO_STREAM_STATE_STARTED ? AAUDIO_OK : AAUDIO_ERROR_INVALID_STATE;
}

ol_backend ol_aaudio_backend(ol_android_io *io) {
  aa_ctx *c = (aa_ctx *)calloc(1, sizeof(aa_ctx));
  c->io = io;
  ol_backend b = {"aaudio", OL_DOMAIN_AAUDIO, aa_open, aa_start, aa_close, c};
  return b;
}
