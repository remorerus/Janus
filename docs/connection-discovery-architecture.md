# RE:Vision unified device connections and LAN discovery

## Goal

A user adds a computer or other endpoint once. RE:Vision stores one logical device record and exposes every supported transport as a capability of that device. Adding a second computer must not require repeating unrelated setup or overwriting credentials belonging to the first computer.

## Existing credentials — preserve them

The current implementation already has three independent security domains:

| Credential | Current storage | Purpose |
|---|---|---|
| Local Voice Bridge PIN/token | Android app-private preferences; mirrored in AIUI storage under `revision_bridge_token` | Authorizes the loopback-only Rokid-to-Android bridge on port 38761 |
| Windows Agent client secret + base URL | Android `EncryptedSharedPreferences`, file `revision_windows_agent` | HMAC authentication to the paired Windows Agent |
| SSH private key | Android Keystore, alias referenced by `Device.sshKeyAlias` | Public-key authentication to Windows OpenSSH; host fingerprints are pinned by `TofuHostKeyVerifier` |

These values must **not** be replaced with one shared secret. They authenticate different services and have different rotation/revocation semantics. The unified manager is a single user-facing workflow and credential vault/index, not a cryptographic key merger.

Migration must be additive:
- Read existing Windows Agent base URL and secret without changing them.
- Keep each existing `Device.sshKeyAlias`, SSH username, address and port intact.
- Keep the current Voice Bridge PIN intact.
- Never regenerate, delete, clear or re-pair an existing credential automatically.
- A user-initiated unpair affects only the selected transport on the selected device.

## Logical model

Introduce a stable `deviceId` (UUID) independent of IP address and display name. A logical device contains:
- `deviceId`, configurable `displayName`, aliases and optional platform/OS metadata;
- a list of discovered network endpoints (address, port, protocol, last-seen time);
- capability flags such as `windows-agent`, `ssh`, `wake-on-lan`, `status`;
- credential references, not raw secrets: Windows pairing record ID, SSH key alias and pinned SSH host-key identity.

An IP address is an endpoint, not the device identity. DHCP changes should update endpoints while preserving the device record and credential references. Two computers with the same display name must remain distinct by ID.

## LAN discovery protocol

Use a small, unauthenticated discovery advertisement that contains no credentials:

Current v1 implementation:
- UDP broadcast request/response on `47822/UDP`; request payload is `REVISION_DISCOVER_V1`.
- Android sends to the limited broadcast address and, when available, the current Wi-Fi subnet broadcast address.
- Response fields: protocol version, stable device UUID, user-configurable display name, TCP port `47821` and capability names.
- The authenticated pairing endpoint remains authoritative. Discovery only finds a candidate; it does not establish trust or grant command access.
- The Windows manager exposes the advertised name and allows the user to change it. The UUID remains stable when the display name changes.
- mDNS/DNS-SD (`_revision-agent._tcp.local`) is a possible later enhancement; it is not required for v1 and is not yet implemented.

The Android discovery UI should show candidates with name, IP, advertised services and reachability. It should deduplicate mDNS and UDP results by UUID, not by IP or name.

## One manager

The Windows Agent web UI should become the single local manager entry point:
- one navigation/header with tabs for Windows commands and SSH commands;
- both catalogs remain stored separately and keep their existing validation/execution rules;
- existing routes `/manager` and `/ssh-manager` remain as compatibility redirects/views;
- the manager displays device identity, advertised name, current address, paired state and enabled capabilities.

The Android RE:Vision settings should likewise have one **Устройства и подключения** entry:
- discover devices;
- select an existing saved device or add a new one;
- pair Windows Agent once;
- configure SSH once and associate its Keystore alias with the same logical device;
- show connection health per transport;
- keep the Voice Bridge PIN in its own local-security section because it protects the glasses-to-phone bridge, not a remote computer.

## Routing rules

Voice commands resolve a logical device first, then select a transport based on the requested capability:
- Windows application/window/media command -> Windows Agent;
- command explicitly requested through SSH, or an SSH-only catalog entry -> SSH;
- Wake-on-LAN -> WOL capability;
- status -> prefer the device's native status probe, then available authenticated transports.

If the user explicitly names a transport (for example, “через SSH”), honour it. Otherwise use the configured preferred transport and fall back only when safe. Never silently fall back from an authenticated Windows action to an arbitrary SSH shell command.

## Rollout sequence and current boundary

1. **Implemented in this branch:** preserve current keys; prioritize device-list voice intent; safely decode AIUI query values; preserve HTTP 502 downstream diagnostics; strip the explicit Windows Agent transport marker; add a stable Windows Agent identity, configurable network name, UDP broadcast discovery, Android discovery UI and one navigation between the Windows/SSH web catalog views.
2. **Still required for full multi-device support:** store multiple Windows pairing records keyed by stable device UUID instead of the current single global Windows Agent record.
3. Associate each saved Android device record with the discovered UUID and endpoint list, while keeping existing SSH Keystore aliases and host-key pins intact.
4. Build the unified Android connection manager and per-device transport preferences.
5. Add voice routing to select the correct device and transport from the logical device registry.

Important current boundary: discovery can find multiple computers, but the existing Windows Agent pairing store and voice command path still represent one active Windows Agent connection. Do not treat discovery alone as completed multi-device command routing.

## Acceptance criteria

- Existing STUDIO PC pairing and SSH key still work after upgrade without entering any key again.
- Discovery finds a machine by its configured broadcast name even when its IP changes.
- A second machine can be paired without changing the first machine's credentials.
- Renaming a device does not change its UUID or invalidate pairing.
- Discovery packets contain no PIN, HMAC secret, SSH private key, or pairing code.
- Every transport can be revoked independently, and a revoke never deletes another transport's credentials.
