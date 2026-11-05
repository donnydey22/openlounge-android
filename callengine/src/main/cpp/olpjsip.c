#include <jni.h>
#include <pjsua-lib/pjsua.h>
static void srtp_mandatory(pjsua_acc_config*a){ a->use_srtp=PJMEDIA_SRTP_MANDATORY; a->srtp_secure_signaling=0; a->srtp_opt.keying_count=1; a->srtp_opt.keying[0]=PJMEDIA_SRTP_KEYING_SDES; }

JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeStart(JNIEnv*e,jclass o,jint mn,jint mx,jint jm){
  pjsua_config c; pjsua_media_config m; pjsua_config_default(&c); pjsua_media_config_default(&m);
  m.jb_init=-1; m.jb_min_pre=mn; m.jb_max_pre=mx; m.jb_max=jm; m.jb_discard_algo=PJMEDIA_JB_DISCARD_PROGRESSIVE;
  m.no_vad=1; m.clock_rate=48000; m.ec_tail_len=200; /* explicit 200 ms echo tail */
  if(pjsua_create()!=PJ_SUCCESS) return -1;
  if(pjsua_init(&c,NULL,&m)!=PJ_SUCCESS) return -2;
  /* pjsua_acc_add asserts without a SIP transport (signaling is our WS, not SIP): bind a loopback-only, ephemeral
     UDP SIP transport so the account exists; nothing listens off-device. */
  { pjsua_transport_config t; pjsua_transport_config_default(&t); t.port=0; t.bound_addr=pj_str("127.0.0.1");
    if(pjsua_transport_create(PJSIP_TRANSPORT_UDP,&t,NULL)!=PJ_SUCCESS) return -3; }
  pjsua_start(); pjsua_set_no_snd_dev();
  { pjsua_acc_config a; pjsua_acc_config_default(&a); srtp_mandatory(&a); a.id=pj_str("sip:openlounge@local"); pjsua_acc_add(&a,PJ_TRUE,NULL); }
  return 0; }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeOpenSound(JNIEnv*e,jclass o){
  return pjsua_set_snd_dev(PJMEDIA_AUD_DEFAULT_CAPTURE_DEV,PJMEDIA_AUD_DEFAULT_PLAYBACK_DEV); }
JNIEXPORT void JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeTeardown(JNIEnv*e,jclass o){
  pjsua_call_hangup_all(); pjsua_set_no_snd_dev(); }
/* Video (t4464u): H.264 via MediaCodec (pjmedia and_media codec). QuickHD start burst then steady caps. */
#if PJMEDIA_HAS_VIDEO
static jint set_h264(int w,int h,int bps){ pj_str_t id=pj_str("H264"); pjmedia_vid_codec_param p;
  if(pjsua_vid_codec_set_priority(&id,255)!=PJ_SUCCESS) return -1;
  if(pjsua_vid_codec_get_param(&id,&p)!=PJ_SUCCESS) return -2;
  p.enc_fmt.det.vid.size.w=w; p.enc_fmt.det.vid.size.h=h; p.enc_fmt.det.vid.avg_bps=bps; p.enc_fmt.det.vid.max_bps=bps;
  return pjsua_vid_codec_set_param(&id,&p)==PJ_SUCCESS?0:-3; }
