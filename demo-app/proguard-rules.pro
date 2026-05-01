# AnchorKit demo-app ProGuard rules

# Keep demo app entry points (Activities referenced in AndroidManifest)
-keep class io.anchorkit.demo.MainActivity { *; }
-keep class io.anchorkit.demo.CameraActivity { *; }

# Keep view binding generated classes
-keep class io.anchorkit.demo.databinding.** { *; }

# Suppress warnings from unused platform dependencies
-dontwarn java.lang.instrument.**
