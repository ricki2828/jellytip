# Releasing Jellytip (Android TV, Google Play)

Driven by `~/ai/scripts/app-release` (`ship jellytip`), or by hand in GitHub Actions. Package `xyz.taiaroa.jellytip`, flavour `appstore`.

| Trigger | Result |
|---|---|
| Actions > Jellytip build > Run workflow on `jellytip` / `jellytip-play`, `play_upload` ticked, pick `play_track` | build + signed AAB + Play upload |
| Push tag `vX.Y.Z` | same, track = repo variable `PLAY_TRACK` (default `internal`) |
| Any other run | APKs + signed AAB artifact `jellytip-release-aab`, no upload |

Without `PLAY_SERVICE_ACCOUNT_JSON` in the `release` Environment the upload job skips with a notice and stays green.

## Version numbers

`versionCode` = `jellytip.yml` `github.run_number` + repo variable `APP_VERSION_CODE_OFFSET` (default 1000), passed as `-PjellytipVersionCode`. Strictly increasing, independent of upstream Wholphin tags. If the workflow file is renamed, run_number restarts: raise the offset first.

## Signing check

The AAB signer SHA-256 is compared with repo variable `EXPECTED_UPLOAD_SHA256` (built-in default below) in the build job and again before upload. Mismatch fails the run.

Upload key SHA-256: `5E:C5:7F:ED:1C:6C:7F:1E:7C:F1:C0:6C:33:C3:E6:F8:F8:97:55:A0:6F:D5:72:BB:F5:EB:A7:5D:81:93:AB:9B`

## Setup

1. GitHub > ricki2828/jellytip > Settings > Environments > `release`: allow the release branch(es) and tag `v*`.
2. `gh secret set PLAY_SERVICE_ACCOUNT_JSON --env release -R ricki2828/jellytip < key.json`, then delete the file.
3. First upload of the app must be done by hand in Play Console.
4. Optional variables: `PLAY_TRACK`, `PLAY_RELEASE_STATUS` (default `draft` until first publish, then `completed`), `PLAY_USER_FRACTION`, `APP_VERSION_CODE_OFFSET`, `EXPECTED_UPLOAD_SHA256`.
5. Release notes: `store/play/release-notes/whatsnew-en-US` (max 500 characters).
