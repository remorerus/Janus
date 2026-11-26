using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Microsoft.Win32;
using System.Linq;
using Microsoft.AspNetCore.Http.Json;

const int Port = 47821;
var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "RevisionWindowsAgent");
Directory.CreateDirectory(dataDir);
var configPath = Path.Combine(dataDir, "agent.json");
var commandsPath = Path.Combine(dataDir, "commands.json");
var sshDataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "WinSshAgent");
Directory.CreateDirectory(sshDataDir);
var sshCommandsPath = Path.Combine(sshDataDir, "commands.json");
var config = AgentConfig.LoadOrCreate(configPath);
var builder = WebApplication.CreateBuilder(args);
builder.Host.UseWindowsService(options => options.ServiceName = "REVisionWindowsAgent");
builder.WebHost.ConfigureKestrel(options => options.ListenAnyIP(Port));
builder.Services.Configure<JsonOptions>(options => options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase);
var app = builder.Build();
var usedNonces = new ConcurrentDictionary<string, DateTimeOffset>();
var pendingSshKeyRequests = new ConcurrentDictionary<string, PendingSshKeyRequest>();
DateTimeOffset? lastRevisionHeartbeatUtc = null;
string? lastRevisionHeartbeatRemote = null;

app.MapGet("/", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    return Results.Redirect("/manager");
});

app.MapGet("/manager", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    return Results.Content(GetManagerHtml(), "text/html; charset=utf-8");
});

app.MapGet("/ssh-manager", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    return Results.Content(GetSshManagerHtml(), "text/html; charset=utf-8");
});

app.MapGet("/v1/ssh-manager/commands", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    return Results.Json(SshCommandCatalog.Load(sshCommandsPath));
});

app.MapPut("/v1/ssh-manager/commands", async (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var request = await context.Request.ReadFromJsonAsync<SshCommandCatalogRequest>();
    if (request?.Commands is null || request.Commands.Count > 100)
        return Results.BadRequest(new { error = "Некорректный список SSH-команд (максимум 100)." });

    var normalized = new List<SshCommandEntry>();
    var registeredPhrases = new HashSet<string>(StringComparer.Ordinal);
    foreach (var item in request.Commands)
    {
        var id = string.IsNullOrWhiteSpace(item.Id) ? Guid.NewGuid().ToString("N") : item.Id.Trim();
        var name = (item.Name ?? "").Trim();
        var phrase = (item.Phrase ?? "").Trim();
        var kind = (item.Kind ?? "").Trim().ToLowerInvariant();
        var target = (item.Target ?? "").Trim();
        var arguments = item.Arguments ?? "";
        var aliases = (item.Aliases ?? new List<string>())
            .Select(x => (x ?? "").Trim()).Where(x => x.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (name.Length is < 1 or > 80 || phrase.Length is < 1 or > 160 ||
            aliases.Count > 12 || aliases.Any(x => x.Length > 160) ||
            target.Length > 500 || arguments.Length > 500 ||
            kind is not ("app" or "bat" or "close" or "restart" or "media" or "hotkey" or "window" or "script"))
            return Results.BadRequest(new { error = "Проверь название, голосовую фразу, варианты (максимум 12), тип и параметры." });

        foreach (var spoken in new[] { phrase }.Concat(aliases))
        {
            var normalizedPhrase = NormalizeVoicePhrase(spoken);
            if (normalizedPhrase.Length == 0) continue;
            if (!registeredPhrases.Add(normalizedPhrase))
                return Results.BadRequest(new { error = $"Голосовая фраза или вариант уже используется другой командой: {spoken}" });
        }
        if (kind == "script" && string.IsNullOrWhiteSpace(item.Command))
            return Results.BadRequest(new { error = "Для старой команды-сценария нужен текст PowerShell/CMD." });
        if ((kind is "app" or "bat" or "restart") && target.Length == 0)
            return Results.BadRequest(new { error = "Укажи полный путь к EXE или BAT-файлу." });
        if (kind == "close" && target.Length == 0)
            return Results.BadRequest(new { error = "Укажи имя процесса или окна." });
        if (kind == "media")
        {
            var globalActions = new[] { "GLOBAL_PLAYPAUSE", "GLOBAL_NEXT", "GLOBAL_PREV", "GLOBAL_STOP", "GLOBAL_MUTE", "GLOBAL_VOLUP", "GLOBAL_VOLDWN" };
            var playerActions = new[] { "/PLAY", "/PAUSE", "/PLAYPAUSE", "/NEXT", "/PREV", "/STOP", "/MUTE", "/VOLUP", "/VOLDWN" };
            var globalAction = target.Length == 0 && globalActions.Contains(arguments, StringComparer.Ordinal);
            var playerAction = string.Equals(Path.GetExtension(target), ".exe", StringComparison.OrdinalIgnoreCase)
                && playerActions.Contains(arguments, StringComparer.Ordinal);
            if (!globalAction && !playerAction)
                return Results.BadRequest(new { error = "Выбери системное мультимедиа-действие или укажи EXE плеера и его действие." });
        }
        if (kind == "hotkey")
        {
            if (!TryNormalizeHotkey(target, out var normalizedHotkey))
                return Results.BadRequest(new { error = "Недопустимое сочетание клавиш. Пример: CTRL+SHIFT+ESC." });
            target = normalizedHotkey;
        }
        if (kind == "window" && arguments is not ("focus" or "minimize" or "maximize" or "restore" or "close" or "next" or "previous"))
            return Results.BadRequest(new { error = "Выбери действие управления окном." });
        if (kind == "window" && arguments is not ("next" or "previous") && target.Length == 0)
            return Results.BadRequest(new { error = "Укажи имя процесса или часть заголовка окна." });

        normalized.Add(new SshCommandEntry(id, name, phrase, kind, target, arguments, aliases,
            item.Shell ?? "powershell", item.Command ?? ""));
    }

    SshCommandCatalog.Save(sshCommandsPath, normalized);
    return Results.Json(new { saved = true, count = normalized.Count });
});

app.MapPost("/v1/ssh-manager/execute", async (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    ExecuteRequest? request;
    try { request = await context.Request.ReadFromJsonAsync<ExecuteRequest>(); }
    catch { return Results.BadRequest(new { error = "Некорректный запрос SSH-команды." }); }
    var phrase = (request?.Phrase ?? "").Trim();
    if (phrase.Length is < 1 or > 160)
        return Results.BadRequest(new { error = "Не указана голосовая фраза." });

    var normalizedPhrase = NormalizeVoicePhrase(phrase);
    var catalog = SshCommandCatalog.Load(sshCommandsPath);
    var matches = catalog.Where(item => new[] { item.Phrase }.Concat(item.Aliases ?? new List<string>())
        .Any(saved => string.Equals(NormalizeVoicePhrase(saved), normalizedPhrase, StringComparison.Ordinal)))
        .ToList();
    if (matches.Count == 0)
        return Results.NotFound(new { error = "В SSH Agent нет команды для фразы: " + phrase });
    if (matches.Count > 1)
        return Results.Conflict(new { error = "Фраза совпала с несколькими SSH-командами. Ничего не выполнено.",
            candidates = matches.Select(x => x.Name).ToArray() });

    var item = matches[0];
    bool executed;
    if (string.Equals(item.Kind, "script", StringComparison.OrdinalIgnoreCase))
    {
        const uint InvalidSessionId = 0xFFFFFFFF;
        var sessionId = NativeMethods.WTSGetActiveConsoleSessionId();
        if (sessionId == InvalidSessionId)
            return Results.Problem("Нет активной пользовательской сессии Windows.");
        var script = item.Command ?? "";
        if (string.Equals(item.Shell, "cmd", StringComparison.OrdinalIgnoreCase))
        {
            var encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            script = "$cmdText = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('" + encoded +
                "')); & $env:ComSpec /d /s /c $cmdText";
        }
        executed = !string.IsNullOrWhiteSpace(script) && RunPowerShellInSession(sessionId, script);
    }
    else
    {
        var command = new AgentCommand(item.Id, item.Name, item.Phrase, item.Kind, item.Target,
            item.Arguments, item.Aliases);
        executed = LaunchCatalogCommand(command);
    }

    if (!executed)
        return Results.Problem("Windows Agent не смог выполнить SSH-команду. Проверь активную пользовательскую сессию, путь и параметры.");
    return Results.Json(new { accepted = true, command = item.Name, phrase, executed = true });
});

app.MapGet("/v1/manager/commands", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    return Results.Json(CommandCatalog.Load(commandsPath));
});

