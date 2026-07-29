# Air+Sea

An open source ATAK-CIV plugin that displays real-time ship and aircraft positions from AIS and ADS-B data sources as CoT markers.

https://github.com/user-attachments/assets/d7ec7bdb-f483-4b2f-a4a9-0fcd011b47b8
## Features

- Converts real-time ship and aircraft position information from AIS and ADS-B to CoT standard markers
- Gathers and displays all ships and aircraft within a map-selected area
- Supports both online and offline monitoring of AIS and ADS-B
- Tags ships and aircraft with their current speed and heading (if available)
- Identify which ships or aircraft are underway at a glance; contacts with speed vectors are labeled with an arrow indicating their direction of travel
- CoT markers are labeled with ship name or aircraft callsign, and are auto-assigned specific CoT types based on their broadcasted category code
- Locally stored and updated ICAO database can identify an aircraft's military affiliation, type, and owner/operator without an internet connection and regardless of ADS-B source
- Includes additional ship information remarks (MMSI, rate of turn, IMO Number, Ship Type, Draught, Destination, ETA)
- Includes additional aircraft information remarks (ICAO, Registration, Type, Altitude, Speed, Squawk, Category, Owner/Operator)
- User can set update frequency, broadcast status, and default team affiliation for generated CoTs
- User can edit CoT team affiliation or specific CoT type, and edits persist across tracking updates

## Setup
### Maritime Tracking APIs
- [aisstream.io](https://aisstream.io/) is supported as a free ship data source. Users must register on aisstream.io and generate an API key to input into the plugin.
- [VesselFinder](https://www.vesselfinder.com/) is supported as a paid service for AIS data. Users must have an API key for a valid [LiveData](https://api.vesselfinder.com/docs/livedata.html) geographic area of coverage. Credit-based API keys for VesselFinder will not work; users must contact VesselFinder to obtain a subscription-based, fixed-fee API key for their desired area of coverage.
### Air Traffic APIs
- [adsb.fi](https://adsb.fi/), [airplanes.live](https://airplanes.live/), and [adsb.lol](https://adsb.lol/) are supported without any API key provided.
- [ADS-B Exchange](https://www.adsbexchange.com/) is supported with a user-provided API key available through [RapidAPI](https://rapidapi.com/adsbx/api/adsbexchange-com1).
- [OpenSky](https://opensky-network.org/) is supported with an optional API key to increase request limits. In testing, it has been observed that OpenSky does not reliably broadcast aircraft category codes, and air contacts may not auto-assign the correct CoT types if using OpenSky as the data source.
### Hardware
#### RTL-SDR
- This plugin supports offline ADS-B or AIS monitoring with a RTL-SDR receiver connected via USB. Follow the setup instructions below:
  - Install the RTL-SDR Driver App from Signalware, [available on Play Store](https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro).
  - Disable battery optimization for the RTL-SDR Driver App/allow to run in background
  - Select ADS-B or AIS data source as "USB: RTL-SDR" (Note: users cannot monitor both AIS and ADS-B simultaneously with an RTL-SDR. Users must set the RTL-SDR for either AIS or ADS-B, but can use an API service at the same time for the other data source)
  - Click "START" next to the RTL-SDR port number to automatically launch the RTL-SDR service with the specified port number
  - Click "START SYNC" to begin monitoring

Users should be aware that monitoring AIS and ADS-B requires different antennas for each protocol. The ADS-B antenna often packaged with RTL-SDR hardware will not work on AIS frequencies. To monitor AIS, users must use an appropriate marine VHF antenna working at 162MHz. Suitable antennas are often listed with a working range of 136-174MHz. ADS-B monitoring requires a 1090MHz antenna.
## Installation

- Install ATAK-CIV version matching the version number supported by Air+Sea build
- Install the Air+Sea plugin APK
- Load the plugin in ATAK:
    - Open ATAK-->Settings-->Tool Preferences-->Package Management
    - Scroll to Air+Sea and check "Loaded"
    - Air+Sea plugin should now appear in ATAK main menu

## Build

Requires ATAK-CIV SDK and Android Studio with Java.

## License

[GNU General Public License v3.0](LICENSE)
