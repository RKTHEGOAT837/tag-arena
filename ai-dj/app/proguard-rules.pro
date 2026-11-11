# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.aidj.**$$serializer { *; }
-keepclassmembers class com.aidj.** { *** Companion; }
-keepclasseswithmembers class com.aidj.** { kotlinx.serialization.KSerializer serializer(...); }
# JNI
-keep class com.aidj.engine.NativeEngine { native <methods>; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
