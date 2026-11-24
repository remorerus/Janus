using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Runtime.InteropServices;
using Microsoft.AspNetCore.Http.Json;

const int Port = 47821;
var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "RevisionWindowsAgent");
Directory.CreateDirectory(dataDir);
var configPath = Path.Combine(dataDir, "agent.json");
var commandsPath = Path.Combine(dataDir, "commands.json");
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
            target.Length is < 1 or > 500 || arguments.Length > 500 ||
            kind is not ("app" or "bat" or "close" or "restart"))
            return Results.BadRequest(new { error = "Проверь название, голосовую фразу, тип и путь к файлу." });
        if (normalized.Any(x => string.Equals(x.Phrase, phrase, StringComparison.OrdinalIgnoreCase)))
            return Results.BadRequest(new { error = $"Голосовая фраза повторяется: {phrase}" });
        normalized.Add(new AgentCommand(Guid.NewGuid().ToString("N"), name, phrase, kind, target, arguments));
    }

    CommandCatalog.Save(commandsPath, normalized);
    return Results.Json(new { saved = true, count = normalized.Count });
});

app.MapPost("/v1/manager/pairing/regenerate", (HttpContext context) =>
{
    if (!IsLocal(context)) return Results.NotFound();
    config.ClientSecret = null;
    config.PairingCode = Convert.ToHexString(RandomNumberGenerator.GetBytes(4));
    config.PairingExpiresUtc = DateTimeOffset.UtcNow.AddHours(24);
    config.Save(configPath);
    File.WriteAllText(Path.ChangeExtension(configPath, ".pairing-code.txt"),
        $"RE:Vision pairing code (valid 24h): {config.PairingCode}{Environment.NewLine}Run this file locally on STUDIO and keep the code private.");
    return Results.Json(new { code = config.PairingCode, expiresUtc = config.PairingExpiresUtc, paired = false });
});

app.MapGet("/v1/status", () => Results.Json(new { name = "RE:Vision Windows Agent", version = "0.2.0-test", paired = config.ClientSecret is not null }));

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
    if (!usedNonces.TryAdd(nonce, now)) return Results.Json(new { error = "nonce_replay" }, statusCode: StatusCodes.Status401Unauthorized);

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
    if (!usedNonces.TryAdd(nonce, now)) return Results.Json(new { error = "nonce_replay" }, statusCode: StatusCodes.Status401Unauthorized);

    ExecuteRequest? request;
    try { request = JsonSerializer.Deserialize<ExecuteRequest>(body, new JsonSerializerOptions { PropertyNameCaseInsensitive = true }); }
    catch { return Results.BadRequest(new { error = "Invalid command request" }); }
    var phrase = (request?.Phrase ?? "").Trim();
    if (phrase.Length is < 1 or > 160) return Results.BadRequest(new { error = "Voice phrase is required" });
    var command = CommandCatalog.Load(commandsPath).FirstOrDefault(x =>
        string.Equals(x.Phrase, phrase, StringComparison.OrdinalIgnoreCase));
    if (command is null) return Results.NotFound(new { error = "No matching command in the local catalog" });
    var launched = LaunchCatalogCommand(command);
    return Results.Json(new { accepted = launched, command = command.Name, kind = command.Kind });
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

