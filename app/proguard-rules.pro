# HyperIsland ToolKit serializes its models with kotlinx.serialization; keep them intact.
-keep class io.github.d4viddf.hyperisland_kit.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn kotlinx.serialization.**
