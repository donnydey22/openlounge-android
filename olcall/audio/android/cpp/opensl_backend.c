/* OpenSL ES backend - the "alternate backend" of the 16 kHz retry.
 * OpenSL goes through a different HAL client path than AAudio (AAudio may use
 * MMAP/AAudioService; OpenSL always uses AudioTrack/AudioRecord in
 * audioserver), which is the point of switching backend rather than only the
 * rate. Every failure returns the raw SLresult. */
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#include "ol_android_backends.h"

#define TAG "OlAudio.OpenSL"
#define NBUF 2
#define MAX_FRAMES 480 /* 10 ms at 48 kHz */

typedef struct {
  ol_android_io *io;
  SLObjectItf engineObj;
  SLEngineItf engine;
  SLObjectItf outMix;
  SLObjectItf player, recorder;
  SLPlayItf playItf;
  SLRecordItf recItf;
  SLAndroidSimpleBufferQueueItf q[2];
  int16_t bufs[2][NBUF][MAX_FRAMES];
  int next[2];
  int frames[2];
} sl_ctx;

static void play_cb(SLAndroidSimpleBufferQueueItf q, void *user) {
  sl_ctx *c = (sl_ctx *)user;
  int16_t *b = c->bufs[OL_DIR_PLAYOUT][c->next[OL_DIR_PLAYOUT]];
  ol_ring_read(c->io->ring[OL_DIR_PLAYOUT], b, (uint32_t)c->frames[OL_DIR_PLAYOUT]);
  (*q)->Enqueue(q, b, (SLuint32)(c->frames[OL_DIR_PLAYOUT] * 2));
  c->next[OL_DIR_PLAYOUT] = (c->next[OL_DIR_PLAYOUT] + 1) % NBUF;
}

static void rec_cb(SLAndroidSimpleBufferQueueItf q, void *user) {
  sl_ctx *c = (sl_ctx *)user;
  int16_t *b = c->bufs[OL_DIR_CAPTURE][c->next[OL_DIR_CAPTURE]];
  ol_ring_write(c->io->ring[OL_DIR_CAPTURE], b, (uint32_t)c->frames[OL_DIR_CAPTURE]);
  (*q)->Enqueue(q, b, (SLuint32)(c->frames[OL_DIR_CAPTURE] * 2));
  c->next[OL_DIR_CAPTURE] = (c->next[OL_DIR_CAPTURE] + 1) % NBUF;
}