static bool LaunchCatalogCommand(AgentCommand command)
{
    try
    {
        var target = Path.GetFullPath(command.Target);
        if (!File.Exists(target)) return false;
        const uint InvalidSessionId = 0xFFFFFFFF;
        uint sessionId = NativeMethods.WTSGetActiveConsoleSessionId();
        if (sessionId == InvalidSessionId) return false;
        if (string.Equals(command.Kind, "close", StringComparison.OrdinalIgnoreCase))
            return StopCatalogProcess(target, sessionId);
        if (string.Equals(command.Kind, "restart", StringComparison.OrdinalIgnoreCase))
            StopCatalogProcess(target, sessionId);

        string application;
        string commandLine;
        string workingDirectory = Path.GetDirectoryName(target) ?? Environment.SystemDirectory;
        if ((string.Equals(command.Kind, "app", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(command.Kind, "restart", StringComparison.OrdinalIgnoreCase)) &&
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
<main><section class="panel"><h2>Сопряжение RE:Vision</h2>
<div class="muted">Создай новый код, если старый не подходит или сопряжение нужно выполнить заново.</div>
<button class="danger" onclick="regeneratePairing()">Сгенерировать новый код</button>
<div id="pairingResult" class="status" aria-live="polite"></div>
<div class="note">После генерации старое сопряжение будет сброшено. Введи показанный код в RE:Vision на очках в течение 24 часов.</div>
</section><section class="panel"><h2>Добавить команду в каталог</h2>
<label>Название</label><input id="name" placeholder="Например, Запуск Mixxx">
<label>Голосовая фраза</label><input id="phrase" placeholder="Запусти Mixxx">
<label>Тип</label><select id="kind"><option value="app">Запустить приложение (.exe)</option><option value="close">Закрыть приложение (.exe)</option><option value="restart">Перезапустить приложение (.exe)</option><option value="bat">Запустить BAT-файл</option></select>
<label>Полный путь к файлу</label><input id="target" placeholder="C:\Program Files\...\app.exe">
<label>Аргументы запуска (необязательно)</label><input id="args" placeholder="--fullscreen">
<button onclick="addCommand()">Добавить в список</button><button class="secondary" onclick="clearForm()">Очистить</button>
<div class="status" id="status"></div>
<div class="note">Команды запускаются только из этого локального каталога и только в активной пользовательской сессии Windows. Добавляй только проверенные EXE и BAT-файлы.</div>
</section><section class="panel"><h2>Мои команды</h2><div id="commands" class="muted">Загрузка…</div><button onclick="saveAll()">Сохранить изменения</button><button class="secondary" onclick="loadCommands()">Обновить список</button></section></main>
<script>
let commands=[];
function el(id){return document.getElementById(id)}
async function regeneratePairing(){
 if(!confirm('Сбросить текущее сопряжение и создать новый код? Старое подключение перестанет работать.'))return;
 const box=el('pairingResult');box.textContent='Создаю новый код…';
 try{
  const r=await fetch('/v1/manager/pairing/regenerate',{method:'POST'});
  const data=await r.json();if(!r.ok)throw Error(data.error||'Не удалось создать код');
  box.innerHTML='';
  const line=document.createElement('div');line.textContent='НОВЫЙ КОД: '+data.code;line.style.cssText='font-size:26px;font-weight:700;letter-spacing:3px;margin:12px 0;color:#a7f3d0';
  const expiry=document.createElement('div');expiry.textContent='Действует 24 часа. Теперь введи этот код в RE:Vision на очках.';
  const copy=document.createElement('button');copy.className='secondary';copy.textContent='Скопировать код';copy.onclick=()=>navigator.clipboard.writeText(data.code);
  box.append(line,expiry,copy);
 }catch(e){box.textContent=e.message}
}
function msg(t){el('status').textContent=t}
function clearForm(){['name','phrase','target','args'].forEach(x=>el(x).value='');el('kind').value='app'}
function addCommand(){const c={name:el('name').value.trim(),phrase:el('phrase').value.trim(),kind:el('kind').value,target:el('target').value.trim(),arguments:el('args').value.trim()};
if(!c.name||!c.phrase||!c.target){msg('Заполни название, фразу и путь к файлу.');return}
if(commands.some(x=>x.phrase.toLowerCase()===c.phrase.toLowerCase())){msg('Такая голосовая фраза уже есть.');return}
commands.push(c);render();clearForm();msg('Команда добавлена в редактор. Нажми «Сохранить изменения».')}
function removeCommand(i){commands.splice(i,1);render();msg('Команда удалена из списка. Сохрани изменения.')}
function render(){const root=el('commands');if(!commands.length){root.textContent='Пока команд нет. Добавь первую слева.';return}
root.innerHTML='';commands.forEach((c,i)=>{const d=document.createElement('div');d.className='item';const info=document.createElement('div');const b=document.createElement('b');b.textContent=c.name;const s=document.createElement('small');s.textContent=c.phrase+' · '+(c.kind==='bat'?'BAT':c.kind==='close'?'Закрытие EXE':c.kind==='restart'?'Перезапуск EXE':'Запуск EXE')+' · '+c.target;info.append(b,s);const btn=document.createElement('button');btn.className='danger';btn.textContent='Удалить';btn.onclick=()=>removeCommand(i);d.append(info,btn);root.append(d)})}
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


