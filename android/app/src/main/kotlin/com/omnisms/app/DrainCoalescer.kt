package com.omnisms.app

/**
 * Keeps at most one drain task active and coalesces any burst of later
 * requests into a single follow-up drain. This is deliberately Android-free
 * so the scheduling contract can be verified with ordinary unit tests.
 */
internal class DrainCoalescer {
    private var active = false
    private var followUpRequested = false

    /** Returns true only for the caller that must submit a worker task. */
    @Synchronized
    fun request(): Boolean {
        if (active) {
            followUpRequested = true
            return false
        }
        active = true
        return true
    }

    /**
     * Called after one drain completes. Returns true if one coalesced
     * follow-up drain is required; otherwise releases the active slot.
     */
    @Synchronized
    fun continueOrRelease(): Boolean {
        if (followUpRequested) {
            followUpRequested = false
            return true
        }
        active = false
        return false
    }

    /** Releases the slot if task submission or execution fails. */
    @Synchronized
    fun reset() {
        active = false
        followUpRequested = false
    }
}
