#ifndef OL_ANDROID_BACKENDS_H
#define OL_ANDROID_BACKENDS_H
#include "../../core/ol_audio.h"
#include "ol_ring.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Rings shared with the ADM: [OL_DIR_CAPTURE] device->ADM, [OL_DIR_PLAYOUT] ADM->device. */
typedef struct {
  ol_ring *ring[2];
} ol_android_io;

/* AAudio (default backend): low-latency, VOICE_COMMUNICATION usage,
 * input preset VOICE_COMMUNICATION (platform AEC/NS), callback mode. */
ol_backend ol_aaudio_backend(ol_android_io *io);
/* OpenSL ES (alternate backend): Android simple buffer queue, recorder
 * preset VOICE_COMMUNICATION, stream type VOICE; used at 16 kHz. */
ol_backend ol_opensl_backend(ol_android_io *io);

#ifdef __cplusplus
}
#endif
#endif
