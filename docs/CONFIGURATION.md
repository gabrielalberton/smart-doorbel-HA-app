# Installation-specific configuration

Copy `.env.example` to the ignored `.env`. Values are divided into **embedded public configuration**, **server configuration**, and **secrets**.

## Values embedded in the APK

These are not secrets. Anyone can extract them from an APK, so never put passwords or tokens in them.

| Variable | Required | Purpose |
|---|---:|---|
| `ANDROID_APPLICATION_ID` | yes | Unique reverse-DNS app ID, e.g. `com.example.smartdoorbell`. Choose before distribution. |
| `APP_DEEP_LINK_SCHEME` | yes | URI scheme used by HA actions. Usually equal to the application ID. |
| `APP_NAME` | yes | Label shown by Android and Capacitor. |
| `ANDROID_VERSION_NAME` | yes | Human version used by the update comparison. Releases derive this from the `vX.Y.Z` tag. |
| `ANDROID_VERSION_CODE` | yes | Strictly increasing Android integer. |
| `PUBLIC_BASE_URL` | yes | Public HTTPS origin of this server/webapp. |
| `LOCAL_BASE_URL` | yes | Local HTTPS origin tested first by the app. |
| `AUTH_HOST` | no | Authentication host allowed inside the WebView, if redirects use one. |
| `GITHUB_RELEASE_REPO` | recommended | Public `owner/repository` whose APK Releases are trusted. |
| `HOME_ASSISTANT_PACKAGE_PREFIX` | yes for HA trigger | Allowed HA Companion package prefix. |
| `DOORBELL_TRIGGER_TAG` | yes for HA trigger | Exact tag used by the discreet HA notification. |
| `DOORBELL_TRIGGER_CHANNEL` | yes for HA trigger | Exact Android notification channel used by HA. |
| `DOORBELL_NOTIFICATION_TITLE` | yes for HA trigger | Title fragment required before a notification becomes a call. |

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
| `android-app/android/app/google-services.json` | only for FCM | Download from your own Firebase project; ignored by Git. |
| Android signing keystore/passwords | for distributable updates | Local secure storage or GitHub Actions secrets. Never commit. |

## Optional server-hosted updater

`APP_UPDATE_VERSION`, `APP_UPDATE_VERSION_CODE`, `APP_UPDATE_APK_PATH` and `APP_UPDATE_SHA256` support the legacy server-hosted APK path. When `GITHUB_RELEASE_REPO` is set, GitHub Releases takes precedence.

## Personalization audit

The public source contains no household-member names in runtime behavior. Installations differ through base URLs, app identity, HA trigger metadata, go2rtc stream names, webhooks, Firebase project and signing identity. No person-specific branching is required.
