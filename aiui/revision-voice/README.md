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
Import the GitHub subdirectory:
- Repository: `remorerus/Janus`
- Branch: `revision-1.9.20-integrated`
- Directory: `aiui/revision-voice`

Bind the imported project to a new AIUI Agent named RE:Vision, then package/upload it in AIUI Studio. Update the glasses resource package through the Rokid app.

## Current milestone
The agent uses the authenticated local Android Voice Bridge. Verify localhost routing on the actual Rokid glasses before relying on commands; device actions require the matching four-digit PIN configured in both apps.
