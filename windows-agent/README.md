# RE:Vision Windows Agent (prototype)

A small Windows service for RE:Vision. It deliberately exposes only a narrow command set:
- `shutdown` — immediate Windows shutdown
- `restart` — immediate Windows restart
- `cancel-shutdown` — cancel a pending shutdown

It does not expose arbitrary shell/PowerShell execution.

## Build
Requires .NET 8 SDK on Windows:
```powershell
dotnet publish .\Revision.WindowsAgent\Revision.WindowsAgent.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o .\publish
Compress-Archive .\publish\* .\Revision.WindowsAgent-win-x64.zip -Force
```

## Install
1. Copy this folder and the publish ZIP to the target Windows PC.
2. Open PowerShell as Administrator.
3. Run `Set-ExecutionPolicy -Scope Process Bypass` and then `./install-agent.ps1`.
4. Retrieve the one-time pairing code locally from `%ProgramData%\RevisionWindowsAgent\agent.pairing-code.txt`.

The service listens on TCP 47821. The installer creates a Private-profile-only inbound firewall rule.

## Security status — prototype
Pairing is one-time and subsequent requests use HMAC-SHA256, a 60-second timestamp window, and replay-resistant nonces. The agent is currently HTTP, not TLS. Pair only on a trusted private LAN; never expose this port to the Internet or a public Wi-Fi network. Before production use, add TLS with certificate pinning (or an equivalent authenticated encrypted transport), persistent device identity management, and explicit user confirmation UX in RE:Vision. Do not treat this prototype as production-secure.

## Pairing protocol
- `GET /v1/status`
- `POST /v1/pair` JSON: `{"code":"ONE_TIME_CODE"}`; returns `clientSecret` (base64).
- Authenticated command: `POST /v1/command/shutdown`, `/restart`, or `/cancel-shutdown`.
- HMAC payload: `METHOD\nPATH\nUNIX_TIMESTAMP\nNONCE\nBODY`; send base64 HMAC in `X-Revision-Signature`, timestamp in `X-Revision-Time`, nonce in `X-Revision-Nonce`.
- `POST /v1/unpair` removes the paired secret.

The pairing code is generated on first service start and expires after 24 hours. Pairing can be reset by stopping the service and removing `agent.json`, then starting it again.
