# Instructions for Codex, Claude and other coding agents

Read `README.md`, `docs/RELEASES.md` and `docs/CONFIGURATION.md` before touching releases, Android identity, authentication, media lifecycle or the NUC deployment. These documents describe the current workflow; verify time-sensitive paths, versions and network state against the machines before acting.

## Repository and live deployment are different trees

- GitHub `main` in `gabrielalberton/smart-doorbel-HA-app` is the public source. Check `git status`, remote and `origin/main` before editing or pulling. Preserve unrelated or uncommitted changes.
- The currently deployed NUC service uses `/home/gabriel/projects/campainha-interfone/` as its Docker build context, `/home/gabriel/Área de trabalho/compose-migration/15-campainha-interfone.yml` as its Compose file, and `/home/gabriel/projects/campainha-interfone-apk/dist/campainha-interfone-latest.apk` as its mounted fallback APK. Those live sources are **not** automatically updated by pushing this repository or creating a GitHub Release. Compare before copying; do not overwrite household-specific server or stream configuration with the generic repository implementation.
- The NUC's checkout of this repository may itself have uncommitted copies of source files. Do not run a destructive reset or assume it matches GitHub. Back up the live server, web UI, Compose and APK before promoting changes. Do not copy `.env`, pairing secrets, camera credentials or signing keys into Git or chat.
- The public root stays behind Authelia. Only the narrowly scoped update and pairing routes bypass it. Never open the full app or `?native_call=1` to anonymous public access to make updates or previews work.

## Release is a gated end-to-end operation

Follow the detailed, ordered procedure and rollback in `docs/RELEASES.md`. In particular:

1. Choose a new `X.Y.Z` and a `versionCode` greater than every distributed APK. Keep `.env.example`, `android-app/package.json`, `android-app/package-lock.json`, Android build metadata and release tag consistent. Do not change the application ID or signing certificate for an in-place update.
2. Build on the trusted NUC with the existing signing identity. Verify package ID, version name/code, signature against the installed/previous APK, APK checksum and that no secret is in the Git diff. The current household APK was signed with that NUC's Android debug keystore; a newly generated debug keystore will not update existing installs.
3. Push the validated source to `main`, tag that commit, then publish `smart-doorbell-X.Y.Z.apk` plus its `.sha256` file to GitHub Releases. Download the published asset again and compare bytes. The GitHub Actions workflow is manual-only; tagging does not build or publish anything.
4. Promote the web UI and any server changes to the **live** NUC build context, promote the verified APK to the mounted fallback path, and update `APP_UPDATE_VERSION`, `APP_UPDATE_VERSION_CODE` and `APP_UPDATE_SHA256` in the live Compose file. Validate Compose, rebuild only the campainha container and check it remains running without restart loops.
5. From outside the container, verify the public manifest announces `X.Y.Z`, `/app-update/latest.apk` serves the exact verified APK, and the protected root still redirects to Authelia. Confirm that a prior APK version displays **Baixar atualização X.Y.Z**. If no Android device is available, report that device installation and notification/audio behavior remain untested; do not claim them as validated.

Do **not** declare a release complete after `git push`, a tag or `gh release create`. The September 2026 incident happened because GitHub had 1.0.4 while the live NUC still served a 1.0.3 manifest, so the installed app correctly showed no update.

## Update mechanism and failure interpretation

The APK adds `app_version` to the web URL. `server/public/index.html` polls `/app-update/manifest.json` on opening and on foreground return, throttled to 15 minutes per loaded page; call preview mode does not poll. `server/server.js` caches the GitHub latest-release lookup for five minutes. It accepts only a newer three-part `vX.Y.Z` tag with the exact `smart-doorbell-X.Y.Z.apk` asset, a valid GitHub SHA-256 digest and size limit. A newer asset is proxied through the server's same-origin `/app-update/latest.apk` and checksum-verified before delivery. If GitHub has no newer usable release or lookup fails, the manifest advertises the configured local fallback APK/version. A stale fallback is observable and must be fixed during every release.

When the button is missing, inspect in order: installed `versionName`/`app_version` query, public manifest response, live Compose fallback values, latest GitHub Release asset/digest, live container image/restart state, then web UI caching and authentication. Check the actual device if available. A GitHub release page alone does not prove the app can discover or download it.

## Media and battery safety

The incoming-call preview may show video but must be silent until **Atender**. Backgrounding either Android activity must immediately stop talkback/microphone capture, both receiver connections and the wake lock; returning may reconnect the cameras, with the microphone off. Preserve the notification trigger, full-screen call and normal Authelia protection. Test preview silence, answer transition, background shutdown, foreground return and interrupted connection races before publishing.
