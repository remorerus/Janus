using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http.Json;

const int Port = 47821;
var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "RevisionWindowsAgent");
Directory.CreateDirectory(dataDir);
var configPath = Path.Combine(dataDir, "agent.json");
var config = AgentConfig.LoadOrCreate(configPath);
var builder = WebApplication.CreateBuilder(args);
builder.Host.UseWindowsService(options => options.ServiceName = "REVisionWindowsAgent");
builder.WebHost.ConfigureKestrel(options => options.ListenAnyIP(Port));
builder.Services.Configure<JsonOptions>(options => options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase);
var app = builder.Build();
var usedNonces = new ConcurrentDictionary<string, DateTimeOffset>();

app.MapGet("/v1/status", () => Results.Json(new { name = "RE:Vision Windows Agent", version = "0.1.0", paired = config.ClientSecret is not null }));

app.MapPost("/v1/pair", (PairRequest request) =>
{
    if (config.ClientSecret is not null) return Results.Conflict(new { error = "Already paired. Reset pairing locally to pair again." });
    if (DateTimeOffset.UtcNow > config.PairingExpiresUtc || string.IsNullOrWhiteSpace(request.Code) ||
        !CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(request.Code), Encoding.UTF8.GetBytes(config.PairingCode)))
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
    if (config.ClientSecret is null || !Authenticate(config.ClientSecret, context.Request.Method, context.Request.Path, timestamp, nonce, body, signature))
        return Results.Unauthorized();

    var now = DateTimeOffset.UtcNow;
    foreach (var item in usedNonces.Where(x => now - x.Value > TimeSpan.FromMinutes(3)).ToArray()) usedNonces.TryRemove(item.Key, out _);
    if (!usedNonces.TryAdd(nonce, now)) return Results.Unauthorized();

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

app.MapPost("/v1/unpair", (HttpContext context) =>
{
    var timestamp = context.Request.Headers["X-Revision-Time"].ToString();
    var nonce = context.Request.Headers["X-Revision-Nonce"].ToString();
    var signature = context.Request.Headers["X-Revision-Signature"].ToString();
    if (config.ClientSecret is null || !Authenticate(config.ClientSecret, "POST", context.Request.Path, timestamp, nonce, "", signature))
        return Results.Unauthorized();
    config.ClientSecret = null;
    config.Save(configPath);
    return Results.Ok(new { paired = false });
});

app.Run($"http://0.0.0.0:{Port}");

static bool Authenticate(string secretBase64, string method, string path, string timestamp, string nonce, string body, string signature)
{
    if (!long.TryParse(timestamp, out var unix) || Math.Abs(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - unix) > 60 ||
        nonce.Length < 16 || nonce.Length > 128 || signature.Length == 0) return false;
    try
    {
        var key = Convert.FromBase64String(secretBase64);
        var payload = $"{method}\n{path}\n{timestamp}\n{nonce}\n{body}";
        var expected = HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(payload));
        var supplied = Convert.FromBase64String(signature);
        return CryptographicOperations.FixedTimeEquals(expected, supplied);
    }
    catch { return false; }
}

sealed record PairRequest(string Code);
sealed record PairResponse(string ClientSecret, string Authentication, int Port);

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
