# Digital Control+ (`gen5w.digitalcontrol`) — v1.1.0

> [!NOTE]
> **AI-Generated Project Notice:** This entire project — including the Gen5W Android 4.4.2 head-unit application, root Binder/CAN service daemon, Android & iOS companion apps, cryptographic protocol, packaging scripts, and documentation — was **built with AI (Google Antigravity)**. Please review the source code and test carefully on your vehicle.

> [!CAUTION]
> **USE ENTIRELY AT YOUR OWN RISK — NO WARRANTY:**
> - **Digital Control+** interacts directly with internal vehicle infotainment Binder services (`IBCMService`, `IDATCService`, `IHEVService`, `IVRService`, `ITM_HSCANService`, `IUSMService`, `IClusterService`) on **NEMESIS (`xwtk/hmg_nemesis`)** equipped Hyundai/Kia/Genesis Wide **Gen5W** (`1920×720`, Android 4.4.2 / API 19) head units.
> - **Key Fob Lockout & Moving Parts Warning:** Remote/phone door lock commands **do not check whether your physical key fob is inside the cabin**, and remote window / power tailgate commands physically move glass and motorized hatches. Always keep your key fob or mechanical key blade on your person and verify clear surroundings before actuating closures.
> - **12V & HV Battery Drain Warning:** Engaging **Utility Mode**, **Bluelink Remote Climate (`IG3`)**, or **HV Battery Preconditioning** wakes vehicle ECUs and high-voltage thermal systems. Monitor vehicle state and battery charge appropriately.
> - Provided **"AS IS"**, without warranty of any kind. Neither the author(s) nor contributors are affiliated with Hyundai Motor Group, Kia, or Genesis, nor liable for any vehicle lockouts, battery drain, damage, or malfunctions. Tested on a **'22 Hyundai Ioniq 5 Limited** (`NEEV.USA.S5W_M`).

---

## Overview

**Digital Control+** is a **100% standalone NEMESIS package** — **no HMG-API installation required** (works whether HMG-API is installed, disabled, or uninstalled). It brings local encrypted **Bluetooth** companion phone controls, custom one-tap **AC Profiles**, **Ignition-Off Bluelink Remote Climate & Seat/Wheel Heating**, **EV Battery Preconditioning**, live vehicle & charging telemetry, and optional **Zero-Trust End-to-End Encrypted (E2EE) Wi-Fi Relay** control to Gen5W vehicles — with **zero paid subscriptions** and **no factory Digital Key hardware required**.