app.MapPut("/v1/manager/commands", async (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var request = await context.Request.ReadFromJsonAsync<CommandCatalogRequest>();
    if (request?.Commands is null || request.Commands.Count > 100)
        return Results.BadRequest(new { error = "Некорректный список команд (максимум 100)." });

    var normalized = new List<AgentCommand>();
    var registeredVoicePhrases = new HashSet<string>(StringComparer.Ordinal);
    foreach (var item in request.Commands)
    {
        var name = (item.Name ?? "").Trim();
        var phrase = (item.Phrase ?? "").Trim();
        var kind = (item.Kind ?? "").Trim().ToLowerInvariant();
        var target = (item.Target ?? "").Trim();
        var arguments = item.Arguments ?? "";
        var aliases = (item.Aliases ?? new List<string>())
            .Select(x => (x ?? "").Trim())
            .Where(x => x.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (name.Length is < 1 or > 80 || phrase.Length is < 1 or > 160 ||
            aliases.Count > 12 || aliases.Any(x => x.Length > 160) ||
            target.Length > 500 || arguments.Length > 500 ||
            kind is not ("app" or "bat" or "close" or "restart" or "media" or "hotkey" or "window"))
            return Results.BadRequest(new { error = "Проверь название, голосовую фразу, варианты распознавания (максимум 12), тип команды и параметры." });
        var localVoicePhrases = new HashSet<string>(StringComparer.Ordinal);
        foreach (var spokenPhrase in new[] { phrase }.Concat(aliases))
        {
            var normalizedPhrase = NormalizeVoicePhrase(spokenPhrase);
            if (normalizedPhrase.Length == 0) continue;
            if (!localVoicePhrases.Add(normalizedPhrase)) continue;
            if (registeredVoicePhrases.Contains(normalizedPhrase))
                return Results.BadRequest(new { error = $"Голосовая фраза или её вариант уже используется другой командой: {spokenPhrase}" });
        }
        if ((kind is "app" or "bat" or "close" or "restart") && target.Length == 0)
            return Results.BadRequest(new { error = "Для этой команды укажи полный путь к файлу." });
        if (kind == "media")
        {
            var globalMediaActions = new[] { "GLOBAL_PLAYPAUSE", "GLOBAL_NEXT", "GLOBAL_PREV", "GLOBAL_STOP", "GLOBAL_MUTE", "GLOBAL_VOLUP", "GLOBAL_VOLDWN" };
            var legacyMediaActions = new[] { "/PLAY", "/PAUSE", "/PLAYPAUSE", "/NEXT", "/PREV", "/STOP", "/MUTE", "/VOLUP", "/VOLDWN" };
            var isGlobal = target.Length == 0 && globalMediaActions.Contains(arguments, StringComparer.Ordinal);
            var isLegacyAppCommand = string.Equals(Path.GetExtension(target), ".exe", StringComparison.OrdinalIgnoreCase) && legacyMediaActions.Contains(arguments, StringComparer.Ordinal);
            if (!isGlobal && !isLegacyAppCommand)
                return Results.BadRequest(new { error = "Выбери системное мультимедиа-действие или укажи EXE и старую команду плеера." });
        }
        if (kind == "hotkey")
        {
            if (!TryNormalizeHotkey(target, out var normalizedHotkey))
                return Results.BadRequest(new { error = "Горячая клавиша должна быть сочетанием из разрешённых клавиш, например CTRL+SHIFT+ESC." });
            target = normalizedHotkey;
            arguments = "";
        }
        if (kind == "window")
        {
            if (arguments is not ("focus" or "minimize" or "maximize" or "restore" or "close" or "next" or "previous"))
                return Results.BadRequest(new { error = "Выбери допустимое действие с окном." });
            if (arguments is "next" or "previous") target = "*";
            else if (target.Length is < 1 or > 200)
                return Results.BadRequest(new { error = "Для управления окном укажи имя процесса или часть заголовка окна." });
        }
        foreach (var spokenPhrase in new[] { phrase }.Concat(aliases))
        {
            var normalizedPhrase = NormalizeVoicePhrase(spokenPhrase);
            if (normalizedPhrase.Length > 0) registeredVoicePhrases.Add(normalizedPhrase);
        }
        normalized.Add(new AgentCommand(Guid.NewGuid().ToString("N"), name, phrase, kind, target, arguments, aliases));
    }

    CommandCatalog.Save(commandsPath, normalized);
    return Results.Json(new { saved = true, count = normalized.Count });
});


app.MapPost("/v1/manager/voice-corrections", async (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var request = await context.Request.ReadFromJsonAsync<VoiceCorrectionRequest>();
    var heard = (request?.HeardPhrase ?? "").Trim();
    var commandId = (request?.CommandId ?? "").Trim();
    if (heard.Length is < 2 or > 160 || string.IsNullOrEmpty(commandId))
        return Results.BadRequest(new { error = "Укажи распознанную фразу (2–160 символов) и команду, которую ты действительно имел в виду." });

    var catalog = CommandCatalog.Load(commandsPath);
    var target = catalog.FirstOrDefault(x => string.Equals(x.Id, commandId, StringComparison.Ordinal));
    if (target is null) return Results.NotFound(new { error = "Выбранная команда больше не существует. Обнови каталог." });

    var normalizedHeard = NormalizeVoicePhrase(heard);
    if (normalizedHeard.Length == 0)
        return Results.BadRequest(new { error = "В распознанной фразе нет подходящих слов." });

    var collision = catalog.Where(x => !string.Equals(x.Id, target.Id, StringComparison.Ordinal))
        .Any(x => GetCommandPhrases(x).Any(p => string.Equals(NormalizeVoicePhrase(p), normalizedHeard, StringComparison.Ordinal)));
    if (collision)
        return Results.Conflict(new { error = "Эта фраза уже закреплена за другой командой. Ничего не изменено." });

    var aliases = target.Aliases ?? new List<string>();
    if (GetCommandPhrases(target).Any(p => string.Equals(NormalizeVoicePhrase(p), normalizedHeard, StringComparison.Ordinal)))
        return Results.Json(new { saved = true, alreadyKnown = true, command = target.Name, aliasCount = aliases.Count });

    if (aliases.Count >= 12)
        return Results.Conflict(new { error = "У команды уже 12 вариантов. Удали ненужный вариант в редакторе команды." });

    aliases.Add(heard);
    var updated = catalog.Select(x => x.Id == target.Id ? x with { Aliases = aliases } : x).ToList();
    CommandCatalog.Save(commandsPath, updated);
    return Results.Json(new { saved = true, alreadyKnown = false, command = target.Name, aliasCount = aliases.Count });
});


app.MapPost("/v1/ssh-key-requests", async (HttpContext context) =>
{
    if (!IsPrivateLan(context)) return Results.NotFound();
    string raw;
    using (var reader = new StreamReader(context.Request.Body, Encoding.UTF8))
        raw = await reader.ReadToEndAsync();
    if (raw.Length is < 2 or > 8192) return Results.BadRequest(new { error = "Некорректный размер запроса." });
    string? deviceName = null, deviceId = null, username = null, publicKey = null;
    try
    {
        using var doc = JsonDocument.Parse(raw);
        var root = doc.RootElement;
        deviceName = root.TryGetProperty("deviceName", out var n) ? n.GetString() : null;
        deviceId = root.TryGetProperty("deviceId", out var d) ? d.GetString() : null;
        username = root.TryGetProperty("username", out var u) ? u.GetString() : null;
        publicKey = root.TryGetProperty("publicKey", out var k) ? k.GetString() : null;
    }
    catch { return Results.BadRequest(new { error = "Некорректный JSON." }); }
    deviceName = (deviceName ?? "").Trim();
    deviceId = (deviceId ?? "").Trim();
    username = (username ?? "").Trim();
    publicKey = (publicKey ?? "").Trim();
    if (deviceName.Length is < 1 or > 80 || deviceId.Length is < 1 or > 128 ||
        !Regex.IsMatch(username, @"^[A-Za-z0-9._-]{1,64}$") ||
        publicKey.Length is < 40 or > 2048 || publicKey.Contains('\r') || publicKey.Contains('\n'))
        return Results.BadRequest(new { error = "Проверь имя устройства, пользователя и открытый ключ." });
    var parts = Regex.Split(publicKey, @"\s+");
    if (parts.Length < 2 || parts[0] is not ("ecdsa-sha2-nistp256" or "ssh-ed25519" or "ssh-rsa"))
        return Results.BadRequest(new { error = "Формат открытого SSH-ключа не поддерживается." });
    byte[] keyBlob;
    try { keyBlob = Convert.FromBase64String(parts[1]); }
    catch { return Results.BadRequest(new { error = "Открытый ключ содержит неверный Base64." }); }
    if (keyBlob.Length < 32 || keyBlob.Length > 1024)
        return Results.BadRequest(new { error = "Недопустимый размер открытого ключа." });
    var remote = context.Connection.RemoteIpAddress;
    var id = Guid.NewGuid().ToString("N");
    var fingerprint = "SHA256:" + Convert.ToBase64String(SHA256.HashData(keyBlob)).TrimEnd('=');
    var item = new PendingSshKeyRequest(id, deviceName, deviceId, username, publicKey,
        fingerprint, remote?.ToString() ?? "LAN", DateTimeOffset.UtcNow);
    foreach (var old in pendingSshKeyRequests.Where(x => DateTimeOffset.UtcNow - x.Value.CreatedUtc > TimeSpan.FromMinutes(10)).ToArray())
        pendingSshKeyRequests.TryRemove(old.Key, out _);
    if (pendingSshKeyRequests.Count >= 20)
        return Results.StatusCode(StatusCodes.Status429TooManyRequests);
    pendingSshKeyRequests[id] = item;
    return Results.Json(new { requestId = id, status = "pending", message = "Ожидается подтверждение в веб-интерфейсе Windows Agent." });
});

app.MapGet("/v1/ssh-key-requests", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var now = DateTimeOffset.UtcNow;
    foreach (var old in pendingSshKeyRequests.Where(x => now - x.Value.CreatedUtc > TimeSpan.FromMinutes(10)).ToArray())
        pendingSshKeyRequests.TryRemove(old.Key, out _);
    return Results.Json(pendingSshKeyRequests.Values.OrderByDescending(x => x.CreatedUtc)
        .Select(x => new { x.Id, x.DeviceName, x.DeviceId, x.Username, x.Fingerprint, x.RemoteAddress, createdUtc = x.CreatedUtc }));
});

app.MapPost("/v1/ssh-key-requests/{id}/approve", (HttpContext context, string id) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    if (!pendingSshKeyRequests.TryRemove(id, out var item)) return Results.NotFound(new { error = "Запрос истёк или уже обработан." });
    try
    {
        var sshDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "ssh");
        Directory.CreateDirectory(sshDirectory);
        var authorizedKeys = Path.Combine(sshDirectory, "administrators_authorized_keys");
        if (!File.Exists(authorizedKeys)) File.WriteAllText(authorizedKeys, "", Encoding.ASCII);
        var existing = File.ReadAllLines(authorizedKeys, Encoding.ASCII);
        if (!existing.Contains(item.PublicKey, StringComparer.Ordinal))
            File.AppendAllText(authorizedKeys, item.PublicKey + Environment.NewLine, Encoding.ASCII);
        var acl = new ProcessStartInfo("icacls.exe") { UseShellExecute = false, CreateNoWindow = true };
        foreach (var arg in new[] { authorizedKeys, "/inheritance:r", "/grant:r", "*S-1-5-32-544:F", "*S-1-5-18:F" })
            acl.ArgumentList.Add(arg);
        using var process = Process.Start(acl);
        if (process is null || !process.WaitForExit(10000) || process.ExitCode != 0)
            return Results.Problem("Не удалось назначить ACL для authorized_keys. Проверь права службы Windows Agent.");
        return Results.Json(new { approved = true, username = item.Username, deviceName = item.DeviceName,
            message = "Ключ добавлен в administrators_authorized_keys. Перезапуск sshd обычно не требуется." });
    }
    catch (Exception ex)
    {
        return Results.Problem("Не удалось установить ключ: " + ex.Message);
    }
});

app.MapPost("/v1/ssh-key-requests/{id}/reject", (HttpContext context, string id) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var removed = pendingSshKeyRequests.TryRemove(id, out _);
    return Results.Json(new { rejected = removed });
});

app.MapGet("/v1/status", () => Results.Json(new {
    name = config.DeviceName,
    deviceId = config.DeviceId,
    version = "0.2.0-test",
    paired = config.ClientSecret is not null
}));

// Authenticated heartbeat from the paired RE:Vision client. Discovery and a saved
// pairing secret alone never mean that the client is currently connected.
app.MapPost("/v1/heartbeat", async (HttpContext context) =>
{
    var body = await new StreamReader(context.Request.Body).ReadToEndAsync();
    var timestamp = context.Request.Headers["X-Revision-Time"].ToString();
    var nonce = context.Request.Headers["X-Revision-Nonce"].ToString();
    var signature = context.Request.Headers["X-Revision-Signature"].ToString();
    var authError = config.ClientSecret is null
        ? "not_paired"
        : GetAuthenticationFailureReason(config.ClientSecret, context.Request.Method, context.Request.Path, timestamp, nonce, body, signature);
    if (authError is not null)
        return Results.Json(new { error = authError }, statusCode: StatusCodes.Status401Unauthorized);

    var now = DateTimeOffset.UtcNow;
    foreach (var item in usedNonces.Where(x => now - x.Value > TimeSpan.FromMinutes(3)).ToArray()) usedNonces.TryRemove(item.Key, out _);
    if (nonce.Length < 16 || !usedNonces.TryAdd(nonce, now)) return Results.Unauthorized();

    lastRevisionHeartbeatUtc = now;
    lastRevisionHeartbeatRemote = context.Connection.RemoteIpAddress?.ToString();
    return Results.Json(new { accepted = true, serverTimeUtc = now });
});

app.MapGet("/v1/manager/connection", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var ageSeconds = lastRevisionHeartbeatUtc is null
        ? (double?)null
        : Math.Max(0, (DateTimeOffset.UtcNow - lastRevisionHeartbeatUtc.Value).TotalSeconds);
    var active = config.ClientSecret is not null && ageSeconds is not null && ageSeconds <= 90;
    return Results.Json(new
    {
        paired = config.ClientSecret is not null,
        active,
        state = active ? "active" : config.ClientSecret is not null ? "paired-unavailable" : "not-paired",
        lastHeartbeatUtc = lastRevisionHeartbeatUtc,
        lastHeartbeatAgeSeconds = ageSeconds,
        remoteAddress = lastRevisionHeartbeatRemote,
        heartbeatTimeoutSeconds = 90
    });
});

// Read-only LAN discovery. This endpoint and UDP advertisement expose no secrets.
app.MapGet("/v1/discovery", () => Results.Json(new {
    protocol = "revision-discovery-v1",
    deviceId = config.DeviceId,
    name = config.DeviceName,
    port = Port,
    capabilities = new[] { "windows-agent", "ssh" }
}));

app.MapGet("/v1/manager/identity", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    return Results.Json(new { deviceId = config.DeviceId, name = config.DeviceName });
});

app.MapPut("/v1/manager/identity", async (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var request = await context.Request.ReadFromJsonAsync<AgentIdentityRequest>();
    var name = (request?.Name ?? "").Trim();
    if (name.Length is < 1 or > 64)
        return Results.BadRequest(new { error = "Имя устройства должно содержать от 1 до 64 символов." });
    config.DeviceName = name;
    config.Save(configPath);
    return Results.Json(new { saved = true, deviceId = config.DeviceId, name = config.DeviceName });
});

app.MapGet("/v1/manager/pairing", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    var paired = config.ClientSecret is not null;
    var codeAvailable = !paired && !string.IsNullOrWhiteSpace(config.PairingCode) && DateTimeOffset.UtcNow <= config.PairingExpiresUtc;
    return Results.Json(new
    {
        paired,
        codeAvailable,
        pairingCode = codeAvailable ? config.PairingCode : null,
        expiresUtc = codeAvailable ? config.PairingExpiresUtc : (DateTimeOffset?)null,
        message = paired ? "Устройство уже привязано. Повторная привязка не требуется." :
            codeAvailable ? "Код действует до указанного времени." : "Активного кода нет или срок его действия истёк."
    });
});

