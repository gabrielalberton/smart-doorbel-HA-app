# Releases, live deployment and in-app updates

This is the release runbook. Read `AGENTS.md` first. A public GitHub Release and a running production app are separate states: finish and verify both. Paths and the current version below describe 2026-09-24; confirm them live before the next release.

## Architecture: who checks what

```text
Android APK versionName/versionCode
  → MainActivity loads the chosen local/public web URL with ?app_version=X.Y.Z
  → server/public/index.html asks /app-update/manifest.json
  → server/server.js checks the latest public GitHub Release and local fallback
  → web UI shows Baixar atualização only if manifest.version > app_version
  → Android DownloadManager fetches same-origin /app-update/latest.apk
  → installer requires same application ID/certificate and higher versionCode
```

The APK does not poll GitHub. The web UI checks the manifest on page load and on foreground return, with a 15-minute minimum interval per loaded page; `native_call=1` call preview does not check. The server caches GitHub's latest-release lookup for five minutes. This is an on-open/foreground check, not a background push notification. To force a fresh visible check, fully close and reopen the app.

The server accepts a newer three-part `vX.Y.Z` tag only if the latest Release has `smart-doorbell-X.Y.Z.apk`, a valid `sha256:` digest, a trusted release URL and an acceptable size. For an accepted newer release, the manifest points to same-origin `/app-update/latest.apk`; the server downloads and checksum-verifies the asset before serving it. When GitHub has no newer usable release or its API fails, the local `APP_UPDATE_*` configuration and mounted APK are used. If a newer release is selected but its APK download or checksum fails, the download endpoint returns an error; fix that mismatch rather than claiming the release is available. Keep the fallback current and byte-verified. A GitHub release with no corresponding live server promotion can leave the app advertising an old version.

The public `/app-update/` route bypasses Authelia so Android's DownloadManager can download an APK. The web root remains protected. Never remove that protection to troubleshoot updates.

## Source and deployment locations

| Role | Path/location |
|---|---|
| Public source | `gabrielalberton/smart-doorbel-HA-app`, branch `main` |
| Trusted build host | NUC `gabriel-nuc`; verify current address and SSH host key |
| NUC checkout of this repository | `/home/gabriel/projects/smart-doorbel-HA-app/` (may have uncommitted changes; inspect) |
| Live Docker build context | `/home/gabriel/projects/campainha-interfone/` (separate from Git checkout) |
| Live Compose | `/home/gabriel/Área de trabalho/compose-migration/15-campainha-interfone.yml` |
| Mounted fallback APK | `/home/gabriel/projects/campainha-interfone-apk/dist/campainha-interfone-latest.apk` |
| Running container | `campainha_interfone` |
| Public app and update origin | `https://campainha.alberton.work` |
| Local credentials | `/etc/homelab/campainha-interfone.env`; never display or copy into the repository |

The live NUC app has installation-specific stream configuration and authentication routing. Compare its `server.js` and `public/index.html` with this repo before promoting a change. Copy only intended behavior; do not replace the live service wholesale with generic defaults. `git push`, a tag and a GitHub Release do not rebuild this container or update Compose variables. Never use `git reset --hard` or overwrite uncommitted NUC work.

## Preconditions for each release

1. Inspect `git status --short --branch`, `git remote -v`, `git fetch origin main`, the NUC checkout state and the current live Compose/container state. Preserve unrelated changes.
2. Select a new `X.Y.Z` above the latest Release. Increase `ANDROID_VERSION_CODE` above the installed APK's `versionCode`. Update `.env.example`, `android-app/package.json` and the root version in `android-app/package-lock.json` consistently. The Android build reads version/name/code and application ID from `.env` or environment variables; confirm the built APK rather than inferring from source.
3. Confirm Android application ID and signing certificate continuity. The 1.0.3 and 1.0.4 household APKs used `work.alberton.campainha` and the same NUC Android debug keystore certificate. Another debug keystore, package ID or a non-increasing versionCode makes Android reject an in-place update. Never commit or upload the keystore.
4. For media changes, verify call preview is muted before **Atender**, backgrounding immediately stops microphone plus both receive streams, and foreground return does not reopen the microphone. Use a real Android device when available; a browser simulation and successful compile cannot prove Android lifecycle behavior.
5. Run focused tests (`node --check` for changed JavaScript, server tests, Android Gradle build, `git diff --check`) and inspect the exact diff for secrets and unrelated changes.

## Build and publish on the trusted NUC

The GitHub Actions workflow is manual-only. Pushing a tag does not trigger a build or publish. This installation builds on the NUC so the existing signing identity stays there. An optional GitHub-hosted build requires the same signing certificate in Actions secrets and explicit owner approval.

Build with NUC-local JDK 21 and Android SDK. Paths can change; verify them. This example shows required inputs, not a command to run blindly. Do not paste secrets into shell history or logs.

