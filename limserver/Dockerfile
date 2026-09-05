# Шаг 1: Сборка кастомной JRE (Tree Shaking)
# Используем образ на базе Debian, так как он поддерживает amd64, arm64 и arm/v7
FROM bellsoft/liberica-openjdk-debian:21 AS jre-builder

RUN $JAVA_HOME/bin/jlink \
    --add-modules \
java.base,\
java.sql,\
java.naming,\
java.management,\
java.logging,\
jdk.httpserver,\
jdk.crypto.ec,\
jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-9 \
    --output /javaruntime

# Шаг 2: Финальный легковесный образ на базе Debian Slim
FROM debian:bookworm-slim
WORKDIR /app

# Копируем кастомную JRE из первого этапа
COPY --from=jre-builder /javaruntime /javaruntime

# Копируем ваш сжатый через ProGuard JAR
COPY build/libs/limserver-chr-small.jar /app/limserver.jar

# Создаем папки для монтирования
RUN mkdir -p /app/db /app/media

# Запуск с оптимизированными параметрами памяти
CMD ["/javaruntime/bin/java", \
     "-Dsun.net.httpserver.maxReqTime=180", \
     "-Dsun.net.httpserver.maxRspTime=180", \
     "-Dsun.net.httpserver.nodelay=true", \
     "-Xms32m", "-Xmx96m", \
     "-jar", "/app/limserver.jar"]
