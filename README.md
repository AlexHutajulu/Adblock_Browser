<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/acafb031-d55e-4c63-b286-1ad6d1d60b95

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. (Optional) If using AI features, configure secrets via the **Secrets panel in AI Studio** (mapped to `.env` / `.env.example`).
5. Run the `debug` build variant on an emulator or physical device (Android Gradle Plugin automatically manages the local development debug keystore).

## Release Signing & Play App Signing

- **Upload Key vs. App Signing Key:** Local or CI release builds (`assembleRelease` / `bundleRelease`) are signed using your **upload key** (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` environment variables). When distributing via Google Play, **Play App Signing** verifies the upload signature and re-signs the final distribution APK/AAB with the production **app-signing key** managed by Google Play.
- Never commit `.jks` / `.keystore` files or signing passwords to version control.
