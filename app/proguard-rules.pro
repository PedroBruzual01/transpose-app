# JNI binds these by hard-coded symbol name
# (Java_com_breakfastquay_rubberband_RubberBandStretcher_*, see
# src/jni/RubberBandStretcherJNI.cpp) rather than JNI_OnLoad/RegisterNatives —
# the class and its native methods must keep their exact names or the native
# library can't find them at runtime (silent UnsatisfiedLinkError, not a
# build-time error).
-keep class com.breakfastquay.rubberband.RubberBandStretcher { *; }

# WebView calls these by name via Java reflection from injected JS
# (window.TransposeBridge.onLevel(...) etc., see BrowserScreen.kt's hook
# script) — obfuscating them breaks that silently, the calls just no-op.
-keep class com.realtimetranspose.browser.TransposeJsBridge { *; }