app.MapPost("/v1/manager/pairing/generate", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    if (config.ClientSecret is not null)
        return Results.Conflict(new { error = "RE:Vision уже сопряжён с этим компьютером. Новый код не требуется; сначала отвяжи устройство штатным способом." });

    config.PairingCode = Convert.ToHexString(RandomNumberGenerator.GetBytes(4));
    config.PairingExpiresUtc = DateTimeOffset.UtcNow.AddHours(24);
    config.Save(configPath);
    var codePath = Path.ChangeExtension(configPath, ".pairing-code.txt");
    File.WriteAllText(codePath,
        $"RE:Vision pairing code (valid 24h): {config.PairingCode}{Environment.NewLine}Run this file locally on this PC and keep the code private.");
    return Results.Json(new
    {
        generated = true,
        pairingCode = config.PairingCode,
        expiresUtc = config.PairingExpiresUtc,
        message = "Создан новый код. Он действует 24 часа."
    });
});

app.MapPost("/v1/pair", async (HttpContext context) =>
{
    // Read the small pairing payload explicitly instead of relying on minimal-API
    // record binding. This keeps Android JSON casing/body handling predictable and
    // returns a useful diagnostic if the incoming payload is malformed.
    string rawBody;
    using (var reader = new StreamReader(context.Request.Body, Encoding.UTF8))
        rawBody = await reader.ReadToEndAsync();

    if (rawBody.Length > 4096)
        return Results.BadRequest(new { error = "Pairing request is too large." });

    string? suppliedCode = null;
    try
    {
        using var document = JsonDocument.Parse(rawBody);
        if (document.RootElement.ValueKind == JsonValueKind.Object)
        {
            foreach (var property in document.RootElement.EnumerateObject())
            {
                if (string.Equals(property.Name, "code", StringComparison.OrdinalIgnoreCase) &&
                    property.Value.ValueKind == JsonValueKind.String)
                {
                    suppliedCode = property.Value.GetString();
                    break;
                }
            }
        }
    }
    catch (JsonException)
    {
        return Results.BadRequest(new { error = "Invalid JSON in pairing request." });
    }

    if (config.ClientSecret is not null)
        return Results.Conflict(new { error = "Already paired. Reset pairing locally to pair again." });

    suppliedCode = suppliedCode?.Trim().ToUpperInvariant();
    if (DateTimeOffset.UtcNow > config.PairingExpiresUtc ||
        string.IsNullOrEmpty(suppliedCode) ||
        !CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(suppliedCode),
            Encoding.UTF8.GetBytes(config.PairingCode)))
        return Results.Unauthorized();

    config.ClientSecret = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
    config.PairingCode = "";
    config.Save(configPath);
    return Results.Json(new PairResponse(config.ClientSecret, "HMAC-SHA256", Port));
});

app.MapPost("/v1/command/{action}", async (HttpContext context, string action) =>
{
    var body = await new StreamReader(context.Request.Body).ReadToEndAsync();
    var timestamp = context.Request.Headers["X-Revision-Time"].ToString();
    var nonce = context.Request.Headers["X-Revision-Nonce"].ToString();
    var signature = context.Request.Headers["X-Revision-Signature"].ToString();
    var authError = config.ClientSecret is null
        ? "not_paired"
        : GetAuthenticationFailureReason(config.ClientSecret, context.Request.Method, context.Request.Path, timestamp, nonce, body, signature);
    if (authError is not null)
        return Results.Json(new { error = authError }, statusCode: StatusCodes.Status401Unauthorized);

    var now = DateTimeOffset.UtcNow;
    foreach (var item in usedNonces.Where(x => now - x.Value > TimeSpan.FromMinutes(3)).ToArray()) usedNonces.TryRemove(item.Key, out _);
    if (!usedNonces.TryAdd(nonce, now)) return Results.Unauthorized();

    if (action == "lock")
    {
        var locked = LockActiveConsoleSession();
        return Results.Json(new { accepted = locked, action, error = locked ? null : "active_user_session_unavailable" });
    }
    if (action == "shutdown" || action == "restart")
    {
        var args = action == "shutdown" ? "/s /t 0" : "/r /t 0";
        var process = Process.Start(new ProcessStartInfo("shutdown.exe", args) { UseShellExecute = false, CreateNoWindow = true });
        return Results.Json(new { accepted = process is not null, action });
    }
    if (action == "cancel-shutdown")
    {
        var process = Process.Start(new ProcessStartInfo("shutdown.exe", "/a") { UseShellExecute = false, CreateNoWindow = true });
        return Results.Json(new { accepted = process is not null, action });
    }
    return Results.BadRequest(new { error = "Unsupported action" });
});

app.MapPost("/v1/command/execute", async (HttpContext context) =>
{
    var body = await new StreamReader(context.Request.Body).ReadToEndAsync();
    var timestamp = context.Request.Headers["X-Revision-Time"].ToString();
    var nonce = context.Request.Headers["X-Revision-Nonce"].ToString();
    var signature = context.Request.Headers["X-Revision-Signature"].ToString();
    var authError = config.ClientSecret is null
        ? "not_paired"
        : GetAuthenticationFailureReason(config.ClientSecret, context.Request.Method, context.Request.Path, timestamp, nonce, body, signature);
    if (authError is not null)
        return Results.Json(new { error = authError }, statusCode: StatusCodes.Status401Unauthorized);

    var now = DateTimeOffset.UtcNow;
    foreach (var item in usedNonces.Where(x => now - x.Value > TimeSpan.FromMinutes(3)).ToArray()) usedNonces.TryRemove(item.Key, out _);
    if (nonce.Length < 16 || !usedNonces.TryAdd(nonce, now)) return Results.Unauthorized();

    ExecuteRequest? request;
    try { request = JsonSerializer.Deserialize<ExecuteRequest>(body, new JsonSerializerOptions { PropertyNameCaseInsensitive = true }); }
    catch { return Results.BadRequest(new { error = "Invalid command request" }); }
    var phrase = (request?.Phrase ?? "").Trim();
    if (phrase.Length is < 1 or > 160) return Results.BadRequest(new { error = "Voice phrase is required" });
    var hypotheses = new List<string> { phrase };
    if (request?.Alternatives is not null)
    {
        foreach (var candidate in request.Alternatives)
        {
            var value = (candidate ?? "").Trim();
            if (value.Length is < 1 or > 160 || hypotheses.Count >= 5) continue;
            if (!hypotheses.Contains(value, StringComparer.OrdinalIgnoreCase)) hypotheses.Add(value);
        }
    }

    var catalog = CommandCatalog.Load(commandsPath);
    AgentCommand? command = null;
    var matchedBy = "exact";
    var matchedHeard = phrase;

    // Try every ASR hypothesis against every saved phrase and its explicit aliases.
    // A primary exact match wins; ASR alternatives are considered in ranked order.
    for (var hypothesisIndex = 0; hypothesisIndex < hypotheses.Count; hypothesisIndex++)
    {
        var heard = hypotheses[hypothesisIndex];
        var exactMatches = catalog.Where(x => GetCommandPhrases(x).Any(saved =>
            string.Equals(NormalizeVoicePhrase(saved), NormalizeVoicePhrase(heard), StringComparison.Ordinal)))
            .ToList();
        if (exactMatches.Count > 1)
            return Results.Conflict(new { error = "Несколько команд совпали с распознанной фразой; ничего не выполнено.",
                heard, candidates = exactMatches.Select(x => x.Name).ToArray() });
        if (exactMatches.Count == 1)
        {
            command = exactMatches[0];
            matchedHeard = heard;
            var canonicalMatch = string.Equals(NormalizeVoicePhrase(command.Phrase),
                NormalizeVoicePhrase(heard), StringComparison.Ordinal);
            matchedBy = hypothesisIndex > 0 ? "alternative-exact" : (canonicalMatch ? "exact" : "alias");
            break;
        }
    }

    // When no exact match exists, use a conservative distance check over all
    // hypotheses and aliases. Ambiguous results are never executed automatically.
    if (command is null)
    {
        var candidates = catalog.Select(item =>
        {
            var best = GetCommandPhrases(item)
                .SelectMany(saved => hypotheses.Select((heard, index) => new
                {
                    Heard = heard,
                    HypothesisIndex = index,
                    Score = VoicePhraseDistance(saved, heard)
                }))
                .OrderBy(x => x.Score)
                .ThenBy(x => x.HypothesisIndex)
                .FirstOrDefault();
            return new { Command = item, Best = best };
        })
        .Where(x => x.Best is not null && x.Best.Score < 1000)
        .OrderBy(x => x.Best!.Score)
        .ThenBy(x => x.Best!.HypothesisIndex)
        .ToList();

        if (candidates.Count == 0)
        {
            // AIMP is a built-in safe launch fallback: older/empty command catalogs
            // must not make the standard "open AIMP" voice command unusable.
            if (IsAimpLaunchPhrase(phrase))
            {
                var aimpPath = ResolveAimpExecutable();
                if (aimpPath is not null)
                {
                    command = new AgentCommand("builtin-aimp", "AIMP", "аимп", "app", aimpPath, "");
                    matchedBy = "built-in";
                    matchedHeard = phrase;
                }
            }

            if (command is null)
                return Results.NotFound(new { error = "No matching command in the local catalog", heard = phrase,
                    alternatives = hypotheses.Skip(1).ToArray() });
        }
        if (command is null)
        {
            var bestScore = candidates[0].Best!.Score;
            var best = candidates.Where(x => x.Best!.Score == bestScore).ToList();
            if (best.Count != 1)
                return Results.Conflict(new { error = "Several commands sound similar; no action was executed.", heard = phrase,
                    candidates = best.Select(x => x.Command.Name).ToArray() });
            command = best[0].Command;
            matchedHeard = best[0].Best!.Heard;
            matchedBy = best[0].Best!.HypothesisIndex > 0 ? "alternative-tolerant" : "tolerant";
        }

    }

    var launched = LaunchCatalogCommand(command);
    return Results.Json(new { accepted = launched, command = command.Name, kind = command.Kind,
        matchedBy, matchedHeard, alternativesConsidered = hypotheses.Count });
});

app.MapPost("/v1/unpair", (HttpContext context) =>
{
    var timestamp = context.Request.Headers["X-Revision-Time"].ToString();
    var nonce = context.Request.Headers["X-Revision-Nonce"].ToString();
    var signature = context.Request.Headers["X-Revision-Signature"].ToString();
    var authError = config.ClientSecret is null
        ? "not_paired"
        : GetAuthenticationFailureReason(config.ClientSecret, "POST", context.Request.Path, timestamp, nonce, "", signature);
    if (authError is not null)
        return Results.Json(new { error = authError }, statusCode: StatusCodes.Status401Unauthorized);
    config.ClientSecret = null;
    config.Save(configPath);
    return Results.Ok(new { paired = false });
});

// UDP broadcast discovery listener. The advertisement is intentionally public
// and contains only a stable ID, display name, port and capability list.
_ = Task.Run(async () =>
{
    using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, 47822));
    app.Lifetime.ApplicationStopping.Register(() => udp.Close());
    while (true)
    {
        try
        {
            IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
            var requestBytes = udp.Receive(ref remote);
            var requestText = Encoding.UTF8.GetString(requestBytes);
            if (!string.Equals(requestText, "REVISION_DISCOVER_V1", StringComparison.Ordinal)) continue;
            var responseBytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
            {
                protocol = "revision-discovery-v1",
                deviceId = config.DeviceId,
                name = config.DeviceName,
                port = Port,
                capabilities = new[] { "windows-agent", "ssh" }
            }));
            await udp.SendAsync(responseBytes, responseBytes.Length, remote);
        }
        catch (ObjectDisposedException) { break; }
        catch (SocketException) { if (app.Lifetime.ApplicationStopping.IsCancellationRequested) break; }
        catch (Exception ex) { Console.Error.WriteLine("LAN discovery responder: " + ex.Message); }
    }
});

app.Run($"http://0.0.0.0:{Port}");


static bool StopCatalogProcess(string target, uint sessionId)
{
    if (!string.Equals(Path.GetExtension(target), ".exe", StringComparison.OrdinalIgnoreCase)) return false;
    string processName = Path.GetFileNameWithoutExtension(target);
    bool found = false;
    foreach (var process in Process.GetProcessesByName(processName))
    {
        using (process)
        {
            try
            {
                if ((uint)process.SessionId != sessionId) continue;
                found = true;
                if (!process.CloseMainWindow() || !process.WaitForExit(3000))
                    process.Kill(true);
                process.WaitForExit(3000);
            }
            catch { }
        }
    }
    return found;
}

