# Шаг 1: Сборка кастомной JRE (Tree Shaking)
FROM docker.io/arm32v7/eclipse-temurin:17-resolute AS jre-builder

WORKDIR /app

# Устанавливаем совместимость с glibc (критично для нативной либки SQLite в Alpine)
RUN apk add --no-cache gcompat libstdc++

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