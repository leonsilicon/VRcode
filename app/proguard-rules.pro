# The Spatial SDK and protobuf-lite ship consumer rules; these cover the app itself.
-keep class dev.improve.vrcode.MainActivity { *; }
# Spatial SDK components are looked up by name at runtime.
-keep class com.meta.spatial.** { *; }
-dontwarn com.meta.spatial.**