static bool IsAimpLaunchPhrase(string phrase)
{
    var tokens = NormalizeVoicePhrase(phrase).Split(' ', StringSplitOptions.RemoveEmptyEntries);
    if (!tokens.Contains("аимп", StringComparer.Ordinal)) return false;
    var allowed = new HashSet<string>(StringComparer.Ordinal)
    {
        "аимп", "открой", "открыть", "запусти", "запустить", "включи", "включить",
        "стартуй", "старт", "запуск", "программу", "приложение"
    };
    return tokens.All(allowed.Contains);
}

static string? ResolveAimpExecutable()
{
    var candidates = new List<string>();
    foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
    {
        try
        {
            using var baseKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view);
            using var appKey = baseKey.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\AIMP.exe");
            var registered = appKey?.GetValue(null) as string;
            if (!string.IsNullOrWhiteSpace(registered)) candidates.Add(registered.Trim().Trim('"'));
        }
        catch { }
    }

    foreach (var root in new[]
    {
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86)
    }.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct(StringComparer.OrdinalIgnoreCase))
    {
        candidates.Add(Path.Combine(root, "AIMP", "AIMP.exe"));
        candidates.Add(Path.Combine(root, "AIMP", "AIMP64.exe"));
    }

    return candidates.FirstOrDefault(path => File.Exists(path));
}

static string NormalizeVoicePhrase(string value)
{
    var rawTokens = Regex.Matches((value ?? "").ToLowerInvariant().Replace('ё', 'е'), @"[\p{L}\p{N}]+")
        .Cast<Match>()
        .Select(m => m.Value)
        .Where(t => t is not ("ревижн" or "ревижен" or "ревижин" or "ревиж" or "пожалуйста" or "агент"))
        .ToArray();

    // Explicit spelling fallback: only decode letter names after the user says
    // "по буквам"/"буквами". This avoids concatenating ordinary conversational words.
    var spellingMarker = Array.FindIndex(rawTokens, t => t is "буквам" or "буквами" or "посимвольно");
    if (spellingMarker >= 0)
    {
        var start = spellingMarker;
        if (start > 0 && rawTokens[start - 1] == "по") start--;
        var letterNames = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["а"]="а", ["бэ"]="б", ["вэ"]="в", ["гэ"]="г", ["дэ"]="д",
            ["е"]="е", ["ё"]="е", ["же"]="ж", ["зэ"]="з", ["и"]="и",
            ["й"]="й", ["и краткое"]="й", ["ка"]="к", ["эль"]="л", ["эм"]="м",
            ["эн"]="н", ["о"]="о", ["пэ"]="п", ["эр"]="р", ["эс"]="с",
            ["тэ"]="т", ["у"]="у", ["эф"]="ф", ["ха"]="х", ["цэ"]="ц",
            ["че"]="ч", ["ша"]="ш", ["ща"]="щ", ["ы"]="ы", ["э"]="э",
            ["ю"]="ю", ["я"]="я"
        };
        var spelled = rawTokens.Skip(spellingMarker + 1).ToArray();
        var decoded = new StringBuilder();
        var validSpelling = spelled.Length >= 2;
        foreach (var letter in spelled)
        {
            if (!letterNames.TryGetValue(letter, out var decodedLetter))
            {
                validSpelling = false;
                break;
            }
            decoded.Append(decodedLetter);
        }
        if (validSpelling)
            rawTokens = rawTokens.Take(start).Append(decoded.ToString()).ToArray();
    }

    var tokens = new List<string>();
    for (var i = 0; i < rawTokens.Length; i++)
    {
        var token = rawTokens[i];

        // ASR often splits a short app name into two words. Merge only known
        // dictionary forms; do not generally concatenate arbitrary speech.
        if (token == "а" && i + 1 < rawTokens.Length &&
            rawTokens[i + 1] is "имп" or "ип" or "имб")
        {
            tokens.Add("аимп");
            i++;
            continue;
        }

        // Pronunciation variants observed for common application names.
        // These are domain aliases, not global consonant substitutions.
        tokens.Add(token switch
        {
            "аимб" or "аип" or "аймп" or "эймп" or "aimp" => "аимп",
            "кром" or "хрома" or "chrome" => "хром",
            _ => token
        });
    }

    return string.Join(" ", tokens);
}

static IEnumerable<string> GetCommandPhrases(AgentCommand command)
{
    yield return command.Phrase;
    if (command.Aliases is not null)
        foreach (var alias in command.Aliases)
            if (!string.IsNullOrWhiteSpace(alias)) yield return alias;
}

static int VoicePhraseDistance(string expected, string heard)
{
    var a = NormalizeVoicePhrase(expected).Split(' ', StringSplitOptions.RemoveEmptyEntries);
    var b = NormalizeVoicePhrase(heard).Split(' ', StringSplitOptions.RemoveEmptyEntries);
    if (a.Length == 0 || a.Length != b.Length) return 1000;
    var total = 0;
    var charCount = 0;
    for (var i = 0; i < a.Length; i++)
    {
        var distance = LevenshteinDistance(a[i], b[i]);
        if (distance > 1) return 1000;
        total += distance;
        charCount += Math.Max(a[i].Length, b[i].Length);
    }
    return total <= Math.Max(1, charCount / 12) ? total : 1000;
}

static int LevenshteinDistance(string a, string b)
{
    var previous = Enumerable.Range(0, b.Length + 1).ToArray();
    var current = new int[b.Length + 1];
    for (var i = 1; i <= a.Length; i++)
    {
        current[0] = i;
        for (var j = 1; j <= b.Length; j++)
        {
            var cost = a[i - 1] == b[j - 1] ? 0 : 1;
            current[j] = Math.Min(Math.Min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
        }
        (previous, current) = (current, previous);
    }
    return previous[b.Length];
}

static bool TryNormalizeHotkey(string value, out string normalized)
{
    normalized = "";
    var raw = (value ?? "").Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
    if (raw.Length is < 1 or > 4) return false;
    var aliases = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["CONTROL"] = "CTRL", ["CTRL"] = "CTRL", ["ALT"] = "ALT",
        ["SHIFT"] = "SHIFT", ["WINDOWS"] = "WIN", ["WIN"] = "WIN", ["META"] = "WIN",
        ["ESCAPE"] = "ESC", ["ESC"] = "ESC", ["RETURN"] = "ENTER", ["ENTER"] = "ENTER",
        ["DEL"] = "DELETE", ["DELETE"] = "DELETE", ["PAGEUP"] = "PGUP",
        ["PAGEDOWN"] = "PGDN", ["SPACEBAR"] = "SPACE", ["SPACE"] = "SPACE"
    };
    var parts = raw.Select(x => aliases.TryGetValue(x, out var alias) ? alias : x.ToUpperInvariant()).ToArray();
    var modifiers = new HashSet<string>(StringComparer.Ordinal) { "CTRL", "ALT", "SHIFT", "WIN" };
    if (parts.Take(parts.Length - 1).Any(x => !modifiers.Contains(x)) ||
        parts.Take(parts.Length - 1).Distinct(StringComparer.Ordinal).Count() != parts.Length - 1)
        return false;
    var key = parts[^1];
    var supported = key.Length == 1 && ((key[0] >= 'A' && key[0] <= 'Z') || (key[0] >= '0' && key[0] <= '9'))
        || key is "ESC" or "ENTER" or "TAB" or "SPACE" or "BACKSPACE" or "DELETE" or "INSERT"
            or "HOME" or "END" or "PGUP" or "PGDN" or "UP" or "DOWN" or "LEFT" or "RIGHT"
        || (key.Length is 2 or 3 && key[0] == 'F' && int.TryParse(key[1..], out var f) && f is >= 1 and <= 24);
    if (!supported) return false;
    normalized = string.Join("+", parts);
    return true;
}

static int VirtualKey(string key)
{
    if (key.Length == 1)
    {
        var c = key[0];
        if (c is >= 'A' and <= 'Z') return c;
        if (c is >= '0' and <= '9') return c;
    }
    if (key.Length > 1 && key[0] == 'F' && int.TryParse(key[1..], out var f) && f is >= 1 and <= 24)
        return 0x70 + f - 1;
    return key switch
    {
        "CTRL" => 0x11, "ALT" => 0x12, "SHIFT" => 0x10, "WIN" => 0x5B,
        "ESC" => 0x1B, "ENTER" => 0x0D, "TAB" => 0x09, "SPACE" => 0x20,
        "BACKSPACE" => 0x08, "DELETE" => 0x2E, "INSERT" => 0x2D,
        "HOME" => 0x24, "END" => 0x23, "PGUP" => 0x21, "PGDN" => 0x22,
        "UP" => 0x26, "DOWN" => 0x28, "LEFT" => 0x25, "RIGHT" => 0x27,
        _ => 0
    };
}

static string BuildHotkeyScript(string combo)
{
    var parts = combo.Split('+');
    var modifiers = parts.Take(parts.Length - 1).Select(VirtualKey).ToArray();
    var key = VirtualKey(parts[^1]);
    var mods = string.Join(",", modifiers.Select(x => "0x" + x.ToString("X2")));
    return """
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RevisionKeySender {
  [DllImport("user32.dll", SetLastError=true)]
  public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extraInfo);
}
'@
$mods = @(__MODIFIERS__)
$key = __KEY__
foreach ($m in $mods) { [RevisionKeySender]::keybd_event([byte]$m, 0, 0, [UIntPtr]::Zero) }
[RevisionKeySender]::keybd_event([byte]$key, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[RevisionKeySender]::keybd_event([byte]$key, 0, 2, [UIntPtr]::Zero)
for ($i = $mods.Count - 1; $i -ge 0; $i--) { [RevisionKeySender]::keybd_event([byte]$mods[$i], 0, 2, [UIntPtr]::Zero) }
""".Replace("__MODIFIERS__", mods).Replace("__KEY__", "0x" + key.ToString("X2"));
}

static string BuildMediaKeyScript(string action)
{
    var key = action switch
    {
        "GLOBAL_PLAYPAUSE" => 0xB3,
        "GLOBAL_NEXT" => 0xB0,
        "GLOBAL_PREV" => 0xB1,
        "GLOBAL_STOP" => 0xB2,
        "GLOBAL_MUTE" => 0xAD,
        "GLOBAL_VOLUP" => 0xAF,
        "GLOBAL_VOLDWN" => 0xAE,
        _ => 0
    };
    if (key == 0) throw new ArgumentException("Unsupported global media action", nameof(action));
    return """
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RevisionMediaKeys {
  [DllImport("user32.dll", SetLastError=true)]
  public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extraInfo);
}
'@
[RevisionMediaKeys]::keybd_event([byte]__KEY__, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[RevisionMediaKeys]::keybd_event([byte]__KEY__, 0, 2, [UIntPtr]::Zero)
""".Replace("__KEY__", "0x" + key.ToString("X2"));
}

