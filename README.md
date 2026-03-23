# 🪙 NFC Coins

<p align="center">
  <strong>An Android NFC wallet for Mifare Classic cards — secure, fast, and field-ready.</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/nfc-coins/actions/workflows/build.yml">
    <img src="https://github.com/duhow/nfc-coins/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/NFC-Mifare%20Classic-blue?logo=nfc" alt="NFC Mifare Classic">
  <img src="https://img.shields.io/badge/License-GPL--3.0-orange" alt="License">
</p>

---

**NFC Coins** is a point-of-sale Android application that reads, writes, and manages coin balances stored on Mifare Classic NFC cards. It is designed for venues, events, or any environment where physical tokens need a digital equivalent — tap a card to pay, top it up, or issue new ones, all with cryptographic security.

---

## ✨ Features

### 💳 Card Operations
- **Read balance** from Mifare Classic NFC cards with a single tap
- **Deduct coins** instantly with one-tap preset buttons (−1 or −2) — button stays active for rapid back-to-back transactions
- **Custom deduction** — tap the balance display and type any amount via the on-screen keyboard
- **Add balance** to any card via the card management menu
- **Format new cards** — initialise blank or factory cards with a secure per-card key and an optional starting balance
- **Reset cards** — wipe a card back to factory defaults (restores the default key)

### 🔒 Security & Integrity
- **Dynamic key derivation** — each card gets a unique authentication key computed as `HMAC-SHA1(PSK, UID)`, so stealing one card's key reveals nothing about others
- **Static key fallback** — optionally use a shared hex or passphrase key for simpler deployments
- **Transaction checksums** — every transaction block is protected by `HMAC-SHA256(PSK + UID, counterBlock + txPayload)`, binding the history to this specific card and PSK
- **Tamper detection** — mismatched checksums trigger an immediate "card tampered" warning
- **Interrupted write recovery** — if a card is removed mid-write, the app detects the inconsistency on the next tap and automatically retries the write before proceeding
- **Single-recharge mode** — optionally lock a card so it can only be topped up once (enforced via Mifare sector trailer access bits)

### 🧒 Age Verification
- Birth year is encoded in the sector trailer GPB byte (`ageByte = birthYear − 1900`)
- A minor indicator (🧒) is displayed whenever the card holder's calculated age is below the configurable legal-age threshold

### 📜 Transaction History
- Up to **4 recent transactions** are stored directly on the card (32 bytes = 2 Mifare blocks)
- Each entry records: timestamp offset, operation (ADD / SUBTRACT), and amount
- Fully visible in the app's main screen without any backend

### 🎨 UI & Customisation
- **Portrait and landscape** layouts, both optimised for counter-top use
- **6 theme colour presets** plus a full **HSV colour wheel picker** for custom brand colours
- **Decimal mode** — display balances as currency (e.g. `1.25` instead of `125`)
- **Audio feedback** — three distinct beep patterns: 1 high beep (success), 2 mid beeps (NFC error), 3 low beeps (insufficient balance)
- **Vibration feedback** — optional haptic confirmation on every transaction
- **Background flash** — green on success, red on error, purple on card management actions
- **Keep screen on** option for always-on counter terminals
- **Auto-reset** — UI clears 7 seconds after a read (unless a deduction button is still active)

---

## 📱 Screenshots

### Main Screen (Portrait)

```
┌─────────────────────────────────────┐
│  Tarjeta: 1A2B3C4D5E6F              │  ← Card UID (hex)
│                                     │
│            125                   🧒 │  ← Balance  (minor indicator)
│           monedas                   │
│                                     │
│   100 → 98                          │  ← Before / After on deduction
│   Tarjeta leída correctamente       │  ← Status message
│                                     │
│         [ −1 ]  [ −2 ]              │  ← Quick-deduct toggle buttons
│                                     │
│  ÚLTIMAS TRANSACCIONES              │
│  12:34:56  +100 monedas             │
│  12:30:22   −1  monedas             │
│  12:28:11   −2  monedas             │
└─────────────────────────────────────┘
```

### Main Screen (Landscape — 2-column layout)

```
┌────────────────────────┬────────────────────────┐
│  Tarjeta: 1A2B3C4D5E6F │                        │
│                        │       [ −1 ]            │
│     125             🧒 │       [ −2 ]            │
│    monedas             │                        │
│                        │  ÚLTIMAS TRANSACCIONES │
│  Tarjeta leída         │  12:34:56  +100        │
│  correctamente         │  12:30:22   −1         │
└────────────────────────┴────────────────────────┘
```

### Advanced Settings

```
┌──────────────────────────────────┐
│ Sector a modificar    [ 14 ]     │
│ Clave estática        [●●●●] 👁🔄│
│ ☑ Clave dinámica (mezclar UID)  │
│ ☑ Cambio de color de fondo      │
│ ☑ Verificar integridad          │
│ ☐ Modo debug                    │
│ ☐ Mantener pantalla encendida   │
│ ☑ Activar sonido                │
│ ☐ Activar vibración             │
│ ☐ Modo decimal (x100)           │
│ Edad adulta legal     [ 18 ]     │
│ Color de tema  [●][●][●][●][●][●]│
│          [ GUARDAR ]             │
└──────────────────────────────────┘
```

