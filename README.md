# HackGpt

HackGpt is a lightweight Android client for online language models. It bundles the chat interface and uses Android's system WebView, with no separate runtime or native libraries in the APK.

[Download the signed Android APK](https://github.com/thearticle/HackGpt/releases/latest)

## Connect a provider

1. Open **Settings**, choose a preset or enter a custom OpenAI-compatible HTTPS base URL, then enter your API key.
2. Tap the **plug** to load the catalog. Check the models you want and tap the **disk** to save.
3. Open the **model picker** to choose a model. Saved models are grouped by provider.

Android encrypts saved provider credentials with a key held in Android Keystore. Chats and appearance preferences are stored on the device. Sending a message or testing a model contacts the selected provider. Images, PDFs, and UTF-8 files are supported where the provider and model accept them. Attachment contents are kept in memory for the current app session.

## Build

Install Android SDK 36 and use JDK 17 or newer. The app runs on Android 8.0 or later with an up-to-date Android System WebView.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

The release build produces an unsigned APK under `app/build/outputs/apk/release/`. Sign it with your Android release key before distributing it.

## Local browser preview

On Windows with Node.js 22, run `node preview/server.cjs` and open [localhost:8082](http://localhost:8082/). This preview shares the Android UI and uses Windows DPAPI to protect its own saved provider credentials.
