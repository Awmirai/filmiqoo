# Preview against the existing server

The 0.5 RC blocked every launch because the deployed server returns HTTP 404 for
`GET /v1/access`. Deployment has not been authorized. The 0.5.1 preview therefore
supports an explicit compatibility mode while keeping production access strict.

- Set `FILMIQOO_LEGACY_SERVER_PREVIEW=true` when building **debug**. The preview
  workflow does this for both the APK and the device tests.
- Only a missing access endpoint (404, without a regional denial code) permits
  preview entry. Network failures, invalid proofs, other HTTP errors and explicit
  regional denials still block access. A denial from any other API also closes
  the gate and cannot be cleared by another legacy 404.
- The preview shows a notice that Iran restriction is inactive. It does not
  claim to verify the user's country. When the server supports the endpoint,
  the normal Iran-only policy applies automatically.
- Release builds always set `LEGACY_SERVER_PREVIEW=false`, regardless of the
  environment. Production still requires the backend/edge policy deployment.
- Compatibility previews use the `.preview` application ID suffix and launcher
  label `Filmiqoo Preview`. They install alongside the earlier RC, whose CI debug
  signing key is different. Existing app data is retained in the earlier app;
  sign in again in the preview. CI debug APKs are not production releases.

Device tests cover the old-server 404, strict access, valid Iran proof, explicit
denials, malformed responses, network/service failures and insecure URLs.
`PreviewLaunchTest` also launches the actual activity against the existing live
server and checks that login and registration screens can be reached. It does
not submit credentials or create an account.
