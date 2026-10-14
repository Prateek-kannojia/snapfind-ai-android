# Rules bundled into facesdk's AAR and applied automatically to any app
# that depends on this module and enables R8/ProGuard minification.

# ONNX Runtime's Java API is the JNI bridge to its native inference engine:
# native code calls back into these classes/methods by name. If R8 renames
# or strips them, model loading/inference fails at runtime with no build-time
# warning -- the exact "silently fails after shrinking" case ProGuard rules
# exist to prevent.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# facesdk's own public API: interfaces and models a host app's code
# references by name (and may reflectively inspect, e.g. Gson/Moshi if a
# consumer serializes FaceMatchResult-like data). Kept so a minified host
# app doesn't rename anything this module or its callers refer to across
# the library boundary.
-keep interface com.example.snapfindai.facesdk.api.** { *; }
-keep class com.example.snapfindai.facesdk.api.** { *; }
-keep class com.example.snapfindai.facesdk.model.** { *; }
-keep class com.example.snapfindai.facesdk.FaceMatchEngine { *; }
