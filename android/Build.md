# Build and Install Guide

## Prerequisites

| Tool | Version | Download |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) or later | https://developer.android.com/studio |
| JDK | 17 (bundled with Android Studio) | — |
| Android SDK | API 36 (installed via SDK Manager) | via Android Studio |

Android Studio includes its own JDK and Gradle wrapper — no separate installation needed.

---

## 1. Open the Project

1. Launch Android Studio.
2. **File → Open** → select the `android-app` folder (the one containing `settings.gradle.kts`).
3. Wait for Gradle sync to complete (first sync downloads dependencies; requires internet access).

If sync fails with a "Could not resolve" error, check **File → Settings → Appearance & Behavior → System Settings → HTTP Proxy** matches your network.

---

## 2. Install the Required SDK

If the SDK Manager reports API 36 is missing:

1. **Tools → SDK Manager → SDK Platforms** tab.
2. Tick **Android 16 ("Baklava") API 36** → **Apply**.

---

## 3. Build

### Option A — Android Studio (simplest)

Select **Build → Make Project** (Ctrl+F9 on Windows).
A successful build produces no red errors in the **Build** output pane.

### Option B — Command line (Gradle wrapper)

Open a terminal in the project root:

```bat
gradlew.bat assembleDebug
```

The APK is written to:
```
app\build\outputs\apk\debug\app-debug.apk
```

For a release build (requires a signing keystore — see §5):
```bat
gradlew.bat assembleRelease
```

---

## 4. Install on the Phone

### Option A — Run directly from Android Studio (easiest)

1. Enable **Developer Options** on your phone:
   Settings → About phone → tap **Build number** 7 times.
2. Enable **USB debugging** in Developer Options.
3. Connect the phone via USB. Accept the "Allow USB debugging?" prompt on the phone.
4. In Android Studio, select your device from the target dropdown (top toolbar).
5. Click **Run ▶** (Shift+F10).
   Android Studio builds, installs, and launches the app automatically.

### Option B — Side-load the APK (no cable after the first install)

Use this if you want to install a pre-built APK without keeping Android Studio connected.

**On the phone:**

1. Enable **Install unknown apps** for your file manager or browser:
   Settings → Apps → (your file manager) → Install unknown apps → Allow.

**Transfer the APK:**

- Connect the phone via USB and copy `app-debug.apk` to the phone's **Downloads** folder, **or**
- Share the APK from your PC using Bluetooth or a cloud service (Google Drive, etc.).

**Install:**

1. Open the **Files** app (or any file manager) on the phone.
2. Navigate to **Downloads**, tap `app-debug.apk`.
3. Tap **Install** and accept the prompt.

---

## 5. Release Signing (optional)

A debug build is sufficient for personal use. If you want a release build:

1. **Build → Generate Signed Bundle / APK → APK → Next**.
2. Create a new keystore (or use an existing one) and fill in the alias and passwords.
3. Select **release** build variant → **Finish**.
4. The signed APK is written to `app\release\app-release.apk`.

Store the keystore file and passwords safely — you need the same keystore for every future update.

---

## 6. Required Phone Settings After Install

Perform these steps once after installing:

1. **Grant Bluetooth permissions** — the app will prompt on first launch; tap **Allow** for all Bluetooth requests.
2. **Enable Developer Options** — Settings → About phone → tap Build number 7 times.
3. **Enable Bluetooth HCI snoop socket** — Settings → Developer Options → Networking → **Enable Bluetooth HCI snoop socket** → On.
   *(Android 15 and below: enable "Bluetooth HCI snoop log" instead and note the file path.)*
4. **Grant "All files" access** (Android 11+ fallback mode only) — Settings → Apps → Pioneer Media Bridge → Permissions → Files and media → **Allow management of all files**.
5. Open the app → **⋮ → Settings** and enter your Pioneer radio and ESP32 identifiers.
6. Toggle **Enable monitoring service** on the main screen.

The service persists across reboots once enabled.
