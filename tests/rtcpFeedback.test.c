#include <assert.h>
#include "../callengine/src/main/cpp/ol_rtcp_feedback.h"
int main(void) {
  unsigned ss[] = { 11, 22, 33 };
  unsigned char pli[12] = { 0x81, 206, 0, 2, 0,0,0,9, 0,0,0,22 };
  assert(ol_rtcp_keyframe_mask(pli,12,ss,3)==2);
  pli[11]=0; assert(ol_rtcp_keyframe_mask(pli,12,ss,3)==0);
  pli[11]=99; assert(ol_rtcp_keyframe_mask(pli,12,ss,3)==0);
  unsigned char fir[28] = { 0x84,206,0,6, 0,0,0,9, 0,0,0,0, 0,0,0,11, 1,0,0,0, 0,0,0,33, 2,0,0,0 };
  assert(ol_rtcp_keyframe_mask(fir,28,ss,3)==5);
  assert(ol_rtcp_keyframe_mask(fir,19,ss,3)==0);
  assert(ol_rtcp_keyframe_mask(fir,27,ss,3)==0);
  fir[0]=0x44; assert(ol_rtcp_keyframe_mask(fir,28,ss,3)==0);
  return 0;
}
