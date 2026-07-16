# Исправление метода getChatIdByUsername в DatabaseHelper

Пользователь хочет обновить метод `getChatIdByUsername`, чтобы при нахождении чата в базе данных (по имени пользователя собеседника) у этого чата автоматически сбрасывался флаг `is_hidden` (устанавливался в `0`). Это позволит "проявить" чат в списке, если он был скрыт.

## Proposed Changes

### [Component Name] :app (Android Client)

#### [MODIFY] [DatabaseHelper.java](file:///C:/Users/attl/AndroidStudioProjects/Lim/app/src/main/java/com/safelogj/lim/DatabaseHelper.java)

Обновить метод `getChatIdByUsername` (строки 169-183):
1. Добавить выполнение UPDATE-запроса для установки `is_hidden = 0`, если чат найден.
2. Обеспечить корректный возврат ID чата через `callback.onSuccess`.
3. Добавить обработку случая, когда чат не найден (возвращать `0L`).
4. Добавить логирование ошибок.

## Verification Plan

### Automated Tests
- Поскольку это ручное исправление в логике работы с БД, проверка будет заключаться в компиляции и анализе кода.

### Manual Verification
- Пользователь сможет проверить, что при поиске существующего, но скрытого чата, он появляется в списке чатов (так как `getChatList` фильтрует по `is_hidden = 0`).
