package com.omnisms.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainCoalescerTest {
    @Test fun burstRequestsProduceOneFollowUpDrain(){
        val coalescer = DrainCoalescer()

        assertTrue(coalescer.request())
        assertFalse(coalescer.request())
        assertFalse(coalescer.request())

        assertTrue(coalescer.continueOrRelease())
        assertFalse(coalescer.continueOrRelease())
        assertTrue(coalescer.request())
    }

    @Test fun resetAllowsSubmissionAfterFailure(){
        val coalescer = DrainCoalescer()

        assertTrue(coalescer.request())
        assertFalse(coalescer.request())
        coalescer.reset()

        assertTrue(coalescer.request())
        assertFalse(coalescer.continueOrRelease())
    }
}
