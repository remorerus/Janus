# RE:Vision Windows Agent — integration test

Тестовый Windows-сервис для управления STUDIO PC через Android-приложение RE:Vision и голосовой агент Rokid.

## Что реализовано в этой ветке

- Сопряжение Android-клиента по одноразовому коду из `%ProgramData%\RevisionWindowsAgent\agent.pairing-code.txt`.
- HMAC-SHA256 для команд после сопряжения, временная метка и одноразовый nonce против повторного воспроизведения.
- `lock` — блокировка активной консольной сессии Windows через штатный LockWorkStation.
- `shutdown`, `restart`, `cancel-shutdown`.
- Локальный редактор каталога приложений и BAT-файлов; удалённое выполнение допускается только по точной сохранённой голосовой фразе.
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

## Win SSH Agent

В той же установке Windows Agent доступна отдельная локальная панель **Win SSH Agent**:
- открой на STUDIO PC в браузере `http://127.0.0.1:47821/ssh-manager`;
- добавляй название, точную голосовую фразу, оболочку PowerShell или CMD и команду;
- список сохраняется отдельно в `%ProgramData%\\WinSshAgent\\commands.json`;
- команды запускаются по SSH из RE:Vision с использованием созданного в Android Keystore ключа. Пароль Windows не используется.

Для исполнения команд через SSH на STUDIO PC должен быть установлен и включён OpenSSH Server, а открытый ключ RE:Vision добавлен в `authorized_keys` именно того Windows-пользователя, под которым выполняется SSH-вход. Установщик копирует runner в `C:\\Program Files\\REVision Windows Agent\\run-ssh-command.ps1` и создаёт каталог Win SSH Agent с правом чтения для локальных пользователей.

Голосовая команда AIUI: `windows_ssh_launch`; передавай точную фразу из каталога в поле `commandPhrase`. Для обычных команд Windows Agent по-прежнему используются отдельные действия `windows_launch`, `windows_lock`, `windows_shutdown` и т. д.

Панель Win SSH Agent доступна только с loopback самого компьютера и не требует отдельного входа. Это намеренно: не открывай порт 47821 в Интернет и добавляй только доверенные команды. Содержимое каталога является исполняемым кодом от имени SSH-пользователя.

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

## Windows OpenSSH public-key setup for Win SSH Agent

1. In RE:Vision, open the STUDIO device and enable remote shutdown/SSH settings.
2. Generate an SSH key and copy the displayed **public** key. The private key remains inside Android Keystore.
3. On STUDIO, open PowerShell **as Administrator** and run the installer script from this repository:

```powershell
.\install-ssh-public-key.ps1 -PublicKey 'PASTE_THE_PUBLIC_KEY_HERE'
```

The script writes the key to `%ProgramData%\ssh\administrators_authorized_keys` and restricts its ACL to SYSTEM and the local Administrators group. It does not change the Windows password, sshd configuration, firewall, or other authentication settings.

The script accepts one OpenSSH public-key line. Never copy or send the private key.
