# RE:Vision Windows Agent — integration test

Тестовый Windows-сервис для управления STUDIO PC через Android-приложение RE:Vision и голосовой агент Rokid.

## Что реализовано в этой ветке

- Сопряжение Android-клиента по одноразовому коду из `%ProgramData%\RevisionWindowsAgent\agent.pairing-code.txt`.
- HMAC-SHA256 для команд после сопряжения, временная метка и одноразовый nonce против повторного воспроизведения.
- `lock` — блокировка активной консольной сессии Windows через штатный LockWorkStation.
- `shutdown`, `restart`, `cancel-shutdown`.
- Локальный редактор каталога приложений и BAT-файлов; удалённое выполнение допускается только по точной сохранённой голосовой фразе. Для EXE доступны запуск, закрытие и перезапуск; BAT-файлы запускаются в активной сессии.
- EXE/BAT запускаются в активной пользовательской сессии, а не в Session 0.

## Android / Rokid

В RE:Vision → Настройки → Windows Agent — STUDIO PC укажи адрес вида `http://192.168.1.10:47821` и одноразовый код с компьютера. Секрет сопряжения сохраняется в Android EncryptedSharedPreferences.

Голосовые действия AIUI:
- `windows_status`
- `windows_lock`
- `windows_shutdown`
- `windows_restart`
- `windows_cancel_shutdown`
- `windows_launch` — требует точную фразу, сохранённую в каталоге.

## Панель команд на STUDIO PC

После установки открой на самом компьютере `http://127.0.0.1:47821/manager`. Панель доступна только с loopback. Добавляй только проверенные EXE и BAT, указывай точную голосовую фразу. Каталог хранится в `%ProgramData%\RevisionWindowsAgent\commands.json`.

## Сборка и установка

GitHub Actions собирает self-contained ZIP для Windows x64. Для установки распакуй архив, открой PowerShell от имени администратора и запусти `install-agent.ps1`. Сервис устанавливается как LocalSystem; это необходимо для получения токена активной пользовательской сессии и запуска процессов на её рабочем столе.

## Важное ограничение безопасности

Это интеграционная тестовая сборка. Транспорт Windows Agent пока HTTP без TLS. HMAC защищает целостность команд после сопряжения, но не шифрует трафик и не защищает одноразовый код от перехвата в сети. Используй только доверенную частную локальную сеть, не подключайся через публичный Wi-Fi и не открывай TCP 47821 в Интернет. Перед постоянным использованием нужен TLS с проверкой сертификата/закреплением ключа и тест на реальном STUDIO PC.

## Протокол

- `GET /v1/status`
- `POST /v1/pair` с JSON `{"code":"ONE_TIME_CODE"}`
- `POST /v1/command/lock`
- `POST /v1/command/shutdown`
- `POST /v1/command/restart`
- `POST /v1/command/cancel-shutdown`
- `POST /v1/command/execute` с JSON `{"phrase":"точная фраза из каталога"}`
- `POST /v1/unpair`

Подписываемая строка: `METHOD\nPATH\nUNIX_TIMESTAMP\nNONCE\nBODY`. Заголовки: `X-Revision-Time`, `X-Revision-Nonce`, `X-Revision-Signature`.
