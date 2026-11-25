using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
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
    foreach (var item in request.Commands)
    {
        var id = string.IsNullOrWhiteSpace(item.Id) ? Guid.NewGuid().ToString("N") : item.Id.Trim();
        var name = (item.Name ?? "").Trim();
        var phrase = (item.Phrase ?? "").Trim();
        var shell = (item.Shell ?? "").Trim().ToLowerInvariant();
        var command = item.Command ?? "";
        if (name.Length is < 1 or > 80 || phrase.Length is < 1 or > 160 ||
            command.Trim().Length is < 1 or > 12000 || shell is not ("powershell" or "cmd"))
            return Results.BadRequest(new { error = "Проверь название, голосовую фразу, оболочку и команду." });
        if (normalized.Any(x => string.Equals(x.Phrase, phrase, StringComparison.OrdinalIgnoreCase)))
            return Results.BadRequest(new { error = $"Голосовая фраза повторяется: {phrase}" });
        normalized.Add(new SshCommandEntry(id, name, phrase, shell, command));
    }

    SshCommandCatalog.Save(sshCommandsPath, normalized);
    return Results.Json(new { saved = true, count = normalized.Count });
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
    foreach (var item in request.Commands)
    {
        var name = (item.Name ?? "").Trim();
        var phrase = (item.Phrase ?? "").Trim();
        var kind = (item.Kind ?? "").Trim().ToLowerInvariant();
        var target = (item.Target ?? "").Trim();
        var arguments = item.Arguments ?? "";
        if (name.Length is < 1 or > 80 || phrase.Length is < 1 or > 160 ||
            target.Length > 500 || arguments.Length > 500 ||
            kind is not ("app" or "bat" or "close" or "restart" or "media" or "hotkey" or "window"))
            return Results.BadRequest(new { error = "Проверь название, голосовую фразу, тип команды и параметры." });
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
        if (normalized.Any(x => string.Equals(x.Phrase, phrase, StringComparison.OrdinalIgnoreCase)))
            return Results.BadRequest(new { error = $"Голосовая фраза повторяется: {phrase}" });
        normalized.Add(new AgentCommand(Guid.NewGuid().ToString("N"), name, phrase, kind, target, arguments));
    }

    CommandCatalog.Save(commandsPath, normalized);
    return Results.Json(new { saved = true, count = normalized.Count });
});


app.MapGet("/v1/status", () => Results.Json(new { name = "RE:Vision Windows Agent", version = "0.2.0-test", paired = config.ClientSecret is not null }));

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
    var catalog = CommandCatalog.Load(commandsPath);
    var command = catalog.FirstOrDefault(x =>
        string.Equals(NormalizeVoicePhrase(x.Phrase), NormalizeVoicePhrase(phrase), StringComparison.Ordinal));
    var matchedBy = "exact";
    if (command is null)
    {
        var candidates = catalog
            .Select(x => new { Command = x, Score = VoicePhraseDistance(x.Phrase, phrase) })
            .Where(x => x.Score < 1000)
            .OrderBy(x => x.Score)
            .ToList();
        if (candidates.Count == 0)
            return Results.NotFound(new { error = "No matching command in the local catalog", heard = phrase });
        var bestScore = candidates[0].Score;
        var best = candidates.Where(x => x.Score == bestScore).ToList();
        if (best.Count != 1)
            return Results.Conflict(new { error = "Several commands sound similar; no action was executed.", heard = phrase,
                candidates = best.Select(x => x.Command.Name).ToArray() });
        command = best[0].Command;
        matchedBy = "tolerant";
    }
    var launched = LaunchCatalogCommand(command);
    return Results.Json(new { accepted = launched, command = command.Name, kind = command.Kind, matchedBy });
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

