# bUwUdzik

Android app for displaying data from the Qingping CGD1 - Bluetooth LE alarm
clock with sensors.

## Warning

App was largely created using LLMs. I still have reviewed the code,
so it's only semi-slop, but you have been warned, etc., etc.

## Features

* Initial setup screen to guide the user through finding and selecting their device.
* Scans for a specific Bluetooth LE device by its MAC address.
* Share saved device with others using QR code.
* Parses and displays sensor data.
* Management of up to 16 device alarms.
* **Custom ringtones uploading.**
* Global alarm switch to enable or disable all device alarms at once.
* Bluetooth state monitoring with automatic prompts to enable it.
* Interactive real-time previews for brightness and volume settings.
* Widget for displaying sensor data on the home screen.
* Configurable background updates to fetch data periodically.
* Settings to customize the device's MAC address, theme (light/dark/system), and language.

## Technical Details

The application is built with modern Android development technologies and targets recent Android
versions.

* **Target API:** The application targets Android 16 (API level 36) and has a minimum requirement of
  Android 14 (API level 34).
* **UI:** Jetpack Compose for a declarative and modern UI.
* **Background Processing:** `WorkManager` and `AlarmManager` for scheduling periodic data fetches,
  ensuring the widget is always up-to-date.

## Screenshots

### Setup screen

<img src="docs/setup.png" alt="Setup screen" width="400"/>

### Waiting for the data

<img src="docs/scan.png" alt="Waiting for the data" width="400"/>

### Main screen

<img src="docs/main.png" alt="Main screen" width="400"/>

### Settings screen

<img src="docs/settings.png" alt="Settings screen" width="400"/>

### Widget on home screen

<img src="docs/widget.png" alt="Widget on home screen" width="400"/>

## Protocol Specification

This section describes the reverse-engineered Bluetooth Low Energy (BLE) protocol for the Qingping
CGD1 Alarm Clock.

### 1. Service & Characteristics Profile

The device uses a custom service structure but relies on standard 128-bit base UUIDs for
characteristics in this specific firmware version.

**Target Service UUID:** `22210000-554a-4546-5542-46534450464d` (Advertised)

| Function      | Characteristic UUID                    | Properties |
|---------------|----------------------------------------|------------|
| Auth Write    | `00000001-0000-1000-8000-00805f9b34fb` | Write      |
| Auth Notify   | `00000002-0000-1000-8000-00805f9b34fb` | Notify     |
| Data Write    | `0000000b-0000-1000-8000-00805f9b34fb` | Write      |
| Data Notify   | `0000000c-0000-1000-8000-00805f9b34fb` | Notify     |
| Sensor Notify | `00000100-0000-1000-8000-00805f9b34fb` | Notify     |

### 2. Authentication (Two-Step Token Protocol)

The device uses a two-step authentication protocol with a 16-byte random token. Once paired, the
same token must be used for all future connections.

**Flow:**

1. Connect to the device and discover services.
2. Enable Notifications on **Auth Notify** (`...0002`).
3. Send **Auth Init** to **Auth Write** (`...0001`): `11 01 [Token 16B]`
4. Wait for ACK on **Auth Notify**: `04 ff 01 00 02` (success, proceed to step 5)
5. Send **Auth Confirm** to **Auth Write**: `11 02 [Token 16B]`
6. Wait for final ACK: `04 ff 02 00 00` (authentication complete)

**Token Management:**

- For new devices: Generate a random 16-byte token
- For paired devices: Use the stored token from previous pairing
- Token must match what the device expects (first successful pairing establishes the token)

**ACK Response Format:** `04 ff [CmdID] [Len] [Status]`

- Status `00` = Success
- Status `01` = Failure
- Status `02` = Continue (for Auth Init, proceed to step 5)

#### 2.1. Time Synchronization

After authentication, it is recommended to synchronize the time.

* **Command (Auth Write):** `05 09 [Timestamp 4B LE]`
* **Response (Auth Notify):** `04 ff 09 00 00` (Success).

### 3. Managing Alarms

The device supports a fixed capacity of **16 alarm slots** (indexed 0-15). All alarm/settings
operations happen on the **Data** characteristics.

#### 3.1. Set Alarm

To create or modify an alarm:

* **Command:** `07 05 [ID] [Enabled] [HH] [MM] [Days] [Snooze]`


* **ID:** The alarm index (0-15).

* **Enabled:** `0x01` = On, `0x00` = Off.

* **HH, MM:** Hour (0-23) and Minute (0-59).

* **Days (Bitmask):**

    * `0x01` = Monday

    * `0x02` = Tuesday

    * `0x04` = Wednesday

    * `0x08` = Thursday

    * `0x10` = Friday

    * `0x20` = Saturday

    * `0x40` = Sunday

    * `0x00` = Once

