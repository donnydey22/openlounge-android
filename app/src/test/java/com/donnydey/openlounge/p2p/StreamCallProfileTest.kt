package com.donnydey.openlounge.p2p

import org.junit.Assert.*
import org.junit.Test

class StreamCallProfileTest {
    private val ice = listOf(
        StreamCallProfile.Ice(listOf("turns:turn.openlounge.app:443?transport=tcp"), "1:u", "c"),
        StreamCallProfile.Ice(listOf("turn:turn.openlounge.app:3478?transport=tcp", "turn:turn.openlounge.app:3478?transport=udp"), "1:u", "c"),
        StreamCallProfile.Ice(listOf("stun:turn.openlounge.app:3478")),
    )

    @Test fun udpFirstOrder() {
        assertEquals(listOf("stun:turn.openlounge.app:3478", "turn:turn.openlounge.app:3478?transport=udp",
            "turn:turn.openlounge.app:3478?transport=tcp", "turns:turn.openlounge.app:443?transport=tcp"),
            StreamCallProfile.orderUdpFirst(ice).map { it.urls[0] })
    }

    @Test fun streamWebRtcVersionLocked() = assertEquals("io.getstream:stream-webrtc-android:1.3.10", StreamCallProfile.STREAM_WEBRTC_ANDROID)

    @Test fun turnTtl() {
        assertEquals(600L, StreamCallProfile.turnSecondsLeft("1600:u", 1000))
        assertNull(StreamCallProfile.turnSecondsLeft("static", 0))
    }

    @Test fun opus3ae8ff34() {
        val sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1;usedtx=1\r\n"
        val out = StreamCallProfile.mungeOpus(sdp)
        assertTrue(out.contains("useinbandfec=1")); assertTrue(out.contains("usedtx=0")); assertTrue(out.contains("stereo=0"))
        assertTrue(out.contains("maxaveragebitrate=48000")); assertTrue(out.contains("ptime=20")); assertTrue(out.contains("a=ptime:20"))
        assertEquals(out, StreamCallProfile.mungeOpus(out))
    }

    @Test fun ladderFloor24k() {
        val l = StreamCallProfile.VoiceLadder()
        assertNull(l.push(10.0, 100.0)); assertEquals(32_000, l.push(10.0, 100.0))
        repeat(10) { l.push(0.0, 600.0) }
        assertEquals(24_000, l.bps)
        var up: Int? = null; repeat(8) { up = l.push(0.0, 50.0) ?: up }
        assertEquals(32_000, up)
    }

    @Test fun everyDropRestartsWithBackoff() {
        val b = StreamCallProfile.IceRestartBackoff()
        assertTrue(b.shouldRestart(0)); b.onConnected()
        assertTrue("second drop after recovery restarts again", b.shouldRestart(5_000)); b.onConnected()
        assertFalse(b.shouldRestart(5_500))
        var t = 6_000L; var n = 0
        repeat(200) { t += 500; if (b.shouldRestart(t)) n++ }
        assertTrue("bounded: $n", n <= 8)
        assertTrue(b.gapMs <= 30_000)
    }

    @Test fun relayProbeNeverGivesUp() {
        assertEquals(3_000L, StreamCallProfile.udpProbeDelay(0))
        assertEquals(60_000L, StreamCallProfile.udpProbeDelay(1000))
        assertTrue(StreamCallProfile.isRelayed("relay", "host")); assertFalse(StreamCallProfile.isRelayed("srflx", "host"))
    }

    @Test fun androidLaneUdpLayerFirst() {
        val l = StreamCallProfile.laneIce(username = "1:u", credential = "c").map { it.urls[0] }
        assertEquals(listOf("stun:165.232.137.71:3478", "turn:165.232.137.71:3478?transport=udp",
            "turn:165.232.137.71:3478?transport=tcp", "turns:turn.openlounge.app:443?transport=tcp"), l)
        assertEquals(listOf("stun:165.232.137.71:3478"), StreamCallProfile.defaultIce().map { it.urls[0] })
        // the ranking never lets a TCP/TLS relay ahead of a UDP entry, whatever order the server sends
        val shuffled = StreamCallProfile.orderUdpFirst(StreamCallProfile.laneIce(username = "1:u", credential = "c").reversed())
        assertEquals(l, shuffled.map { it.urls[0] })
    }
}
