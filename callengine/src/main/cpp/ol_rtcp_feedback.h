#ifndef OL_RTCP_FEEDBACK_H
#define OL_RTCP_FEEDBACK_H
#include <stdint.h>
/* PSFB: PLI names its media SSRC; FIR names each target in an 8-byte FCI entry.
 * A zero/unknown target is NOT permission to refresh every simulcast encoder. */
static inline uint32_t ol_fb_ssrc(const unsigned char *p) {
  return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}
static inline unsigned ol_rtcp_keyframe_mask(const unsigned char *p, int n,
                                            const unsigned *ssrc, unsigned count) {
  if (n < 12 || p[0] >> 6 != 2 || p[1] != 206) return 0;
  unsigned mask = 0, fmt = p[0] & 31;
  if (fmt != 1 && fmt != 4) return 0;
  if (fmt == 4 && (n < 20 || (n - 12) % 8)) return 0;
  for (int off = fmt == 1 ? 8 : 12; off + 4 <= n; off += fmt == 1 ? n : 8) {
    uint32_t target = ol_fb_ssrc(p + off);
    for (unsigned i = 0; i < count && i < 3; i++) if (target && target == ssrc[i]) mask |= 1u << i;
  }
  return mask;
}
#endif
