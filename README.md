# 🪙 NFC Coins

<p align="center">
  <strong>An Android NFC Point-of-Sale manager for Mifare Classic and NTAG2xx NFC cards - simple, fast, easy to use.</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/nfc-coins/actions/workflows/build.yml">
    <img src="https://github.com/duhow/nfc-coins/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/NFC-Mifare%20Classic%20%7C%20NTAG-blue?logo=nfc" alt="NFC Mifare Classic | NTAG">
  <a href="https://sladge.net"><img src="https://sladge.net/badge.svg" alt="AI Slop Inside"></a>
  <img src="https://img.shields.io/badge/License-MIT-orange" alt="License">
</p>

<p align="center">
<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/duhow/nfc-coins">
  <img src="https://github.com/ImranR98/Obtainium/blob/main/assets/graphics/badge_obtainium.png" 
       alt="Get it on Obtainium" align="center" height="54" />
</a>

<a href="https://github.com/duhow/nfc-coins/releases/latest">
  <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/4711835e032fe2735dc80c1329beb4685899aa91/get-it-on-github.png"
       alt="Download APK" align="center" height="81" />
</a>
</p>

---

**NFC Coins** is a Point-of-Sale Android application that reads, writes, and manages coin balances stored on Mifare Classic and NTAG NFC cards (NTAG20x and NTAG21x series).
It is designed for venues, events, or any environment where physical tokens need a digital equivalent.  
Tap a card to pay, top it up, and issue new ones, in a simple interface.

---

<p align="center" width="100%">
  <img src="https://github.com/user-attachments/assets/f10fedb0-22b7-4fdf-9fba-b772c9abc050" width="300" height="600">
  <img src="https://github.com/user-attachments/assets/bc23cd60-071f-475d-9555-6a1c9bd72a4c" width="300" height="600">
</p>

--- 

## ✨ Features

### 💳 Card Operations
- **Read balance** from Mifare Classic or NTAG NFC cards with a single tap
- **Add balance** to any card via the card management menu
- **Deduct coins** instantly with one-tap preset buttons (-1 or -2). Button stays active for rapid back-to-back transactions
- **Custom deduction**, tap the balance display and type any amount via the on-screen keyboard
- **Format new cards**, initialise blank or factory cards with a secure per-card key and an optional starting balance
- **Reset cards**, when you no longer want to use the service, wipe a card back to factory defaults (restores a default key)

### 🔒 Security & Integrity
- **Dynamic key derivation** - each card gets a unique authentication key computed as `HMAC-SHA1(PSK, UID)`, so stealing one card's key reveals nothing about others
- **Static key fallback** - optionally use a shared hex or passphrase key for simpler deployments
- **Transaction checksums** - every transaction block is protected by `HMAC-SHA256(PSK + UID, counterBlock + txPayload)`, binding the history to this specific card and PSK
- **Tamper detection** - mismatched checksums trigger an immediate "card tampered" warning
- **Interrupted write recovery** - if a card is removed mid-write, the app detects the inconsistency on the next tap and automatically retries the write before proceeding
- **Single-recharge mode** - optionally lock a card so it can only be topped up once (enforced via Mifare sector trailer access bits)

### 🧒 Age Verification
- **OPTIONAL:** Birth year is encoded in the sector trailer GPB byte (`ageByte = birthYear - 1900`).
- A minor indicator (🧒) is displayed whenever the card holder's calculated age is below the configurable legal-age threshold

### 📜 Transaction History
- Up to **4 recent transactions** are stored directly on the card.
- Each entry records: timestamp offset, operation (ADD / SUBTRACT), and amount
- Fully visible in the app's main screen without any backend

### 🎨 UI & Customisation
- **Portrait and landscape** layouts, both optimised for counter-top use
- **6 theme colour presets** plus a full **HSV colour wheel picker** for custom brand colours
- **Decimal mode** - display balances as currency (e.g. `1.25` instead of `125`)
- **Audio feedback** - three distinct beep patterns: 1 high beep (success), 2 mid beeps (NFC error), 3 low beeps (insufficient balance)
- **Vibration feedback** - optional haptic confirmation on every transaction
- **Background flash** - green on success, red on error, purple on card management actions
- **Keep screen on** option for always-on counter terminals

