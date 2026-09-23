# Шаг 1: Сборка кастомной JRE (Tree Shaking)
# Используем BellSoft, так как он поддерживает и x86_64, и arm64, и arm/v7 на Alpine
FROM bellsoft/liberica-openjdk-alpine:17 AS jre-builder

RUN $JAVA_HOME/bin/jlink \
    --module-path "$JAVA_HOME/jmods" \
    --add-modules java.base,java.sql,java.naming,java.management,java.logging,jdk.httpserver,jdk.crypto.ec,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /javaruntime

# Шаг 2: Финальный легковесный образ
FROM alpine:latest
WORKDIR /app

# Устанавливаем совместимость с glibc (для SQLite)
RUN apk add --no-cache gcompat libstdc++

# Копируем кастомную JRE и JAR
COPY --from=jre-builder /javaruntime /javaruntime
COPY limserver-chr-small.jar /app/limserver.jar

# Создаем папки
RUN mkdir -p /app/db /app/media

# Запуск
CMD ["/javaruntime/bin/java", \
     "-Dsun.net.httpserver.maxReqTime=180", \
     "-Dsun.net.httpserver.maxRspTime=180", \
     "-Dsun.net.httpserver.nodelay=true", \
     "-Xms32m", "-Xmx96m", \
     "-jar", "/app/limserver.jar"]
