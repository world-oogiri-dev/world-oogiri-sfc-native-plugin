package com.worldoogiri.poc.straight_face_landmarker

internal object NativeStopSource {
    const val DART_STOP_CAMERA = "DART_STOP_CAMERA"
    const val RUNTIME_RELEASE = "RUNTIME_RELEASE"
    const val PLATFORM_VIEW_DISPOSE = "PLATFORM_VIEW_DISPOSE"
    const val ACTIVITY_DETACH = "ACTIVITY_DETACH"
    const val ENGINE_DETACH = "ENGINE_DETACH"
    const val CONFIG_CHANGE = "CONFIG_CHANGE"
    const val WATCHDOG_STOP = "WATCHDOG_STOP"
    const val CAMERA_INITIALIZATION_FAILURE = "CAMERA_INITIALIZATION_FAILURE"
    const val CAMERA_BIND_FAILURE = "CAMERA_BIND_FAILURE"
    const val START_CAMERA_REPLACEMENT = "START_CAMERA_REPLACEMENT"
    const val UNKNOWN = "UNKNOWN"

    fun fromDart(value: String?): String = when (value) {
        DART_STOP_CAMERA -> DART_STOP_CAMERA
        RUNTIME_RELEASE -> RUNTIME_RELEASE
        else -> UNKNOWN
    }
}

internal data class NativeStopRequest(
    val source: String,
    val reason: String,
    val controllerGeneration: Long,
    val nativeLifecycle: String,
    val startInFlight: Boolean,
    val requestedElapsedMs: Long,
    val dartStartAttempt: Long,
    val dartLeaseGeneration: Long,
) {
    fun toMetrics(): Map<String, Any> = mapOf(
        "stopSource" to source,
        "stopReason" to reason,
        "stopControllerGeneration" to controllerGeneration,
        "stopNativeLifecycle" to nativeLifecycle,
        "stopDuringStart" to startInFlight,
        "stopRequestedElapsedMs" to requestedElapsedMs,
        "dartStartAttempt" to dartStartAttempt,
        "dartLeaseGeneration" to dartLeaseGeneration,
    )
}

/** Controller-local, first-write-wins diagnostic storage. */
internal class FirstNativeStopRequest {
    private var first: NativeStopRequest? = null

    @Synchronized
    fun recordIfAbsent(request: NativeStopRequest): NativeStopRequest? {
        if (first != null) return null
        first = request
        return request
    }

    @Synchronized
    fun snapshot(): NativeStopRequest? = first
}
