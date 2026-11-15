-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.gallery.**$$serializer { *; }
-keepclassmembers class app.gallery.** { *** Companion; }
-keepclasseswithmembers class app.gallery.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class ai.onnxruntime.** { *; }
-dontwarn org.slf4j.**

# ML Kit creates its component registrars by reflection (R8 full mode strips their constructors).
-keep class com.google.mlkit.**Registrar { <init>(); }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
# MapLibre native code calls back into these.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
