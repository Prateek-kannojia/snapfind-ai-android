package com.example.snapfindai.facesdk.internal

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context

/**
 * Loads an ONNX model into a runnable session. Shared by the detector and
 * embedder implementations so there's one place that knows how model bytes
 * become a session, however those bytes were obtained: a bundled asset (the
 * default, validated models) or bytes a host app supplies itself (a newer
 * version of the same model, one fetched at runtime, one loaded from its own
 * assets — anywhere).
 */
internal object OrtSessions {
    fun fromAsset(context: Context, env: OrtEnvironment, assetPath: String): OrtSession {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return fromBytes(env, bytes)
    }

    fun fromBytes(env: OrtEnvironment, modelBytes: ByteArray): OrtSession =
        env.createSession(modelBytes, OrtSession.SessionOptions())
}
