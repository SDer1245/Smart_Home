# Smart Home BT

Native Android application (Kotlin + Jetpack Compose / Material 3) for controlling four ESP32 relay channels over Bluetooth Classic SPP.

## Build the APK online (no Android Studio required)

This project includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

1. Sign in to GitHub and create a **new empty repository** (for example, `SmartHomeBT`).
2. Extract this ZIP on your computer.
3. Upload the **contents** of the extracted `SmartHomeBT` folder to the repository root. Make sure the `.github/workflows/build-apk.yml` file is included. GitHub's web uploader may hide dot-folders; if it does, use GitHub Desktop or upload the project using Git.
4. Open the repository's **Actions** tab.
5. Select **Build Smart Home BT APK** and click **Run workflow** (or wait for the first push build to finish).
6. Open the completed workflow run. Under **Artifacts**, download `SmartHomeBT-debug-apk`.
7. Extract that artifact ZIP. It contains `app-debug.apk`.
8. Transfer `app-debug.apk` to your Android phone and install it. If Android asks, allow installation from that browser/file manager.

The GitHub build creates a real debug APK. It is not produced by converting a ZIP with a file converter.

## Requirements
- Android 8.0 / API 26 or newer
- ESP32 firmware implementing Bluetooth Classic SPP and receiving single-byte commands:
  - `A` = Light 1
  - `B` = Light 2
  - `C` = Fan
  - `D` = Socket

## ESP32 pairing and connection
1. Flash ESP32 firmware with Bluetooth device name `HomeAutomation` and SPP service UUID `00001101-0000-1000-8000-00805F9B34FB`.
2. Pair `HomeAutomation` from Android Settings > Bluetooth.
3. Open Smart Home BT, grant Bluetooth permissions, then select the device from the paired list.
4. Once connected, the dashboard sends one ASCII character per tap.

## Technical notes
- Android 12+ uses `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` runtime permissions.
- Android 8–11 uses fine location permission for Bluetooth discovery.
- The app manifest intentionally does not request INTERNET, Wi-Fi, or account permissions.
- The dashboard assumes commands are accepted when written to the Bluetooth socket. It does not read relay state back from the ESP32, so UI state is optimistic and resets when the app process restarts.
- Automatic reconnect is attempted on startup. A foreground connection service would be recommended for a production release.

## Safety
This project sends control commands only. Ensure relay contacts, isolation, fusing, enclosure, and mains wiring are designed and installed safely. Do not work on live mains wiring.
