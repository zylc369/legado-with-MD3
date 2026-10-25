package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheBookPendingReadRequestTest {

    @Test
    fun firstRelayoutRequestKeepsReading() {
        val request = mergePendingReadRequest(
            previous = null,
            resetPageOffset = false,
            nav = ReaderNavIntent.KeepReading,
        )

        assertEquals(
            PendingReadRequest(
                resetPageOffset = false,
                nav = ReaderNavIntent.KeepReading,
            ),
            request,
        )
    }

    @Test
    fun resetPageOffsetIsRetainedAcrossPendingRequests() {
        val request = mergePendingReadRequest(
            previous = PendingReadRequest(
                resetPageOffset = true,
                nav = ReaderNavIntent.KeepReading,
            ),
            resetPageOffset = false,
            nav = ReaderNavIntent.KeepReading,
        )

        assertTrue(request.resetPageOffset)
        assertEquals(ReaderNavIntent.KeepReading, request.nav)
    }

    @Test
    fun anyNavigationRequestOverridesPositionPreservation() {
        val request = mergePendingReadRequest(
            previous = PendingReadRequest(
                resetPageOffset = false,
                nav = ReaderNavIntent.KeepReading,
            ),
            resetPageOffset = false,
            nav = ReaderNavIntent.UserJump,
        )

        assertEquals(ReaderNavIntent.UserJump, request.nav)
    }

    @Test
    fun laterRelayoutCannotOverrideNavigationRequest() {
        val request = mergePendingReadRequest(
            previous = PendingReadRequest(
                resetPageOffset = false,
                nav = ReaderNavIntent.UserJump,
            ),
            resetPageOffset = false,
            nav = ReaderNavIntent.KeepReading,
        )

        assertEquals(ReaderNavIntent.UserJump, request.nav)
    }
}
