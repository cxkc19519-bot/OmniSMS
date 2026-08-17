package com.omnisms.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessageFingerprintTest {
    @Test fun fingerprintIsStableAndDoesNotExposeMessage(){
        val first=MessageFingerprint.create("示例发送方","固定虚构短信 123456",1_700_000_000_000)
        val second=MessageFingerprint.create("示例发送方","固定虚构短信 123456",1_700_000_000_000)
        assertEquals(first,second)
        assertEquals(64,first.length)
        assertFalse(first.contains("123456"))
    }

    @Test fun timestampSeparatesRepeatedMessages(){
        assertNotEquals(
            MessageFingerprint.create("示例发送方","相同虚构正文",1_700_000_000_000),
            MessageFingerprint.create("示例发送方","相同虚构正文",1_700_000_001_000)
        )
    }
}