static string BuildWindowScript(string target, string action)
{
    var safeTarget = "'" + (target ?? "").Replace("'", "''") + "'";
    var safeAction = "'" + (action ?? "").Replace("'", "''") + "'";
    return """
$ErrorActionPreference = 'Stop'
$target = __TARGET__
$action = __ACTION__
if ($target -eq '*') {
  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RevisionWindowKeys {
  [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extraInfo);
}
'@
  [RevisionWindowKeys]::keybd_event(0x12,0,0,[UIntPtr]::Zero)
  if ($action -eq 'previous') { [RevisionWindowKeys]::keybd_event(0x10,0,0,[UIntPtr]::Zero) }
  [RevisionWindowKeys]::keybd_event(0x09,0,0,[UIntPtr]::Zero)
  Start-Sleep -Milliseconds 60
  [RevisionWindowKeys]::keybd_event(0x09,0,2,[UIntPtr]::Zero)
  if ($action -eq 'previous') { [RevisionWindowKeys]::keybd_event(0x10,0,2,[UIntPtr]::Zero) }
  [RevisionWindowKeys]::keybd_event(0x12,0,2,[UIntPtr]::Zero)
  exit 0
}
Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Text;
using System.Runtime.InteropServices;
public static class RevisionWindowApi {
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr extra);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
  [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int command);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
  public static IntPtr Find(string query) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((hWnd, lParam) => {
      if (!IsWindowVisible(hWnd)) return true;
      var title = new StringBuilder(512);
      GetWindowText(hWnd, title, title.Capacity);
      uint pid;
      GetWindowThreadProcessId(hWnd, out pid);
      string processName = "";
      try { processName = Process.GetProcessById((int)pid).ProcessName; } catch {}
      if (processName.Equals(query, StringComparison.OrdinalIgnoreCase) ||
          title.ToString().IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0) {
        found = hWnd;
        return false;
      }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
'@
if ($target -match '^(хром|гугл хром)$') { $target = 'chrome' }
elseif ($target -eq 'проводник') { $target = 'explorer' }
elseif ($target -match '^(аимп|аип)$') { $target = 'aimp' }
$hWnd = [RevisionWindowApi]::Find($target)
if ($hWnd -eq [IntPtr]::Zero) { exit 3 }
switch ($action) {
  'focus' {
    [RevisionWindowApi]::ShowWindowAsync($hWnd, 9) | Out-Null
    [RevisionWindowApi]::BringWindowToTop($hWnd) | Out-Null
    [RevisionWindowApi]::SetForegroundWindow($hWnd) | Out-Null
  }
  'restore' { [RevisionWindowApi]::ShowWindowAsync($hWnd, 9) | Out-Null; [RevisionWindowApi]::SetForegroundWindow($hWnd) | Out-Null }
  'minimize' { [RevisionWindowApi]::ShowWindowAsync($hWnd, 6) | Out-Null }
  'maximize' { [RevisionWindowApi]::ShowWindowAsync($hWnd, 3) | Out-Null; [RevisionWindowApi]::SetForegroundWindow($hWnd) | Out-Null }
  'close' { [RevisionWindowApi]::PostMessage($hWnd, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null }
}
""".Replace("__TARGET__", safeTarget).Replace("__ACTION__", safeAction);
}

static bool RunPowerShellInSession(uint sessionId, string script)
{
    if (!NativeMethods.WTSQueryUserToken(sessionId, out var userToken)) return false;
    IntPtr primaryToken = IntPtr.Zero;
    try
    {
        if (!NativeMethods.DuplicateTokenEx(userToken, 0x000F01FF, IntPtr.Zero, 2, 1, out primaryToken)) return false;
        IntPtr environment = IntPtr.Zero;
        try
        {
            NativeMethods.CreateEnvironmentBlock(out environment, primaryToken, false);
            var powershell = Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe");
            var encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            var commandLine = new StringBuilder($"powershell.exe -NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand {encoded}");
            var startup = new NativeMethods.STARTUPINFO
            {
                cb = Marshal.SizeOf<NativeMethods.STARTUPINFO>(),
                lpDesktop = @"winsta0\default"
            };
            if (!NativeMethods.CreateProcessAsUser(primaryToken, powershell, commandLine, IntPtr.Zero, IntPtr.Zero,
                    false, 0x08000400, environment, Environment.SystemDirectory, ref startup, out var process)) return false;
            NativeMethods.CloseHandle(process.hThread);
            NativeMethods.CloseHandle(process.hProcess);
            return true;
        }
        finally
        {
            if (environment != IntPtr.Zero) NativeMethods.DestroyEnvironmentBlock(environment);
        }
    }
    finally
    {
        if (primaryToken != IntPtr.Zero) NativeMethods.CloseHandle(primaryToken);
        NativeMethods.CloseHandle(userToken);
    }
}