#else
static jint set_h264(int w,int h,int bps){ return -9; }
#endif
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeVideoQuickHd(JNIEnv*e,jclass o){ return set_h264(1920,1080,2500000); }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeVideoSteady(JNIEnv*e,jclass o){ return set_h264(1280,720,1200000); }
/* Phone -> mediasoup PlainTransport audio leg (Donny 12:37 AM decision 2), shared ol_sfu.c. */
#include "ol_sfu.h"
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuAudioStart(JNIEnv*e,jclass o,jstring ip,jint port,jstring tx,jstring rx,jlong ssrc,jint txPt,jint rxPt,jint toneHz,jboolean nullSnd){
  const char*a=(*e)->GetStringUTFChars(e,ip,0),*b=(*e)->GetStringUTFChars(e,tx,0),*c=(*e)->GetStringUTFChars(e,rx,0);
  static __thread pj_thread_desc d; pj_thread_t*t; if(!pj_thread_is_registered()) pj_thread_register("jni",d,&t);
  if(nullSnd) pjsua_set_null_snd_dev();
  jint r=ol_sfu_audio_start(a,port,b,c,(unsigned)ssrc,txPt,rxPt,toneHz);
  (*e)->ReleaseStringUTFChars(e,ip,a); (*e)->ReleaseStringUTFChars(e,tx,b); (*e)->ReleaseStringUTFChars(e,rx,c); return r; }
JNIEXPORT jintArray JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuStats(JNIEnv*e,jclass o){
  static __thread pj_thread_desc d; pj_thread_t*t; if(!pj_thread_is_registered()) pj_thread_register("jni",d,&t);
  unsigned rx=0,lost=0,tx=0; ol_sfu_stats(&rx,&lost,&tx); jint v[4]={(jint)rx,(jint)lost,(jint)tx,ol_sfu_rx_level()};
  jintArray a=(*e)->NewIntArray(e,4); (*e)->SetIntArrayRegion(e,a,0,4,v); return a; }
JNIEXPORT void JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuStop(JNIEnv*e,jclass o){
  static __thread pj_thread_desc d; pj_thread_t*t; if(!pj_thread_is_registered()) pj_thread_register("jni",d,&t); ol_sfu_stop(); }
/* Video spec v2 leg (ol_sfu_video.c): own transport/SRTP key/3 SSRCs. Received AUs are queued here and polled from
   Kotlin (decoder thread), so the pjmedia rx thread never attaches to the JVM. */
#include <string.h>
#include <stdlib.h>
#include "ol_sfu_video.h"
#define OLQ 64
static struct { unsigned char *d; int n, key; unsigned ts; } vq[OLQ]; static int vq_h, vq_t; static pj_mutex_t *vq_mx; static unsigned vq_drop;
#define REG() static __thread pj_thread_desc d_; pj_thread_t*t_; if(!pj_thread_is_registered()) pj_thread_register("jni",d_,&t_)
static void vq_sink(void *u, const unsigned char *a, int n, int key, unsigned ts) { (void)u; pj_mutex_lock(vq_mx);
  int nx = (vq_t + 1) % OLQ; if (nx == vq_h) { free(vq[vq_h].d); vq[vq_h].d = NULL; vq_h = (vq_h + 1) % OLQ; vq_drop++; }
  vq[vq_t].d = malloc(n); memcpy(vq[vq_t].d, a, n); vq[vq_t].n = n; vq[vq_t].key = key; vq[vq_t].ts = ts; vq_t = nx; pj_mutex_unlock(vq_mx); }
static void vq_clear(void) { if (!vq_mx) return; pj_mutex_lock(vq_mx); while (vq_h != vq_t) { free(vq[vq_h].d); vq[vq_h].d = NULL; vq_h = (vq_h + 1) % OLQ; } vq_drop = 0; pj_mutex_unlock(vq_mx); }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuVideoStart(JNIEnv*e,jclass o,jstring ip,jint port,jstring tx,jstring rx,jlongArray ssrcs,jint pt){
  REG(); if (!vq_mx) pj_mutex_create_simple(pjsua_pool_create("olvq", 512, 512), "olvq", &vq_mx); vq_clear();
  const char*a=(*e)->GetStringUTFChars(e,ip,0),*b=(*e)->GetStringUTFChars(e,tx,0),*c=(*e)->GetStringUTFChars(e,rx,0);
  jsize n=(*e)->GetArrayLength(e,ssrcs); jlong v[3]={0}; if(n>3)n=3; (*e)->GetLongArrayRegion(e,ssrcs,0,n,v); unsigned s[3]={(unsigned)v[0],(unsigned)v[1],(unsigned)v[2]};
  ol_sfu_video_set_sink(vq_sink, NULL);
  jint r=ol_sfu_video_start(a,port,b,c,s,n,pt);
  (*e)->ReleaseStringUTFChars(e,ip,a); (*e)->ReleaseStringUTFChars(e,tx,b); (*e)->ReleaseStringUTFChars(e,rx,c); return r; }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuVideoSend(JNIEnv*e,jclass o,jint layer,jbyteArray au,jint len,jlong ts,jboolean key){
  REG(); jbyte*p=(*e)->GetPrimitiveArrayCritical(e,au,0); if(!p) return -1;
  jint r=ol_sfu_video_send(layer,(const unsigned char*)p,len,(unsigned)ts,key?1:0); (*e)->ReleasePrimitiveArrayCritical(e,au,p,JNI_ABORT); return r; }
