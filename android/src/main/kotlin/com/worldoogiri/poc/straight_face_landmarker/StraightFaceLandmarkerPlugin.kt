package com.worldoogiri.poc.straight_face_landmarker

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-only bridge used by the isolated SFC-A PoC.
 *
 * The bridge owns camera/MediaPipe resources, while the Dart host owns all
 * product-neutral calibration and straight-face decision logic.
 */
class StraightFaceLandmarkerPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var applicationContext: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var eventSinkAttached = false
    private var eventSinkGeneration = 0L
    private var activityBinding: ActivityPluginBinding? = null
    private var preview: StraightFaceCameraPlatformView? = null
    private var controller: StraightFaceCameraController? = null
    private var pendingStart: PendingStart? = null
    private var cameraStartInFlight = false
    private var pendingEvidenceSave: PendingEvidenceSave? = null
    private var claimedEvidence: ClaimedEvidence? = null
    private var lastMetrics: Map<String, Any> = emptyMap()
    private var nextControllerGeneration = 0L
    private var lifecycleBlockedCode: String? = null
    private var lifecycleBlockedMessage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
        binding.platformViewRegistry.registerViewFactory(
            CAMERA_VIEW,
            CameraViewFactory(::registerPreview, ::unregisterPreview),
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        stopController()
        discardClaimedEvidence()
        saveExecutor.shutdown()
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity("Activity recreated during camera session")
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity("Activity detached during camera session")
    }

    private fun detachActivity(message: String) {
        pendingStart?.result?.error("activity_detached", message, null)
        pendingStart = null
        pendingEvidenceSave?.result?.error("activity_detached", message, null)
        pendingEvidenceSave = null
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        stopController()
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
        eventSinkAttached = true
        eventSinkGeneration += 1
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        eventSinkAttached = false
        eventSinkGeneration += 1
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isPreviewReady" -> result.success(
                preview != null && activityBinding?.activity != null,
            )
            "startCamera" -> startCamera(call, result)
            "stopCamera" -> stopController(
                onComplete = { metrics -> result.success(metrics) },
                onFailure = { code, message -> result.error(code, message, null) },
            )
            "monotonicNowMs" -> result.success(SystemClock.elapsedRealtime())
            "claimFrameJpeg" -> {
                val timestamp = call.argument<Number>("frameTimestampMs")?.toLong()
                if (timestamp == null) {
                    result.error("invalid_argument", "frameTimestampMs is required", null)
                    return
                }
                val active = controller
                if (active == null) {
                    result.success(null)
                    return
                }
                active.claimFrameJpeg(timestamp) { bytes -> result.success(bytes) }
            }
            "retainEvidenceFrame" -> retainEvidenceFrame(call, result)
            "releaseEvidenceCandidate" -> releaseEvidenceCandidate(call, result)
            "claimEvidencePhoto" -> claimEvidencePhoto(call, result)
            "saveEvidencePhoto" -> saveEvidencePhoto(call, result)
            "discardEvidencePhoto" -> discardEvidencePhoto(call, result)
            "discardFrames" -> {
                controller?.discardCompletedFrames()
                result.success(null)
            }
            "getMetrics" -> result.success(currentMetrics())
            else -> result.notImplemented()
        }
    }

    private fun startCamera(call: MethodCall, result: MethodChannel.Result) {
        val sessionId = call.argument<String>("sessionId")
        if (sessionId.isNullOrBlank()) {
            result.error("invalid_argument", "sessionId is required", null)
            return
        }
        lifecycleBlockedCode?.let { code ->
            result.error(
                "camera_lifecycle_blocked",
                lifecycleBlockedMessage ?: code,
                currentMetrics(),
            )
            return
        }
        val activity = activityBinding?.activity
        if (activity == null) {
            result.error("no_activity", "No foreground Activity is attached", null)
            return
        }
        if (preview == null) {
            result.error("no_preview", "Camera PlatformView has not been created", null)
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (pendingStart != null) {
                result.error("permission_pending", "A camera permission request is already active", null)
                return
            }
            pendingStart = PendingStart(sessionId, result)
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST,
            )
            return
        }
        startController(sessionId, result)
    }

    private fun startController(sessionId: String, result: MethodChannel.Result) {
        if (cameraStartInFlight) {
            result.error("camera_start_in_progress", "A camera start is already in progress", null)
            return
        }
        val activity = activityBinding?.activity
        val lifecycleOwner = activity as? LifecycleOwner
        val activePreview = preview
        if (activity == null || lifecycleOwner == null || activePreview == null) {
            result.error(
                "invalid_host",
                "The host Activity must be a LifecycleOwner and the preview must exist",
                null,
            )
            return
        }
        if (claimedEvidence?.sessionId != null && claimedEvidence?.sessionId != sessionId) {
            discardClaimedEvidence()
        }
        cameraStartInFlight = true
        val replied = AtomicBoolean(false)
        fun fail(code: String, message: String) {
            if (replied.compareAndSet(false, true)) {
                cameraStartInFlight = false
                result.error(code, message, currentMetrics())
            }
        }
        fun succeed(metrics: Map<String, Any>) {
            if (replied.compareAndSet(false, true)) {
                cameraStartInFlight = false
                result.success(metrics)
            }
        }
        stopController(
            onComplete = {
                if (replied.get()) return@stopController
                lifecycleBlockedCode?.let { code ->
                    fail("camera_lifecycle_blocked", lifecycleBlockedMessage ?: code)
                    return@stopController
                }
                val currentActivity = activityBinding?.activity
                val currentLifecycleOwner = currentActivity as? LifecycleOwner
                val currentPreview = preview
                if (currentActivity == null || currentLifecycleOwner == null || currentPreview == null) {
                    fail(
                        "invalid_host",
                        "The host Activity must be a LifecycleOwner and the preview must exist",
                    )
                    return@stopController
                }
                val next = StraightFaceCameraController(
                    applicationContext = applicationContext,
                    activity = currentActivity,
                    lifecycleOwner = currentLifecycleOwner,
                    previewView = currentPreview.previewView,
                    controllerGeneration = ++nextControllerGeneration,
                    emit = ::emit,
                )
                controller = next
                next.start(
                    newSessionId = sessionId,
                    onReady = { metrics ->
                        lastMetrics = metrics
                        succeed(metrics)
                    },
                    onFailure = { code, message ->
                        lastMetrics = next.metrics()
                        if (lastMetrics["lifecycleState"] == "stop_failed") {
                            lifecycleBlockedCode = code
                            lifecycleBlockedMessage = message
                        }
                        if (controller === next) controller = null
                        fail(code, message)
                    },
                )
            },
            onFailure = { code, message -> fail(code, message) },
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode == EVIDENCE_SAVE_PERMISSION_REQUEST) {
            val pending = pendingEvidenceSave ?: return true
            pendingEvidenceSave = null
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pending.result.error(
                    "photo_save_permission_denied",
                    "Photo save permission was denied",
                    null,
                )
            } else {
                persistEvidence(pending.evidence, pending.result)
            }
            return true
        }
        if (requestCode != CAMERA_PERMISSION_REQUEST) return false
        val pending = pendingStart ?: return true
        pendingStart = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pending.result.error(
                "camera_permission_denied",
                "Camera permission was denied",
                null,
            )
            emit(
                mapOf(
                    "kind" to "permission_denied",
                    "sessionId" to pending.sessionId,
                ),
            )
            return true
        }
        startController(pending.sessionId, pending.result)
        return true
    }

    private fun retainEvidenceFrame(call: MethodCall, result: MethodChannel.Result) {
        val sessionId = call.argument<String>("sessionId")
        val candidateId = call.argument<String>("candidateId")
        val timestamp = call.argument<Number>("frameTimestampMs")?.toLong()
        if (sessionId.isNullOrBlank() || candidateId.isNullOrBlank() || timestamp == null) {
            result.error(
                "invalid_argument",
                "sessionId, candidateId and frameTimestampMs are required",
                null,
            )
            return
        }
        val active = controller
        if (active == null) {
            result.success(false)
            return
        }
        active.retainEvidenceFrame(sessionId, candidateId, timestamp) { retained ->
            result.success(retained)
        }
    }

    private fun releaseEvidenceCandidate(call: MethodCall, result: MethodChannel.Result) {
        val sessionId = call.argument<String>("sessionId")
        val candidateId = call.argument<String>("candidateId")
        if (sessionId.isNullOrBlank() || candidateId.isNullOrBlank()) {
            result.error("invalid_argument", "sessionId and candidateId are required", null)
            return
        }
        val active = controller
        if (active == null) {
            result.success(false)
            return
        }
        active.releaseEvidenceCandidate(sessionId, candidateId) { released ->
            result.success(released)
        }
    }

    private fun claimEvidencePhoto(call: MethodCall, result: MethodChannel.Result) {
        val sessionId = call.argument<String>("sessionId")
        val candidateId = call.argument<String>("candidateId")
        val timestamp = call.argument<Number>("frameTimestampMs")?.toLong()
        if (sessionId.isNullOrBlank() || candidateId.isNullOrBlank() || timestamp == null) {
            result.error(
                "invalid_argument",
                "sessionId, candidateId and frameTimestampMs are required",
                null,
            )
            return
        }
        val active = controller
        if (active == null) {
            result.success(null)
            return
        }
        active.claimEvidenceFrame(sessionId, candidateId, timestamp) { capture ->
            if (capture == null) {
                result.success(null)
                return@claimEvidenceFrame
            }
            discardClaimedEvidence()
            val evidence = ClaimedEvidence(
                evidenceId = UUID.randomUUID().toString(),
                sessionId = capture.sessionId,
                frameTimestampMs = capture.frameTimestampMs,
                width = capture.width,
                height = capture.height,
                mirrored = capture.mirrored,
                bytes = capture.bytes,
            )
            claimedEvidence = evidence
            result.success(evidence.publicMap(includeBytes = true))
        }
    }

    private fun saveEvidencePhoto(call: MethodCall, result: MethodChannel.Result) {
        val sessionId = call.argument<String>("sessionId")
        val evidenceId = call.argument<String>("evidenceId")
        val evidence = claimedEvidence
        if (sessionId.isNullOrBlank() || evidenceId.isNullOrBlank()) {
            result.error("invalid_argument", "sessionId and evidenceId are required", null)
            return
        }
        if (evidence == null || evidence.sessionId != sessionId || evidence.evidenceId != evidenceId) {
            result.error("evidence_not_found", "Evidence photo is unavailable or expired", null)
            return
        }
        val activity = activityBinding?.activity
        if (activity == null) {
            result.error("no_activity", "No foreground Activity is attached", null)
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (pendingEvidenceSave != null) {
                result.error("permission_pending", "A photo save permission request is active", null)
                return
            }
            pendingEvidenceSave = PendingEvidenceSave(evidence, result)
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                EVIDENCE_SAVE_PERMISSION_REQUEST,
            )
            return
        }
        persistEvidence(evidence, result)
    }

    private fun persistEvidence(evidence: ClaimedEvidence, result: MethodChannel.Result) {
        val bytes = evidence.bytes.copyOf()
        saveExecutor.execute {
            var uri: android.net.Uri? = null
            try {
                val resolver = applicationContext.contentResolver
                val displayName = "WORLD_OOGIRI_SFC_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/WORLD OOGIRI",
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                    ?: throw IllegalStateException("MediaStore output stream is unavailable")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
                val response = mapOf(
                    "saved" to true,
                    "contentUri" to uri.toString(),
                    "displayName" to displayName,
                    "mimeType" to "image/jpeg",
                )
                activityBinding?.activity?.runOnUiThread { result.success(response) }
                    ?: mainThreadResult(result, response)
            } catch (error: Exception) {
                uri?.let { applicationContext.contentResolver.delete(it, null, null) }
                mainHandlerResultError(result, "photo_save_failed", error.message ?: error.javaClass.simpleName)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun discardEvidencePhoto(call: MethodCall, result: MethodChannel.Result) {
        val sessionId = call.argument<String>("sessionId")
        val evidenceId = call.argument<String>("evidenceId")
        val evidence = claimedEvidence
        if (evidence == null) {
            result.success(mapOf("discarded" to false))
            return
        }
        if (sessionId != evidence.sessionId || evidenceId != evidence.evidenceId) {
            result.error("evidence_mismatch", "Evidence does not belong to this session", null)
            return
        }
        discardClaimedEvidence()
        result.success(mapOf("discarded" to true))
    }

    private fun discardClaimedEvidence() {
        claimedEvidence?.bytes?.fill(0)
        claimedEvidence = null
    }

    private fun mainThreadResult(result: MethodChannel.Result, value: Any?) {
        activityBinding?.activity?.runOnUiThread { result.success(value) }
            ?: android.os.Handler(android.os.Looper.getMainLooper()).post { result.success(value) }
    }

    private fun mainHandlerResultError(
        result: MethodChannel.Result,
        code: String,
        message: String,
    ) {
        activityBinding?.activity?.runOnUiThread { result.error(code, message, null) }
            ?: android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.error(code, message, null)
            }
    }

    private fun stopController(
        onComplete: (Map<String, Any>) -> Unit = {},
        onFailure: (String, String) -> Unit = { _, _ -> },
    ) {
        val active = controller
        if (active == null) {
            val blockedCode = lifecycleBlockedCode
            if (blockedCode != null) {
                mainHandler.post {
                    onFailure(blockedCode, lifecycleBlockedMessage ?: blockedCode)
                }
            } else {
                mainHandler.post { onComplete(currentMetrics()) }
            }
            return
        }
        active.stop { outcome ->
            lastMetrics = outcome.metrics
            if (controller === active) controller = null
            if (outcome.errorCode == null) {
                onComplete(currentMetrics())
            } else {
                lifecycleBlockedCode = outcome.errorCode
                lifecycleBlockedMessage = outcome.errorMessage
                onFailure(
                    outcome.errorCode,
                    outcome.errorMessage ?: outcome.errorCode,
                )
            }
        }
    }

    private fun currentMetrics(): Map<String, Any> {
        val base = controller?.metrics() ?: lastMetrics
        return base + mapOf(
            "nativeVersion" to StraightFaceCameraController.NATIVE_VERSION,
            "pluginLifecycleBlocked" to (lifecycleBlockedCode != null),
            "pluginLifecycleBlockCode" to (lifecycleBlockedCode ?: ""),
            "eventSinkAttached" to eventSinkAttached,
            "eventSinkGeneration" to eventSinkGeneration,
        )
    }

    private fun emit(event: Map<String, Any?>) {
        activityBinding?.activity?.runOnUiThread {
            eventSink?.success(event)
        }
    }

    private fun registerPreview(view: StraightFaceCameraPlatformView) {
        preview = view
    }

    private fun unregisterPreview(view: StraightFaceCameraPlatformView) {
        if (preview === view) {
            stopController()
            preview = null
        }
    }

    private data class PendingStart(
        val sessionId: String,
        val result: MethodChannel.Result,
    )

    private data class PendingEvidenceSave(
        val evidence: ClaimedEvidence,
        val result: MethodChannel.Result,
    )

    private data class ClaimedEvidence(
        val evidenceId: String,
        val sessionId: String,
        val frameTimestampMs: Long,
        val width: Int,
        val height: Int,
        val mirrored: Boolean,
        val bytes: ByteArray,
    ) {
        fun publicMap(includeBytes: Boolean): Map<String, Any?> = buildMap {
            put("evidenceId", evidenceId)
            put("sessionId", sessionId)
            put("frameTimestampMs", frameTimestampMs)
            put("width", width)
            put("height", height)
            put("mirrored", mirrored)
            put("mimeType", "image/jpeg")
            if (includeBytes) put("bytes", bytes)
        }
    }

    private class CameraViewFactory(
        private val onCreate: (StraightFaceCameraPlatformView) -> Unit,
        private val onDispose: (StraightFaceCameraPlatformView) -> Unit,
    ) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
            return StraightFaceCameraPlatformView(context, onCreate, onDispose)
        }
    }

    companion object {
        private const val METHOD_CHANNEL = "straight_face_landmarker/methods"
        private const val EVENT_CHANNEL = "straight_face_landmarker/events"
        private const val CAMERA_VIEW = "straight_face_landmarker/camera_view"
        private const val CAMERA_PERMISSION_REQUEST = 24018
        private const val EVIDENCE_SAVE_PERMISSION_REQUEST = 24019
    }
}
