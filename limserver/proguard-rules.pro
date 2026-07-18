# Не трогаем точку входа нашего бота
-keep class com.safelogj.limserver.** {
    public static void main(java.lang.String[]);
}

# Отключаем обфускацию (сжатие имен), чтобы логи оставались читаемыми в Winbox
-dontobfuscate
-dontoptimize

# Игнорируем предупреждения от сторонних библиотек (иначе сборка упадет из-за чужих варнингов)
-dontwarn **

# Сохраняем аннотации и сигнатуры (критично для Jackson/Gson и работы с JSON)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Logback + SLF4J
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }
-keep class * implements org.slf4j** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
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

# HikariCP
-keep class com.zaxxer.hikari.** { *; }
-dontwarn com.zaxxer.hikari.**