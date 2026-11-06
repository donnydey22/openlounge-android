package com.donnydey.openlounge
import com.donnydey.openlounge.rtc.RtcSignaling
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class RtcSignalingTest {
 @Test fun callingStunUsesOwnedPionListenerNotLegacyCoturn() {
  val servers=JSONArray(RtcSignaling.iceServersFrom(null))
  assertEquals("stun:turn.openlounge.app:13478",servers.getJSONObject(0).getJSONArray("urls").getString(0))
 }
 @Test fun peerRestartBuffersCandidatesForNewDescriptionAndRejectsOldGeneration() {
  val s=RtcSignaling("call",false){_,_->};s.remoteSet=true
  assertNotNull(s.accept(JSONObject().put("callId","call").put("gen",1).put("seq",0).put("kind","offer").put("from","caller")))
  assertFalse(s.remoteSet)
  assertNull(s.accept(JSONObject().put("callId","call").put("gen",0).put("seq",99)))
 }
}
