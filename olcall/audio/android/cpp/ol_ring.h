/* Single-producer/single-consumer int16 ring buffer (audio callback thread
 * <-> ADM driver thread). Lock-free, C11 atomics. */
#ifndef OL_RING_H
#define OL_RING_H
#include <stdint.h>
#include <string.h>
#ifdef __cplusplus
/* same layout/semantics when included from the C++ ADM */
#include <atomic>
#define OL_ATOMIC(T) std::atomic<T>
using std::atomic_fetch_add;
using std::atomic_load;
using std::atomic_load_explicit;
using std::atomic_store;
using std::atomic_store_explicit;
using std::memory_order_acquire;
using std::memory_order_relaxed;
using std::memory_order_release;
#else
#include <stdatomic.h>
#define OL_ATOMIC(T) _Atomic T
#endif

typedef struct {
  int16_t *buf;
  uint32_t cap; /* power of two */
  OL_ATOMIC(uint32_t) w, r;
  OL_ATOMIC(uint32_t) overruns, underruns;
} ol_ring;

static inline void ol_ring_init(ol_ring *q, int16_t *storage, uint32_t cap_pow2) {
  q->buf = storage;
  q->cap = cap_pow2;
  atomic_store(&q->w, 0);
  atomic_store(&q->r, 0);
  atomic_store(&q->overruns, 0);
  atomic_store(&q->underruns, 0);
}
static inline uint32_t ol_ring_avail(ol_ring *q) { return (uint32_t)(atomic_load(&q->w) - atomic_load(&q->r)); }
static inline uint32_t ol_ring_write(ol_ring *q, const int16_t *s, uint32_t n) {
  uint32_t w = atomic_load_explicit(&q->w, memory_order_relaxed), r = atomic_load_explicit(&q->r, memory_order_acquire);
  uint32_t space = q->cap - (w - r);
  if (n > space) { atomic_fetch_add(&q->overruns, 1u); n = space; }
  for (uint32_t i = 0; i < n; i++) q->buf[(w + i) & (q->cap - 1)] = s[i];
  atomic_store_explicit(&q->w, w + n, memory_order_release);
  return n;
}
/* reads n samples; zero-fills (and counts an underrun) when short */
static inline uint32_t ol_ring_read(ol_ring *q, int16_t *d, uint32_t n) {
  uint32_t r = atomic_load_explicit(&q->r, memory_order_relaxed), w = atomic_load_explicit(&q->w, memory_order_acquire);
  uint32_t have = w - r, k = n < have ? n : have;
  for (uint32_t i = 0; i < k; i++) d[i] = q->buf[(r + i) & (q->cap - 1)];
  if (k < n) { memset(d + k, 0, (n - k) * sizeof(int16_t)); atomic_fetch_add(&q->underruns, 1u); }
  atomic_store_explicit(&q->r, r + k, memory_order_release);
  return k;
}
#endif
