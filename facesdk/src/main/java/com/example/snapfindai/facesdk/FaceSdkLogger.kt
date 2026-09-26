package com.example.snapfindai.facesdk

/**
 * The SDK never pulls in a logging framework of its own — this is the hook
 * a host app wires into whatever it already uses (Timber, Crashlytics,
 * plain Log.e). Failures the SDK recovers from internally (e.g. one bad
 * event photo in a batch) are reported here instead of being silently
 * swallowed.
 */
fun interface FaceSdkLogger {
    fun onError(message: String, throwable: Throwable)

    companion object {
        val NONE = FaceSdkLogger { _, _ -> }
    }
}
