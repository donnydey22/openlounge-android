/* AAudio backend (API 26+; voice usage/preset applied on API 28+). Every
 * failure returns the raw aaudio_result_t.
 *
 * AAudio is resolved at runtime with dlopen/dlsym (the same approach as Google's Oboe AAudioLoader), so
 * libolcall_tgcalls.so does not list libaaudio.so as NEEDED and still loads on API 24-25, where AAudio does not
 * exist. There, aa_open returns AAUDIO_ERROR_UNAVAILABLE and ol_open_with_fallback moves on to OpenSL ES. */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>

#include "ol_android_backends.h"

#define AA_FUNCS(X) \
  X(AAudio_createStreamBuilder) X(AAudio_convertResultToText) \
  X(AAudioStreamBuilder_setDirection) X(AAudioStreamBuilder_setFormat) X(AAudioStreamBuilder_setChannelCount) \
  X(AAudioStreamBuilder_setSampleRate) X(AAudioStreamBuilder_setSharingMode) X(AAudioStreamBuilder_setPerformanceMode) \
  X(AAudioStreamBuilder_setDataCallback) X(AAudioStreamBuilder_setErrorCallback) X(AAudioStreamBuilder_openStream) \
  X(AAudioStreamBuilder_delete) X(AAudioStream_getDirection) X(AAudioStream_requestStop) X(AAudioStream_close) \
  X(AAudioStream_getFormat) X(AAudioStream_getChannelCount) X(AAudioStream_getSampleRate) \
  X(AAudioStream_requestStart) X(AAudioStream_waitForStateChange)
/* API 28+ only: may be NULL on API 26-27 */
#define AA_FUNCS_28(X) X(AAudioStreamBuilder_setUsage) X(AAudioStreamBuilder_setContentType) X(AAudioStreamBuilder_setInputPreset)

#define AA_DECL(f) static __typeof__(&f) p_##f;
AA_FUNCS(AA_DECL)
AA_FUNCS_28(AA_DECL)
static int aa_loaded;
static pthread_once_t aa_once = PTHREAD_ONCE_INIT;

static void aa_load(void) {
  void *h = dlopen("libaaudio.so", RTLD_NOW);
  if (!h) return; /* API 24-25: no AAudio */
  int ok = 1;
#define AA_SYM(f) p_##f = (__typeof__(&f))dlsym(h, #f); if (!p_##f) ok = 0;
#define AA_SYM_OPT(f) p_##f = (__typeof__(&f))dlsym(h, #f);
  AA_FUNCS(AA_SYM)
  AA_FUNCS_28(AA_SYM_OPT)
  aa_loaded = ok;
}

static int aa_available(void) {
  pthread_once(&aa_once, aa_load);
  return aa_loaded;
}

#define TAG "OlAudio.AAudio"

typedef struct {
  ol_android_io *io;
  AAudioStream *s[2];
  int channels[2];
} aa_ctx;

static aaudio_data_callback_result_t data_cb(AAudioStream *st, void *user, void *audio, int32_t frames) {
  aa_ctx *c = (aa_ctx *)user;
  if (p_AAudioStream_getDirection(st) == AAUDIO_DIRECTION_INPUT) {
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
  __android_log_print(ANDROID_LOG_WARN, TAG, "stream error %d (%s)", err, p_AAudio_convertResultToText(err));
}

static void aa_close(void *ctx, ol_direction dir) {
  aa_ctx *c = (aa_ctx *)ctx;
  if (c->s[dir]) {
    p_AAudioStream_requestStop(c->s[dir]);
    p_AAudioStream_close(c->s[dir]);
    c->s[dir] = NULL;
  }
}

static int32_t aa_open(void *ctx, ol_direction dir, int rate, int channels, int *actual) {
  aa_ctx *c = (aa_ctx *)ctx;
  if (!aa_available()) return AAUDIO_ERROR_UNAVAILABLE;
  aa_close(ctx, dir);
  AAudioStreamBuilder *b = NULL;
  aaudio_result_t r = p_AAudio_createStreamBuilder(&b);
  if (r != AAUDIO_OK) return r;
  p_AAudioStreamBuilder_setDirection(b, dir == OL_DIR_CAPTURE ? AAUDIO_DIRECTION_INPUT : AAUDIO_DIRECTION_OUTPUT);
  p_AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
  p_AAudioStreamBuilder_setChannelCount(b, channels);
  if (rate > 0) p_AAudioStreamBuilder_setSampleRate(b, rate);
  p_AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
  p_AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  /* usage/content/preset are API 28+; on API 26-27 AAudio opens without the
   * voice-communication routing hints (WebRTC software APM still runs). */
  if (p_AAudioStreamBuilder_setUsage) p_AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_VOICE_COMMUNICATION);
  if (p_AAudioStreamBuilder_setContentType) p_AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SPEECH);
  if (dir == OL_DIR_CAPTURE && p_AAudioStreamBuilder_setInputPreset) p_AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
  p_AAudioStreamBuilder_setDataCallback(b, data_cb, c);
  p_AAudioStreamBuilder_setErrorCallback(b, error_cb, c);
  r = p_AAudioStreamBuilder_openStream(b, &c->s[dir]);
  p_AAudioStreamBuilder_delete(b);
  if (r != AAUDIO_OK) {
    c->s[dir] = NULL;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "openStream dir=%d rate=%d -> %d (%s)", dir, rate, r, p_AAudio_convertResultToText(r));
    return r;
  }
  if (p_AAudioStream_getFormat(c->s[dir]) != AAUDIO_FORMAT_PCM_I16 || p_AAudioStream_getChannelCount(c->s[dir]) != channels) {
    aa_close(ctx, dir);
    return AAUDIO_ERROR_INVALID_FORMAT;
  }
  *actual = p_AAudioStream_getSampleRate(c->s[dir]);
  return AAUDIO_OK;
}

static int32_t aa_start(void *ctx, ol_direction dir) {
  aa_ctx *c = (aa_ctx *)ctx;
  if (!c->s[dir]) return AAUDIO_ERROR_INVALID_STATE;
  aaudio_result_t r = p_AAudioStream_requestStart(c->s[dir]);
  if (r != AAUDIO_OK) return r;
  aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
  r = p_AAudioStream_waitForStateChange(c->s[dir], AAUDIO_STREAM_STATE_STARTING, &next, 500 * 1000000LL);
  if (r != AAUDIO_OK) return r;
  return next == AAUDIO_STREAM_STATE_STARTED ? AAUDIO_OK : AAUDIO_ERROR_INVALID_STATE;
}

ol_backend ol_aaudio_backend(ol_android_io *io) {
  aa_ctx *c = (aa_ctx *)calloc(1, sizeof(aa_ctx));
  c->io = io;
  ol_backend b = {"aaudio", OL_DOMAIN_AAUDIO, aa_open, aa_start, aa_close, c};
  return b;
}
