package com.worldoogiri.poc.straight_face_landmarker

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import io.flutter.plugin.platform.PlatformView

internal class StraightFaceCameraPlatformView(
    context: Context,
    private val onCreate: (StraightFaceCameraPlatformView) -> Unit,
    private val onDispose: (StraightFaceCameraPlatformView) -> Unit,
) : PlatformView {
    val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    init {
        onCreate(this)
    }

    override fun getView(): View = previewView

    override fun dispose() {
        onDispose(this)
    }
}
