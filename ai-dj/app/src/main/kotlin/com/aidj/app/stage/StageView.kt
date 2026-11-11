package com.aidj.app.stage

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.PowerManager
import com.aidj.app.data.GraphicsQuality

/**
 * GLSurfaceView hosting the stage. Rendering runs on its own GL thread, fully decoupled from the audio
 * thread: a slow GPU can only drop frames, never audio. Render scale and frame-rate cap follow quality.
 */
class StageView(context: Context, val inputs: StageInputs) : GLSurfaceView(context) {
    private val renderer = StageRenderer(inputs)
    private var lastScale = 0f

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Called from the UI on every state update. */
    fun refreshPowerState() {
        val pm = context.getSystemService(PowerManager::class.java)
        inputs.powerSave = pm?.isPowerSaveMode == true
        val q = if (inputs.powerSave) GraphicsQuality.LOW else if (inputs.quality == GraphicsQuality.AUTO) inputs.effectiveQuality else inputs.quality
        val scale = when (q) { GraphicsQuality.LOW -> 0.6f; GraphicsQuality.MEDIUM -> 0.8f; else -> 1f }
        if (scale != lastScale && width > 0 && height > 0) {
            lastScale = scale
            holder.setFixedSize((width * scale).toInt().coerceAtLeast(320), (height * scale).toInt().coerceAtLeast(240))
        }
    }
}