---

## 🔧 How It Works

### Card Data Layout (Sector 14, default)

| Block | Contents |
|-------|----------|
| 0 | **Value Block** — current balance (Mifare native format, 3× redundancy) |
| 1 | **Transaction history** — bytes 0–15 (init timestamp + first 2 transactions) |
| 2 | **Transaction history** — bytes 16–31 (last 2 transactions + 4-byte HMAC checksum) |
| 3 | **Sector Trailer** — Key A (derived), access bits, GPB (age byte), Key B (derived) |

### Transaction Record Format (6 bytes each, up to 4 records)

```
Bytes 0–3  : Init timestamp (uint32, Unix seconds, big-endian)
Per record : [seconds offset: 3 bytes] [opcode: 1 byte] [amount: 2 bytes big-endian]
             opcodes: 0x01 = ADD, 0x02 = SUBTRACT
Last 4 bytes: HMAC-SHA256 checksum (first 4 bytes of full hash)
```

### Key Derivation

```
Dynamic key  = HMAC-SHA1(PSK.utf8, cardUID)[0..5]   → 6 bytes, unique per card
Static key   = provided hex literal  OR  SHA-256(passphrase)[0..5]
```

### Atomic Balance Operations

Mifare Classic Value Blocks support hardware-atomic `increment` / `decrement` + `transfer` operations. NFC Coins uses these to guarantee that a power loss or card removal during a transaction will never corrupt the balance.

---

## ⚙️ Settings Reference

| Setting | Default | Description |
|---------|---------|-------------|
| **Sector** | `14` | Mifare sector (1–15) that holds balance & history |
| **Static key** | `NfcPosSecretKey2024` | PSK used for key derivation and checksums |
| **Dynamic key** | ✅ enabled | Mix card UID into the auth key for per-card uniqueness |
| **Background flash** | ✅ enabled | Flash screen colour on each tap |
| **Verify integrity** | ✅ enabled | Reject cards with invalid HMAC checksums |
| **Debug mode** | ❌ disabled | Show raw checksums and key info in the UI |
| **Keep screen on** | ❌ disabled | Prevent the display from sleeping |
| **Sound** | ✅ enabled | Beep tones on tap events |
| **Vibration** | ❌ disabled | Haptic feedback on tap events |
| **Decimal mode** | ❌ disabled | Display balance ÷ 100 (e.g. `1.25` instead of `125`) |
| **Legal age** | `18` | Age threshold for the minor indicator |
| **Theme colour** | Purple | Accent colour for buttons, checkboxes, and the action bar |

---

## 🏗️ Project Structure

```
nfc-coins/
├── app/src/main/
│   ├── java/net/duhowpi/nfccoins/
│   │   ├── MainActivity.kt              # Core NFC logic, UI, state machine
│   │   ├── AdvancedSettingsActivity.kt  # Settings screen
│   │   ├── TransactionBlock.kt          # Transaction history serialisation & HMAC
│   │   ├── BackspaceEditText.kt         # Custom EditText for amount input
│   │   └── ColorWheelView.kt            # HSV colour picker widget
│   ├── res/
│   │   ├── layout/                      # Portrait layouts
│   │   ├── layout-land/                 # Landscape layouts
│   │   ├── menu/main_menu.xml           # Action bar menu
│   │   ├── values/strings.xml           # UI strings (Spanish)
│   │   └── xml/nfc_tech_filter.xml      # NFC technology whitelist
│   └── AndroidManifest.xml
├── .github/workflows/
│   ├── build.yml                        # CI: debug APK on every push/PR
│   └── release.yml                      # CD: signed release APK on tag
└── build.gradle
```

---

## 🚀 Build

**Requirements:** Android SDK, Java 11+

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

### Custom PSK

The pre-shared key embedded in the APK can be overridden at build time so that your cards will not be readable by a generic build:

```bash
./gradlew assembleRelease -PNFC_POS_PSK=YourSecretKey
# or via environment variable
NFC_POS_PSK=YourSecretKey ./gradlew assembleRelease
```

> ⚠️ **Keep your PSK secret.** Anyone with the PSK and the app source can authenticate to your cards.

---

## 📋 Requirements

| Requirement | Minimum |
|-------------|---------|
| Android version | **8.0 Oreo (API 26)** |
| NFC hardware | **Required** (Mifare Classic support) |
| Target SDK | **35** |

> **Note:** Some Android devices (notably many recent Google Pixel phones) do not include a Mifare Classic-compatible NFC controller. The app requires a device that exposes the `MifareClassic` technology class.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0**. See the [`LICENSE`](LICENSE) file for details.
