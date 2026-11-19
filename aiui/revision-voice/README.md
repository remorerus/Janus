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
- Branch: `revision-aiui-agent`
- Directory: `aiui/revision-voice`

Bind the imported project to a new AIUI Agent named RE:Vision, then package/upload it in AIUI Studio. Update the glasses resource package through the Rokid app.

## Current milestone
The first build is a bridge-integration foundation. It checks whether the AIUI runtime can reach the loopback bridge and contains the command flow, but commands remain blocked until the Android bridge adds token authentication and the localhost route is verified on the glasses.