```bash
cd /home/gabriel/projects/smart-doorbel-HA-app/android-app
ANDROID_APPLICATION_ID=work.alberton.campainha \
APP_DEEP_LINK_SCHEME=work.alberton.campainha APP_NAME=Campainha npm run sync

cd android
JAVA_HOME=/home/gabriel/.local/jdks/temurin-21.0.11 \
ANDROID_HOME=/home/gabriel/android-sdk \
ANDROID_APPLICATION_ID=work.alberton.campainha \
APP_DEEP_LINK_SCHEME=work.alberton.campainha APP_NAME=Campainha \
./gradlew --no-daemon :app:assembleDebug --console=plain
```

Inspect the APK with Android build tools (`aapt dump badging`, `apksigner verify --print-certs`) and compare its certificate with the currently distributed APK. Compute SHA-256 and verify ZIP/APK structure. Keep package ID and signing identity stable; `versionCode` must rise. Copy the verified APK to release staging as `smart-doorbell-X.Y.Z.apk` and create a matching `.sha256` file. Verify `sha256sum -c`.

Commit and push the reviewed source to `main`; tag that same commit `vX.Y.Z`, push the tag, then create the Release with the verified APK and checksum. Download the GitHub asset again and compare byte-for-byte with the built APK. You may stage the Release as a draft while promoting the NUC, but draft releases are not returned by GitHub's `releases/latest` API; publish it and run public checks before declaring the update discoverable. A successful `gh release create` alone does not complete the task.

## Promote to the live NUC

Before changing production, back up live `server.js`, `public/index.html`, Compose and mounted APK under a dated, private NUC backup directory. Verify checksums and record a rollback path. The 1.0.4 backup is under `/home/gabriel/homelab_backups/campainha/2026-09-24-v1.0.4/`.

Merge intended server/web UI changes into `/home/gabriel/projects/campainha-interfone/`, preserving its stream names, pairing and household-specific behavior. Copy the verified APK to the mounted fallback path. Set these in live Compose to the actual built artifact:

```text
APP_UPDATE_VERSION=X.Y.Z
APP_UPDATE_VERSION_CODE=<built APK versionCode>
APP_UPDATE_APK_PATH=/apk-dist/campainha-interfone-latest.apk
APP_UPDATE_SHA256=<built APK SHA-256>
GITHUB_RELEASE_REPO=gabrielalberton/smart-doorbel-HA-app
```

Validate Compose, then rebuild only the doorbell service:

```bash
cd '/home/gabriel/Área de trabalho/compose-migration'
docker compose -f 15-campainha-interfone.yml config --quiet
docker compose -f 15-campainha-interfone.yml up -d --build campainha-interfone
```

Confirm container process stability and recent logs. Do not treat `Up` or a single HTTP response as complete validation.

## Mandatory end-to-end release gates

Perform these against the public origin after the live container is recreated:

1. `GET https://campainha.alberton.work/app-update/manifest.json` returns intended `version: X.Y.Z` and built APK SHA-256. It must not still announce the old version. The URL is public without Authelia. With the local fallback at the same version as the Release, the manifest may omit `source: github-releases`; do not mistake that for a failed deployment. Verify the GitHub asset and public APK bytes separately.
2. `GET https://campainha.alberton.work/app-update/latest.apk` returns HTTP 200 and an APK whose SHA-256 matches both the local build and published GitHub asset. Check content type and size. HEAD alone does not verify bytes.
3. `HEAD https://campainha.alberton.work/` still redirects to `auth.alberton.work`; update stays narrowly exempt.
4. Confirm a WebView loaded as `/?app_version=<previous version>` displays **Baixar atualização X.Y.Z** in settings. Test the phone when available. The UI throttles checks for 15 minutes within one page; fully restart it for a fresh test.
5. Confirm Android accepts the APK as an update on a real device when available. For media releases, check preview silence, answer audio and immediate background shutdown of microphone/streams.

Only report what was actually verified. If no phone is available, say device installation and live audio/battery behavior remain unverified. Do not claim users received an update because a GitHub page exists.

## If the update button is missing

Check in order: installed APK `versionName` and WebView `app_version` query; public manifest response and whether it is newer; NUC Compose fallback values and mounted APK checksum; GitHub latest Release asset/digest; container image, startup time and logs; then page/session cache and actual device. The APK does not poll GitHub directly. In September 2026 GitHub had 1.0.4 while the live NUC still returned 1.0.3, so the app correctly hid the button.

## Rollback

Restore backed-up live server/web UI, Compose and mounted APK; validate Compose, rebuild only the campainha container, then recheck process health, protected root and public update endpoints. Removing or replacing a GitHub Release is separate: installed Android apps cannot be downgraded by advertising an older APK. To undo an installed bad release, publish a new higher `versionName` and `versionCode` containing reverted behavior. Keep signing identity unchanged.

## Optional GitHub Actions path

The workflow at `.github/workflows/release-android.yml` runs only through **Actions → Release Android APK → Run workflow**. To use it, configure `ANDROID_APPLICATION_ID`, `APP_DEEP_LINK_SCHEME`, `APP_NAME` and an `ANDROID_VERSION_CODE_BASE` above all distributed builds as Actions variables. It also requires `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` as Actions secrets. The keystore must be the existing distribution key; otherwise Android will reject an in-place update. Do not add these secrets or run this workflow merely because a tag was pushed. The trusted NUC path above is the active deployment method.
