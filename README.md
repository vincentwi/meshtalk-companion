# MeshTalk Companion App

Android companion phone app for MeshTalk glasses. Runs on a standard Android phone and acts as a mesh networking hub.

## Architecture

```
┌─────────────┐     BLE GATT     ┌──────────────┐    UDP Multicast    ┌──────────────┐     BLE GATT     ┌─────────────┐
│  Glasses A   │◄──────────────►│  Phone A       │◄─────────────────►│  Phone B       │◄──────────────►│  Glasses B   │
│  (Mercury)   │  Audio + Ctrl  │  (Companion)   │  Audio Relay      │  (Companion)   │  Audio + Ctrl  │  (Mercury)   │
└─────────────┘                 └──────────────┘                    └──────────────┘                 └─────────────┘
```

## Components

- **GlassesGattServer** — BLE GATT server advertising MeshTalk service. Glasses connect to us.
- **MeshDiscovery** — UDP multicast (239.42.42.42:18431) for phone-to-phone peer discovery
- **AudioRelay** — Routes Opus audio frames between BLE (glasses) and UDP mesh (other phones)
- **CompanionService** — Foreground service keeping everything alive

## BLE Protocol

| Characteristic | UUID (suffix) | Direction | Purpose |
|---|---|---|---|
| Audio TX | ea01 | Glasses → Phone | Opus audio from glasses mic |
| Audio RX | ea02 | Phone → Glasses | Opus audio from mesh peers |
| Control | ea03 | Bidirectional | JSON control messages |
| Status | ea04 | Phone → Glasses | JSON status updates |

Service UUID: `6ba1b218-15a8-461f-9fa8-5dcae273ea00`

## Building

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
```

## License

Apache 2.0
