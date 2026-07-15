# GitHub Releases and in-app updates

## Feasibility

Yes. A public GitHub Release can be the canonical APK source:

1. A tag such as `v1.0.0` triggers `.github/workflows/release-android.yml`.
2. GitHub Actions builds one endpoint-independent APK.
3. The APK is signed with a keystore stored in Actions secrets.
4. The workflow publishes the APK and SHA-256 file in the Release.
5. The backend reads `releases/latest`; the app displays its download button when the release tag is newer than its installed version.

No GitHub API token is needed for reading releases from a public repository. The backend caches the latest-release response for five minutes.

## Preferred release path: build on the trusted NUC

This deployment intentionally keeps the signing keystore off GitHub. The preferred flow is:

1. build and sign on the always-on NUC with the existing keystore;
2. verify package ID, increasing `versionCode`, certificate, APK magic and SHA-256 locally;
3. create the Git tag and push it;
4. publish the verified APK and checksum with `gh release create`;
5. download the GitHub asset again and compare it byte-for-byte before offering it through the update endpoint.

The GitHub Actions workflow is optional and manual-only. It will not run when a tag is pushed, and signing secrets should not be configured unless the deployment owner explicitly changes this policy.

## Optional GitHub Actions variables

Configure under **Settings → Secrets and variables → Actions → Variables**:

- `ANDROID_APPLICATION_ID`
- `APP_DEEP_LINK_SCHEME`
- `APP_NAME`
- `ANDROID_VERSION_CODE_BASE` — use a number above every previously distributed build; the workflow adds `github.run_number`.

Base URLs, authentication host, Home Assistant trigger metadata and stream names belong to each server's private `.env` and are downloaded during first-run pairing. They are not GitHub build variables.

## Optional GitHub Actions secrets

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
