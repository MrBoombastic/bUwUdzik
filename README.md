<!--suppress HtmlDeprecatedAttribute -->
<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="website/media/logo-white.svg">
    <source media="(prefers-color-scheme: light)" srcset="website/media/logo-black.svg">
    <img src="website/media/logo-white.svg" width="100" alt="clOwOck Logo">
  </picture>
</div>

# clOwOck

<!--suppress HtmlDeprecatedAttribute -->
<p align="center">
<a href="https://github.com/MrBoombastic/clOwOck/actions">
  <img src="https://img.shields.io/github/actions/workflow/status/MrBoombastic/clOwOck/release.yml?branch=main" alt="Build Status">
</a>
<a href="https://github.com/MrBoombastic/clOwOck/releases">
  <img src="https://img.shields.io/github/v/release/MrBoombastic/clOwOck" alt="GitHub release">
</a>
<a href="https://github.com/MrBoombastic/clOwOck/releases">
  <img src="https://img.shields.io/github/downloads/MrBoombastic/clOwOck/total" alt="GitHub all releases">
</a>
<a href="https://github.com/MrBoombastic/clOwOck/issues">
  <img src="https://img.shields.io/github/issues/MrBoombastic/clOwOck" alt="GitHub issues">
</a>
<a href="https://github.com/MrBoombastic/clOwOck/network">
  <img src="https://img.shields.io/github/forks/MrBoombastic/clOwOck" alt="GitHub forks">
</a>
<a href="https://github.com/MrBoombastic/clOwOck/stargazers">
  <img src="https://img.shields.io/github/stars/MrBoombastic/clOwOck" alt="GitHub stars">
</a>
<a href="https://github.com/MrBoombastic/clOwOck/blob/main/LICENSE">
  <img src="https://img.shields.io/github/license/MrBoombastic/clOwOck" alt="GitHub license">
</a>
</p>

<!--suppress HtmlDeprecatedAttribute -->
<div align="center">
<a href="https://play.google.com/store/apps/details?id=com.mrboombastic.buwudzik">
  <img src=".github/gplay.svg" width="200" alt="Get it on Google Play">
</a>
</div>


Android app for the Qingping Cleargrass CGD1 (`cgllc.clock.dove`) - Bluetooth LE alarm clock with
sensors.

<details>
<summary><strong>Table of Contents</strong></summary>

