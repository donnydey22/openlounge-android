package app.openlounge.pjsip
// Open Lounge PJSIP engine (pjproject 2.17 prefab). One path; SRTP SDES mandatory;
// adaptive jbuf (progressive discard) + PLC; full teardown on every call end.
object PjsipEngine {
    init { System.loadLibrary("pjproject"); System.loadLibrary("olpjsip") }
    external fun nativeStart(jbMinPre: Int = 1, jbMaxPre: Int = 16, jbMax: Int = 50): Int
    external fun nativeOpenSound(): Int     // called when ConnectionService call becomes active
    external fun nativeTeardown()           // hangup all + release sound device
    fun teardown() = nativeTeardown()
}
