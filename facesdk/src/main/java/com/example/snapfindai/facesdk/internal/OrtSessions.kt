package com.example.snapfindai.facesdk.internal

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context

/** Loads a bundled ONNX model from assets/models/. Shared by FaceDetector and FaceEmbedder so there's one place that knows the asset layout. */
internal object OrtSessions {
    fun load(context: Context, env: OrtEnvironment, assetName: String): OrtSession {
        val opts = OrtSession.SessionOptions()
        return context.assets.open("models/$assetName").use { input ->
            env.createSession(input.readBytes(), opts)
        }
    }
}
