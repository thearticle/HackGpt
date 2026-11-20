<div align="center">

<img src="docs/hackgpt-logo.png" width="112" height="112" alt="HackGpt lime wireframe logo">

# HackGpt

**A lightweight Android chat client for your own AI providers.**

[![Download APK](https://img.shields.io/badge/Download-Signed%20APK-BAFF56?style=for-the-badge&logo=android&logoColor=111a0f)](https://github.com/thearticle/HackGpt/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-315c16?style=for-the-badge)](#getting-started)
[![Java 17](https://img.shields.io/badge/Build-Java%2017-273820?style=for-the-badge)](#build-from-source)

[Features](#features) · [Getting started](#getting-started) · [Visual replies](#visual-replies) · [Build](#build-from-source) · [Credits](#credits)

</div>

---

## Overview

HackGpt brings several online model providers into one Android app. Connect with your own API keys, choose the models you want in the picker, and chat in a lime-green terminal interface. The APK bundles the interface and uses Android System WebView, keeping the download small.

## Features

| Feature | What you can do |
| --- | --- |
| **Multiple providers** | Use the OpenRouter, OpenAI, or Gemini presets, or connect to a custom OpenAI-compatible HTTPS endpoint. Browse and save models by provider. |
| **Streaming replies** | Read answers as they arrive and stop a response in progress. See response time, token counts, throughput, and reasoning summaries when the provider reports them. |
| **Rich formatting** | Read headings, lists, emphasis, code blocks, quotes, and scrollable tables directly in chat. |
| **Attachments** | Pick images, PDFs, and UTF-8 files such as TXT, CSV, or JSON. Choose a model that supports the selected file type. |
| **Searchable history** | Search titles, messages, and attachment names. Swipe right on a chat to rename it or left to delete it. |
| **Appearance** | Switch between Dark and Light, adjust chat font size, or enable Full screen to reclaim the Android status bar area. |

## Getting started

1. Download the signed APK and install it on Android 8.0 or later. An up-to-date Android System WebView is recommended. [GitHub Releases](https://github.com/thearticle/HackGpt/releases/latest)
2. Open **Settings**. Choose a preset or enter a custom HTTPS API base URL, then enter the API key for that provider.
3. Tap the **plug** to load models. Check the models you want to use, then tap the **disk** to save the provider.
4. Open **Select model** in the header and pick a model to start chatting.

The **play** icon in Settings sends a test request to each checked model. Your provider may charge for those requests.

## Visual replies

The next Android build adds bar charts, mind maps, flowcharts, org charts, and sequence diagrams to model replies. Ask in plain language. HackGpt adds format guidance when it recognizes a request for a visual.

| To see a… | Try asking |
| --- | --- |
| Bar chart | “Draw a bar chart comparing 12 orders on Monday, 18 on Tuesday, and 15 on Wednesday.” |
| Mind map | “Make a mind map for planning a weekend in Bandung.” |
| Flowchart | “Draw a flowchart for resetting a forgotten password.” |
| Org chart | “Create an org chart for a small product team.” |
| Sequence diagram | “Show a sequence diagram for a checkout request between the customer, shop, and payment provider.” |

## Storage and connectivity

The Android app saves credentials using Android Keystore-backed encryption. Chat history and appearance preferences are stored on your device. Attachment names stay in history, and file contents remain available for the current app session.

Sending messages or running a model test sends the relevant inputs to your selected provider. Model capabilities and token reporting depend on that provider.

## Build from source

Use **JDK 17 or newer** and install **Android SDK 36**.

```powershell
git clone https://github.com/thearticle/HackGpt.git
cd HackGpt
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

Gradle writes APKs to `app/build/outputs/apk/`. Sign the release APK with your release key before distribution.

| Path | Role |
| --- | --- |
| `app/src/main/` | Android activity, file picker, provider networking, and credential storage |
| `preview/index.html` | Shared chat interface and visual reply rendering |
| `preview/server.cjs` | Local browser preview and provider request routing |

### Browser preview

On Windows with Node.js 22, run `node preview/server.cjs`. The preview uses its own Windows DPAPI credential store. [Open the local preview](http://localhost:8082/)

## Credits

Created by **Fauzan Afianto**. In the app, hold **+** for five seconds to reveal the credits screen. [thearticle on GitHub](https://github.com/thearticle)