---

## 🔧 How It Works

### Card Data Layout (Sector 14, default) - Mifare Classic

Data is stored on a single sector, so you can have multiple applications, or serve NDEF messages (such as URLs).

| Block | Contents |
|-------|----------|
| 0 | **Value Block** - current balance (Mifare native format, 3x redundancy) |
| 1 | **Transaction history** - bytes 0-15 (init timestamp + first 2 transactions) |
| 2 | **Transaction history** - bytes 16-31 (last 2 transactions + 4-byte HMAC checksum) |
| 3 | **Sector Trailer** - Key A (derived), access bits, GPB (age byte), Key B (derived) |

### Card Data Layout - NTAG20x / NTAG21x

NTAG20x (e.g. NTAG203) and NTAG21x (e.g. NTAG213, NTAG215, NTAG216) tags are supported.
Data is stored in user-memory pages as a contiguous 44-byte (11-page) block, placed at the end of user memory so that the beginning of user pages remains available for NDEF records.

| Bytes | Pages | Contents |
|-------|-------|----------|
| 0–3   | +0    | **Magic** - `"COIN"` ASCII identifier |
| 4–7   | +1    | **Meta** - version, flags (single-recharge), birth year |
| 8–9   | +2    | **Balance** (uint16, big-endian) |
| 10–41 | +3–10 | **Transactions + HMAC checksum** (4 records × 6 bytes + 4-byte HMAC) |
| 42–43 | +10   | Padding |

### Transaction Record Format (6 bytes each, up to 4 records)

```
Bytes 0-3  : Init timestamp (uint32, Unix seconds, big-endian)
Per record : [seconds offset: 3 bytes] [opcode: 1 byte] [amount: 2 bytes big-endian]
             opcodes: 0x01 = ADD, 0x02 = SUBTRACT
Last 4 bytes: HMAC-SHA256 checksum (first 4 bytes of full hash)
```

### Key Derivation

```
Dynamic key  = HMAC-SHA1(PSK.utf8, cardUID)[0..5]   → 6 bytes, unique per card
Static key   = provided hex literal  OR  SHA-256(passphrase)[0..5]
```

### Custom PSK

The pre-shared key embedded in the APK can be overridden at build and run time so that your cards will not be readable by a this public build.
You can customize this in application settings.

> [!WARNING]
> **Keep your 🔐 PSK secret.** Anyone with the PSK and the app can authenticate to your cards.

### Atomic Balance Operations

Mifare Classic Value Blocks support hardware-atomic `increment` / `decrement` + `transfer` operations.
NFC Coins uses these to guarantee that a power loss or card removal during a transaction will never corrupt the balance.

---

## 📋 Requirements

| Requirement | Minimum |
|-------------|---------|
| Android version | **8.0 Oreo (API 26)** |
| NFC hardware | **Required** (Mifare Classic or NTAG support) |
| Target SDK | **35** |

> [!NOTE]
> Some Android devices (notably many recent Google Pixel phones) do not include a Mifare Classic-compatible NFC controller. The app requires a device that exposes the `MifareClassic` or `MifareUltralight` technology class. NTAG20x and NTAG21x cards (which use the `MifareUltralight` / `NfcA` tech class) are supported on a broader range of devices.

---

## ⚠️ Disclaimer

This software is provided **as-is**, without any warranty of any kind. The author accepts no responsibility for:

- **Data loss** - any kind of data loss, balance or transaction history stored on NFC cards
- **Card access loss** - including cards becoming permanently unreadable due to key changes or incorrect formatting
- **Any other damage** - direct or indirect, arising from the use or misuse of this software

Always **test on spare cards** before deploying to production. **Back up your 🔐 PSK** - losing it means losing access to every card formatted with it. Use this software at your own risk.

---

## 📄 License

This project is licensed under the **MIT License**. See the [`LICENSE`](LICENSE) file for details.
