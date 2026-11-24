# Bondhu AI Native Android Project (`com.bondhuai.app`)

## Important Setup Before Building APK
1. Download `google-services.json` from Firebase Console for project **`startup-notify-8b116`** (Android package name: **`com.bondhuai.app`**).
2. Place it at: `app/google-services.json` (or add its contents as a GitHub Repository Secret named `GOOGLE_SERVICES_JSON`).
3. Keep `.github/workflows/build_apk.yml` at the **root** of your GitHub repository (if you upload the contents inside `android_app/` to the root of your repo, `.github/workflows/build_apk.yml` is already at the root!).
4. Run the **"Build Bondhu AI Android APK"** workflow in GitHub Actions to download `app-debug.apk`.
