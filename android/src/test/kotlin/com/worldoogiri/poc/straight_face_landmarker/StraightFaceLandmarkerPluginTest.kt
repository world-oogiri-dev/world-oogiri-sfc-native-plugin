package com.worldoogiri.poc.straight_face_landmarker

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.mockito.Mockito
import kotlin.test.Test

/*
 * This demonstrates a simple unit test of the Kotlin portion of this plugin's implementation.
 *
 * Once you have built the plugin's example app, you can run these tests from the command
 * line by running `./gradlew testDebugUnitTest` in the `example/android/` directory, or
 * you can run them directly from IDEs that support JUnit such as Android Studio.
 */

internal class StraightFaceLandmarkerPluginTest {
    @Test
    fun evidenceMethodsRejectMissingIdentityAndTimestamp() {
        val plugin = StraightFaceLandmarkerPlugin()
        val mockResult: MethodChannel.Result = Mockito.mock(MethodChannel.Result::class.java)

        plugin.onMethodCall(MethodCall("retainEvidenceFrame", emptyMap<String, Any>()), mockResult)

        Mockito.verify(mockResult).error(
            Mockito.eq("invalid_argument"),
            Mockito.anyString(),
            Mockito.isNull(),
        )
    }

    @Test
    fun unknownMethodIsNotImplemented() {
        val plugin = StraightFaceLandmarkerPlugin()
        val mockResult: MethodChannel.Result = Mockito.mock(MethodChannel.Result::class.java)

        plugin.onMethodCall(MethodCall("unknownMethod", null), mockResult)

        Mockito.verify(mockResult).notImplemented()
    }
}