static SLresult ensure_engine(sl_ctx *c) {
  if (c->engine) return SL_RESULT_SUCCESS;
  SLresult r = slCreateEngine(&c->engineObj, 0, NULL, 0, NULL, NULL);
  if (r != SL_RESULT_SUCCESS) return r;
  if ((r = (*c->engineObj)->Realize(c->engineObj, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS) return r;
  return (*c->engineObj)->GetInterface(c->engineObj, SL_IID_ENGINE, &c->engine);
}

static void sl_close(void *ctx, ol_direction dir) {
  sl_ctx *c = (sl_ctx *)ctx;
  if (dir == OL_DIR_PLAYOUT) {
    if (c->player) { (*c->player)->Destroy(c->player); c->player = NULL; c->playItf = NULL; }
    if (c->outMix) { (*c->outMix)->Destroy(c->outMix); c->outMix = NULL; }
  } else if (c->recorder) {
    (*c->recorder)->Destroy(c->recorder);
    c->recorder = NULL;
    c->recItf = NULL;
  }
  c->q[dir] = NULL;
  if (!c->player && !c->recorder && c->engineObj) {
    (*c->engineObj)->Destroy(c->engineObj);
    c->engineObj = NULL;
    c->engine = NULL;
  }
}

static int32_t sl_open(void *ctx, ol_direction dir, int rate, int channels, int *actual) {
  sl_ctx *c = (sl_ctx *)ctx;
  if (rate <= 0) rate = 48000;
  if (channels != 1 || rate * 10 / 1000 > MAX_FRAMES) return SL_RESULT_PARAMETER_INVALID;
  sl_close(ctx, dir);
  SLresult r = ensure_engine(c);
  if (r != SL_RESULT_SUCCESS) return (int32_t)r;
  c->frames[dir] = rate / 100;
  SLDataLocator_AndroidSimpleBufferQueue locq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, NBUF};
  SLDataFormat_PCM fmt = {SL_DATAFORMAT_PCM, 1, (SLuint32)rate * 1000, SL_PCMSAMPLEFORMAT_FIXED_16,
                          SL_PCMSAMPLEFORMAT_FIXED_16, SL_SPEAKER_FRONT_CENTER, SL_BYTEORDER_LITTLEENDIAN};
  if (dir == OL_DIR_PLAYOUT) {
    if ((r = (*c->engine)->CreateOutputMix(c->engine, &c->outMix, 0, NULL, NULL)) != SL_RESULT_SUCCESS) return (int32_t)r;
    if ((r = (*c->outMix)->Realize(c->outMix, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS) return (int32_t)r;
    SLDataSource src = {&locq, &fmt};
    SLDataLocator_OutputMix locm = {SL_DATALOCATOR_OUTPUTMIX, c->outMix};
    SLDataSink snk = {&locm, NULL};
    const SLInterfaceID ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_ANDROIDCONFIGURATION};
    const SLboolean req[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    if ((r = (*c->engine)->CreateAudioPlayer(c->engine, &c->player, &src, &snk, 2, ids, req)) != SL_RESULT_SUCCESS) return (int32_t)r;
    SLAndroidConfigurationItf cfg;
    if ((*c->player)->GetInterface(c->player, SL_IID_ANDROIDCONFIGURATION, &cfg) == SL_RESULT_SUCCESS) {
      SLint32 stream = SL_ANDROID_STREAM_VOICE;
      (*cfg)->SetConfiguration(cfg, SL_ANDROID_KEY_STREAM_TYPE, &stream, sizeof(stream));
    }
    if ((r = (*c->player)->Realize(c->player, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS) return (int32_t)r;
    if ((r = (*c->player)->GetInterface(c->player, SL_IID_PLAY, &c->playItf)) != SL_RESULT_SUCCESS) return (int32_t)r;
    if ((r = (*c->player)->GetInterface(c->player, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &c->q[dir])) != SL_RESULT_SUCCESS) return (int32_t)r;
    if ((r = (*c->q[dir])->RegisterCallback(c->q[dir], play_cb, c)) != SL_RESULT_SUCCESS) return (int32_t)r;
  } else {
    SLDataLocator_IODevice locd = {SL_DATALOCATOR_IODEVICE, SL_IODEVICE_AUDIOINPUT, SL_DEFAULTDEVICEID_AUDIOINPUT, NULL};
    SLDataSource src = {&locd, NULL};
    SLDataSink snk = {&locq, &fmt};
    const SLInterfaceID ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_ANDROIDCONFIGURATION};
    const SLboolean req[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    if ((r = (*c->engine)->CreateAudioRecorder(c->engine, &c->recorder, &src, &snk, 2, ids, req)) != SL_RESULT_SUCCESS) return (int32_t)r;
    SLAndroidConfigurationItf cfg;
    if ((*c->recorder)->GetInterface(c->recorder, SL_IID_ANDROIDCONFIGURATION, &cfg) == SL_RESULT_SUCCESS) {
      SLuint32 preset = SL_ANDROID_RECORDING_PRESET_VOICE_COMMUNICATION;
      (*cfg)->SetConfiguration(cfg, SL_ANDROID_KEY_RECORDING_PRESET, &preset, sizeof(preset));
    }
    if ((r = (*c->recorder)->Realize(c->recorder, SL_BOOLEAN_FALSE)) != SL_RESULT_SUCCESS) return (int32_t)r;
    if ((r = (*c->recorder)->GetInterface(c->recorder, SL_IID_RECORD, &c->recItf)) != SL_RESULT_SUCCESS) return (int32_t)r;
    if ((r = (*c->recorder)->GetInterface(c->recorder, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &c->q[dir])) != SL_RESULT_SUCCESS) return (int32_t)r;
    if ((r = (*c->q[dir])->RegisterCallback(c->q[dir], rec_cb, c)) != SL_RESULT_SUCCESS) return (int32_t)r;
  }
  *actual = rate;
  return SL_RESULT_SUCCESS;
}

static int32_t sl_start(void *ctx, ol_direction dir) {
  sl_ctx *c = (sl_ctx *)ctx;
  if (!c->q[dir]) return SL_RESULT_PRECONDITIONS_VIOLATED;
  memset(c->bufs[dir], 0, sizeof(c->bufs[dir]));
  SLresult r;
  for (int i = 0; i < NBUF; i++)
    if ((r = (*c->q[dir])->Enqueue(c->q[dir], c->bufs[dir][i], (SLuint32)(c->frames[dir] * 2))) != SL_RESULT_SUCCESS) return (int32_t)r;
  if (dir == OL_DIR_PLAYOUT) return (int32_t)(*c->playItf)->SetPlayState(c->playItf, SL_PLAYSTATE_PLAYING);
  return (int32_t)(*c->recItf)->SetRecordState(c->recItf, SL_RECORDSTATE_RECORDING);
}

ol_backend ol_opensl_backend(ol_android_io *io) {
  sl_ctx *c = (sl_ctx *)calloc(1, sizeof(sl_ctx));
  c->io = io;
  ol_backend b = {"opensles", OL_DOMAIN_OPENSLES, sl_open, sl_start, sl_close, c};
  return b;
}