Out of the box, **Digital Control+** runs in **100% local Bluetooth-only mode** (`DEFAULT_REMOTE_URL = ""`) with zero internet traffic. If you want remote Wi-Fi control when out of Bluetooth range (for example, while your car is parked on home/driveway Wi-Fi or a hotspot), you can connect it to your own self-hosted instance of [**Digital Connect+ Server**](https://github.com/DVWburner/DigitalConnectPlus-Server).

---

## Release Artifacts (`v1.1.0`)

| Platform | File | Installation |
|---|---|---|
| **Gen5W Head Unit (USB Stick)** *(Android 4.4.2)* | `gen5w-digital-control-1.1.0-nemesis.zip` | Extract to the root of a FAT32 USB drive and plug into a **NEMESIS** (`xwtk/hmg_nemesis`) equipped Gen5W head unit (or install `gen5w-digital-control-1.1.0-nemesis-package.zip` in NEMESIS Manager). **No HMG-API required.** |
| **Android Companion App** *(Android 7.0+)* | `DigitalControlPlus-Phone-1.1.0.apk` | Install directly on your Android phone (`v1` + `v2` signed). |
| **iOS Companion App** *(iOS 16.0+)* | `DigitalControlPlus-1.1.0.ipa` | Unsigned Release `.ipa` — sideload onto your iPhone using **AltStore**, **SideStore**, or **Sideloadly**. |
| **Self-Hosted E2EE Relay Server** *(Optional)* | [`DigitalConnectPlus-Server`](https://github.com/DVWburner/DigitalConnectPlus-Server) | Optional Windows Service + System Tray `.NET 10` Zero-Trust E2EE relay server for out-of-range Wi-Fi commands. |

---

## Core Features

### 1. Standalone Root Service (`svcmgr`) & Automatic Trust Wipe on Removal
- Runs directly under **NEMESIS `svcmgr`** (`gen5w.digitalcontrol/digital-control-svc`, `uid=root`, `mode=restart`) using `/system/bin/app_process` — completely independent of HMG-API, and automatically migrates existing `v1.0.0` installs in place without wiping your PIN or paired phones.
- On first launch in the car, **Digital Control+** prompts you to create a **4 to 8 digit Owner Security PIN** (`PBKDF2-HMAC-SHA256`, 20,000 iterations) before any phone can pair.
- In-car controls remain visible when locked and automatically present the tactile PIN pad whenever tapped.
- Removing **Digital Control+** in NEMESIS Manager automatically runs `pm uninstall`, wiping all phone trust relationships, resetting the PIN, and rotating the car's cryptographic identity.

### 2. MITM-Resistant Bluetooth Pairing (Android RFCOMM/BLE + iPhone BLE)
- Supports **Android** (Bluetooth Classic RFCOMM + BLE GATT fallback) and **iPhone** (`CoreBluetooth` BLE GATT Peripheral ↔ Gen5W BLE Central with 20-byte KitKat MTU packet reassembly).
- Derives a **4-character visual SAS confirmation code** displayed simultaneously on the `1920×720` car screen and your phone — requiring confirmation on **both** screens before issuing a car-signed `EC P-256` `PairingGrant`.

### 3. Ignition-On & Ignition-Off Cabin Climate + One-Tap AC Profiles
- **Custom AC Profiles** on the Home tab let you combine target cabin temperature, blower/auto mode, A/C, heat, defrost, heated steering wheel, and front seat heating/cooling (`Off / 1 / 2 / 3`) into a single tap, always syncing Driver & Passenger zones.
- **Full E-GMP Ignition-Off Remote Climate Protocol:** When ignition is OFF (`VCU_RDY != 1`), **Digital Control+** automatically wakes `IG3` and drives Hyundai's native telematics HSCAN + BCM profile frames (`ITM_HSCANService.requestImmediatelyAirCon` + `IBCMService.requestHeatingSeatProfileCmd`) to set your exact target temperature (`62–82°F` / `17–27°C`), Front Defrost, Rear Defogger/Mirrors, Steering Wheel Heat, and Front Seat Heat/Ventilation *(note: E-GMP hardware locks blower fan speed to `AUTO 1` while `IGN1` is off)*, and cleanly tears down climate + `IG3` when turned off.

### 4. EV Battery Preconditioning, Utility Mode, & Body Controls
- **HV Battery Preconditioning:** Direct `IHEVService` control + live pack temperature (`°F` & `°C`) + active heater status indicator (works even while in Utility Mode).
- **Programmatic Utility Mode:** Engage Utility Mode directly from the head unit or phone when vehicle conditions are met.
- **Doors, Hatch, Windows, & Charge Port:** Direct `IBCMService` / `IVRService` / `IUSMService` controls for Lock/Unlock doors, Open/Close power tailgate, Open/Close EV charge port, and power windows (**Vent 25%**, **All 50%**, **All Up**, **All Down**, and individual front windows).
- **Live Vehicle Sensors:** Last confirmation timestamp, Door Lock & Ajar status, Rear Hatch & Charge Door state, 4-corner **TPMS Tire Status**, HV SoC % (`0.5%` display resolution), HV SOH, Odometer, Estimated Range, Charging Power (`kW`) & Time Remaining, 12V Aux Battery %, and Outside Temperature.

### 5. Optional Zero-Trust E2EE Wi-Fi Relay & Command Activity Log
- Enter your self-hosted server domain (e.g., `remote.example.com` — `https://` is assumed automatically) on either the phone or car.
- **100% Blind E2EE Relay:** Commands are encrypted on your phone to the car's `EC P-256` public key and signed by your phone's `EC P-256` private key (`SHA256withECDSA`). Even the relay server cannot read or forge commands.
- **Live Check-In Presence & Activity Log:** Tracks the car's last Wi-Fi check-in (warning you concisely if the car appears offline while still letting you queue commands with a 5-minute TTL) and displays a clean log of **`<Command> sent`**, **`<Command> executed`**, or **`<Command> timed out`**.

### 6. Cross-Platform Security Key Backup (`.g5wkey` + Cloud OS Backup)
- Supports automatic **Google App Backup** (Android) and **Apple iPhone / iCloud Keychain Backup** (iOS), plus local passphrase-encrypted **`.g5wkey`** files (`PBKDF2-HMAC-SHA256` 100,000 iterations + `AES-256-CBC` + `HMAC-SHA256`) transferable between Android and iPhone.

---

## Building from Source

### Android APK + Standalone Gen5W NEMESIS Package + Host Test Suite (Windows PowerShell)
Requires JDK 11+, Android SDK Platform 28 + Build-Tools `28.0.3`, and stock Gen5W `framework-res.apk` (**no HMG-API checkout required**):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\build.ps1"
```

Outputs are written to `dist/` and verified against the 11-suite `DigitalControlHostTest` cryptographic, JSON, & protocol test harness, NEMESIS descriptor/toolbox script checks, and zero-HMG-API `classes.dex` assertions.

### iOS Companion App (`.ipa`)
Generated via [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`phone-ios/project.yml`) on macOS or automatically via [`.github/workflows/build-ios-ipa.yml`](.github/workflows/build-ios-ipa.yml):

```bash
cd phone-ios
xcodegen generate
xcodebuild clean build \
  -project Gen5WDigitalControl.xcodeproj \
  -scheme Gen5WDigitalControl \
  -configuration Release \
  -sdk iphoneos \
  CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO
```

---

## License

Released under the [MIT License](LICENSE). Provided **"AS IS"** without warranty of any kind.
