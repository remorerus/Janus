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

## Automatic trusted handshake and credential provisioning

The target is one user action — **Add device / Подключить** — after which RE:Vision and the computer provision and save their own credentials. The user must never copy private keys or manually configure separate Windows and SSH secrets.

### Handshake flow

1. **Discover:** Android finds candidates through UDP discovery. This is only an announcement; any device on the LAN can imitate it. Discovery metadata is never treated as proof of identity.
2. **Start enrollment:** the user selects the candidate and approves adding it. The Windows manager shows a short-lived one-time approval QR/code tied to that candidate's stable `deviceId`, expiring quickly and usable once. Scanning/approving this is the only first-time trust action.
3. **Authenticate the handshake:** establish an encrypted channel using TLS 1.3. During first enrollment, bind both peers' public identity keys/certificate fingerprints to the approved one-time enrollment. Show a short comparison phrase or fingerprint on both sides when needed to prevent accidental approval of a different host.
4. **Generate identities locally:** Android creates/uses its own non-exportable identity key in Android Keystore; Windows creates/uses its machine identity key in the Windows certificate/CNG store protected by the OS. Neither endpoint sends its private key.
5. **Exchange public identity:** each peer sends its public certificate/key and a signed handshake transcript including both device IDs, ephemeral handshake values, protocol version and negotiated capabilities. Both sides verify the transcript and pin the peer identity.
6. **Provision services automatically over the authenticated channel:**
   - Windows Agent authorizes the Android app identity for its Windows command API. During a migration period it may provision a unique legacy HMAC credential per device, but the target is mutual TLS/public-key authentication rather than one shared secret.
   - Android sends only the public half of its SSH key and the selected Windows SSH username. The Windows Agent installs it into the correct OpenSSH `authorized_keys` location only after the user-approved enrollment, with restrictive ACLs. The SSH private key stays in Android Keystore.
   - The existing SSH host-key pin is retained or confirmed as part of the same enrollment.
7. **Persist:** both peers save the other's public identity, stable device ID, capability list, endpoint hints and independently revocable service grants. Android stores references to credential aliases in the per-device record; Windows stores the peer identity and authorization under its protected ProgramData configuration.
8. **Reconnect automatically:** future discovery finds the same device ID, then the peers authenticate using the pinned identities. A changed IP is just a new endpoint; it must not trigger re-pairing. A renamed device remains the same identity.

### Security rules

- Do not make one universal secret for Voice Bridge, Windows Agent and SSH. A single enrollment flow provisions separate credentials/grants for each service, so revoking SSH does not revoke Windows Agent and vice versa.
- The local Voice Bridge PIN/token is a separate Rokid-to-Android loopback boundary. Keep the existing PIN and storage intact; it is not transmitted to a computer.
- Discovery broadcasts contain no approval code, PIN, public-key authorization grant or private key.
- The one-time enrollment approval is random, short-lived, single-use, bound to the selected device ID and rate-limited. Never accept an approval code merely because a host answered UDP discovery.
- Private keys never leave their originating device. Only public keys/certificates and service-specific authorization grants are exchanged.
- On device removal, revoke the selected device's service grants and unpin its peer identity. Do not clear credentials belonging to other devices.
- If a device is reinstalled or its identity key changes, treat it as a new trust decision and require approval again; do not silently accept a new fingerprint.

### What this means for the user

For STUDIO PC, the existing pairing and SSH key are migrated in place: no new code and no key re-entry. For a new computer, the user finds it in the list and approves one enrollment QR/code. RE:Vision and the computer then create, exchange, save and validate the service credentials automatically. Future connections are silent unless the identity changes.

This full handshake, mutual TLS and automatic SSH public-key provisioning are the next implementation milestone; they are not yet implemented by the current UDP discovery prototype. Until that milestone is complete and tested, existing Windows HMAC pairing and SSH key installation remain in place.

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
