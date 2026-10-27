package com.worldoogiri.poc.straight_face_landmarker

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.hypot

internal class StraightFaceCameraController(
    private val applicationContext: Context,
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val controllerGeneration: Long,
    private val emit: (Map<String, Any?>) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stateLock = Any()
    private val inferenceBusy = AtomicBoolean(false)
    private val watchdogTriggered = AtomicBoolean(false)
    private val cameraFrames = AtomicLong(0)
    private val submittedFrames = AtomicLong(0)
    private val droppedBusyFrames = AtomicLong(0)
    private val resultFrames = AtomicLong(0)
    private val noFaceFrames = AtomicLong(0)
    private val inferenceTotalMs = AtomicLong(0)
    private val maxInferenceMs = AtomicLong(0)
    private val inferenceStartedAtMs = AtomicLong(0)
    private val lastMediaPipeCallbackAtMs = AtomicLong(0)
    private val watchdogCount = AtomicLong(0)
    private val pendingFrames = LinkedHashMap<Long, PendingFrame>()
    private val completedFrames = LinkedHashMap<Long, Bitmap>()
    private val evidenceFrames = LinkedHashMap<Long, EvidenceFrame>()
    private val evidenceCandidateTimestamps = LinkedHashMap<String, Long>()

    @Volatile private var active = false
    @Volatile private var sessionId = ""
    @Volatile private var generation = 0L
    @Volatile private var lastFrameTimestampMs = 0L
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var imageAnalysis: ImageAnalysis? = null
    @Volatile private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var lifecycleState = "created"
    @Volatile private var cameraBound = false
    private var stopStarted = false
    private var stopOutcome: StopOutcome? = null
    private val stopCallbacks = mutableListOf<(StopOutcome) -> Unit>()
    private var startOutcome: StartOutcome? = null
    private var startCallback: ((StartOutcome) -> Unit)? = null
    private var baselinePssKb = 0L
    private var startedAtMs = 0L

    fun start(
        newSessionId: String,
        onReady: (Map<String, Any>) -> Unit,
        onFailure: (String, String) -> Unit,
    ) {
        synchronized(stateLock) {
            if (stopStarted) {
                mainHandler.post {
                    onFailure("camera_start_cancelled", "Camera was stopped before start completed")
                }
                return
            }
            startCallback = { outcome ->
                if (outcome.errorCode == null) {
                    onReady(outcome.metrics)
                } else {
                    onFailure(
                        outcome.errorCode,
                        outcome.errorMessage ?: outcome.errorCode,
                    )
                }
            }
        }
        sessionId = newSessionId
        active = true
        generation += 1
        val localGeneration = generation
        lifecycleState = "starting"
        baselinePssKb = Debug.getPss()
        startedAtMs = SystemClock.elapsedRealtime()
        analysisExecutor.execute {
            if (!isCurrent(localGeneration)) return@execute
            try {
                val created = createFaceLandmarker(localGeneration)
                if (!isCurrent(localGeneration)) {
                    created.close()
                    return@execute
                }
                faceLandmarker = created
                mainHandler.post {
                    if (isCurrent(localGeneration)) bindCamera(localGeneration)
                }
            } catch (error: RuntimeException) {
                failStart(
                    "mediapipe_initialization",
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun createFaceLandmarker(localGeneration: Long): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result, input ->
                onResult(localGeneration, result, input.width, input.height)
            }
            .setErrorListener errorListener@{ error ->
                if (!isCurrent(localGeneration)) return@errorListener
                clearInferenceBusy()
                sendError("mediapipe_runtime", error.message ?: error.javaClass.simpleName)
            }
            .build()
        return FaceLandmarker.createFromOptions(applicationContext, options)
    }

    private fun bindCamera(localGeneration: Long) {
        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener(
            listener@{
                if (!isCurrent(localGeneration)) return@listener
                try {
                    val provider = providerFuture.get()
                    if (!isCurrent(localGeneration)) return@listener
                    cameraProvider = provider
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(previewView.display.rotation)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analyzer = ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(previewView.display.rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                                analyze(localGeneration, image)
                            }
                            this.imageAnalysis = imageAnalysis
                        }
                    provider.unbindAll()
                    if (!isCurrent(localGeneration)) {
                        analyzer.clearAnalyzer()
                        return@listener
                    }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analyzer,
                    )
                    cameraBound = true
                    lifecycleState = "ready"
                    completeStart(StartOutcome(metrics = metrics()))
                    emit(
                        mapOf(
                            "kind" to "camera_started",
                            "sessionId" to sessionId,
                            "timestampMs" to SystemClock.elapsedRealtime(),
                            "nativeVersion" to NATIVE_VERSION,
                            "controllerGeneration" to controllerGeneration,
                            "lifecycleState" to lifecycleState,
                        ),
                    )
                } catch (error: Exception) {
                    failStart("camera_binding", error.message ?: error.javaClass.simpleName)
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    private fun analyze(localGeneration: Long, imageProxy: ImageProxy) {
        cameraFrames.incrementAndGet()
        if (!isCurrent(localGeneration)) {
            imageProxy.close()
            return
        }
        if (inferenceBusy.get()) {
            droppedBusyFrames.incrementAndGet()
            imageProxy.close()
            val busySince = inferenceStartedAtMs.get()
            val busyForMs = (SystemClock.elapsedRealtime() - busySince).coerceAtLeast(0)
            if (busySince > 0 && busyForMs >= CALLBACK_WATCHDOG_TIMEOUT_MS &&
                watchdogTriggered.compareAndSet(false, true)
            ) {
                watchdogCount.incrementAndGet()
                lifecycleState = "callback_timeout"
                sendError(
                    "mediapipe_callback_timeout",
                    "MediaPipe callback did not return for ${busyForMs}ms",
                )
                // Do not clear inferenceBusy here. The active Landmarker is
                // torn down instead, so no second detectAsync can overlap it.
                stop()
            }
            return
        }
        if (!inferenceBusy.compareAndSet(false, true)) {
            droppedBusyFrames.incrementAndGet()
            imageProxy.close()
            return
        }
        var rotatedBitmap: Bitmap? = null
        try {
            var frameTimestamp = SystemClock.elapsedRealtime()
            if (frameTimestamp <= lastFrameTimestampMs) frameTimestamp = lastFrameTimestampMs + 1
            lastFrameTimestampMs = frameTimestamp
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val sourceWidth = imageProxy.width
            val sourceHeight = imageProxy.height
            val bitmapBuffer = Bitmap.createBitmap(
                sourceWidth,
                sourceHeight,
                Bitmap.Config.ARGB_8888,
            )
            imageProxy.planes[0].buffer.rewind()
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            imageProxy.close()
            // Keep analysis coordinates unmirrored. PreviewView may mirror the
            // front-camera preview; formal photo mirroring remains Q-04.
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true,
            )
            if (rotatedBitmap !== bitmapBuffer) bitmapBuffer.recycle()
            synchronized(pendingFrames) {
                pendingFrames[frameTimestamp] = PendingFrame(
                    bitmap = rotatedBitmap,
                    meanLuma = sampledMeanLuma(rotatedBitmap),
                    rotationDegrees = rotationDegrees,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
            }
            submittedFrames.incrementAndGet()
            inferenceStartedAtMs.set(SystemClock.elapsedRealtime())
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            faceLandmarker?.detectAsync(mpImage, frameTimestamp)
                ?: throw IllegalStateException("FaceLandmarker is not initialized")
        } catch (error: Exception) {
            imageProxy.close()
            clearInferenceBusy()
            rotatedBitmap?.let { bitmap ->
                synchronized(pendingFrames) {
                    pendingFrames.entries.removeIf { entry -> entry.value.bitmap === bitmap }
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            sendError("frame_processing", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun onResult(
        localGeneration: Long,
        result: FaceLandmarkerResult,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        val resultAtMs = SystemClock.elapsedRealtime()
        val timestamp = result.timestampMs()
        val pendingFrame = synchronized(pendingFrames) { pendingFrames.remove(timestamp) }
        if (!isCurrent(localGeneration)) {
            pendingFrame?.bitmap?.recycleSafely()
            return
        }
        clearInferenceBusy(resultAtMs)
        pendingFrame?.bitmap?.let { retainCompletedFrame(timestamp, it) }
        val inferenceMs = (resultAtMs - timestamp).coerceAtLeast(0)
        resultFrames.incrementAndGet()
        inferenceTotalMs.addAndGet(inferenceMs)
        maxInferenceMs.updateAndGet { current -> maxOf(current, inferenceMs) }

        val landmarks = result.faceLandmarks().firstOrNull()
        val blendshapeGroups = result.faceBlendshapes().orElse(emptyList())
        val blendshapeMap: Map<String, Double> = blendshapeGroups.firstOrNull()
            ?.associate { category -> category.categoryName() to category.score().toDouble() }
            ?: emptyMap()
        val eyeFeatureMap: Map<String, Double> = landmarks?.let(::eyeFeatures) ?: emptyMap()
        val faceCount = result.faceLandmarks().size
        if (faceCount == 0) noFaceFrames.incrementAndGet()
        val observationState = when {
            faceCount > 0 -> "observed"
            pendingFrame != null && pendingFrame.meanLuma < TOO_DARK_MEAN_LUMA -> "too_dark"
            else -> "no_face"
        }
        emit(
            mapOf(
                "kind" to "observation",
                "sessionId" to sessionId,
                "frameTimestampMs" to timestamp,
                "resultTimestampMs" to resultAtMs,
                "inferenceMs" to inferenceMs,
                "faceCount" to faceCount,
                "observationState" to observationState,
                "meanLuma" to pendingFrame?.meanLuma,
                "lipGapRatio" to landmarks?.let(::lipGapRatio),
                "blendshapes" to blendshapeMap,
                "eyeFeatures" to eyeFeatureMap,
                "inputWidth" to inputWidth,
                "inputHeight" to inputHeight,
                "sourceWidth" to pendingFrame?.sourceWidth,
                "sourceHeight" to pendingFrame?.sourceHeight,
                "rotationDegrees" to pendingFrame?.rotationDegrees,
                "analysisMirrored" to false,
                "cameraFrameCount" to cameraFrames.get(),
                "submittedFrameCount" to submittedFrames.get(),
                "droppedBusyFrameCount" to droppedBusyFrames.get(),
                "resultFrameCount" to resultFrames.get(),
                "pssKb" to Debug.getPss(),
                "controllerGeneration" to controllerGeneration,
            ),
        )
    }

    private fun lipGapRatio(
        landmarks: List<NormalizedLandmark>,
    ): Double? {
        if (landmarks.size <= 263) return null
        val upper = landmarks[13]
        val lower = landmarks[14]
        val leftEye = landmarks[33]
        val rightEye = landmarks[263]
        val lipGap = hypot(
            (upper.x() - lower.x()).toDouble(),
            (upper.y() - lower.y()).toDouble(),
        )
        val eyeDistance = hypot(
            (leftEye.x() - rightEye.x()).toDouble(),
            (leftEye.y() - rightEye.y()).toDouble(),
        )
        if (eyeDistance <= 0.000001) return null
        return lipGap / eyeDistance
    }

    /**
     * Local eye features normalized by each eye's corner distance. They are
     * only meaningful relative to the same session's fixed calibration; they
     * are not screen gaze coordinates and are never persisted or logged.
     */
    private fun eyeFeatures(landmarks: List<NormalizedLandmark>): Map<String, Double>? {
        if (landmarks.size <= LEFT_IRIS_CENTER) return null
        val left = eyeGeometry(
            landmarks = landmarks,
            outerIndex = LEFT_EYE_OUTER,
            innerIndex = LEFT_EYE_INNER,
            lidPairs = LEFT_EYE_LID_PAIRS,
            irisIndex = LEFT_IRIS_CENTER,
        ) ?: return null
        val right = eyeGeometry(
            landmarks = landmarks,
            outerIndex = RIGHT_EYE_OUTER,
            innerIndex = RIGHT_EYE_INNER,
            lidPairs = RIGHT_EYE_LID_PAIRS,
            irisIndex = RIGHT_IRIS_CENTER,
        ) ?: return null

        val leftEyeX = (landmarks[LEFT_EYE_OUTER].x() + landmarks[LEFT_EYE_INNER].x()) / 2.0
        val leftEyeY = (landmarks[LEFT_EYE_OUTER].y() + landmarks[LEFT_EYE_INNER].y()) / 2.0
        val rightEyeX = (landmarks[RIGHT_EYE_OUTER].x() + landmarks[RIGHT_EYE_INNER].x()) / 2.0
        val rightEyeY = (landmarks[RIGHT_EYE_OUTER].y() + landmarks[RIGHT_EYE_INNER].y()) / 2.0
        val eyeMidX = (leftEyeX + rightEyeX) / 2.0
        val eyeMidY = (leftEyeY + rightEyeY) / 2.0
        val interEyeDistance = hypot(leftEyeX - rightEyeX, leftEyeY - rightEyeY)
        if (interEyeDistance <= MIN_NORMALIZER) return null
        val nose = landmarks[NOSE_TIP]

        return mapOf(
            "leftGazeX" to left.gazeX,
            "leftGazeY" to left.gazeY,
            "rightGazeX" to right.gazeX,
            "rightGazeY" to right.gazeY,
            "leftEyeAperture" to left.aperture,
            "rightEyeAperture" to right.aperture,
            "leftEyeApertureLegacy" to left.legacyAperture,
            "rightEyeApertureLegacy" to right.legacyAperture,
            "headYaw" to ((nose.x().toDouble() - eyeMidX) / interEyeDistance),
            "headPitch" to ((nose.y().toDouble() - eyeMidY) / interEyeDistance),
            "headRoll" to atan2(leftEyeY - rightEyeY, leftEyeX - rightEyeX),
        )
    }

    private fun eyeGeometry(
        landmarks: List<NormalizedLandmark>,
        outerIndex: Int,
        innerIndex: Int,
        lidPairs: Array<Pair<Int, Int>>,
        irisIndex: Int,
    ): EyeGeometry? {
        val outer = landmarks[outerIndex]
        val inner = landmarks[innerIndex]
        val iris = landmarks[irisIndex]
        val axisX = inner.x().toDouble() - outer.x().toDouble()
        val axisY = inner.y().toDouble() - outer.y().toDouble()
        val width = hypot(axisX, axisY)
        if (width <= MIN_NORMALIZER) return null
        val unitX = axisX / width
        val unitY = axisY / width
        val perpendicularX = -unitY
        val perpendicularY = unitX
        val midpointX = (inner.x().toDouble() + outer.x().toDouble()) / 2.0
        val midpointY = (inner.y().toDouble() + outer.y().toDouble()) / 2.0
        val irisOffsetX = iris.x().toDouble() - midpointX
        val irisOffsetY = iris.y().toDouble() - midpointY
        val normalizedLidDistances = lidPairs.map { (topIndex, bottomIndex) ->
            val top = landmarks[topIndex]
            val bottom = landmarks[bottomIndex]
            hypot(
                top.x().toDouble() - bottom.x().toDouble(),
                top.y().toDouble() - bottom.y().toDouble(),
            ) / width
        }
        if (normalizedLidDistances.isEmpty()) return null
        // A3 robust aperture averages three official eye-contour lid pairs.
        // The first pair is A2's legacy value and remains available for an
        // on-device old/new comparison. No landmarks or samples are stored.
        val legacyAperture = normalizedLidDistances.first()
        val aperture = normalizedLidDistances.average()
        return EyeGeometry(
            gazeX = (irisOffsetX * unitX + irisOffsetY * unitY) / width,
            gazeY = (irisOffsetX * perpendicularX + irisOffsetY * perpendicularY) / width,
            aperture = aperture,
            legacyAperture = legacyAperture,
        )
    }

    private fun retainCompletedFrame(timestamp: Long, bitmap: Bitmap) {
        synchronized(completedFrames) {
            completedFrames[timestamp] = bitmap
            while (completedFrames.size > MAX_COMPLETED_FRAMES) {
                val oldest = completedFrames.entries.first()
                completedFrames.remove(oldest.key)
                oldest.value.recycleSafely()
            }
        }
    }

    /**
     * Coarse local-only brightness guard used to avoid treating an obviously
     * dark frame as proof that the face left the frame. This is a provisional
     * PoC quality threshold, not a face rule.
     */
    private fun sampledMeanLuma(bitmap: Bitmap): Double {
        val xStep = maxOf(1, bitmap.width / LUMA_GRID_SIZE)
        val yStep = maxOf(1, bitmap.height / LUMA_GRID_SIZE)
        var sum = 0.0
        var count = 0
        var y = yStep / 2
        while (y < bitmap.height) {
            var x = xStep / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                sum += 0.2126 * Color.red(pixel) +
                    0.7152 * Color.green(pixel) +
                    0.0722 * Color.blue(pixel)
                count += 1
                x += xStep
            }
            y += yStep
        }
        return if (count == 0) 0.0 else sum / count
    }

    fun claimFrameJpeg(timestamp: Long, callback: (ByteArray?) -> Unit) {
        analysisExecutor.execute {
            val bitmap = synchronized(completedFrames) { completedFrames.remove(timestamp) }
            val bytes = bitmap?.let {
                ByteArrayOutputStream().use { output ->
                    it.compress(Bitmap.CompressFormat.JPEG, 88, output)
                    output.toByteArray()
                }.also { _ -> it.recycleSafely() }
            }
            mainHandler.post { callback(bytes) }
        }
    }

    /**
     * Pins one exact, already-analysed frame as a possible violation start.
     * No nearest-frame fallback is allowed: returning false means the caller
     * must report that an evidence image could not be obtained.
     */
    fun retainEvidenceFrame(
        expectedSessionId: String,
        candidateId: String,
        timestamp: Long,
        callback: (Boolean) -> Unit,
    ) {
        analysisExecutor.execute {
            if (!active || expectedSessionId != sessionId || candidateId.isBlank()) {
                mainHandler.post { callback(false) }
                return@execute
            }
            val retained = synchronized(completedFrames) {
                synchronized(evidenceFrames) {
                    releaseEvidenceCandidateLocked(candidateId)
                    val existing = evidenceFrames[timestamp]
                    val bitmap = existing?.bitmap ?: completedFrames.remove(timestamp)
                    if (bitmap == null || bitmap.isRecycled) {
                        false
                    } else {
                        val frame = existing ?: EvidenceFrame(bitmap = bitmap)
                        frame.candidateIds.add(candidateId)
                        evidenceFrames[timestamp] = frame
                        evidenceCandidateTimestamps[candidateId] = timestamp
                        trimEvidenceCandidatesLocked()
                        true
                    }
                }
            }
            mainHandler.post { callback(retained) }
        }
    }

    fun releaseEvidenceCandidate(
        expectedSessionId: String,
        candidateId: String,
        callback: (Boolean) -> Unit,
    ) {
        analysisExecutor.execute {
            val released = if (expectedSessionId == sessionId) {
                synchronized(evidenceFrames) { releaseEvidenceCandidateLocked(candidateId) }
            } else {
                false
            }
            mainHandler.post { callback(released) }
        }
    }

    /**
     * Claims the exact pinned violation-start frame. The returned JPEG is
     * mirrored only for the user's front-camera preview; analysis coordinates
     * and the A3 decision pipeline remain unmirrored and unchanged.
     */
    fun claimEvidenceFrame(
        expectedSessionId: String,
        candidateId: String,
        timestamp: Long,
        callback: (CapturedEvidence?) -> Unit,
    ) {
        analysisExecutor.execute {
            if (expectedSessionId != sessionId || candidateId.isBlank()) {
                mainHandler.post { callback(null) }
                return@execute
            }
            val bitmap = synchronized(completedFrames) {
                synchronized(evidenceFrames) {
                    val pinnedTimestamp = evidenceCandidateTimestamps[candidateId]
                    val selected = if (pinnedTimestamp == timestamp) {
                        evidenceFrames.remove(timestamp)?.also { frame ->
                            frame.candidateIds.forEach(evidenceCandidateTimestamps::remove)
                        }?.bitmap
                    } else {
                        // An exact completed frame is acceptable even if the
                        // asynchronous pin request lost a race. Never select a
                        // neighbouring timestamp.
                        completedFrames.remove(timestamp)
                    }
                    selected
                }
            }
            val captured = bitmap?.takeUnless(Bitmap::isRecycled)?.let {
                encodeMirroredEvidence(it, timestamp)
            }
            mainHandler.post { callback(captured) }
        }
    }

    fun discardCompletedFrames() {
        analysisExecutor.execute {
            synchronized(completedFrames) {
                completedFrames.values.forEach { it.recycleSafely() }
                completedFrames.clear()
            }
            synchronized(evidenceFrames) {
                evidenceFrames.values.forEach { it.bitmap.recycleSafely() }
                evidenceFrames.clear()
                evidenceCandidateTimestamps.clear()
            }
        }
    }

    fun metrics(): Map<String, Any> {
        val results = resultFrames.get()
        val currentPss = Debug.getPss()
        val now = SystemClock.elapsedRealtime()
        val busySince = inferenceStartedAtMs.get()
        return mapOf(
            "nativeVersion" to NATIVE_VERSION,
            "controllerGeneration" to controllerGeneration,
            "lifecycleState" to lifecycleState,
            "cameraBound" to cameraBound,
            "analysisBound" to (imageAnalysis != null),
            "inferenceBusy" to inferenceBusy.get(),
            "inferenceBusyForMs" to if (inferenceBusy.get() && busySince > 0) {
                (now - busySince).coerceAtLeast(0)
            } else {
                0L
            },
            "lastMediaPipeCallbackAgeMs" to lastMediaPipeCallbackAtMs.get().let { callbackAt ->
                if (callbackAt > 0) (now - callbackAt).coerceAtLeast(0) else -1L
            },
            "watchdogCount" to watchdogCount.get(),
            "sessionId" to sessionId,
            "cameraFrames" to cameraFrames.get(),
            "submittedFrames" to submittedFrames.get(),
            "droppedBusyFrames" to droppedBusyFrames.get(),
            "resultFrames" to results,
            "noFaceFrames" to noFaceFrames.get(),
            "averageInferenceMs" to if (results == 0L) 0.0 else inferenceTotalMs.get().toDouble() / results,
            "maxInferenceMs" to maxInferenceMs.get(),
            "baselinePssKb" to baselinePssKb,
            "currentPssKb" to currentPss,
            "pssDeltaKb" to currentPss - baselinePssKb,
            "sessionElapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0),
            "retainedFrameCapacity" to MAX_COMPLETED_FRAMES,
            "evidenceCandidateCapacity" to MAX_EVIDENCE_CANDIDATES,
            "evidenceCandidateCount" to synchronized(evidenceFrames) {
                evidenceCandidateTimestamps.size
            },
        )
    }

    fun stop(callback: ((StopOutcome) -> Unit)? = null) {
        var immediate: StopOutcome? = null
        synchronized(stateLock) {
            stopOutcome?.let { completed ->
                immediate = completed
                return@synchronized
            }
            if (callback != null) stopCallbacks.add(callback)
            if (stopStarted) return@synchronized
            stopStarted = true
            active = false
            generation += 1
            lifecycleState = "stopping"
            if (startOutcome == null) {
                startOutcome = StartOutcome(
                    errorCode = "camera_start_cancelled",
                    errorMessage = "Camera was stopped before start completed",
                )
            }
        }
        if (immediate != null) {
            mainHandler.post { callback?.invoke(immediate) }
            return
        }
        beginStop()
    }

    private fun beginStop() {
        mainHandler.post {
            var unbindFailure: Throwable? = null
            try {
                imageAnalysis?.clearAnalyzer()
                imageAnalysis = null
                cameraProvider?.unbindAll()
                cameraProvider = null
                cameraBound = false
            } catch (error: Throwable) {
                unbindFailure = error
            }
            closeLandmarkerAndAwaitTermination(unbindFailure)
        }
    }

    private fun closeLandmarkerAndAwaitTermination(unbindFailure: Throwable?) {
        try {
            analysisExecutor.execute {
                var closeFailure: Throwable? = null
                try {
                    faceLandmarker?.close()
                } catch (error: Throwable) {
                    closeFailure = error
                } finally {
                    faceLandmarker = null
                    clearInferenceBusy()
                    recycleFrameBuffers()
                }
                try {
                    analysisExecutor.shutdown()
                    teardownExecutor.execute {
                        val outcome = try {
                            val terminated = analysisExecutor.awaitTermination(
                                STOP_TERMINATION_TIMEOUT_MS,
                                TimeUnit.MILLISECONDS,
                            )
                            when {
                                !terminated -> StopOutcome(
                                    metrics = metrics(),
                                    errorCode = "camera_teardown_timeout",
                                    errorMessage = "Camera analysis did not terminate within ${STOP_TERMINATION_TIMEOUT_MS}ms",
                                )
                                unbindFailure != null -> StopOutcome(
                                    metrics = metrics(),
                                    errorCode = "camera_unbind_failed",
                                    errorMessage = unbindFailure.message
                                        ?: unbindFailure.javaClass.simpleName,
                                )
                                closeFailure != null -> StopOutcome(
                                    metrics = metrics(),
                                    errorCode = "mediapipe_close_failed",
                                    errorMessage = closeFailure.message
                                        ?: closeFailure.javaClass.simpleName,
                                )
                                else -> StopOutcome(metrics = metrics())
                            }
                        } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            StopOutcome(
                                metrics = metrics(),
                                errorCode = "camera_teardown_interrupted",
                                errorMessage = error.message ?: error.javaClass.simpleName,
                            )
                        } catch (error: Throwable) {
                            StopOutcome(
                                metrics = metrics(),
                                errorCode = "camera_teardown_failed",
                                errorMessage = error.message ?: error.javaClass.simpleName,
                            )
                        }
                        completeStop(outcome)
                    }
                    teardownExecutor.shutdown()
                } catch (error: Throwable) {
                    completeStop(
                        StopOutcome(
                            metrics = metrics(),
                            errorCode = "camera_teardown_failed",
                            errorMessage = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        } catch (error: Throwable) {
            completeStop(
                StopOutcome(
                    metrics = metrics(),
                    errorCode = "camera_teardown_failed",
                    errorMessage = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun recycleFrameBuffers() {
        synchronized(pendingFrames) {
            pendingFrames.values.forEach { it.bitmap.recycleSafely() }
            pendingFrames.clear()
        }
        synchronized(completedFrames) {
            completedFrames.values.forEach { it.recycleSafely() }
            completedFrames.clear()
        }
        synchronized(evidenceFrames) {
            evidenceFrames.values.forEach { it.bitmap.recycleSafely() }
            evidenceFrames.clear()
            evidenceCandidateTimestamps.clear()
        }
    }

    private fun completeStop(outcome: StopOutcome) {
        val callbacks: List<(StopOutcome) -> Unit>
        val pendingStart: ((StartOutcome) -> Unit)?
        synchronized(stateLock) {
            if (stopOutcome != null) return
            stopOutcome = outcome
            lifecycleState = if (outcome.errorCode == null) "stopped" else "stop_failed"
            callbacks = stopCallbacks.toList()
            stopCallbacks.clear()
            pendingStart = if (startOutcome != null && startCallback != null) {
                val callback = startCallback
                startCallback = null
                callback
            } else {
                null
            }
        }
        if (outcome.errorCode == null) {
            emit(
                mapOf(
                    "kind" to "camera_stopped",
                    "sessionId" to sessionId,
                    "timestampMs" to SystemClock.elapsedRealtime(),
                    "nativeVersion" to NATIVE_VERSION,
                    "controllerGeneration" to controllerGeneration,
                    "lifecycleState" to lifecycleState,
                ),
            )
        } else {
            sendError(outcome.errorCode, outcome.errorMessage ?: outcome.errorCode)
        }
        mainHandler.post {
            pendingStart?.invoke(startOutcome ?: StartOutcome(metrics = metrics()))
            callbacks.forEach { it(outcome) }
        }
    }

    private fun completeStart(outcome: StartOutcome) {
        val callback: ((StartOutcome) -> Unit)?
        synchronized(stateLock) {
            if (startOutcome != null) return
            startOutcome = outcome
            callback = startCallback
            startCallback = null
        }
        mainHandler.post { callback?.invoke(outcome) }
    }

    private fun failStart(code: String, message: String) {
        if (!isCurrent(generation)) return
        sendError(code, message)
        synchronized(stateLock) {
            if (startOutcome == null) {
                startOutcome = StartOutcome(errorCode = code, errorMessage = message)
            }
        }
        stop()
    }

    private fun clearInferenceBusy(nowMs: Long = SystemClock.elapsedRealtime()) {
        inferenceBusy.set(false)
        inferenceStartedAtMs.set(0)
        lastMediaPipeCallbackAtMs.set(nowMs)
    }

    private fun isCurrent(localGeneration: Long): Boolean {
        return active && generation == localGeneration
    }

    private fun sendError(code: String, message: String) {
        emit(
            mapOf(
                "kind" to "camera_error",
                "sessionId" to sessionId,
                "code" to code,
                "message" to message,
                "timestampMs" to SystemClock.elapsedRealtime(),
                "nativeVersion" to NATIVE_VERSION,
                "controllerGeneration" to controllerGeneration,
                "lifecycleState" to lifecycleState,
            ),
        )
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private fun releaseEvidenceCandidateLocked(candidateId: String): Boolean {
        val timestamp = evidenceCandidateTimestamps.remove(candidateId) ?: return false
        val frame = evidenceFrames[timestamp] ?: return true
        frame.candidateIds.remove(candidateId)
        if (frame.candidateIds.isEmpty()) {
            evidenceFrames.remove(timestamp)
            frame.bitmap.recycleSafely()
        }
        return true
    }

    private fun trimEvidenceCandidatesLocked() {
        while (evidenceCandidateTimestamps.size > MAX_EVIDENCE_CANDIDATES) {
            val oldestCandidateId = evidenceCandidateTimestamps.entries.first().key
            releaseEvidenceCandidateLocked(oldestCandidateId)
        }
    }

    private fun encodeMirroredEvidence(bitmap: Bitmap, timestamp: Long): CapturedEvidence? {
        return try {
            val matrix = Matrix().apply { postScale(-1f, 1f) }
            val mirrored = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            )
            val bytes = ByteArrayOutputStream().use { output ->
                mirrored.compress(Bitmap.CompressFormat.JPEG, EVIDENCE_JPEG_QUALITY, output)
                output.toByteArray()
            }
            val captured = CapturedEvidence(
                bytes = bytes,
                sessionId = sessionId,
                frameTimestampMs = timestamp,
                width = mirrored.width,
                height = mirrored.height,
                mirrored = true,
            )
            if (mirrored !== bitmap) mirrored.recycleSafely()
            bitmap.recycleSafely()
            captured
        } catch (_: RuntimeException) {
            bitmap.recycleSafely()
            null
        }
    }

    private data class PendingFrame(
        val bitmap: Bitmap,
        val meanLuma: Double,
        val rotationDegrees: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private data class EvidenceFrame(
        val bitmap: Bitmap,
        val candidateIds: MutableSet<String> = linkedSetOf(),
    )

    data class CapturedEvidence(
        val bytes: ByteArray,
        val sessionId: String,
        val frameTimestampMs: Long,
        val width: Int,
        val height: Int,
        val mirrored: Boolean,
    )

    private data class EyeGeometry(
        val gazeX: Double,
        val gazeY: Double,
        val aperture: Double,
        val legacyAperture: Double,
    )

    data class StopOutcome(
        val metrics: Map<String, Any>,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    private data class StartOutcome(
        val metrics: Map<String, Any> = emptyMap(),
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    companion object {
        const val NATIVE_VERSION = "SFC-R4-20261027-native-lifecycle-fix"
        private const val CALLBACK_WATCHDOG_TIMEOUT_MS = 10000L
        private const val STOP_TERMINATION_TIMEOUT_MS = 10000L
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val MAX_COMPLETED_FRAMES = 6
        private const val MAX_EVIDENCE_CANDIDATES = 8
        private const val EVIDENCE_JPEG_QUALITY = 90
        private const val LUMA_GRID_SIZE = 16
        private const val TOO_DARK_MEAN_LUMA = 25.0
        private const val MIN_NORMALIZER = 0.000001
        private const val NOSE_TIP = 1
        private const val RIGHT_EYE_OUTER = 33
        private const val RIGHT_EYE_INNER = 133
        private const val RIGHT_IRIS_CENTER = 468
        private const val LEFT_EYE_OUTER = 263
        private const val LEFT_EYE_INNER = 362
        private const val LEFT_IRIS_CENTER = 473
        private val RIGHT_EYE_LID_PAIRS = arrayOf(159 to 145, 158 to 153, 160 to 144)
        private val LEFT_EYE_LID_PAIRS = arrayOf(386 to 374, 385 to 380, 387 to 373)
    }
}
