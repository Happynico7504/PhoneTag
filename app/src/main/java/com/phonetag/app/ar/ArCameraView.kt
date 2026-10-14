package com.phonetag.app.ar

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The ARCore camera feed. While this is on screen (and the app is resumed) the ARCore session
 * runs: it renders the camera image, tracks the phone in 3D, scans sync markers and feeds
 * person detection. Only one of these should be shown at a time.
 */
@Composable
fun ArCameraView(engine: ArEngine, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(engine.renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }
    DisposableEffect(lifecycleOwner, engine) {
        val activity = context.findActivity()
        var running = false
        fun start() {
            if (!running && activity != null && engine.resume(activity)) {
                glView.onResume()
                running = true
            }
        }
        fun stop() {
            if (!running) return
            glView.onPause() // waits for the GL thread, so no frame races the session pause
            engine.pause()
            running = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer) // replays ON_RESUME if already resumed
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    AndroidView(factory = { glView }, modifier = modifier)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
