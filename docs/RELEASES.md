# GitHub Releases and in-app updates

## Feasibility

Yes. A public GitHub Release can be the canonical APK source:

1. A tag such as `v1.0.0` triggers `.github/workflows/release-android.yml`.
2. GitHub Actions builds an APK with installation-specific repository variables.
3. The APK is signed with a keystore stored in Actions secrets.
4. The workflow publishes the APK and SHA-256 file in the Release.
5. The backend reads `releases/latest`; the app displays its download button when the release tag is newer than its installed version.

No GitHub API token is needed for reading releases from a public repository. The backend caches the latest-release response for five minutes.

## Repository variables

Configure under **Settings → Secrets and variables → Actions → Variables**:

- `PUBLIC_BASE_URL`
- `LOCAL_BASE_URL`
- `AUTH_HOST` (optional)
- `ANDROID_APPLICATION_ID`
- `APP_DEEP_LINK_SCHEME`
- `APP_NAME`
- `ANDROID_VERSION_CODE_BASE` — use a number above every previously distributed build; the workflow adds `github.run_number`.

## Required Actions secrets

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create the base64 value locally without printing the private key:

```bash
base64 -w0 release.keystore > release.keystore.base64
# Paste the file content into ANDROID_KEYSTORE_BASE64, then delete the temporary file.
```

## Signature continuity

Android accepts an update only when the package ID and signing certificate match the installed app and `versionCode` increases.

If an existing APK was signed by a debug key, there are two choices:

- keep that exact keystore in Actions secrets for seamless updates; or
- switch to a new release key and require a one-time uninstall/reinstall. Future releases must then keep the new key forever.

Never upload a keystore as a Release asset or commit it.

## Publishing

Push a tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Or run **Actions → Release Android APK → Run workflow** with a `vX.Y.Z` tag. The tag/version must follow semantic versioning because the app compares numeric dot-separated versions.

## Rollback warning

Publishing an older tag does not downgrade Android. Release a new higher `versionName` and higher `versionCode` containing the rollback changes.
