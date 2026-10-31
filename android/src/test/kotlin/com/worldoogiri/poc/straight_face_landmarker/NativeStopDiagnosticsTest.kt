package com.worldoogiri.poc.straight_face_landmarker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class NativeStopDiagnosticsTest {
    @Test
    fun firstRequestWinsAndLaterCleanupCannotOverwriteIt() {
        val recorder = FirstNativeStopRequest()
        val platformDispose = request(
            source = NativeStopSource.PLATFORM_VIEW_DISPOSE,
            reason = "platform_view_disposed",
            generation = 4,
            startInFlight = true,
        )
        val runtimeCleanup = request(
            source = NativeStopSource.RUNTIME_RELEASE,
            reason = "runtime_release",
            generation = 4,
            startInFlight = false,
        )

        assertEquals(platformDispose, recorder.recordIfAbsent(platformDispose))
        assertNull(recorder.recordIfAbsent(runtimeCleanup))
        assertEquals(platformDispose, recorder.snapshot())
    }

    @Test
    fun controllerGenerationAndDartCorrelationRemainOnFirstRequest() {
        val recorder = FirstNativeStopRequest()
        val first = request(
            source = NativeStopSource.ACTIVITY_DETACH,
            reason = "activity_detached",
            generation = 7,
            startInFlight = true,
        )

        recorder.recordIfAbsent(first)
        val metrics = recorder.snapshot()!!.toMetrics()

        assertEquals(7L, metrics["stopControllerGeneration"])
        assertEquals(31L, metrics["dartStartAttempt"])
        assertEquals(12L, metrics["dartLeaseGeneration"])
        assertEquals("starting", metrics["stopNativeLifecycle"])
        assertEquals(true, metrics["stopDuringStart"])
        assertEquals(245L, metrics["stopRequestedElapsedMs"])
    }

    @Test
    fun dartSourceOnlyAcceptsKnownDiagnosticValues() {
        assertEquals(
            NativeStopSource.DART_STOP_CAMERA,
            NativeStopSource.fromDart(NativeStopSource.DART_STOP_CAMERA),
        )
        assertEquals(
            NativeStopSource.RUNTIME_RELEASE,
            NativeStopSource.fromDart(NativeStopSource.RUNTIME_RELEASE),
        )
        assertEquals(NativeStopSource.UNKNOWN, NativeStopSource.fromDart("invented"))
        assertEquals(NativeStopSource.UNKNOWN, NativeStopSource.fromDart(null))
    }

    private fun request(
        source: String,
        reason: String,
        generation: Long,
        startInFlight: Boolean,
    ) = NativeStopRequest(
        source = source,
        reason = reason,
        controllerGeneration = generation,
        nativeLifecycle = "starting",
        startInFlight = startInFlight,
        requestedElapsedMs = 245L,
        dartStartAttempt = 31L,
        dartLeaseGeneration = 12L,
    )
}
