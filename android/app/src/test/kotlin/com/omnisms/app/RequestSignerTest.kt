package com.omnisms.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RequestSignerTest {
    @Test fun signatureIsStableAndBodyBound(){
        val secret="01234567890123456789012345678901".toByteArray()
        val first=RequestSigner.sign("POST","/v1/messages","2026-08-17T01:02:03Z","nonce-example-001","message-example-001","{}".toByteArray(),secret)
        val second=RequestSigner.sign("POST","/v1/messages","2026-08-17T01:02:03Z","nonce-example-001","message-example-001","{}".toByteArray(),secret)
        val changed=RequestSigner.sign("POST","/v1/messages","2026-08-17T01:02:03Z","nonce-example-001","message-example-001","{\"x\":1}".toByteArray(),secret)
        assertEquals("PgwXTuhgEo8yzAVmS2YRGSf5rgvR8P7PSrP8iORICI0",first)
        assertEquals(first,second);assertNotEquals(first,changed)
    }
}
