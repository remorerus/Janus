# Win SSH Agent

Актуальный Win SSH Agent теперь встроен как локальная веб-панель в пакет **RE:Vision Windows Agent**. Старое отдельное WinForms-приложение из этого каталога — legacy и для новой установки не требуется.

Открой на STUDIO PC:

`http://127.0.0.1:47821/ssh-manager`

Панель позволяет создавать, редактировать и удалять команды, задавать точную голосовую фразу, выбирать PowerShell или CMD и сохранять многострочные сценарии. Каталог:

`%ProgramData%\WinSshAgent\commands.json`

## Выполнение

Android-клиент RE:Vision подключается напрямую к Windows OpenSSH Server по ключу, приватная часть которого остаётся в Android Keystore. Затем запускается `run-ssh-command.ps1`, который ищет точную голосовую фразу в каталоге Win SSH Agent и исполняет соответствующий сценарий от имени SSH-пользователя Windows.

Runner включается в Windows Agent ZIP и устанавливается в:

`C:\Program Files\REVision Windows Agent\run-ssh-command.ps1`

Не запускай старый WinForms-редактор и не используй прежний каталог `%ProgramData%\REVisionSshAgent` для новых команд. Установщик Windows Agent автоматически переносит существующий каталог в новое расположение, если новый каталог ещё не создан.

Для SSH требуется Windows OpenSSH Server и публичный ключ RE:Vision в `authorized_keys` соответствующей Windows-учётной записи. Парольная SSH-аутентификация не используется.
