
# Отключаем обфускацию (сжатие имен), чтобы логи оставались читаемыми в Winbox
-dontobfuscate
-optimizationpasses 5
-allowaccessmodification
# Игнорируем предупреждения от сторонних библиотек (иначе сборка упадет из-за чужих варнингов)
-dontwarn **
# Сохраняем аннотации и сигнатуры (критично для Jackson/Gson и работы с JSON)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Logback + SLF4J
# Используем allowoptimization, чтобы ProGuard мог анализировать вызовы внутри библиотек и вырезать их.
-keep class com.safelogj.limserver.** { *; }
-keep,allowoptimization class com.zaxxer.hikari.** { *; }
-keep,allowoptimization class org.slf4j.** { *; }
-keep,allowoptimization class ch.qos.logback.** { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-assumenosideeffects class * {
    public void trace(...);
    public void debug(...);
    public boolean isTraceEnabled();
    public boolean isDebugEnabled();
    public org.slf4j.spi.LoggingEventBuilder atTrace();
    public org.slf4j.spi.LoggingEventBuilder atDebug();
}
# Gson
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
# Sqlite
-keep class org.sqlite.** { *; }
# JNA (Java Native Access)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * {
    native <methods>;
}