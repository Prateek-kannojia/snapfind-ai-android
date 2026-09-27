package com.example.snapfindai.facesdk

/**
 * Thrown when a model passed to createFromAsset()/createFromBytes() doesn't
 * match the input/output shape the detector or embedder implementation
 * expects -- e.g. an embedder model loaded as a detector. Caught at
 * construction time, with a message naming what was expected and what was
 * actually found, rather than surfacing as an opaque crash the first time
 * detect()/embed() is called.
 */
class InvalidModelException(message: String) : Exception(message)
