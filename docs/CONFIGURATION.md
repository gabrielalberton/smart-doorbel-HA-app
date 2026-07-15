# Installation-specific configuration

Copy `.env.example` to the ignored `.env`. The distributed APK is endpoint-independent: on first launch the user supplies only the server's HTTPS endpoint and pairing password. The password is verified but never saved by the app.

## Values fixed in the universal APK

These are not secrets. Anyone can extract them from an APK, so never put passwords or tokens in them.

| Variable | Required | Purpose |
|---|---:|---|
| `ANDROID_APPLICATION_ID` | yes | Unique reverse-DNS app ID, e.g. `com.example.smartdoorbell`. Choose before distribution. |
| `APP_DEEP_LINK_SCHEME` | yes | URI scheme used by HA actions. Usually equal to the application ID. |
| `APP_NAME` | yes | Label shown by Android and Capacitor. |
| `ANDROID_VERSION_NAME` | yes | Human version used by the update comparison. Releases derive this from the `vX.Y.Z` tag. |
| `ANDROID_VERSION_CODE` | yes | Strictly increasing Android integer. |

These values define Android identity/signature only. They are not installation settings. A normal user installing the published APK does not fill them.

## Configuration returned during pairing

The server reads these values and returns the non-secret subset from `POST /api/pair` after password verification:

| Variable | Required | Purpose |
|---|---:|---|
| `PUBLIC_BASE_URL` | yes | Public HTTPS origin of this server/webapp. |
| `LOCAL_BASE_URL` | no | Local HTTPS origin tested first by the app. |
| `AUTH_HOST` | no | Authentication redirect host accepted inside the WebView. |
| `GITHUB_RELEASE_REPO` | recommended | Public `owner/repository` whose APK Releases are trusted. |
| `HOME_ASSISTANT_PACKAGE_PREFIX` | yes for HA trigger | Allowed HA Companion package prefix. |
| `DOORBELL_TRIGGER_TAG` | yes for HA trigger | Exact tag used by the discreet HA notification. |
| `DOORBELL_TRIGGER_CHANNEL` | yes for HA trigger | Exact Android notification channel used by HA. |
| `DOORBELL_NOTIFICATION_TITLE` | yes for HA trigger | Title fragment required before a notification becomes a call. |

The app stores this non-secret configuration in private app storage. It does **not** store the pairing password.

## Server and camera topology

| Variable | Required | Purpose |
|---|---:|---|
| `FRIGATE_URL` | yes | Internal Frigate URL used for go2rtc WebRTC proxying. |
| `GO2RTC_URL` | yes | Internal go2rtc API URL for optional live audio tests. |
| `PRIMARY_STREAM` | yes | Main go2rtc stream with receive video/audio and talkback support. |
| `PRIMARY_STREAM_OPTIONS` | yes | Comma-separated selectable go2rtc streams. |
| `SECONDARY_STREAM` | no | Optional second camera; leave blank to hide it. |
| `TALKBACK_TCP_STREAM` | no | Stream used by the TCP PCMU test. |
| `TALKBACK_UDP_STREAM` | no | Stream used by the UDP PCMU test. |
| `TALK_LOCK_TTL_MS` | no | Server-side exclusive microphone lock TTL. |
| `PORT` | no | Fastify listen port; default `8080`. |
| `GITHUB_RELEASE_ASSET_SUFFIX` | no | Asset suffix selected from latest release; default `.apk`. |

Camera usernames/passwords belong in Frigate/go2rtc configuration, **not** in this repository or APK.

## Secrets

| Variable/file | Required | Storage |
|---|---:|---|
| `HA_WEBHOOK_URL` | for gate button | Server `.env` or secret manager only. Never frontend/APK. |
| `ATTEND_WEBHOOK_URL` | optional | Server `.env` or secret manager only. |
| `PAIRING_PASSWORD_HASH` | yes | Scrypt hash of the first-run pairing password. Never store the plaintext password. |
| `android-app/android/app/google-services.json` | only for FCM | Download from your own Firebase project; ignored by Git. |
| Android signing keystore/passwords | for distributable updates | Local secure storage or GitHub Actions secrets. Never commit. |

Generate the pairing hash interactively so the plaintext does not enter shell history:

```bash
cd server
node scripts/hash-pairing-password.js
```

Copy only the resulting `scrypt:...` value into `PAIRING_PASSWORD_HASH` in the private server `.env`.

## Optional server-hosted updater

`APP_UPDATE_VERSION`, `APP_UPDATE_VERSION_CODE`, `APP_UPDATE_APK_PATH` and `APP_UPDATE_SHA256` support the legacy server-hosted APK path. When `GITHUB_RELEASE_REPO` is set, GitHub Releases takes precedence.

## Personalization audit

The public source contains no household-member names in runtime behavior. Installations differ through base URLs, app identity, HA trigger metadata, go2rtc stream names, webhooks, Firebase project and signing identity. No person-specific branching is required.
