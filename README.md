# NFC Coins

An Android NFC wallet application that reads and manages balances on Mifare Classic NFC cards.

## Minimum Android Version

**Android 8.0 Oreo (API 26)** is required.

## Features

- Read balance from Mifare Classic NFC cards (sector 14)
- Deduct 1 or 2 coins with NFC tap
- Add balance to cards
- Format new cards with a unique per-card key derived from the UID

## Build

```bash
./gradlew assembleRelease
```

The PSK (pre-shared key) used for card authentication can be overridden at build time:

```bash
./gradlew assembleRelease -PNFC_POS_PSK=YourSecretKey
```
