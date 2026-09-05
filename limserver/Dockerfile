# Шаг 1: Сборка кастомной JRE (Tree Shaking)
FROM docker.io/arm32v7/eclipse-temurin:17-resolute AS jre-builder

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
    --compress=2 \
    --output /javaruntime

# Шаг 2: Финальный легковесный образ
FROM arm32v7/alpine:latest
WORKDIR /app

# Устанавливаем совместимость с glibc (критично для нативной либки SQLite в Alpine)
RUN apk add --no-cache gcompat libstdc++

# Копируем кастомную JRE и твой JAR
COPY --from=jre-builder /javaruntime /javaruntime
COPY build/libs/limserver-chr-small.jar /app/limserver.jar

# Создаем папки для монтирования (чтобы права доступа были верные)
RUN mkdir -p /app/db /app/media

# Запуск с ограничением памяти для роутера
CMD ["/javaruntime/bin/java", \
     "-Dsun.net.httpserver.maxReqTime=180", \
     "-Dsun.net.httpserver.maxRspTime=180", \
     "-Dsun.net.httpserver.nodelay=true", \
     "-Xms32m", "-Xmx96m", \
     "-jar", "/app/limserver.jar"]