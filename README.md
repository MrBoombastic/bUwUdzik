# bUwUdzik

bUwUdzik is an Android application for displaying data from a Qingping CGD1 - Bluetooth LE alarm
clock with sensors.

## Features

* Scans for a specific Bluetooth LE device by its MAC address.
* Parses and displays sensor data.
* Widget for displaying the latest sensor data on the home screen.
* Configurable background updates to fetch data periodically.
* Settings to customize the device's MAC address, theme (light/dark/system), and language.
* Initial setup screen to guide the user through finding and selecting their device.

## Technical Details

The application is built with modern Android development technologies and targets recent Android versions.

*   **Target API:** The application targets Android 15 (API level 36) and has a minimum requirement of Android 14 (API level 34).
*   **UI:** Jetpack Compose for a declarative and modern UI.
*   **Bluetooth LE:** It uses Android's native Bluetooth LE scanner to listen for advertisement packets from the sensor. It filters for devices advertising the specific service UUID `0000fdcd-0000-1000-8000-00805f9b34fb`.
*   **Data Parsing:** The sensor data is extracted from the service data field of the advertisement packet. The custom data format is as follows:
    *   Byte 1: Device ID (must be `0x0C` for CGD1)
    *   Bytes 10-11: Temperature (16-bit Little Endian signed integer, divided by 10)
    *   Bytes 12-13: Humidity (16-bit Little Endian unsigned integer, divided by 10)
    *   Byte 16: Battery level (unsigned 8-bit integer)
*   **Background Processing:** `WorkManager` and `AlarmManager` for scheduling periodic data fetches, ensuring the widget is always up-to-date.
*   **Navigation:** Jetpack Navigation for navigating between screens.
*   **Data Persistence:** `SharedPreferences` for storing user settings like the device MAC address and theme preferences.

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
