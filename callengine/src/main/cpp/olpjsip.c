#include <jni.h>
#include <pjsua-lib/pjsua.h>
static void srtp_mandatory(pjsua_acc_config*a){ a->use_srtp=PJMEDIA_SRTP_MANDATORY; a->srtp_secure_signaling=0; a->srtp_opt.keying_count=1; a->srtp_opt.keying[0]=PJMEDIA_SRTP_KEYING_SDES; }

JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeStart(JNIEnv*e,jclass o,jint mn,jint mx,jint jm){
  pjsua_config c; pjsua_media_config m; pjsua_config_default(&c); pjsua_media_config_default(&m);
  m.jb_init=-1; m.jb_min_pre=mn; m.jb_max_pre=mx; m.jb_max=jm; m.jb_discard_algo=PJMEDIA_JB_DISCARD_PROGRESSIVE;
  m.no_vad=1; m.clock_rate=48000;
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