static bool LaunchCatalogCommand(AgentCommand command)
{
    try
    {
        const uint InvalidSessionId = 0xFFFFFFFF;
        uint sessionId = NativeMethods.WTSGetActiveConsoleSessionId();
        if (sessionId == InvalidSessionId) return false;
        if (string.Equals(command.Kind, "hotkey", StringComparison.OrdinalIgnoreCase))
            return RunPowerShellInSession(sessionId, BuildHotkeyScript(command.Target));
        if (string.Equals(command.Kind, "media", StringComparison.OrdinalIgnoreCase) && string.IsNullOrWhiteSpace(command.Target))
            return RunPowerShellInSession(sessionId, BuildMediaKeyScript(command.Arguments));
        if (string.Equals(command.Kind, "window", StringComparison.OrdinalIgnoreCase))
            return RunPowerShellInSession(sessionId, BuildWindowScript(command.Target, command.Arguments));
        var target = Path.GetFullPath(command.Target);
        if (!File.Exists(target)) return false;
        if (string.Equals(command.Kind, "close", StringComparison.OrdinalIgnoreCase))
            return StopCatalogProcess(target, sessionId);
        if (string.Equals(command.Kind, "restart", StringComparison.OrdinalIgnoreCase))
            StopCatalogProcess(target, sessionId);

        string application;
        string commandLine;
        string workingDirectory = Path.GetDirectoryName(target) ?? Environment.SystemDirectory;
        if ((string.Equals(command.Kind, "app", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(command.Kind, "restart", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(command.Kind, "media", StringComparison.OrdinalIgnoreCase)) &&
            string.Equals(Path.GetExtension(target), ".exe", StringComparison.OrdinalIgnoreCase))
        {
            application = target;
            commandLine = "\"" + target + "\"" + (string.IsNullOrWhiteSpace(command.Arguments) ? "" : " " + command.Arguments);
        }
        else if (string.Equals(command.Kind, "bat", StringComparison.OrdinalIgnoreCase) &&
            string.Equals(Path.GetExtension(target), ".bat", StringComparison.OrdinalIgnoreCase))
        {
            application = Path.Combine(Environment.SystemDirectory, "cmd.exe");
            commandLine = "cmd.exe /d /c call \"" + target + "\"" +
                (string.IsNullOrWhiteSpace(command.Arguments) ? "" : " " + command.Arguments);
        }
        else return false;

        if (!NativeMethods.WTSQueryUserToken(sessionId, out var userToken)) return false;
        IntPtr primaryToken = IntPtr.Zero;
        try
        {
            if (!NativeMethods.DuplicateTokenEx(userToken, 0x000F01FF, IntPtr.Zero, 2, 1, out primaryToken)) return false;
            IntPtr environment = IntPtr.Zero;
            try
            {
                NativeMethods.CreateEnvironmentBlock(out environment, primaryToken, false);
                var startup = new NativeMethods.STARTUPINFO { cb = Marshal.SizeOf<NativeMethods.STARTUPINFO>(), lpDesktop = @"winsta0\default" };
                var args = new System.Text.StringBuilder(commandLine);
                if (!NativeMethods.CreateProcessAsUser(primaryToken, application, args, IntPtr.Zero, IntPtr.Zero,
                        false, 0x00000400, environment, workingDirectory, ref startup, out var process)) return false;
                NativeMethods.CloseHandle(process.hThread);
                NativeMethods.CloseHandle(process.hProcess);
                return true;
            }
            finally
            {
                if (environment != IntPtr.Zero) NativeMethods.DestroyEnvironmentBlock(environment);
            }
        }
        finally
        {
            if (primaryToken != IntPtr.Zero) NativeMethods.CloseHandle(primaryToken);
            NativeMethods.CloseHandle(userToken);
        }
    }
    catch { return false; }
}

static bool LockActiveConsoleSession()
{
    const uint InvalidSessionId = 0xFFFFFFFF;
    uint sessionId = NativeMethods.WTSGetActiveConsoleSessionId();
    if (sessionId == InvalidSessionId || !NativeMethods.WTSQueryUserToken(sessionId, out var userToken))
        return false;

    IntPtr primaryToken = IntPtr.Zero;
    try
    {
        const uint TokenAllAccess = 0x000F01FF;
        if (!NativeMethods.DuplicateTokenEx(userToken, TokenAllAccess, IntPtr.Zero, 2, 1, out primaryToken))
            return false;

        var startup = new NativeMethods.STARTUPINFO
        {
            cb = Marshal.SizeOf<NativeMethods.STARTUPINFO>(),
            lpDesktop = @"winsta0\default"
        };
        var commandLine = new System.Text.StringBuilder("rundll32.exe user32.dll,LockWorkStation");
        if (!NativeMethods.CreateProcessAsUser(primaryToken, Path.Combine(Environment.SystemDirectory, "rundll32.exe"),
                commandLine, IntPtr.Zero, IntPtr.Zero, false, 0x00000400,
                IntPtr.Zero, Environment.SystemDirectory, ref startup, out var process))
            return false;

        NativeMethods.CloseHandle(process.hThread);
        NativeMethods.CloseHandle(process.hProcess);
        return true;
    }
    finally
    {
        if (primaryToken != IntPtr.Zero) NativeMethods.CloseHandle(primaryToken);
        NativeMethods.CloseHandle(userToken);
    }
}

static bool IsLocal(HttpContext context) =>
    context.Connection.RemoteIpAddress is not null && IPAddress.IsLoopback(context.Connection.RemoteIpAddress);

static bool IsPrivateLan(HttpContext context)
{
    var address = context.Connection.RemoteIpAddress;
    if (address is null) return false;
    if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
    if (IPAddress.IsLoopback(address) || address.IsIPv6LinkLocal || address.IsIPv6SiteLocal) return true;
    var b = address.GetAddressBytes();
    if (b.Length == 4)
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
            (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
    return b.Length == 16 && (b[0] & 0xFE) == 0xFC;
}

static string? GetAuthenticationFailureReason(string secretBase64, string method, string path, string timestamp, string nonce, string body, string signature)
{
    if (!long.TryParse(timestamp, out var unix)) return "invalid_timestamp";
    if (Math.Abs(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - unix) > 60) return "timestamp_out_of_window";
    if (nonce.Length < 16 || nonce.Length > 128) return "invalid_nonce";
    if (signature.Length == 0) return "missing_signature";
    try
    {
        var key = Convert.FromBase64String(secretBase64);
        if (key.Length != 32) return "stored_secret_length_invalid";
        var payload = $"{method}\n{path}\n{timestamp}\n{nonce}\n{body}";
        var expected = HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(payload));
        var supplied = Convert.FromBase64String(signature);
        return CryptographicOperations.FixedTimeEquals(expected, supplied) ? null : "signature_mismatch";
    }
    catch (FormatException) { return "invalid_base64"; }
    catch { return "authentication_error"; }
}

static string GetSshManagerHtml() => """
<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>RE:Vision SSH Agent</title><style>
:root{color-scheme:dark;font:15px Segoe UI,Arial;background:#111827;color:#e5e7eb}*{box-sizing:border-box}body{margin:0;padding:28px;max-width:1240px;margin:auto}
header{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:22px}.muted{color:#9ca3af}.pill{padding:6px 10px;border-radius:20px;background:#263449;color:#a7f3d0}.manager-nav{display:flex;gap:8px;margin-top:12px;flex-wrap:wrap}.manager-nav a{color:#bfdbfe;text-decoration:none;border:1px solid #475569;border-radius:8px;padding:7px 11px}.manager-nav a:hover{background:#334155}
main{display:grid;grid-template-columns:minmax(300px,.95fr) minmax(360px,1.05fr);gap:18px}.panel{background:#1f2937;border:1px solid #374151;border-radius:14px;padding:20px}h1{font-size:23px;margin:0}h2{font-size:18px;margin:0 0 16px}
label{display:block;color:#cbd5e1;margin:12px 0 6px}input,select,textarea{width:100%;padding:11px;border-radius:8px;border:1px solid #4b5563;background:#111827;color:#f9fafb}textarea{min-height:90px;resize:vertical;font:13px Segoe UI,Arial}
button{border:0;border-radius:8px;padding:10px 14px;background:#2563eb;color:white;font-weight:600;cursor:pointer;margin:8px 6px 0 0}button.secondary{background:#4b5563}button.danger{background:#991b1b}
.full{grid-column:1/-1}.grid2{display:grid;grid-template-columns:1fr 1fr;gap:12px}.item{padding:12px;border:1px solid #374151;border-radius:9px;margin:8px 0;display:flex;justify-content:space-between;gap:12px;align-items:center}.item small{display:block;color:#9ca3af;margin-top:4px;white-space:pre-wrap;overflow-wrap:anywhere}.item button{margin:0}.actions{display:flex;gap:6px;flex-wrap:wrap}.note{font-size:13px;line-height:1.5;color:#fcd34d;margin-top:16px}.status{min-height:24px;color:#a7f3d0;margin-top:10px}
@media(max-width:820px){body{padding:14px}main{grid-template-columns:1fr}.grid2{grid-template-columns:1fr}header{align-items:flex-start;flex-direction:column}}
</style></head><body>
<header><div><h1>RE:Vision · SSH Agent</h1><div class="muted">Компьютер, привязка RE:Vision и каталог голосовых SSH-команд</div><nav class="manager-nav"><a href="/manager">Windows Agent</a><a href="/ssh-manager">SSH Agent</a></nav></div><span class="pill">OpenSSH · ключевая авторизация</span></header>
<main>
<section class="panel full"><h2>Имя компьютера в сети</h2>
<div class="grid2"><div><label>Постоянный ID</label><input id="deviceId" readonly></div><div><label>Отображаемое имя</label><input id="deviceName" maxlength="64" placeholder="Например, Студио Локал"></div></div>
<button onclick="saveIdentity()">Сохранить имя</button><button class="secondary" onclick="refreshDeviceState()">Обновить состояние</button><div class="status" id="identityStatus"></div>
<h2 style="margin-top:22px">Привязка RE:Vision</h2><div id="pairingStatus" class="muted">Проверка привязки…</div><div class="status" id="pairingMessage"></div>
</section>
<section class="panel full"><h2>Запросы на SSH-подключение</h2><div id="sshRequests" class="muted">Проверка запросов…</div><div class="status" id="sshRequestStatus"></div></section>
<section class="panel"><h2 id="formTitle">Добавить команду в каталог</h2>
<label>Название команды</label><input id="name" maxlength="80" placeholder="Например, Запуск AIMP">
<label>Голосовая фраза</label><input id="phrase" maxlength="160" placeholder="Воспроизведи AIMP">
<label>Варианты распознавания через запятую</label><input id="aliases" maxlength="2000" placeholder="Включи AIMP, Запусти плеер">
<label>Тип действия</label><select id="kind" onchange="updateFields()">
<option value="app">Запустить приложение (.exe)</option><option value="close">Закрыть приложение (.exe)</option><option value="restart">Перезапустить приложение (.exe)</option><option value="bat">Запустить BAT-файл</option><option value="media">Управление мультимедиа</option><option value="hotkey">Горячие клавиши</option><option value="window">Управление окнами и фокусом</option><option value="script">Сценарий PowerShell / CMD (совместимость)</option>
</select>
<div id="mediaOptions" style="display:none"><label>Системное мультимедиа-действие Windows</label><select id="mediaAction"><option value="GLOBAL_PLAYPAUSE">Воспроизведение / Пауза</option><option value="GLOBAL_NEXT">Следующий трек</option><option value="GLOBAL_PREV">Предыдущий трек</option><option value="GLOBAL_STOP">Остановить</option><option value="GLOBAL_MUTE">Включить / выключить системный звук</option><option value="GLOBAL_VOLUP">Увеличить системную громкость</option><option value="GLOBAL_VOLDWN">Уменьшить системную громкость</option></select><div class="note">Используются системные мультимедиа-клавиши Windows.</div></div>
<div id="hotkeyOptions" style="display:none"><label>Сочетание клавиш</label><input id="hotkeyCombo" maxlength="80" placeholder="CTRL+SHIFT+ESC"></div>
<div id="windowOptions" style="display:none"><label>Действие с окном</label><select id="windowAction"><option value="focus">Переключиться на окно</option><option value="minimize">Свернуть</option><option value="maximize">Развернуть на весь экран</option><option value="restore">Восстановить окно</option><option value="close">Закрыть окно</option><option value="next">Следующее окно (Alt+Tab)</option><option value="previous">Предыдущее окно (Alt+Shift+Tab)</option></select></div>
<label id="targetLabel">Полный путь к файлу / имя процесса или окна</label><input id="target" maxlength="500" placeholder="C:\Program Files\AIMP\AIMP.exe">
<label id="argumentsLabel">Аргументы запуска (необязательно)</label><input id="arguments" maxlength="500" placeholder="--fullscreen">
<div id="scriptFields" style="display:none"><label>Оболочка сценария</label><select id="scriptShell"><option value="powershell">PowerShell</option><option value="cmd">CMD</option></select><label>Текст сценария</label><textarea id="scriptCommand" maxlength="12000" spellcheck="false"></textarea></div><div class="note" id="kindHint">Для запуска укажи полный путь к EXE.</div>
<div class="actions"><button onclick="saveEditor()">Сохранить команду</button><button class="secondary" onclick="clearEditor()">Новая команда</button></div><div class="status" id="status"></div>
<div class="note">Команды выполняются по SSH от имени указанного Windows-пользователя. Добавляй только доверенные приложения и действия.</div>
</section>
<section class="panel"><h2>Сохранённые SSH-команды</h2><div id="commands" class="muted">Загрузка…</div><button onclick="saveAll()">Сохранить каталог</button><button class="secondary" onclick="loadCommands()">Обновить список</button><div class="muted" style="margin-top:14px;font-size:12px">Файл: %ProgramData%\\WinSshAgent\\commands.json</div></section>
</main>
<script>
let commands=[],editingId=null;
function el(id){return document.getElementById(id)}
function msg(t){el('status').textContent=t}
function splitAliases(s){return [...new Set((s||'').split(/[,;\\n]+/).map(x=>x.trim()).filter(Boolean))]}
function updateFields(){
 const k=el('kind').value,media=k==='media',hotkey=k==='hotkey',win=k==='window',script=k==='script';
 el('scriptFields').style.display=script?'block':'none';el('mediaOptions').style.display=media?'block':'none';el('hotkeyOptions').style.display=hotkey?'block':'none';el('windowOptions').style.display=win?'block':'none';
 el('targetLabel').textContent=hotkey?'':win?'Имя процесса или часть заголовка окна':'Полный путь к файлу';
 el('targetLabel').style.display=(media||hotkey)?'none':'block';el('target').style.display=(media||hotkey)?'none':'block';
 el('argumentsLabel').style.display=(media||hotkey||win||script)?'none':'block';el('arguments').style.display=(media||hotkey||win||script)?'none':'block';
 const hints={app:'Укажи полный путь к EXE.',close:'Укажи полный путь к EXE, процесс которого нужно закрыть.',restart:'Укажи полный путь к EXE.',bat:'Укажи полный путь к BAT-файлу.',media:'Выбери системное мультимедиа-действие.',hotkey:'Примеры: CTRL+C, ALT+F4, WIN+D, CTRL+SHIFT+ESC.',window:'Укажи процесс/часть заголовка и выбери действие с окном.',script:'Старый формат сценария сохранён для совместимости.'};
 el('kindHint').textContent=hints[k]||'';
}
function clearEditor(){editingId=null;['name','phrase','aliases','target','arguments','scriptCommand','hotkeyCombo'].forEach(x=>el(x).value='');el('scriptShell').value='powershell';el('kind').value='app';el('mediaAction').value='GLOBAL_PLAYPAUSE';el('windowAction').value='focus';el('formTitle').textContent='Добавить команду в каталог';updateFields()}
function saveEditor(){
 const kind=el('kind').value,winAction=el('windowAction').value;
 const target=kind==='hotkey'?el('hotkeyCombo').value.trim():(kind==='window'&&['next','previous'].includes(winAction)?'*':(kind==='media'?'':el('target').value.trim()));
 const args=kind==='media'?el('mediaAction').value:(kind==='window'?winAction:el('arguments').value.trim());
 const c={id:editingId||crypto.randomUUID().replaceAll('-',''),name:el('name').value.trim(),phrase:el('phrase').value.trim(),aliases:splitAliases(el('aliases').value),kind,target,arguments:args,shell:el('scriptShell').value,command:el('scriptCommand').value};
 if(!c.name||!c.phrase){msg('Заполни название и основную голосовую фразу.');return}
 if(c.aliases.length>12){msg('Можно добавить не более 12 вариантов произнесения.');return}
 const all=new Set();for(const x of commands){if(x.id===editingId)continue;for(const p of [x.phrase,...(x.aliases||[])])all.add(p.trim().toLocaleLowerCase('ru'))}
 for(const p of [c.phrase,...c.aliases])if(all.has(p.toLocaleLowerCase('ru'))){msg('Эта фраза уже назначена другой команде: '+p);return}
 if(['app','bat','restart','close'].includes(kind)&&!target){msg('Укажи полный путь к файлу или EXE.');return}
 if(kind==='media'&&!args){msg('Выбери действие мультимедиа.');return}
 if(kind==='hotkey'&&!target){msg('Укажи сочетание клавиш.');return}
 if(kind==='window'&&!args){msg('Выбери действие с окном.');return}
 if(kind==='window'&&!['next','previous'].includes(args)&&!target){msg('Укажи имя процесса или часть заголовка окна.');return}
 if(kind==='script'&&!c.command.trim()){msg('Вставь текст сценария PowerShell или CMD.');return}
 if(editingId){const i=commands.findIndex(x=>x.id===editingId);if(i>=0)commands[i]=c}else commands.push(c);
 render();clearEditor();msg('Изменение внесено. Нажми «Сохранить каталог».')
}
function editCommand(id){const c=commands.find(x=>x.id===id);if(!c)return;editingId=id;el('name').value=c.name;el('phrase').value=c.phrase;el('aliases').value=(c.aliases||[]).join(', ');el('kind').value=c.kind||'script';el('target').value=c.target||'';el('arguments').value=c.arguments||'';el('scriptShell').value=c.shell||'powershell';el('scriptCommand').value=c.command||'';el('hotkeyCombo').value=c.kind==='hotkey'?(c.target||''):'';el('mediaAction').value=c.kind==='media'?(c.arguments||'GLOBAL_PLAYPAUSE'):'GLOBAL_PLAYPAUSE';el('windowAction').value=c.kind==='window'?(c.arguments||'focus'):'focus';updateFields();el('formTitle').textContent='Изменить команду';window.scrollTo({top:0,behavior:'smooth'})}
function removeCommand(id){commands=commands.filter(x=>x.id!==id);if(editingId===id)clearEditor();render();msg('Команда удалена из редактора. Нажми «Сохранить каталог».')}
function render(){const root=el('commands');root.replaceChildren();if(!commands.length){root.textContent='Пока команд нет. Добавь первую слева.';return}
 commands.forEach(c=>{const d=document.createElement('div');d.className='item';const info=document.createElement('div');const b=document.createElement('b');b.textContent=c.name;const s=document.createElement('small');s.textContent=c.phrase+(c.aliases?.length?' · '+c.aliases.join(', '):'')+' · '+c.kind+' · '+c.target;info.append(b,s);const actions=document.createElement('div');actions.className='actions';const edit=document.createElement('button');edit.className='secondary';edit.textContent='Изменить';edit.onclick=()=>editCommand(c.id);const del=document.createElement('button');del.className='danger';del.textContent='Удалить';del.onclick=()=>removeCommand(c.id);actions.append(edit,del);d.append(info,actions);root.append(d)})}
async function loadCommands(){try{const r=await fetch('/v1/ssh-manager/commands',{cache:'no-store'});if(!r.ok)throw Error('HTTP '+r.status);commands=(await r.json()).map(x=>({...x,kind:x.kind||'script',target:x.target||'',arguments:x.arguments||'',aliases:x.aliases||[],shell:x.shell||'powershell',command:x.command||''}));render();msg('Каталог загружен: '+commands.length)}catch(e){msg('Не удалось загрузить SSH-каталог: '+e.message)}}
async function saveAll(){try{const r=await fetch('/v1/ssh-manager/commands',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({commands})});const d=await r.json();if(!r.ok)throw Error(d.error||'Ошибка сохранения');msg('Сохранено. Команд: '+d.count)}catch(e){msg(e.message)}}
async function loadIdentity(){try{const r=await fetch('/v1/manager/identity',{cache:'no-store'});const d=await r.json();if(!r.ok)throw Error(d.error||'HTTP '+r.status);el('deviceId').value=d.deviceId;el('deviceName').value=d.name;el('identityStatus').textContent='Имя и постоянный ID загружены.'}catch(e){el('identityStatus').textContent='Ошибка: '+e.message}}
async function saveIdentity(){try{const r=await fetch('/v1/manager/identity',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:el('deviceName').value.trim()})});const d=await r.json();if(!r.ok)throw Error(d.error||'HTTP '+r.status);el('deviceId').value=d.deviceId;el('identityStatus').textContent='Отображаемое имя сохранено: '+d.name}catch(e){el('identityStatus').textContent='Ошибка: '+e.message}}
async function refreshDeviceState(){await loadIdentity();await loadPairing()}
async function loadConnectionState(){
 try{
  const r=await fetch('/v1/manager/connection',{cache:'no-store'});
  if(!r.ok)throw Error('HTTP '+r.status);
  const d=await r.json();
  const state=el('connectionState'),details=el('connectionDetails');
  if(d.active){state.textContent='Активно · очки на связи';state.style.color='#86efac';}
  else if(d.paired){state.textContent='Сопряжено, но сейчас недоступно';state.style.color='#fcd34d';}
  else{state.textContent='Не сопряжено';state.style.color='#fca5a5';}
  details.textContent=d.lastHeartbeatUtc?'Последний heartbeat: '+new Date(d.lastHeartbeatUtc).toLocaleString()+' · '+Math.round(d.lastHeartbeatAgeSeconds)+' сек. назад'+(d.remoteAddress?' · IP: '+d.remoteAddress:''):'Heartbeat ещё не получен. Сохранённая привязка сама по себе не считается активным соединением.';
 }catch(e){el('connectionState').textContent='Не удалось проверить соединение: '+e.message;}
}
async function loadPairing(){try{const r=await fetch('/v1/manager/pairing',{cache:'no-store'});const d=await r.json();if(!r.ok)throw Error(d.error||'HTTP '+r.status);el('pairingStatus').textContent=d.paired?'Устройство уже привязано. Повторная привязка не требуется.':d.message;el('pairingMessage').textContent=d.paired?'Привязка активна.':(d.codeAvailable?'Код доступен для первичной привязки.':'Активного кода нет или он истёк.')}catch(e){el('pairingStatus').textContent='Не удалось проверить привязку: '+e.message}}
async function loadSshRequests(){try{const r=await fetch('/v1/ssh-key-requests',{cache:'no-store'});if(!r.ok)throw Error('HTTP '+r.status);const items=await r.json(),root=el('sshRequests');root.replaceChildren();if(!items.length){root.textContent='Новых запросов нет.';return}items.forEach(x=>{const card=document.createElement('div');card.className='item';const info=document.createElement('div');const title=document.createElement('b');title.textContent=x.deviceName+' просит SSH-доступ';const details=document.createElement('small');details.textContent='Пользователь Windows: '+x.username+' · IP: '+x.remoteAddress+'\\nОтпечаток: '+x.fingerprint;info.append(title,details);const actions=document.createElement('div');actions.className='actions';const yes=document.createElement('button');yes.textContent='Разрешить';yes.onclick=()=>handleSshRequest(x.id,'approve');const no=document.createElement('button');no.className='danger';no.textContent='Отклонить';no.onclick=()=>handleSshRequest(x.id,'reject');actions.append(yes,no);card.append(info,actions);root.append(card)})}catch(e){el('sshRequestStatus').textContent='Не удалось проверить запросы: '+e.message}}
async function handleSshRequest(id,action){try{const r=await fetch('/v1/ssh-key-requests/'+encodeURIComponent(id)+'/'+action,{method:'POST'});const d=await r.json();if(!r.ok)throw Error(d.error||'HTTP '+r.status);el('sshRequestStatus').textContent=action==='approve'?'Подключение разрешено.':'Запрос отклонён.';await loadSshRequests()}catch(e){el('sshRequestStatus').textContent=e.message}}
updateFields();loadIdentity();loadPairing();loadCommands();loadSshRequests();setInterval(loadSshRequests,2500);
</script></body></html>
""";


static string GetManagerHtml() => """
<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>RE:Vision Windows Agent</title><style>
:root{color-scheme:dark;font:15px Segoe UI,Arial;background:#111827;color:#e5e7eb}*{box-sizing:border-box}body{margin:0;padding:28px;max-width:1100px;margin:auto}
header{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:22px}.muted{color:#9ca3af}.pill{padding:6px 10px;border-radius:20px;background:#263449;color:#a7f3d0}.manager-nav{display:flex;gap:8px;margin-top:12px;flex-wrap:wrap}.manager-nav a{color:#bfdbfe;text-decoration:none;border:1px solid #475569;border-radius:8px;padding:7px 11px}.manager-nav a:hover{background:#334155}
main{display:grid;grid-template-columns:1fr 1.15fr;gap:18px}.panel{background:#1f2937;border:1px solid #374151;border-radius:14px;padding:20px}h1{font-size:23px;margin:0}h2{font-size:18px;margin:0 0 16px}
label{display:block;color:#cbd5e1;margin:12px 0 6px}input,select{width:100%;padding:11px;border-radius:8px;border:1px solid #4b5563;background:#111827;color:#f9fafb}
button{border:0;border-radius:8px;padding:10px 14px;background:#2563eb;color:white;font-weight:600;cursor:pointer;margin:8px 6px 0 0}button.secondary{background:#4b5563}button.danger{background:#991b1b}
.item{padding:12px;border:1px solid #374151;border-radius:9px;margin:8px 0;display:flex;justify-content:space-between;gap:12px;align-items:center}.item small{display:block;color:#9ca3af;margin-top:4px}.item button{margin:0}.note{font-size:13px;line-height:1.5;color:#fcd34d;margin-top:16px}.status{min-height:24px;color:#a7f3d0;margin-top:10px}
@media(max-width:760px){body{padding:14px}main{grid-template-columns:1fr}header{align-items:flex-start;flex-direction:column}}
</style></head><body>
<header><div><h1>RE:Vision · Центр управления</h1><div class="muted">Единая панель подключений и команд компьютера</div><nav class="manager-nav"><a href="/manager">Windows Agent</a><a href="/ssh-manager">SSH Agent</a></nav></div><span class="pill">Локальная панель</span></header>
<main><section class="panel pairing"><h2>Имя компьютера в сети</h2><div class="muted">Постоянный ID: <span id="deviceId">загрузка…</span></div><label>Отображаемое имя</label><input id="deviceName" maxlength="64" placeholder="Например, STUDIO PC"><button type="button" onclick="saveIdentity()">Сохранить имя</button><div class="status" id="identityStatus"></div></section><section class="panel pairing"><h2>Состояние соединения с очками</h2><div id="connectionState" class="status">Проверка соединения…</div><div id="connectionDetails" class="muted">Ожидание подтверждённого heartbeat от RE:Vision.</div><button type="button" class="secondary" onclick="loadConnectionState()">Обновить состояние</button></section><section class="panel pairing"><h2>Привязка RE:Vision</h2>
<div id="pairingState" class="status">Проверка состояния привязки…</div>
<div id="pairingCodeWrap" style="display:none"><label>Текущий код привязки</label><div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><strong id="pairingCode" style="font-size:24px;letter-spacing:3px"></strong><button type="button" class="secondary" onclick="copyPairingCode()">Копировать</button></div><div id="pairingExpiry" class="muted"></div><div class="note">Код предназначен только для привязки RE:Vision. Не отправляй его посторонним.</div></div>
<button type="button" onclick="generatePairingCode()">Сгенерировать новый код</button><button type="button" class="secondary" onclick="loadPairing()">Обновить состояние</button>
</section><section class="panel"><h2>Добавить команду в каталог</h2>
<label>Название</label><input id="name" placeholder="Например, Запуск Mixxx">
<label>Голосовая фраза</label><input id="phrase" placeholder="Запусти Mixxx">
<label>Варианты распознавания через запятую</label><input id="aliases" placeholder="Например: запусти аимб, открой а имп, запусти AIMP">
<label>Тип</label><select id="kind" onchange="updateKindFields()"><option value="app">Запустить приложение (.exe)</option><option value="close">Закрыть приложение (.exe)</option><option value="restart">Перезапустить приложение (.exe)</option><option value="bat">Запустить BAT-файл</option><option value="media">Управление мультимедиа</option><option value="hotkey">Горячие клавиши</option><option value="window">Управление окнами и фокусом</option></select>
<div id="mediaOptions" style="display:none"><label>Системное мультимедиа-действие Windows</label><select id="mediaAction"><option value="GLOBAL_PLAYPAUSE">Воспроизведение / Пауза</option><option value="GLOBAL_NEXT">Следующий трек</option><option value="GLOBAL_PREV">Предыдущий трек</option><option value="GLOBAL_STOP">Остановить</option><option value="GLOBAL_MUTE">Включить / выключить системный звук</option><option value="GLOBAL_VOLUP">Увеличить системную громкость</option><option value="GLOBAL_VOLDWN">Уменьшить системную громкость</option></select><div class="note">Команды отправляются через системные мультимедиа-клавиши Windows. Работают с плеерами и браузерами, которые поддерживают системное управление мультимедиа.</div></div>
<div id="hotkeyOptions" style="display:none"><label>Сочетание клавиш</label><input id="hotkeyCombo" placeholder="CTRL+ALT+T"></div>
<div id="windowOptions" style="display:none"><label>Действие с окном</label><select id="windowAction" onchange="updateKindFields()"><option value="focus">Переключиться на окно</option><option value="minimize">Свернуть</option><option value="maximize">Развернуть на весь экран</option><option value="restore">Восстановить окно</option><option value="close">Закрыть окно</option><option value="next">Следующее окно (Alt+Tab)</option><option value="previous">Предыдущее окно (Alt+Shift+Tab)</option></select></div>
<label id="targetLabel">Полный путь к файлу / имя процесса или окна</label><input id="target" placeholder="C:\Program Files\...\app.exe">
<label id="argsLabel">Аргументы запуска (необязательно)</label><input id="args" placeholder="--fullscreen">
<button id="addCommandButton" onclick="addCommand()">Добавить в список</button><button class="secondary" onclick="clearForm()">Очистить</button>
<div class="status" id="status"></div>
<div class="note">Команды запускаются только из этого локального каталога и только в активной пользовательской сессии Windows. Добавляй только проверенные EXE и BAT-файлы.</div>
</section><section class="panel"><h2>Мои команды</h2><div id="commands" class="muted">Загрузка…</div><button onclick="saveAll()">Сохранить изменения</button><button class="secondary" onclick="loadCommands()">Обновить список</button></section><section class="panel"><h2>Обучить распознавание</h2><div class="muted">Если Ревижн услышал фразу неправильно, выбери правильную команду. После подтверждения ошибочный вариант сохранится как её дополнительный голосовой псевдоним.</div><label>Какую фразу услышал агент</label><input id="correctionPhrase" maxlength="160" placeholder="Например: открой а имп"><label>Какую команду ты имел в виду</label><select id="correctionCommand"><option value="">Сначала загрузи каталог команд</option></select><button type="button" onclick="saveCorrection()">Запомнить исправление</button><div class="status" id="correctionStatus"></div><div class="note">Исправление сохраняется только после твоего подтверждения. Оно не запускает команду и не меняет основную голосовую фразу.</div></section></main>
<script>
let commands=[],editingIndex=-1;
function el(id){return document.getElementById(id)}
function msg(t){el('status').textContent=t}
async function loadIdentity(){
 try{
  const r=await fetch('/v1/manager/identity',{cache:'no-store'});
  if(!r.ok)throw Error('HTTP '+r.status);
  const d=await r.json();
  el('deviceId').textContent=d.deviceId;
  el('deviceName').value=d.name||'';
 }catch(e){el('identityStatus').textContent='Не удалось загрузить имя устройства: '+e.message}
}
async function saveIdentity(){
 try{
  const r=await fetch('/v1/manager/identity',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:el('deviceName').value})});
  const d=await r.json();
  if(!r.ok)throw Error(d.error||'HTTP '+r.status);
  el('deviceId').textContent=d.deviceId;
  el('identityStatus').textContent='Сетевое имя сохранено: '+d.name;
 }catch(e){el('identityStatus').textContent='Не удалось сохранить имя: '+e.message}
}
async function loadPairing(){
 try{
  const r=await fetch('/v1/manager/pairing',{cache:'no-store'});
  if(!r.ok)throw Error('HTTP '+r.status);
  const p=await r.json();
  el('pairingCodeWrap').style.display=p.codeAvailable?'block':'none';
  el('pairingCode').textContent=p.pairingCode||'';
  el('pairingExpiry').textContent=p.expiresUtc?'Действует до: '+new Date(p.expiresUtc).toLocaleString():'';
  el('pairingState').textContent=p.message;
  const generateButton=document.querySelector('[onclick="generatePairingCode()"]');
  if(generateButton)generateButton.style.display=p.paired?'none':'inline-block';
 }catch(e){el('pairingState').textContent='Не удалось проверить привязку: '+e.message}
}
async function generatePairingCode(){
 const status=el('pairingState');
 if(!confirm('Создать новый код сопряжения? Предыдущий незавершённый код перестанет действовать.'))return;
 try{
  const r=await fetch('/v1/manager/pairing/generate',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});
  const d=await r.json();
  if(!r.ok)throw Error(d.error||'HTTP '+r.status);
  await loadPairing();
  status.textContent=d.message;
 }catch(e){status.textContent='Не удалось создать код: '+e.message}
}
function copyPairingCode(){const code=el('pairingCode').textContent;if(code)navigator.clipboard.writeText(code)}
function updateKindFields(){
 const kind=el('kind').value, media=kind==='media', hotkey=kind==='hotkey', win=kind==='window';
 const winGlobal=win&&['next','previous'].includes(el('windowAction').value);
 el('mediaOptions').style.display=media?'block':'none';
 el('hotkeyOptions').style.display=hotkey?'block':'none';
 el('windowOptions').style.display=win?'block':'none';
 el('targetLabel').style.display=(hotkey||winGlobal||media)?'none':'block';
 el('target').style.display=(hotkey||winGlobal||media)?'none':'block';
 el('targetLabel').textContent=win?'Имя процесса или часть заголовка окна':'Полный путь к файлу';
 el('target').placeholder=win?'chrome, explorer, aimp или часть заголовка':'C:\\Program Files\\...\\app.exe';
 el('argsLabel').style.display=(media||hotkey||win)?'none':'block';
 el('args').style.display=(media||hotkey||win)?'none':'block';
}
function clearForm(){editingIndex=-1;['name','phrase','aliases','target','args','hotkeyCombo'].forEach(x=>el(x).value='');el('kind').value='app';el('mediaAction').value='GLOBAL_PLAYPAUSE';el('windowAction').value='focus';el('addCommandButton').textContent='Добавить в список';updateKindFields()}
function addCommand(){
 const kind=el('kind').value, action=el('windowAction').value;
 const target=kind==='hotkey'?el('hotkeyCombo').value.trim():(kind==='window'&&['next','previous'].includes(action)?'*':(kind==='media'?'':el('target').value.trim()));
 const aliases=el('aliases').value.split(/[,;\n]+/).map(x=>x.trim()).filter(Boolean);
 const c={name:el('name').value.trim(),phrase:el('phrase').value.trim(),aliases,kind,target,arguments:kind==='media'?el('mediaAction').value:(kind==='window'?action:(kind==='hotkey'?'':el('args').value.trim()))};
 if(aliases.length>12){msg('Можно добавить не более 12 вариантов распознавания.');return}
 if(!c.name||!c.phrase||(!c.target&&kind!=='window'&&kind!=='media')){msg('Заполни название, голосовую фразу и параметры команды.');return}
 if(kind==='window'&&!['next','previous'].includes(action)&&!c.target){msg('Укажи имя процесса или часть заголовка окна.');return}
 if(commands.some((x,i)=>i!==editingIndex&&x.phrase.toLowerCase()===c.phrase.toLowerCase())){msg('Такая голосовая фраза уже есть.');return}
 if(editingIndex>=0){commands[editingIndex]=c}else{commands.push(c)}render();clearForm();msg('Изменение внесено. Нажми «Сохранить изменения».')
}
function editCommand(i){const c=commands[i];if(!c)return;editingIndex=i;el('name').value=c.name||'';el('phrase').value=c.phrase||'';el('aliases').value=(c.aliases||[]).join(', ');el('kind').value=c.kind||'app';el('target').value=c.target||'';el('args').value=c.arguments||'';if(c.kind==='hotkey')el('hotkeyCombo').value=c.target||'';if(c.kind==='media')el('mediaAction').value=c.arguments||'GLOBAL_PLAYPAUSE';if(c.kind==='window')el('windowAction').value=c.arguments||'focus';updateKindFields();el('addCommandButton').textContent='Сохранить команду в списке';msg('Редактирование команды. Нажми «Сохранить изменения» после сохранения каталога.');window.scrollTo({top:0,behavior:'smooth'})}
function removeCommand(i){commands.splice(i,1);if(editingIndex===i)clearForm();else if(editingIndex>i)editingIndex--;render();msg('Команда удалена из списка. Сохрани изменения.')}
function render(){const select=el('correctionCommand');if(select){const chosen=select.value;select.innerHTML='';const empty=document.createElement('option');empty.value='';empty.textContent='Выбери команду';select.append(empty);commands.forEach(c=>{const o=document.createElement('option');o.value=c.id;o.textContent=c.name+' — '+c.phrase;select.append(o)});if(commands.some(c=>c.id===chosen))select.value=chosen}const root=el('commands');if(!commands.length){root.textContent='Пока команд нет. Добавь первую слева.';return}
root.innerHTML='';commands.forEach((c,i)=>{const d=document.createElement('div');d.className='item';const info=document.createElement('div');const b=document.createElement('b');b.textContent=c.name;const s=document.createElement('small');const kindLabel={media:'Мультимедиа',hotkey:'Горячие клавиши',window:'Управление окнами',bat:'BAT',close:'Закрытие EXE',restart:'Перезапуск EXE',app:'Запуск EXE'}[c.kind]||c.kind;s.textContent=c.phrase+' · '+kindLabel+' · '+(c.kind==='hotkey'?c.target:(c.kind==='window'?(c.target==='*'?'':c.target+' · ')+c.arguments:c.target+(c.kind==='media'?' · '+c.arguments:'')))+((c.aliases&&c.aliases.length)?' · Варианты: '+c.aliases.join(', '):'');info.append(b,s);const actions=document.createElement('div');actions.className='actions';const edit=document.createElement('button');edit.className='secondary';edit.textContent='Изменить';edit.onclick=()=>editCommand(i);const btn=document.createElement('button');btn.className='danger';btn.textContent='Удалить';btn.onclick=()=>removeCommand(i);actions.append(edit,btn);d.append(info,actions);root.append(d)})}
async function loadCommands(){try{const r=await fetch('/v1/manager/commands');if(!r.ok)throw Error();commands=await r.json();render();msg('Каталог загружен.')}catch(e){msg('Не удалось загрузить каталог. Открой панель локально на STUDIO PC.')}}
async function saveAll(){try{const r=await fetch('/v1/manager/commands',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({commands})});const data=await r.json();if(!r.ok)throw Error(data.error||'Ошибка сохранения');msg('Сохранено. Команд в каталоге: '+data.count)}catch(e){msg(e.message)}}
async function saveCorrection(){const status=el('correctionStatus');try{const heardPhrase=el('correctionPhrase').value.trim(),commandId=el('correctionCommand').value;if(!heardPhrase||!commandId)throw Error('Заполни фразу и выбери правильную команду.');const r=await fetch('/v1/manager/voice-corrections',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({heardPhrase,commandId})});const data=await r.json();if(!r.ok)throw Error(data.error||'Не удалось сохранить исправление');el('correctionPhrase').value='';await loadCommands();status.textContent=data.alreadyKnown?'Этот вариант уже известен.':'Запомнил вариант для команды «'+data.command+'». Теперь его можно произнести.'}catch(e){status.textContent=e.message}}
loadCommands();
loadIdentity();
loadPairing();
loadConnectionState();
setInterval(loadConnectionState,5000);
</script></body></html>
""";


static class NativeMethods
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO
    {
        public int cb;
        public string? lpReserved;
        public string? lpDesktop;
        public string? lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION
    {
        public IntPtr hProcess, hThread;
        public uint dwProcessId, dwThreadId;
    }

    [DllImport("kernel32.dll")]
    public static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("Wtsapi32.dll", SetLastError = true)]
    public static extern bool WTSQueryUserToken(uint sessionId, out IntPtr token);

    [DllImport("advapi32.dll", SetLastError = true)]
    public static extern bool DuplicateTokenEx(IntPtr existingToken, uint desiredAccess, IntPtr tokenAttributes,
        int impersonationLevel, int tokenType, out IntPtr newToken);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CreateProcessAsUser(IntPtr token, string applicationName,
        System.Text.StringBuilder commandLine, IntPtr processAttributes, IntPtr threadAttributes,
        bool inheritHandles, uint creationFlags, IntPtr environment, string currentDirectory,
        ref STARTUPINFO startupInfo, out PROCESS_INFORMATION processInformation);

    [DllImport("userenv.dll", SetLastError = true)]
    public static extern bool CreateEnvironmentBlock(out IntPtr environment, IntPtr token, bool inherit);

    [DllImport("userenv.dll", SetLastError = true)]
    public static extern bool DestroyEnvironmentBlock(IntPtr environment);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr handle);
}

