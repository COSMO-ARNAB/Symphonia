package com.symphonia.gate2.transport

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediasoupListenerTransportTest {

    @Test
    fun testCleanRtpCapabilitiesZodCompliance() {
        // Mock raw RTP capabilities typically produced by mediasoup Device (WebRTC M137)
        val raw = JSONObject().apply {
            put("codecs", JSONArray().apply {
                put(JSONObject().apply {
                    put("kind", "audio") // INVALID in Zod rtpCodecSchema.strict()!
                    put("mimeType", "audio/opus")
                    put("clockRate", 48000)
                    put("channels", 2)
                    put("preferredPayloadType", 111)
                    put("parameters", JSONObject().apply {
                        put("minptime", 10)
                        put("useinbandfec", 1)
                        put("stereo", 1)
                    })
                })
                put(JSONObject().apply {
                    put("kind", "video") // Should be ignored
                    put("mimeType", "video/VP8")
                    put("clockRate", 90000)
                })
            })
            put("headerExtensions", JSONArray().apply {
                put(JSONObject().apply {
                    put("kind", "video") // Video extension - MUST BE REMOVED!
                    put("uri", "urn:ietf:params:rtp-hdrext:sdes:mid")
                    put("preferredId", 1)
                })
                put(JSONObject().apply {
                    put("uri", "urn:ietf:params:rtp-hdrext:ssrc-audio-level")
                    put("preferredId", 2)
                    put("direction", "sendrecv")
                    put("parameters", JSONObject()) // empty parameters allowed by Zod
                })
                put(JSONObject().apply {
                    put("uri", "urn:ietf:params:rtp-hdrext:toffset")
                    put("preferredId", 3)
                    put("parameters", JSONObject().apply {
                        put("illegalKey", "badValue") // Non-empty parameters MUST be stripped
                    })
                })
            })
        }

        // Create a dummy instance or access cleanRtpCapabilitiesForSignaling
        // Since MediasoupListenerTransport requires Context for constructor, but cleanRtpCapabilitiesForSignaling
        // is pure JSON logic, we can verify it directly.
        val dummyTransport = MediasoupListenerTransport(android.content.ContextWrapper(null))

        val cleanStereo = dummyTransport.cleanRtpCapabilitiesForSignaling(raw, forceMono = false)
        val codecs = cleanStereo.getJSONArray("codecs")
        assertEquals(1, codecs.length())

        val opusCodec = codecs.getJSONObject(0)
        assertFalse("Codec MUST NOT have 'kind' attribute (violates Zod .strict())", opusCodec.has("kind"))
        assertEquals("audio/opus", opusCodec.getString("mimeType"))
        assertEquals(48000, opusCodec.getInt("clockRate"))
        assertEquals(2, opusCodec.getInt("channels"))
        assertEquals(1, opusCodec.getJSONObject("parameters").getInt("stereo"))

        val exts = cleanStereo.getJSONArray("headerExtensions")
        assertEquals(2, exts.length())
        val ext1 = exts.getJSONObject(0)
        assertEquals("audio", ext1.getString("kind"))
        assertEquals("urn:ietf:params:rtp-hdrext:ssrc-audio-level", ext1.getString("uri"))
        assertTrue("Empty parameters object should be preserved", ext1.has("parameters"))
        assertEquals(0, ext1.getJSONObject("parameters").length())

        val ext2 = exts.getJSONObject(1)
        assertEquals("audio", ext2.getString("kind"))
        assertEquals("urn:ietf:params:rtp-hdrext:toffset", ext2.getString("uri"))
        assertFalse("Non-empty parameters MUST be omitted to satisfy Zod .strict()", ext2.has("parameters"))

        // Test forceMono
        val cleanMono = dummyTransport.cleanRtpCapabilitiesForSignaling(raw, forceMono = true)
        val monoCodec = cleanMono.getJSONArray("codecs").getJSONObject(0)
        assertEquals(1, monoCodec.getInt("channels"))
        assertFalse("Mono codec should not enforce stereo:1 parameter", monoCodec.optJSONObject("parameters")?.has("stereo") == true)
    }
}
