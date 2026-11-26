# RE:Vision Voice Agent

This is the AIUI agent source project. It is separate from the Android RE:Vision app but lives in the same GitHub repository so both can be versioned together.

## Build
Install Node.js 20+ and the Rokid AIX CLI:

```bash
npm install -g @yodaos-pkg/aix-cli
aix check ./aiui/revision-voice
aix pack ./aiui/revision-voice -o ./dist/RE-Vision.aix
```

The GitHub Actions workflow packages this directory and publishes the resulting `.aix` as a downloadable workflow artifact.

## Import to AIUI Studio
Import `RE-Vision.aix` as an update to the existing agent «Ревижн», not as a new agent. Preserve the existing Agent ID:
`c54c14a5-db8e-4db5-9ca5-cccdfc11c74a`.

Keep the existing intents and commands. Publish/update the existing agent resource in AIUI Studio, then synchronize the glasses resources through Hi Rokid.


The stable existing Agent ID is stored in `.aix/agent-id` and is retained by the GitHub Actions pack step; keep this file in source control for future updates.

## Current milestone
The agent uses the authenticated local Android Voice Bridge. Verify localhost routing on the actual Rokid glasses before relying on commands; device actions require the matching four-digit PIN configured in both apps.
