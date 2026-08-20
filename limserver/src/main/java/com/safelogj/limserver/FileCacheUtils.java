package com.safelogj.limserver;

import com.sun.jna.Native;
import com.sun.jna.Platform;

import java.io.File;

public class FileCacheUtils {

    private FileCacheUtils() {}

    // Объявляем прямое обращение к функции открытия файла и fadvise из C-библиотеки Linux
    private static class DirectCLib {
        static {
            if (Platform.isLinux()) {
                Native.register(Platform.C_LIBRARY_NAME);
            }
        }
        public static native int open(String path, int flags);
        // Системный close
        public static native int close(int fd);
        // Системный posix_fadvise
        public static native int posix_fadvise(int fd, long offset, long len, int advice);
    }

    public static void dropFileFromCache(File file) {
        if (!Platform.isLinux() || file == null || !file.exists()) {
            LimController.log.info("[CacheCleaner] не линукс или файла нет");
            return;
        }

        int fd = -1;
        try {
            // 1. Открываем файл напрямую через Linux API (получаем честный native fd)
            fd = DirectCLib.open(file.getAbsolutePath(), 0); // Флаг O_RDONLY в Linux = 0

            if (fd < 0) {
               LimController.log.info("[CacheCleaner] failed to open file by system call. FD: {}", fd);
                return;
            }
            // 2. Вызываем posix_fadvise (от 0 до конца файла)
            int result = DirectCLib.posix_fadvise(fd, 0, file.length(), 4); // Флаг POSIX_FADV_DONTNEED = 4 (Выселить страницы из RAM)

            if (result == 0) {
                LimController.log.info("[CacheCleaner] File {} successfully evicted from RAM!", file.getName());
            } else {
                LimController.log.info("[CacheCleaner] posix_fadvise return code: {}", result);
            }
        } catch (Exception e) {
            LimController.log.error("[CacheCleaner] error clearing cache: {}", e.getMessage());
        } finally {
            // 3. Обязательно закрываем файловый дескриптор Linux
            if (fd >= 0) {
                DirectCLib.close(fd);
            }
        }
    }
}