static string NormalizeVoicePhrase(string value)
{
    var tokens = Regex.Matches((value ?? "").ToLowerInvariant().Replace('ё', 'е'), @"[\p{L}\p{N}]+")
        .Cast<Match>()
        .Select(m => m.Value)
        .Where(t => t is not ("ревижн" or "ревижен" or "ревижин" or "ревиж" or "пожалуйста" or "агент"))
        .Select(t => t switch { "аип" or "аймп" or "aimp" => "аимп", _ => t });
    return string.Join(" ", tokens);
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
<title>Win SSH Agent</title><style>
:root{color-scheme:dark;font:15px Segoe UI,Arial;background:#111827;color:#e5e7eb}*{box-sizing:border-box}body{margin:0;padding:28px;max-width:1180px;margin:auto}
header{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:22px}.muted{color:#9ca3af}.pill{padding:6px 10px;border-radius:20px;background:#263449;color:#a7f3d0}
main{display:grid;grid-template-columns:minmax(300px,.9fr) minmax(360px,1.1fr);gap:18px}.panel{background:#1f2937;border:1px solid #374151;border-radius:14px;padding:20px}h1{font-size:23px;margin:0}h2{font-size:18px;margin:0 0 16px}
label{display:block;color:#cbd5e1;margin:12px 0 6px}input,select,textarea{width:100%;padding:11px;border-radius:8px;border:1px solid #4b5563;background:#111827;color:#f9fafb}textarea{min-height:190px;resize:vertical;font:13px Consolas,monospace}
button{border:0;border-radius:8px;padding:10px 14px;background:#2563eb;color:white;font-weight:600;cursor:pointer;margin:8px 6px 0 0}button.secondary{background:#4b5563}button.danger{background:#991b1b}
.pairing{grid-column:1/-1}.item{padding:12px;border:1px solid #374151;border-radius:9px;margin:8px 0;display:flex;justify-content:space-between;gap:12px;align-items:center}.item small{display:block;color:#9ca3af;margin-top:4px;white-space:pre-wrap;overflow-wrap:anywhere}.item button{margin:0}.actions{display:flex;gap:6px;flex-wrap:wrap}.note{font-size:13px;line-height:1.5;color:#fcd34d;margin-top:16px}.status{min-height:24px;color:#a7f3d0;margin-top:10px}
@media(max-width:800px){body{padding:14px}main{grid-template-columns:1fr}header{align-items:flex-start;flex-direction:column}}
</style></head><body>
<header><div><h1>Win SSH Agent</h1><div class="muted">Каталог SSH-команд STUDIO PC · локальная панель</div></div><span class="pill">SSH по ключу · OpenSSH</span></header>
<main><section class="panel"><h2 id="formTitle">Добавить SSH-команду</h2>
<label>Название команды</label><input id="name" maxlength="80" placeholder="Например, Запуск Mixxx">
<label>Точная голосовая фраза</label><input id="phrase" maxlength="160" placeholder="Запусти Mixxx">
<label>Оболочка</label><select id="shell"><option value="powershell">PowerShell</option><option value="cmd">CMD</option></select>
<label>Команда или сценарий</label><textarea id="command" maxlength="12000" spellcheck="false" placeholder="Start-Process 'C:\Program Files\Mixxx\mixxx.exe'"></textarea>
<div class="actions"><button onclick="saveEditor()">Сохранить команду</button><button class="secondary" onclick="clearEditor()">Новая</button></div>
<div class="status" id="status"></div>
<div class="note">Команда выполняется на STUDIO PC от имени SSH-пользователя Windows. Доступ к панели есть только с самого компьютера. Сохраняй только доверенные команды.</div>
</section><section class="panel"><h2>Сохранённые SSH-команды</h2><div id="commands" class="muted">Загрузка…</div><button onclick="saveAll()">Сохранить каталог</button><button class="secondary" onclick="loadCommands()">Обновить список</button><div class="muted" style="margin-top:14px;font-size:12px">Файл: %ProgramData%\\WinSshAgent\\commands.json</div></section></main>
<script>
let commands=[],editingId=null;
function el(id){return document.getElementById(id)}
function msg(t){el('status').textContent=t}
function clearEditor(){editingId=null;['name','phrase','command'].forEach(x=>el(x).value='');el('shell').value='powershell';el('formTitle').textContent='Добавить SSH-команду'}
function saveEditor(){const c={id:editingId||crypto.randomUUID().replaceAll('-',''),name:el('name').value.trim(),phrase:el('phrase').value.trim(),shell:el('shell').value,command:el('command').value};
if(!c.name||!c.phrase||!c.command.trim()){msg('Заполни название, точную фразу и команду.');return}
const duplicate=commands.some(x=>x.phrase.toLowerCase()===c.phrase.toLowerCase()&&x.id!==editingId);if(duplicate){msg('Такая голосовая фраза уже есть.');return}
if(editingId){const i=commands.findIndex(x=>x.id===editingId);if(i>=0)commands[i]=c}else commands.push(c);render();clearEditor();msg('Изменение внесено. Нажми «Сохранить каталог».')}
function editCommand(id){const c=commands.find(x=>x.id===id);if(!c)return;editingId=id;el('name').value=c.name;el('phrase').value=c.phrase;el('shell').value=c.shell;el('command').value=c.command;el('formTitle').textContent='Изменить SSH-команду';window.scrollTo({top:0,behavior:'smooth'})}
function removeCommand(id){commands=commands.filter(x=>x.id!==id);if(editingId===id)clearEditor();render();msg('Команда удалена из редактора. Нажми «Сохранить каталог».')}
function render(){const root=el('commands');root.replaceChildren();if(!commands.length){root.textContent='Пока команд нет. Добавь первую слева.';return}
commands.forEach(c=>{const d=document.createElement('div');d.className='item';const info=document.createElement('div');const b=document.createElement('b');b.textContent=c.name;const s=document.createElement('small');s.textContent=c.phrase+' · '+(c.shell==='cmd'?'CMD':'PowerShell')+' · '+c.command;info.append(b,s);const actions=document.createElement('div');actions.className='actions';const edit=document.createElement('button');edit.className='secondary';edit.textContent='Изменить';edit.onclick=()=>editCommand(c.id);const del=document.createElement('button');del.className='danger';del.textContent='Удалить';del.onclick=()=>removeCommand(c.id);actions.append(edit,del);d.append(info,actions);root.append(d)})}
async function loadCommands(){try{const r=await fetch('/v1/ssh-manager/commands');if(!r.ok)throw Error('HTTP '+r.status);commands=await r.json();render();msg('Каталог загружен: '+commands.length)}catch(e){msg('Не удалось загрузить SSH-каталог: '+e.message)}}
async function saveAll(){try{const r=await fetch('/v1/ssh-manager/commands',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({commands})});const data=await r.json();if(!r.ok)throw Error(data.error||'Ошибка сохранения');msg('Сохранено. Команд: '+data.count)}catch(e){msg(e.message)}}
updateKindFields();
loadCommands();
loadPairing();
</script></body></html>
""";


static string GetManagerHtml() => """
<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>RE:Vision Windows Agent</title><style>
:root{color-scheme:dark;font:15px Segoe UI,Arial;background:#111827;color:#e5e7eb}*{box-sizing:border-box}body{margin:0;padding:28px;max-width:1100px;margin:auto}
header{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:22px}.muted{color:#9ca3af}.pill{padding:6px 10px;border-radius:20px;background:#263449;color:#a7f3d0}
main{display:grid;grid-template-columns:1fr 1.15fr;gap:18px}.panel{background:#1f2937;border:1px solid #374151;border-radius:14px;padding:20px}h1{font-size:23px;margin:0}h2{font-size:18px;margin:0 0 16px}
label{display:block;color:#cbd5e1;margin:12px 0 6px}input,select{width:100%;padding:11px;border-radius:8px;border:1px solid #4b5563;background:#111827;color:#f9fafb}
button{border:0;border-radius:8px;padding:10px 14px;background:#2563eb;color:white;font-weight:600;cursor:pointer;margin:8px 6px 0 0}button.secondary{background:#4b5563}button.danger{background:#991b1b}
.item{padding:12px;border:1px solid #374151;border-radius:9px;margin:8px 0;display:flex;justify-content:space-between;gap:12px;align-items:center}.item small{display:block;color:#9ca3af;margin-top:4px}.item button{margin:0}.note{font-size:13px;line-height:1.5;color:#fcd34d;margin-top:16px}.status{min-height:24px;color:#a7f3d0;margin-top:10px}
@media(max-width:760px){body{padding:14px}main{grid-template-columns:1fr}header{align-items:flex-start;flex-direction:column}}
</style></head><body>
<header><div><h1>RE:Vision Windows Agent</h1><div class="muted">Панель управления STUDIO PC · тестовая версия</div></div><span class="pill">Локальная панель</span></header>
<main><section class="panel pairing"><h2>Привязка RE:Vision</h2>
<div id="pairingState" class="status">Проверка состояния привязки…</div>
<div id="pairingCodeWrap" style="display:none"><label>Текущий код привязки</label><div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><strong id="pairingCode" style="font-size:24px;letter-spacing:3px"></strong><button type="button" class="secondary" onclick="copyPairingCode()">Копировать</button></div><div id="pairingExpiry" class="muted"></div><div class="note">Код предназначен только для привязки RE:Vision. Не отправляй его посторонним.</div></div>
<button type="button" class="secondary" onclick="loadPairing()">Обновить состояние</button>
</section><section class="panel"><h2>Добавить команду в каталог</h2>
<label>Название</label><input id="name" placeholder="Например, Запуск Mixxx">
<label>Голосовая фраза</label><input id="phrase" placeholder="Запусти Mixxx">
<label>Тип</label><select id="kind" onchange="updateKindFields()"><option value="app">Запустить приложение (.exe)</option><option value="close">Закрыть приложение (.exe)</option><option value="restart">Перезапустить приложение (.exe)</option><option value="bat">Запустить BAT-файл</option><option value="media">Управление мультимедиа</option><option value="hotkey">Горячие клавиши</option><option value="window">Управление окнами и фокусом</option></select>
<div id="mediaOptions" style="display:none"><label>Куда отправлять мультимедиа-команду</label><select id="mediaTarget" onchange="updateKindFields()"><option value="global">Системно — активный плеер Windows</option><option value="app">Старый режим — запуск EXE с параметром</option></select><label>Действие</label><select id="mediaAction"><option value="GLOBAL_PLAYPAUSE">Воспроизведение / Пауза</option><option value="GLOBAL_NEXT">Следующий трек</option><option value="GLOBAL_PREV">Предыдущий трек</option><option value="GLOBAL_STOP">Остановить</option><option value="GLOBAL_MUTE">Включить / выключить системный звук</option><option value="GLOBAL_VOLUP">Увеличить системную громкость</option><option value="GLOBAL_VOLDWN">Уменьшить системную громкость</option><option value="/PLAY">Старый режим: воспроизведение</option><option value="/PAUSE">Старый режим: пауза</option><option value="/PLAYPAUSE">Старый режим: play/pause</option><option value="/NEXT">Старый режим: следующий трек</option><option value="/PREV">Старый режим: предыдущий трек</option><option value="/STOP">Старый режим: стоп</option><option value="/MUTE">Старый режим: mute</option><option value="/VOLUP">Старый режим: громкость +</option><option value="/VOLDWN">Старый режим: громкость −</option></select></div>
<div id="hotkeyOptions" style="display:none"><label>Сочетание клавиш</label><input id="hotkeyCombo" placeholder="CTRL+ALT+T"></div>
<div id="windowOptions" style="display:none"><label>Действие с окном</label><select id="windowAction" onchange="updateKindFields()"><option value="focus">Переключиться на окно</option><option value="minimize">Свернуть</option><option value="maximize">Развернуть на весь экран</option><option value="restore">Восстановить окно</option><option value="close">Закрыть окно</option><option value="next">Следующее окно (Alt+Tab)</option><option value="previous">Предыдущее окно (Alt+Shift+Tab)</option></select></div>
<label id="targetLabel">Полный путь к файлу / имя процесса или окна</label><input id="target" placeholder="C:\Program Files\...\app.exe">
<label id="argsLabel">Аргументы запуска (необязательно)</label><input id="args" placeholder="--fullscreen">
<button onclick="addCommand()">Добавить в список</button><button class="secondary" onclick="clearForm()">Очистить</button>
<div class="status" id="status"></div>
<div class="note">Команды запускаются только из этого локального каталога и только в активной пользовательской сессии Windows. Добавляй только проверенные EXE и BAT-файлы.</div>
</section><section class="panel"><h2>Мои команды</h2><div id="commands" class="muted">Загрузка…</div><button onclick="saveAll()">Сохранить изменения</button><button class="secondary" onclick="loadCommands()">Обновить список</button></section></main>
<script>
let commands=[];
function el(id){return document.getElementById(id)}
function msg(t){el('status').textContent=t}
async function loadPairing(){
 try{
  const r=await fetch('/v1/manager/pairing',{cache:'no-store'});
  if(!r.ok)throw Error('HTTP '+r.status);
  const p=await r.json();
  el('pairingCodeWrap').style.display=p.codeAvailable?'block':'none';
  el('pairingCode').textContent=p.pairingCode||'';
  el('pairingExpiry').textContent=p.expiresUtc?'Действует до: '+new Date(p.expiresUtc).toLocaleString():'';
  el('pairingState').textContent=p.message;
 }catch(e){el('pairingState').textContent='Не удалось проверить привязку: '+e.message}
}
function copyPairingCode(){const code=el('pairingCode').textContent;if(code)navigator.clipboard.writeText(code)}
function updateKindFields(){
 const kind=el('kind').value, media=kind==='media', hotkey=kind==='hotkey', win=kind==='window';
 const winGlobal=win&&['next','previous'].includes(el('windowAction').value);
 const mediaGlobal=media&&el('mediaTarget').value==='global';
 el('mediaOptions').style.display=media?'block':'none';
 el('hotkeyOptions').style.display=hotkey?'block':'none';
 el('windowOptions').style.display=win?'block':'none';
 el('targetLabel').style.display=(hotkey||winGlobal||mediaGlobal)?'none':'block';
 el('target').style.display=(hotkey||winGlobal||mediaGlobal)?'none':'block';
 el('targetLabel').textContent=win?'Имя процесса или часть заголовка окна':'Полный путь к файлу';
 el('target').placeholder=win?'chrome, explorer, aimp или часть заголовка':'C:\\Program Files\\...\\app.exe';
 el('argsLabel').style.display=(media||hotkey||win)?'none':'block';
 el('args').style.display=(media||hotkey||win)?'none':'block';
}
function clearForm(){['name','phrase','target','args','hotkeyCombo'].forEach(x=>el(x).value='');el('kind').value='app';el('mediaTarget').value='global';el('mediaAction').value='GLOBAL_PLAYPAUSE';el('windowAction').value='focus';updateKindFields()}
function addCommand(){
 const kind=el('kind').value, action=el('windowAction').value;
 const target=kind==='hotkey'?el('hotkeyCombo').value.trim():(kind==='window'&&['next','previous'].includes(action)?'*':(kind==='media'&&el('mediaTarget').value==='global'?'':el('target').value.trim()));
 const c={name:el('name').value.trim(),phrase:el('phrase').value.trim(),kind,target,arguments:kind==='media'?el('mediaAction').value:(kind==='window'?action:(kind==='hotkey'?'':el('args').value.trim()))};
 if(!c.name||!c.phrase||(!c.target&&kind!=='window'&&!(kind==='media'&&el('mediaTarget').value==='global'))){msg('Заполни название, голосовую фразу и параметры команды.');return}
 if(kind==='window'&&!['next','previous'].includes(action)&&!c.target){msg('Укажи имя процесса или часть заголовка окна.');return}
 if(commands.some(x=>x.phrase.toLowerCase()===c.phrase.toLowerCase())){msg('Такая голосовая фраза уже есть.');return}
 commands.push(c);render();clearForm();msg('Команда добавлена. Нажми «Сохранить изменения».')
}
function removeCommand(i){commands.splice(i,1);render();msg('Команда удалена из списка. Сохрани изменения.')}
function render(){const root=el('commands');if(!commands.length){root.textContent='Пока команд нет. Добавь первую слева.';return}
root.innerHTML='';commands.forEach((c,i)=>{const d=document.createElement('div');d.className='item';const info=document.createElement('div');const b=document.createElement('b');b.textContent=c.name;const s=document.createElement('small');const kindLabel={media:'Мультимедиа',hotkey:'Горячие клавиши',window:'Управление окнами',bat:'BAT',close:'Закрытие EXE',restart:'Перезапуск EXE',app:'Запуск EXE'}[c.kind]||c.kind;s.textContent=c.phrase+' · '+kindLabel+' · '+(c.kind==='hotkey'?c.target:(c.kind==='window'?(c.target==='*'?'':c.target+' · ')+c.arguments:c.target+(c.kind==='media'?' · '+c.arguments:'')));info.append(b,s);const btn=document.createElement('button');btn.className='danger';btn.textContent='Удалить';btn.onclick=()=>removeCommand(i);d.append(info,btn);root.append(d)})}
async function loadCommands(){try{const r=await fetch('/v1/manager/commands');if(!r.ok)throw Error();commands=await r.json();render();msg('Каталог загружен.')}catch(e){msg('Не удалось загрузить каталог. Открой панель локально на STUDIO PC.')}}
async function saveAll(){try{const r=await fetch('/v1/manager/commands',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({commands})});const data=await r.json();if(!r.ok)throw Error(data.error||'Ошибка сохранения');msg('Сохранено. Команд в каталоге: '+data.count)}catch(e){msg(e.message)}}
loadCommands();
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
sealed record SshCommandInput(string? Id, string? Name, string? Phrase, string? Shell, string? Command);
sealed record SshCommandEntry(string Id, string Name, string Phrase, string Shell, string Command);

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

sealed record ExecuteRequest(string? Phrase);
sealed record PairRequest(string Code);
sealed record PairResponse(string ClientSecret, string Authentication, int Port);
sealed record CommandCatalogRequest(List<AgentCommandInput>? Commands);
sealed record AgentCommandInput(string? Name, string? Phrase, string? Kind, string? Target, string? Arguments);
sealed record AgentCommand(string Id, string Name, string Phrase, string Kind, string Target, string Arguments);

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
    public string PairingCode { get; set; } = "";
    public DateTimeOffset PairingExpiresUtc { get; set; }
    public string? ClientSecret { get; set; }
    public static AgentConfig LoadOrCreate(string path)
    {
        if (File.Exists(path)) return JsonSerializer.Deserialize<AgentConfig>(File.ReadAllText(path)) ?? new AgentConfig();
        var c = new AgentConfig { PairingCode = Convert.ToHexString(RandomNumberGenerator.GetBytes(4)), PairingExpiresUtc = DateTimeOffset.UtcNow.AddHours(24) };
        c.Save(path);
        File.WriteAllText(Path.ChangeExtension(path, ".pairing-code.txt"), $"RE:Vision pairing code (valid 24h): {c.PairingCode}{Environment.NewLine}Run this file locally on STUDIO and keep the code private.");
        return c;
    }
    public void Save(string path) => File.WriteAllText(path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
}


