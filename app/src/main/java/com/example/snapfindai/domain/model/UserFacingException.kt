package com.example.snapfindai.domain.model

/**
 * A failure whose [message] was written to be read by the user.
 *
 * Everything else that can be thrown here carries a message written for
 * whoever is reading a stack trace -- "EACCES (Permission denied)",
 * "Downloaded 'w600k_mbf.onnx' does not match the expected checksum", a bare
 * class name, or nothing at all. Those used to reach the screen verbatim,
 * because `e.message ?: "something went wrong"` can't tell the difference
 * between a sentence meant for a person and one meant for a logcat.
 *
 * This type is that difference. A layer turning a failure into something on
 * screen shows the message only when it arrives in one of these, and logs
 * anything else behind a generic line -- so the user gets a readable
 * sentence and the detail is still there for whoever is debugging.
 */
class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)
