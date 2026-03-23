# 🪙 NFC Coins

<p align="center">
  <strong>An Android NFC Point-of-Sale manager for Mifare Classic cards - simple, fast, easy to use.</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/nfc-coins/actions/workflows/build.yml">
    <img src="https://github.com/duhow/nfc-coins/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/NFC-Mifare%20Classic-blue?logo=nfc" alt="NFC Mifare Classic">
  <img src="https://img.shields.io/badge/License-MIT-orange" alt="License">
</p>

---

**NFC Coins** is a Point-of-Sale Android application that reads, writes, and manages coin balances stored on Mifare Classic NFC cards.
It is designed for venues, events, or any environment where physical tokens need a digital equivalent.  
Tap a card to pay, top it up, and issue new ones, in a simple interface.

---

## ✨ Features

### 💳 Card Operations
- **Read balance** from Mifare Classic NFC cards with a single tap
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

### Card Data Layout (Sector 14, default)

Data is stored on a single sector, so you can have multiple applications, or serve NDEF messages (such as URLs).

| Block | Contents |
|-------|----------|
| 0 | **Value Block** - current balance (Mifare native format, 3x redundancy) |
| 1 | **Transaction history** - bytes 0-15 (init timestamp + first 2 transactions) |
| 2 | **Transaction history** - bytes 16-31 (last 2 transactions + 4-byte HMAC checksum) |
| 3 | **Sector Trailer** - Key A (derived), access bits, GPB (age byte), Key B (derived) |

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
| NFC hardware | **Required** (Mifare Classic support) |
| Target SDK | **35** |

> [!NOTE]
> Some Android devices (notably many recent Google Pixel phones) do not include a Mifare Classic-compatible NFC controller. The app requires a device that exposes the `MifareClassic` technology class.

---

## 📄 License

This project is licensed under the **MIT License**. See the [`LICENSE`](LICENSE) file for details.
