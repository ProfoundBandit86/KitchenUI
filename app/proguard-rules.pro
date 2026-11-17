# KitchenUI release shrinking: optimize libraries (Compose, coroutines, Coil), keep app code whole.

# VLC calls into Java from native code
-keep class org.videolan.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# KitchenUI and the Flux TV port (JSON field names, JNI callbacks, manifest classes)
-keep class com.kitchenui.launcher.** { *; }
-keep class com.fluxtv.app.** { *; }

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable

-dontwarn org.videolan.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn kotlin.**
-ignorewarnings