- [Warning](#warning)
- [Features](#features)
- [Screenshots](#screenshots)
- [Technical Details](#technical-details)
    - [Firmware Compatibility](#firmware-compatibility)
- [Protocol Specification](#protocol-specification)
    - [1. Service & Characteristics Profile](#1-service--characteristics-profile)
    - [2. Protocol Structure](#2-protocol-structure)
        - [2.1. Known Headers](#21-known-headers)
        - [2.2. Authentication (Two-Step Token Protocol)](#22-authentication-two-step-token-protocol)
        - [2.3. Time Synchronization](#23-time-synchronization)
    - [3. Managing Alarms](#3-managing-alarms)
        - [3.1. Set Alarm](#31-set-alarm)
        - [3.2. Alarm Payload (5 bytes)](#32-alarm-payload-5-bytes)
        - [3.3. Delete Alarm](#33-delete-alarm)
        - [3.4. Read Alarms](#34-read-alarms)
    - [4. Device Settings](#4-device-settings)
        - [4.1. Set Immediate Brightness (Preview)](#41-set-immediate-brightness-preview)
        - [4.2. Preview Ringtone](#42-preview-ringtone)
    - [5. Real-Time Sensor Stream (Connected)](#5-real-time-sensor-stream-connected)
    - [6. Passive Sensor Stream (Advertising)](#6-passive-sensor-stream-advertising)
    - [7. Battery Level (Connected)](#7-battery-level-connected)
    - [8. Firmware Version](#8-firmware-version)
    - [9. Audio Transfer Protocol (Ringtone Upload)](#9-audio-transfer-protocol-ringtone-upload)
        - [Known Ringtone Signatures](#known-ringtone-signatures)
        - [Custom Ringtone Slots](#custom-ringtone-slots)
        - [Custom Ringtone JSON Manifest](#custom-ringtone-json-manifest)
        - [Upload Protocol](#upload-protocol)
    - [10. Known Command IDs Summary](#10-known-command-ids-summary)
    - [11. GATT Disconnection Status Codes](#11-gatt-disconnection-status-codes)

</details>

## Warning

App was largely created using LLMs. I still have reviewed the code,
so it's only semi-slop, but you have been warned, etc., etc.

## Features

- Initial setup screen to guide the user through finding and selecting their device
- Scans for a specific Bluetooth LE device by its MAC address
- Share a saved device with others using QR code
- Parses and displays sensor data
- Management of up to 16 device alarms
- **Custom ringtones support**
- Global alarm switch to enable or disable all device alarms at once
- Bluetooth state monitoring with automatic prompts to enable it
- Interactive real-time previews for brightness and volume settings
- Widget for displaying sensor data on the home screen
- Configurable background updates to fetch data periodically
- Settings to customize the device's MAC address, theme (light/dark/system), and language

## Screenshots

<img src="website/media/setup.png" width="23%" alt="Setup screenshot"></img>
<img src="website/media/pair.png" width="23%" alt="Pairing screenshot"></img>
<img src="website/media/settings.png" width="23%" alt="Settings screenshot"></img>
<img src="website/media/s1.png" width="23%" alt="Home page screenshot"></img>
<img src="website/media/s2.png" width="23%" alt="Alarms page screenshot"></img>
<img src="website/media/s3.png" width="23%" alt="Alarm settings screenshot"></img>
<img src="website/media/s4.png" width="23%" alt="Device settings screenshot"></img>
<img src="website/media/s5.png" width="23%" alt="Ringtone page screenshot"></img>
<img src="website/media/s6.png" width="23%" alt="Trimming audio screenshot"></img>
<img src="website/media/import.png" width="23%" alt="Import device screenshot"></img>
<img src="website/media/export.png" width="23%" alt="Export device screenshot"></img>
<img src="website/media/widget.png" width="23%" alt="Multiple widgets screenshot"></img>

## Technical Details

The application is built with modern Android development technologies and targets recent Android
versions.

- **Target API:** The application targets Android 17 (API level 37) and has a minimum requirement of
  Android 10 (API level 29)
- **UI:** Jetpack Compose for a declarative and modern UI
- **Background Processing:** `AlarmManager` starts filtered BLE scans through a `PendingIntent` for
  periodic widget updates. User-requested refreshes use a short `connectedDevice` foreground service
  so modern Android versions can execute the scan immediately.

### Firmware Compatibility

| Version      | Status  | Notes                                                          |
|--------------|---------|----------------------------------------------------------------|
| `1.0.1_0046` | Unknown |                                                                |
| `1.0.1_0063` | Unknown |                                                                |
| `1.0.1_0067` | Unknown |                                                                |
| `1.0.1_0126` | Unknown |                                                                |
| `1.0.1_0130` | Working | *Marked as the latest for some devices, perhaps different HW?* |
| `1.0.1_0132` | Working | *Marked as the latest for some devices, perhaps different HW?* |

## Protocol Specification

This section describes the reverse-engineered Bluetooth Low Energy (BLE) protocol for the Qingping
CGD1 Alarm Clock.

### 1. Service & Characteristics Profile

The device uses a custom service structure but relies on standard 128-bit base UUIDs for
characteristics.

**Custom GATT Service UUID:** `22210000-554a-4546-5542-46534450464d`

This is the service discovered after connecting. Passive advertisements carry sensor data under the
16-bit `FDCD` service-data UUID documented in section 6; the custom GATT UUID is not the
service-data
filter used for scanning.

| Function      | Characteristic UUID                    | Properties |
|---------------|----------------------------------------|------------|
| Auth Write    | `00000001-0000-1000-8000-00805f9b34fb` | Write      |
| Auth Notify   | `00000002-0000-1000-8000-00805f9b34fb` | Notify     |
| Data Write    | `0000000b-0000-1000-8000-00805f9b34fb` | Write      |
| Data Notify   | `0000000c-0000-1000-8000-00805f9b34fb` | Notify     |
| Sensor Notify | `00000100-0000-1000-8000-00805f9b34fb` | Notify     |

### 2. Protocol Structure

Most commands follow a simple **Header + Command + Payload** structure.

**Request Format:** `[Header] [Command] [Payload...]`  
**ACK Format (Notify):** `04 ff [Command] [Status] [Payload...]`

#### 2.1. Known Headers

| Value         | Constant                | Used for                                        |
|---------------|-------------------------|-------------------------------------------------|
| `0x11`        | `Header.AUTH`           | Authentication steps                            |
| `0x05`        | `Header.TIME`           | Time synchronization                            |
| `0x01`        | `Header.GET_DATA`       | Read-only requests (Settings, Alarms, Firmware) |
| `0x07`        | `Header.SET_ALARM`      | Writing alarm data                              |
| `0x02`        | `Header.BRIGHTNESS`     | Immediate brightness preview                    |
| `0x01`/`0x02` | `Header.RINGTONE_V1/V2` | Ringtone preview                                |
| `0x13`        | `Header.SET_SETTINGS`   | Writing device settings                         |
| `0x08`        | `Header.AUDIO_INIT`     | Audio upload initialization                     |
| `0x81`        | `Header.AUDIO_PACKET`   | Audio data stream packets                       |

#### 2.2. Authentication (Two-Step Token Protocol)

The device uses a two-step authentication protocol with a 16-byte random token. Once paired, the
same token must be used for all future connections.

**Flow:**

1. Connect to the device and discover services
2. Enable Notifications on **Auth Notify** (`...0002`)
3. Send **Auth Init** to **Auth Write** (`...0001`): `11 01 [Token 16B]`
4. Wait for ACK on **Auth Notify**: `04 ff 01 00 02` (success, proceed to step 5)
5. Send **Auth Confirm** to **Auth Write**: `11 02 [Token 16B]`
6. Wait for final ACK: `04 ff 02 00 00`

Device will send you an ACK even when the token is bad. Try to sync time or do other "privileged"
action and check if the device will close connection with you.

**Token Management:**

- For new devices: Generate a random 16-byte token
- For paired devices: Use the stored token from the previous pairing
- Token must match what the device expects (first successful pairing establishes the token)
- Persist a newly generated token only after a privileged command, such as time synchronization,
  succeeds. An Auth Confirm ACK alone does not prove that the token was accepted.

**ACK Response Format:** `04 ff [CmdID] [Status] [Payload...]`

- Status `00` = Success
- Status `01` = Failure
- Status `02` = Continue (for Auth Init, proceed to step 5)

#### 2.3. Time Synchronization

After authentication, it is recommended to synchronize the time.

- **Command (Auth Write):** `05 09 [Timestamp 4B LE]`
- **Response (Auth Notify):** `04 ff 09 00 00` (Success)

### 3. Managing Alarms

The device supports a fixed capacity of **16 alarm slots** (indexed 0-15). All alarm/settings
operations happen on the **Data** characteristics.

#### 3.1. Set Alarm

To create or modify an alarm:

- **Command:** `07 05 [ID] [Enabled] [HH] [MM] [Days] [Snooze]`
- **ID:** The alarm index (0-15)
- **Enabled:** `0x01` = On, `0x00` = Off
- **HH, MM:** Hour (0-23) and Minute (0-59)
- **Days (Bitmask):**
    - `0x01` = Monday
    - `0x02` = Tuesday
    - `0x04` = Wednesday
    - `0x08` = Thursday
    - `0x10` = Friday
    - `0x20` = Saturday
    - `0x40` = Sunday
    - `0x00` = Once
- **Snooze:** `0x01` = On, `0x00` = Off

#### 3.2. Alarm Payload (5 bytes)

The 5-byte alarm entry structure used in both **Set Alarm** and **Read Alarms**:

| Byte | Description   | Range / Values                            |
|------|---------------|-------------------------------------------|
| 0    | Enabled State | `0x01` (On), `0x00` (Off), `0xFF` (Empty) |
| 1    | Hour          | `0-23`, `0xFF` (Empty)                    |
| 2    | Minute        | `0-59`, `0xFF` (Empty)                    |
| 3    | Repeat Days   | Bitmask (see above), `0xFF` (Empty)       |
| 4    | Snooze        | `0x01` (On), `0x00` (Off), `0xFF` (Empty) |

#### 3.3. Delete Alarm

To delete an alarm, overwrite it with `FF` values (marking it as empty/unused).

- **Command:** `07 05 [ID] FF FF FF FF FF`

#### 3.4. Read Alarms

- **Command:** `01 06`
- **Response:** `11 06 [Base Index] [Alarm Entry 1 (5B)] ...`
- **Alarm Entry:** `[Enabled] [HH] [MM] [Days] [Snooze]`

**Note:** Device sends multiple packets if needed (up to 4 alarms per packet). All 16 slots are
returned, empty slots have `FF FF FF FF FF` values.

- **ACK (after Set/Delete):** `04 ff 05 00 00` (Success)

### 4. Device Settings

Managed via a single comprehensive payload on **Data Write**.

- **Command:** Start with `13` (Set Settings) or `01 02` (Read Settings)
- **Set Settings Payload (20 bytes):**

  `13 01 [Vol] [Hdr1] [Hdr2] [Flags] [Timezone] [Duration] [Brightness] [NightStartH] [NightStartM] [NightEndH] [NightEndM] [TzSign] [NightEn] [Reserved?] [Sig 4B]`

| Byte  | Value           | Description                                                                                                    |
|-------|-----------------|----------------------------------------------------------------------------------------------------------------|
| 0     | `0x13`          | Command ID                                                                                                     |
| 1     | `0x01` / `0x02` | Set / Read Response                                                                                            |
| 2     | `1-5`           | Sound Volume                                                                                                   |
| 3-4   | `58 02`         | Fixed Header / Version (???)                                                                                   |
| 5     | Bitmask         | Mode Flags: See the **Mode Flags Breakdown** table below.                                                      |
| 6     | Integer         | Timezone Offset (Units of 6 minutes). Device does not handle DST automatically.                                |
| 7     | Seconds         | Backlight Duration (0=Off)                                                                                     |
| 8     | Packed          | Brightness (High nibble: Day/10, Low nibble: Night/10)                                                         |
| 9-10  | HH:MM           | Night Start Time                                                                                               |
| 11-12 | HH:MM           | Night End Time                                                                                                 |
| 13    | `0/1`           | Timezone Sign (1=Positive, 0=Negative)                                                                         |
| 14    | `0/1`           | Night Mode Enabled                                                                                             |
| 15    | -               | Reserved (preserved from device response)                                                                      |
| 16-19 | `Sig 4B`        | Ringtone signature (4 bytes). Identifies the device ringtone — see the "Known Ringtone Signatures" list below. |

#### Mode Flags Breakdown (Byte 5)

This byte acts as a **bitfield** where individual bits control specific boolean settings.

| Bit | Value (Hex) | Description              | 0 (Off/Default) | 1 (On/Active) |
|-----|-------------|--------------------------|-----------------|---------------|
| 0   | `0x01`      | Language                 | Chinese         | English       |
| 1   | `0x02`      | Time Format              | 24-hour         | 12-hour       |
| 2   | `0x04`      | Temp Unit                | Celsius         | Fahrenheit    |
| 3   | `0x08`      | *(Reserved ?)*           | -               | -             |
| 4   | `0x10`      | Master Alarm Disable (!) | Enabled         | Disabled      |
| 5-7 | -           | *(Unused ?)*             | -               | -             |

**Workaround:** Disabling night mode is being done via setting 1-minute night mode (i.e.
`00:00 - 00:01`). Yup, it's that stupid; even official app does this.

#### 4.1. Set Immediate Brightness (Preview)

- **Command (Data Write):** `02 03 [Value]`
- **Value:** Brightness level / 10 (`0-10`).
- **Response (Data Notify):** `04 ff 03 00 00` (Success).

#### 4.2. Preview Ringtone

Plays a generic "beep" sound for testing volume level (not the user's selected ringtone).

- **Command (Data Write):** `01 04` (Play at current volume) or `02 04 [Vol]` (Play at volume `1-5`)
- **Response (Data Notify):** `04 ff 04 00 00` (Success)

### 5. Real-Time Sensor Stream (Connected)

- **Target:** `00000100-...` (Notify)
- **Format:** `[00] [Temp L] [Temp H] [Hum L] [Hum H]`
- **Values:** Temperature is signed Int16 LE / 100.0; humidity is unsigned UInt16 LE / 100.0.

### 6. Passive Sensor Stream (Advertising)

The device also broadcasts sensor data in its BLE advertisement packets via Service Data.

- **Service UUID:** `0000fdcd-0000-1000-8000-00805f9b34fb` (ClearGrass/Qingping Service)
- **Format (Service Data):** An 8-byte header followed by type-length-value (TLV) objects.

| Byte | Value            | Description                                                           |
|------|------------------|-----------------------------------------------------------------------|
| 0    | `0x08` or `0x88` | Qingping packet type (0x88 has bit 7 set to indicate MAC is embedded) |
| 1    | `0x0c`           | Model ID (`0x0C` = CGD1)                                              |
| 2-7  | MAC              | Device MAC address (6 bytes, reversed)                                |

Known objects after byte 7:

| Type | Length | Value                                                                                      |
|------|--------|--------------------------------------------------------------------------------------------|
| `01` | `04`   | `[Temp L] [Temp H] [Hum L] [Hum H]`; signed temperature and unsigned humidity, both / 10.0 |
| `02` | `01`   | Battery percentage as UInt8 (`0-100`)                                                      |

For example, a common 17-byte payload is:
`[08|88] 0c [MAC 6B] 01 04 [Temp 2B] [Humidity 2B] 02 01 [Battery]`.

### 7. Battery Level (Connected)

- **Service UUID:** `0x180f`, **Char UUID:** `0x2a19`
- **Format:** 1 byte (percentage)
- The app reads this characteristic and subscribes when notifications are supported.

### 8. Firmware Version

- **Command (Auth Write):** `01 0d`
- **Response (Auth Notify):** `0b [Length] [ASCII String]`

### 9. Audio Transfer Protocol (Ringtone Upload)

#### Known Ringtone Signatures

Official apps are using these 4-byte signatures to identify ringtones:

| Ringtone           | Signature (Hex) |
|--------------------|-----------------|
| Beep               | `fd c3 66 a5`   |
| Digital Ringtone   | `09 61 bb 77`   |
| Digital Ringtone 2 | `ba 2c 2c 8c`   |
| Cuckoo             | `ea 2d 4c 02`   |
| Telephone          | `79 1b ac b3`   |
| Exotic Guitar      | `1d 01 9f d6`   |
| Lively Piano       | `6e 70 b6 59`   |
| Story Piano        | `8f 00 48 86`   |
| Forest Piano       | `26 52 25 19`   |

#### Custom Ringtone Slots

For uploading custom ringtones, this app is using these alternating slot signatures:

| Slot     | Signature (Hex) |
|----------|-----------------|
| Custom 1 | `de ad de ad`   |
| Custom 2 | `be ef be ef`   |

**Important:** Always alternate between slots when uploading new custom audio. Doesn't matter how
you name it. The device may reject
uploads if the target signature matches the currently active ringtone, but audio itself is different
from the one you are uploading.

#### Custom Ringtone JSON Manifest

If you want to host your own ringtone repository, you can create a JSON manifest file. Just point to
a JSON file (e.g., `https://example.com/rings/index.json`), and the app will parse the manifest for
ringtone URLs

**Manifest Format:**

The JSON file should map hex signatures to objects containing at least a `"wav"` URL. The official
app uses an additional `"pcm"` field, but this app takes the Wave and converts it on its own.

```json
{
  "1d019fd6": {
    "name": "Exotic Guitar",
    "wav": "https://example.com/rings/1d019fd6.wav"
  },
  "6e70b659": {
    "name": "Lively Piano",
    "wav": "https://example.com/rings/6e70b659.wav"
  },
  "8f004886": {
    "name": "Story Piano",
    "wav": "https://example.com/rings/8f004886.wav"
  },
  "26522519": {
    "name": "Forest Piano",
    "wav": "https://example.com/rings/26522519.wav"
  },
  "fdc366a5": {
    "name": "Beep",
    "wav": "https://example.com/rings/fdc366a5.wav"
  },
  "ea2d4c02": {
    "name": "Cuckoo",
    "wav": "https://example.com/rings/ea2d4c02.wav"
  },
  "0961bb77": {
    "name": "Digital Ringtone",
    "wav": "https://example.com/rings/0961bb77.wav"
  },
  "ba2c2c8c": {
    "name": "Digital Ringtone 2",
    "wav": "https://example.com/rings/ba2c2c8c.wav"
  },
  "791bacb3": {
    "name": "Telephone Ringtone",
    "wav": "https://example.com/rings/791bacb3.wav"
  }
}
```

**Key:** Hex signature (without `0x` prefix).  
**Value:** Object with `name` (display name) and `wav` (full URL to WAV file).

#### Upload Protocol

**Audio Format:** 8-bit Unsigned PCM, 8000 Hz, Mono

**Step 1 - Init Command (Data Write):**

```
08 10 [Size 3B LE] [Signature 4B]
```

- Size: Audio length in bytes (Little Endian, 3 bytes)
- Signature: Target ringtone slot signature

**Step 2 - Wait for Init ACK (Data Notify):**

```
04 ff 10 00 [Status]
```

- Status `00` or `09` = Success, proceed with upload

**Step 3 - Send Audio Data:**

- Packet size: 128 bytes
- Packets per block: 4 (512 bytes per block)
- Packet header: Prepend `81 08` to every 128-byte audio packet
- Wait for block ACK (`04 ff 08 ...`) after every 4 packets

**Step 4 - Completion:**

After sending all audio data, the device will apply the new ringtone.

### 10. Known Command IDs Summary

| Cmd | Sub | Characteristic | Description                             |
|-----|-----|----------------|-----------------------------------------|
| 11  | 01  | Auth Write     | Auth Init (+ 16B token)                 |
| 11  | 02  | Auth Write     | Auth Confirm (+ 16B token)              |
| 05  | 09  | Auth Write     | Time Sync (+ 4B timestamp LE)           |
| 01  | 0D  | Auth Write     | Read Firmware Version                   |
| 13  | 01  | Data Write     | Set Settings (Volume, Brightness, etc.) |
| 01  | 02  | Data Write     | Read Settings                           |
| 02  | 03  | Data Write     | Set Immediate Brightness                |
| 01  | 04  | Data Write     | Preview Ringtone (current volume)       |
| 02  | 04  | Data Write     | Preview Ringtone (+ 1B volume)          |
| 07  | 05  | Data Write     | Set Alarm                               |
| 01  | 06  | Data Write     | Read Alarms                             |
| 08  | 10  | Data Write     | Audio Upload Init                       |
| 81  | 08  | Data Write     | Audio packet (+ 128B padded audio)      |

**ACK Format (Notify characteristics):** `04 ff [CmdSub] [Status] [Payload...]`

### 11. GATT Disconnection Status Codes

When the device disconnects, the GATT status indicates the reason:

| Status | Meaning                     | Description                        |
|--------|-----------------------------|------------------------------------|
| 0      | `GATT_SUCCESS`              | Normal disconnect (user requested) |
| 8      | `GATT_CONN_TIMEOUT`         | Connection timeout                 |
| 19     | `GATT_CONN_TERMINATE_PEER`  | Device terminated connection       |
| 22     | `GATT_CONN_TERMINATE_LOCAL` | Link lost / local host terminated  |