sealed record SshCommandCatalogRequest(List<SshCommandInput>? Commands);
sealed record SshCommandInput(string? Id, string? Name, string? Phrase, string? Kind, string? Target,
    string? Arguments, List<string>? Aliases = null, string? Shell = null, string? Command = null);
sealed record SshCommandEntry(string Id, string Name, string Phrase, string Kind, string Target,
    string Arguments, List<string>? Aliases = null, string? Shell = null, string? Command = null);

sealed class SshCommandCatalog
{
    public static List<SshCommandEntry> Load(string path)
    {
        if (!File.Exists(path)) return new();
        try { return JsonSerializer.Deserialize<List<SshCommandEntry>>(File.ReadAllText(path)) ?? new(); }
        catch { return new(); }
    }
    public static void Save(string path, List<SshCommandEntry> commands)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(commands, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, path, true);
    }
}

sealed record ExecuteRequest(string? Phrase, List<string>? Alternatives = null);
sealed record PairRequest(string Code);
sealed record PairResponse(string ClientSecret, string Authentication, int Port);
sealed record CommandCatalogRequest(List<AgentCommandInput>? Commands);
sealed record AgentCommandInput(string? Name, string? Phrase, string? Kind, string? Target, string? Arguments,
    List<string>? Aliases = null);
