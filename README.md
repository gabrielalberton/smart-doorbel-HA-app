# Smart Doorbell HA App

Open-source Android + web intercom for a Home Assistant / Frigate / go2rtc doorbell setup.

The repository contains:

- `android-app/` — Capacitor/Kotlin Android app with lock-screen incoming-call UI, local/public route selection, Home Assistant notification trigger, optional FCM, talkback safety timeout and self-update support.
- `server/` — Fastify web UI and server-side proxy for go2rtc WebRTC, Home Assistant actions, talkback locking and update discovery.
- `.github/workflows/release-android.yml` — signed APK publishing to GitHub Releases.

## Security model

No Home Assistant token, webhook URL, camera credential, signing key or Firebase configuration is committed. The repository intentionally excludes `.env`, `google-services.json`, keystores, APK/AAB files, build output and local Android configuration.

Public/local base URLs are configuration rather than secrets: they are necessarily embedded in a configured APK. Authentication and authorization must still be enforced by your reverse proxy/backend.

## Quick start

```bash
cp .env.example .env
# Edit .env for your installation.

cd server
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

See [Configuration](docs/CONFIGURATION.md) for every installation-specific value and [Releases](docs/RELEASES.md) for signing and automatic update setup.

## Update flow

When `GITHUB_RELEASE_REPO` is configured, the server's `/app-update/manifest.json` reads the latest public GitHub Release, selects its APK asset and returns the release version/download URL. The Android app only accepts release downloads from the configured repository (or the configured server's legacy `/app-update/latest.apk`).

The in-app download button appears only when the latest release tag is newer than the installed `versionName`.

## Home Assistant trigger

The current low-battery trigger path uses a discreet Home Assistant Companion notification with the configured channel/tag. The Android `NotificationListenerService` consumes it and opens a native incoming-call screen. Each installation must configure its own HA automation and grant the app notification-listener/full-screen access.

FCM is optional. To enable it, create your own Firebase Android app and add the untracked `android-app/android/app/google-services.json` before building.

## Important deployment notes

- Use HTTPS for both local and public origins.
- Keep Home Assistant webhook URLs server-side only.
- Do not expose go2rtc/Frigate administrative APIs directly to the internet.
- Configure public WebRTC candidates/TURN when off-LAN media is required.
- Use a stable signing key from the first distributed build; Android rejects in-place updates signed by another key.

## License

MIT