/* Next received AU as [key, ts(4 BE), annexb...] or null. */
JNIEXPORT jbyteArray JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuVideoPoll(JNIEnv*e,jclass o){
  REG();
  if (!vq_mx) return NULL; pj_mutex_lock(vq_mx); if (vq_h == vq_t) { pj_mutex_unlock(vq_mx); return NULL; }
  int n=vq[vq_h].n; jbyteArray a=(*e)->NewByteArray(e,n+5); jbyte h[5]={(jbyte)vq[vq_h].key,(jbyte)(vq[vq_h].ts>>24),(jbyte)(vq[vq_h].ts>>16),(jbyte)(vq[vq_h].ts>>8),(jbyte)vq[vq_h].ts};
  (*e)->SetByteArrayRegion(e,a,0,5,h); (*e)->SetByteArrayRegion(e,a,5,n,(const jbyte*)vq[vq_h].d); free(vq[vq_h].d); vq[vq_h].d=NULL; vq_h=(vq_h+1)%OLQ; pj_mutex_unlock(vq_mx); return a; }
JNIEXPORT jintArray JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuVideoStats(JNIEnv*e,jclass o){
  REG();
  unsigned rp,ra,rk,rg,tp,ta[3],tk[3]; ol_sfu_video_stats(&rp,&ra,&rk,&rg,&tp,ta,tk);
  jint v[12]={(jint)rp,(jint)ra,(jint)rk,(jint)rg,(jint)tp,(jint)ta[0],(jint)ta[1],(jint)ta[2],(jint)tk[0],(jint)tk[1],(jint)tk[2],(jint)vq_drop};
  jintArray a=(*e)->NewIntArray(e,12); (*e)->SetIntArrayRegion(e,a,0,12,v); return a; }
JNIEXPORT void JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuVideoStop(JNIEnv*e,jclass o){ REG(); ol_sfu_video_stop(); ol_sfu_video_set_sink(NULL,NULL); vq_clear(); }
/* PLI/FIR from the SFU since the last poll, as a per-layer bitmask; the encoder answers with an immediate IDR. */
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuVideoTakeKeyReq(JNIEnv*e,jclass o){ (void)e;(void)o; return (jint)ol_sfu_video_take_keyreq(); }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeSfuSetMute(JNIEnv*e,jclass o,jboolean m){
  static __thread pj_thread_desc d; pj_thread_t*t; if(!pj_thread_is_registered()) pj_thread_register("jni",d,&t); return ol_sfu_set_mute(m?1:0); }
/* Phone-to-phone P2P leg (ol_p2p.c): pjnath ICE + pjmedia SRTP SDES. ICE callbacks run on pjsua threads, so events
   (local SDP / connected / failed) are queued here and taken by Kotlin, same pattern as the video AU queue. */