* **Snooze:** `0x01` = On, `0x00` = Off.

#### 3.2. Delete Alarm

To delete an alarm, overwrite it with `FF` values (marking it as empty/unused).

* **Command:** `07 05 [ID] FF FF FF FF FF`

#### 3.3. Read Alarms

* **Command:** `01 06`
* **Response:** `11 06 [Base Index] [Alarm Entry 1 (5B)] ...`
* **Alarm Entry:** `[Enabled] [HH] [MM] [Days] [Snooze]`

**Note:** Device sends multiple packets if needed (up to 4 alarms per packet). All 16 slots are
returned, empty slots have `FF FF FF FF FF` values.

* **ACK (after Set/Delete):** `04 ff 05 00 00` (Success)

### 4. Device Settings

Managed via a single comprehensive payload on **Data Write**.

* **Command:** Start with `13` (Set Settings) or `01 02` (Read Settings).
* **Set Settings Payload (20 bytes):**
  `13 01 [Vol] [Hdr1] [Hdr2] [Flags] [Timezone] [Duration] [Brightness] [NightStartH] [NightStartM] [NightEndH] [NightEndM] [TzSign] [NightEn] [Sig 4B]`

| Byte  | Value           | Description                                                                                                    |
|-------|-----------------|----------------------------------------------------------------------------------------------------------------|
| 0     | `0x13`          | Command ID                                                                                                     |
| 1     | `0x01` / `0x02` | Set / Read Response                                                                                            |
| 2     | `1-5`           | Sound Volume                                                                                                   |
| 3-4   | `58 02`         | Fixed Header / Version                                                                                         |
| 5     | Bitmask         | Mode Flags: See the **Mode Flags Breakdown** table below.                                                      |
| 6     | Integer         | Timezone Offset (Units of 6 minutes)                                                                           |
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

**Workaround:** Disabling night mode is being done via setting 1-minute night mode (ie.
`00:00 - 00:01`). Yup, it's that stupid, even official app does this.

#### 4.1. Set Immediate Brightness (Preview)

* **Command (Data Write):** `02 03 [Value]`
* **Value:** Brightness level / 10 (`0-10`).
* **Response (Data Notify):** `04 ff 03 00 00` (Success).

#### 4.2. Preview Ringtone

Plays a generic "beep" sound for testing volume level (not the user's selected ringtone).

* **Command (Data Write):** `01 04` (Play at current volume) or `02 04 [Vol]` (Play at volume `1-5`)
* **Response (Data Notify):** `04 ff 04 00 00` (Success).

### 5. Real-Time Sensor Stream

* **Target:** `00000100-...` (Notify)
* **Format:** `[00] [Temp L] [Temp H] [Hum L] [Hum H]`
* **Values:** Little Endian Int16 / 100.0.

### 6. Battery Level

* **Service UUID:** `0x180f`, **Char UUID:** `0x2a19`.
* **Format:** 1 byte (percentage).

### 7. Firmware Version

* **Command (Auth Write):** `01 0d`
* **Response (Auth Notify):** `0b [Length] [ASCII String]`

### 8. Audio Transfer Protocol (Ringtone Upload)

#### Known Ringtone Signatures

The device uses 4-byte signatures to identify ringtones:

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

For uploading custom ringtones, app is using these alternating slot signatures:

| Slot     | Signature (Hex) |
|----------|-----------------|
| Custom 1 | `de ad de ad`   |
| Custom 2 | `be ef be ef`   |

**Important:** Always alternate between slots when uploading new custom audio. Doesn't matter how
you name it. The device may reject
uploads if the target signature matches the currently active ringtone, but is different from the one
you are uploading.

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
- First packet header: Prepend `81 08` to the audio data
- Wait for block ACK (`04 ff 08 ...`) after every 4 packets

**Step 4 - Completion:**
After sending all audio data, the device will apply the new ringtone.

### 9. Known Command IDs Summary

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

**ACK Format (Notify characteristics):** `04 ff [CmdSub] [Len] [Status]`

### 10. GATT Disconnection Status Codes

When the device disconnects, the GATT status indicates the reason:

| Status | Meaning                     | Description                        |
|--------|-----------------------------|------------------------------------|
| 0      | `GATT_SUCCESS`              | Normal disconnect (user requested) |
| 8      | `GATT_CONN_TIMEOUT`         | Connection timeout                 |
| 19     | `GATT_CONN_TERMINATE_PEER`  | Device terminated connection       |
| 22     | `GATT_CONN_TERMINATE_LOCAL` | Link lost / local host terminated  |