sealed record AgentCommand(string Id, string Name, string Phrase, string Kind, string Target, string Arguments,
    List<string>? Aliases = null);

sealed class CommandCatalog
{
    public static List<AgentCommand> Load(string path)
    {
        if (!File.Exists(path)) return new List<AgentCommand>();
        try { return JsonSerializer.Deserialize<List<AgentCommand>>(File.ReadAllText(path)) ?? new(); }
        catch { return new(); }
    }
    public static void Save(string path, List<AgentCommand> commands)
    {
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(commands, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, path, true);
    }
}

sealed class AgentConfig
{
    public string DeviceId { get; set; } = "";
    public string DeviceName { get; set; } = "";
    public string PairingCode { get; set; } = "";
    public DateTimeOffset PairingExpiresUtc { get; set; }
    public string? ClientSecret { get; set; }
    public static AgentConfig LoadOrCreate(string path)
    {
        AgentConfig c;
        if (File.Exists(path))
        {
            c = JsonSerializer.Deserialize<AgentConfig>(File.ReadAllText(path)) ?? new AgentConfig();
            bool changed = false;
            if (string.IsNullOrWhiteSpace(c.DeviceId)) { c.DeviceId = Guid.NewGuid().ToString("N"); changed = true; }
            if (string.IsNullOrWhiteSpace(c.DeviceName)) { c.DeviceName = Environment.MachineName; changed = true; }
            if (changed) c.Save(path);
            return c;
        }
        c = new AgentConfig {
            DeviceId = Guid.NewGuid().ToString("N"),
            DeviceName = Environment.MachineName,
            PairingCode = Convert.ToHexString(RandomNumberGenerator.GetBytes(4)),
            PairingExpiresUtc = DateTimeOffset.UtcNow.AddHours(24)
        };
        c.Save(path);
        File.WriteAllText(Path.ChangeExtension(path, ".pairing-code.txt"), $"RE:Vision pairing code (valid 24h): {c.PairingCode}{Environment.NewLine}Run this file locally on STUDIO and keep the code private.");
        return c;
    }
    public void Save(string path) => File.WriteAllText(path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
}

sealed record AgentIdentityRequest(string Name);
sealed record VoiceCorrectionRequest(string? HeardPhrase, string? CommandId);



record PendingSshKeyRequest(string Id, string DeviceName, string DeviceId, string Username,
    string PublicKey, string Fingerprint, string RemoteAddress, DateTimeOffset CreatedUtc);