#include "ol_p2p.h"
#define OLPQ 8
static struct { int ev; char *t; } pq[OLPQ]; static int pq_h, pq_t; static pj_mutex_t *pq_mx;
static void p2p_ev(int ev, const char *t, void *u) { (void)u; pj_mutex_lock(pq_mx); int nx = (pq_t + 1) % OLPQ;
  if (nx != pq_h) { pq[pq_t].ev = ev; pq[pq_t].t = strdup(t ? t : ""); pq_t = nx; } pj_mutex_unlock(pq_mx); }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pBegin(JNIEnv*e,jclass o,jboolean offerer,jstring stun,jstring turn,jstring tu,jstring tp,jboolean tcp,jboolean relayOnly,jint toneHz,jboolean nullSnd){
  REG(); if (!pq_mx) pj_mutex_create_simple(pjsua_pool_create("olpq", 512, 512), "olpq", &pq_mx);
  const char*a=(*e)->GetStringUTFChars(e,stun,0),*b=(*e)->GetStringUTFChars(e,turn,0),*c=(*e)->GetStringUTFChars(e,tu,0),*d=(*e)->GetStringUTFChars(e,tp,0);
  if(nullSnd) pjsua_set_null_snd_dev();
  ol_p2p_cfg g = { offerer?1:0, a, b, c, d, tcp?1:0, relayOnly?1:0, toneHz };
  jint r=ol_p2p_begin(&g,p2p_ev,NULL);
  (*e)->ReleaseStringUTFChars(e,stun,a); (*e)->ReleaseStringUTFChars(e,turn,b); (*e)->ReleaseStringUTFChars(e,tu,c); (*e)->ReleaseStringUTFChars(e,tp,d); return r; }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pRemote(JNIEnv*e,jclass o,jstring sdp){
  REG(); const char*s=(*e)->GetStringUTFChars(e,sdp,0); jint r=ol_p2p_remote(s); (*e)->ReleaseStringUTFChars(e,sdp,s); return r; }
/* Next event as "ev\ntext" or null. */
JNIEXPORT jstring JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pTake(JNIEnv*e,jclass o){
  if (!pq_mx) return NULL; pj_mutex_lock(pq_mx); if (pq_h == pq_t) { pj_mutex_unlock(pq_mx); return NULL; }
  size_t n = strlen(pq[pq_h].t); char *s = malloc(n + 4); s[0] = (char)('0' + pq[pq_h].ev); s[1] = '\n'; memcpy(s + 2, pq[pq_h].t, n + 1);
  free(pq[pq_h].t); pq_h = (pq_h + 1) % OLPQ; pj_mutex_unlock(pq_mx); jstring r = (*e)->NewStringUTF(e, s); free(s); return r; }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pRestart(JNIEnv*e,jclass o){ REG(); return ol_p2p_restart(); }
JNIEXPORT jintArray JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pStats(JNIEnv*e,jclass o){
  REG(); unsigned rx=0,lost=0,tx=0; ol_p2p_stats(&rx,&lost,&tx); jint v[4]={(jint)rx,(jint)lost,(jint)tx,ol_p2p_rx_level()};
  jintArray a=(*e)->NewIntArray(e,4); (*e)->SetIntArrayRegion(e,a,0,4,v); return a; }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pSetMute(JNIEnv*e,jclass o,jboolean m){ REG(); return ol_p2p_set_mute(m?1:0); }
JNIEXPORT void JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pStop(JNIEnv*e,jclass o){ REG(); ol_p2p_stop(); }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pActivate(JNIEnv*e,jclass o,jboolean on){ REG(); return ol_p2p_activate(on?1:0); }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pVideoLive(JNIEnv*e,jclass o,jboolean on){ REG(); return ol_p2p_video_live(on?1:0); }
JNIEXPORT jintArray JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeP2pVideoStats(JNIEnv*e,jclass o){
  REG(); unsigned rx=0,tx=0; ol_p2p_video_stats(&rx,&tx); jint v[2]={(jint)rx,(jint)tx}; jintArray a=(*e)->NewIntArray(e,2); (*e)->SetIntArrayRegion(e,a,0,2,v); return a; }
