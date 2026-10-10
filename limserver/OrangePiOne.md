# Установка и настройка LimServer на Orange Pi One (Armbian / Debian ARM32)
# Installing and Configuring LimServer on Orange Pi One (Armbian / Debian ARM32)

Данная инструкция описывает процесс установки и настройки нативного сервера Lim на одноплатный компьютер Orange Pi One (512MB RAM) под управлением Armbian Minimal (Debian).
This guide describes the process of installing and configuring the native Lim server on an Orange Pi One single-board computer (512MB RAM) running Armbian Minimal (Debian).

---

## 1. Подготовка системы и установка Java 21 / System Preparation & Java 21 Installation

Обновляем список пакетов и устанавливаем легкую версию OpenJDK 21 JRE:
Update package list and install lightweight OpenJDK 21 JRE:

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless
```

---

## 2. Создание рабочей директории / Creating Working Directories

Создаем структуру папок для приложения, базы данных и медиафайлов:
Create directory structure for application, database, and media files:

```bash
sudo mkdir -p /opt/limserver/db /opt/limserver/media
```

---

## 3. Генерация SSL-сертификата (если нет готового) / Generating SSL Certificate (Optional)

Если у вас нет готового SSL-сертификата `.p12` (например, из MikroTik), сгенерируйте его одной командой с помощью утилиты `keytool` (выполнять в `cmd` или `PowerShell` на ПК):
If you do not have a ready-made `.p12` SSL certificate (e.g. from MikroTik), generate one using Java's `keytool` utility (run in `cmd` or `PowerShell` on PC):

```cmd
# Генерация SSL-сертификата .p12 с явным указанием пути / Generating .p12 SSL certificate with explicit output path
keytool -genkeypair -alias limserver -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore "C:\Users\attl\Downloads\limcert.p12" -validity 3650 -storepass limservercert -ext SAN=ip:192.168.88.4 -dname "CN=LimServer"
```

---

## 4. Копирование файлов на Orange Pi / Copying Files to Orange Pi

Скопируйте с вашего компьютера файлы проекта в соответствующие директории (выполнять в `cmd` или `PowerShell` на ПК):
Copy project files from your PC to corresponding directories (run in `cmd` or `PowerShell` on PC):

```cmd
# 1. Копируем исполняемый JAR-файл сервера / Copy executable server JAR file
C:\Windows\System32\OpenSSH\scp.exe "C:\Users\attl\Downloads\limserver-chr-small.jar" root@192.168.88.4:/opt/limserver/limserver-chr-small.jar

# 2. Копируем файл конфигурации и SSL-сертификат в папку db / Copy config file and SSL certificate to db folder
C:\Windows\System32\OpenSSH\scp.exe "C:\Users\attl\Downloads\server.properties" root@192.168.88.4:/opt/limserver/db/server.properties
C:\Windows\System32\OpenSSH\scp.exe "C:\Users\attl\Downloads\limcert.p12" root@192.168.88.4:/opt/limserver/db/limcert.p12
```

---

## 5. Настройка автозапуска через systemd / Configuring Auto-start via systemd

Создаем файл службы systemd:
Create systemd service file:

```bash
sudo nano /etc/systemd/system/limserver.service
```

Вставляем следующее содержимое:
Insert the following content:

```ini
[Unit]
Description=Lim Messenger Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/limserver
ExecStart=/usr/bin/java \
  -Dsun.net.httpserver.maxReqTime=180 \
  -Dsun.net.httpserver.maxRspTime=180 \
  -Dsun.net.httpserver.nodelay=true \
  -Xms64m -Xmx256m \
  -jar /opt/limserver/limserver-chr-small.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```
(Чтобы сохранить и выйти в nano: нажмите Ctrl + O, затем Enter, затем Ctrl + X).
(To save and exit nano: press Ctrl + O, then Enter, then Ctrl + X).
Применяем конфигурацию и запускаем службу:
Apply configuration and start the service:

```bash
# Обновляем конфигурацию служб / Reload systemd manager configuration
sudo systemctl daemon-reload

# Включаем автозапуск при загрузке системы / Enable auto-start on boot
sudo systemctl enable limserver

# Запускаем службу / Start the service
sudo systemctl start limserver
```

---

## 6. Управление службой и просмотр логов / Managing Service & Viewing Logs

- **Проверить статус работы сервера / Check server status:**
  ```bash
  sudo systemctl status limserver
  ```

- **Смотреть логи сервера в реальном времени / View real-time server logs:**
  ```bash
  sudo journalctl -u limserver -f
  ```

- **Перезапустить сервер / Restart server:**
  ```bash
  sudo systemctl restart limserver
  ```

- **Остановить сервер / Stop server:**
  ```bash
  sudo systemctl stop limserver
  ```
