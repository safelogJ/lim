
# Gson
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keep class com.google.gson.reflect.** { *; }
-keep class * extends com.google.gson.reflect.**
-keepclassmembers class * {
    @com.google.gson.annotations.** <fields>;
}
-keep class * extends java.lang.Record { *; }
-keepclassmembers class * extends java.lang.Record { <fields>; <init>(...); }

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# 1. Общие настройки (помогают при отладке крашей в консоли Google Play)
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod

# Не трогаем наши модели и запросы, чтобы ключи в JSON не переименовались
-keep class com.safelogj.lim.model.** { *; }
-keep class com.safelogj.lim.request.** { *; }
-keep class com.safelogj.lim.response.** { *; }

# 5. Glide (библиотека для загрузки изображений)
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}
# Если используешь Glide на Android 10+ (API 29)
-dontwarn com.bumptech.glide.load.resource.bitmap.VideoDecoder
