#include <jni.h>
#include <pjsua-lib/pjsua.h>
static void srtp_mandatory(pjsua_acc_config*a){ a->use_srtp=PJMEDIA_SRTP_MANDATORY; a->srtp_secure_signaling=0; a->srtp_opt.keying_count=1; a->srtp_opt.keying[0]=PJMEDIA_SRTP_KEYING_SDES; }

JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeStart(JNIEnv*e,jclass o,jint mn,jint mx,jint jm){
  pjsua_config c; pjsua_media_config m; pjsua_config_default(&c); pjsua_media_config_default(&m);
  m.jb_init=-1; m.jb_min_pre=mn; m.jb_max_pre=mx; m.jb_max=jm; m.jb_discard_algo=PJMEDIA_JB_DISCARD_PROGRESSIVE;
  m.no_vad=1; m.clock_rate=48000;
  if(pjsua_create()!=PJ_SUCCESS) return -1;
  if(pjsua_init(&c,NULL,&m)!=PJ_SUCCESS) return -2;
  pjsua_start(); pjsua_set_no_snd_dev();
  { pjsua_acc_config a; pjsua_acc_config_default(&a); srtp_mandatory(&a); a.id=pj_str("sip:openlounge@local"); pjsua_acc_add(&a,PJ_TRUE,NULL); }
  return 0; }
JNIEXPORT jint JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeOpenSound(JNIEnv*e,jclass o){
  return pjsua_set_snd_dev(PJMEDIA_AUD_DEFAULT_CAPTURE_DEV,PJMEDIA_AUD_DEFAULT_PLAYBACK_DEV); }
JNIEXPORT void JNICALL Java_com_donnydey_openlounge_pjsip_PjsipEngine_nativeTeardown(JNIEnv*e,jclass o){
  pjsua_call_hangup_all(); pjsua_set_no_snd_dev(); }
