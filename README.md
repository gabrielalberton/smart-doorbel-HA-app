# Smart Doorbell HA App

Open-source Android + web intercom for a Home Assistant / Frigate / go2rtc doorbell setup.

The repository contains:

- `android-app/` — Capacitor/Kotlin Android app with lock-screen incoming-call UI, local/public route selection, Home Assistant notification trigger, optional FCM, immediate background media shutdown and self-update support.
- `server/` — Fastify web UI and server-side proxy for go2rtc WebRTC, Home Assistant actions, talkback locking and update discovery.
- `.github/workflows/release-android.yml` — optional, manually dispatched GitHub-hosted release workflow. This installation builds and signs on the NUC.

## Security model

No Home Assistant token, webhook URL, camera credential, signing key or Firebase configuration is committed. The repository intentionally excludes `.env`, `google-services.json`, keystores, APK/AAB files, build output and local Android configuration.

The APK does not embed a household's URLs, streams or Home Assistant trigger metadata. On first launch it asks for the server endpoint and a pairing password. The server returns non-secret runtime configuration; the password is never saved in the app.

## Quick start

```bash
cp .env.example .env
# Edit .env for your installation.

cd server
node scripts/hash-pairing-password.js
# Put only the generated hash in ../.env as PAIRING_PASSWORD_HASH.

npm ci
npm run start:configured
```

For Android:

```bash
cd android-app
npm ci
npm run sync
cd android
./gradlew --no-daemon :app:assembleDebug --console=plain
```

The debug APK is generated under `android-app/android/app/build/outputs/apk/debug/` and is ignored by Git.

On first launch, enter only `https://your-doorbell-host.example` and the pairing password selected by the server administrator.

See [Configuration](docs/CONFIGURATION.md) for installation-specific values and [Releases and deployment](docs/RELEASES.md) for the complete release, NUC promotion, verification and rollback procedure. Agents must read [AGENTS.md](AGENTS.md) before changing or releasing this app; [CLAUDE.md](CLAUDE.md) points to the same instructions.

For this deployment, releases are built, signed and verified on the trusted NUC and then uploaded with the GitHub CLI. GitHub-hosted signing is retained only as an optional manual workflow.

## Update flow

The Android app does **not** check GitHub itself. It loads the server's web UI with `app_version=<installed version>`. That UI asks the server's public `/app-update/manifest.json` for the available version, on opening and on returning to the foreground (at most every 15 minutes per loaded page). The server checks the latest GitHub Release for an APK named `smart-doorbell-X.Y.Z.apk` that is newer than its configured local fallback version. The server exposes the accepted APK at its own `/app-update/latest.apk`, verifies the GitHub asset's SHA-256 when proxying a newer release, and falls back to the configured local APK when GitHub has no newer usable release or the lookup fails. If a selected newer APK fails download or checksum verification, the download fails and must be repaired.

The in-app download button appears only if the manifest version is newer than the APK's `versionName`. A GitHub Release by itself is **not a completed deployment**: the live NUC server and web UI are built from a separate directory, and the fallback manifest/APK must be promoted and verified. In September 2026, publishing 1.0.4 while the live NUC still advertised 1.0.3 caused the update button to stay hidden. The [release checklist](docs/RELEASES.md) makes this verification mandatory.

## Home Assistant trigger

The current low-battery trigger path uses a discreet Home Assistant Companion notification with the configured channel/tag. The Android `NotificationListenerService` consumes it and opens a native incoming-call screen. Each installation must configure its own HA automation and grant the app notification-listener/full-screen access.

FCM is optional. To enable it, create your own Firebase Android app and add the untracked `android-app/android/app/google-services.json` before building.

## Important deployment notes

- Use HTTPS for both local and public origins.
- Keep Home Assistant webhook URLs server-side only.
- Do not expose go2rtc/Frigate administrative APIs directly to the internet.
- Configure public WebRTC candidates/TURN when off-LAN media is required.
- Use a stable signing key from the first distributed build; Android rejects in-place updates signed by another key.
- In the call preview (`native_call=1`), camera sound must remain muted until **Atender** is tapped. When the app goes into the background, stop microphone capture and both WebRTC receivers immediately; do not rely on a delayed JavaScript timer.

## License

MIT
