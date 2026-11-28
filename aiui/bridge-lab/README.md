# RE:Vision Bridge Lab

This is a disposable, isolated probe. It does not replace the production Voice Bridge.

## Test sequence

1. Install `bridge-lab-debug.apk` on the Rokid glasses.
2. Open **RE:Vision Bridge Lab** and tap **Запустить Foreground Service**.
3. Confirm the app reports a successful loopback echo from `127.0.0.1:38763`.
4. Import this AIUI project in AIUI Studio and upload its test agent to the same glasses.
5. Open the test agent page and tap **Проверить связь**.
6. Repeat with the AIUI page closed, then with the glasses screen off for 2–5 minutes. Wake the glasses and issue the test again.

## What each result proves

- APK self-test success: Android Foreground Service and its local WebSocket server work on the device.
- AIUI test success: AIUI JavaScript can reach the Android app through loopback on that firmware.
- Success after closing AIUI UI / screen-off: evidence that the foreground service and channel survive those lifecycle conditions. Repeat several times before adopting it.

The server binds only to `127.0.0.1`, not the LAN interface. A token is required. The token is a lab-only constant and must be replaced with a proper pairing secret before production use.

No AccessibilityService, special-access settings, or Windows companion is involved.
