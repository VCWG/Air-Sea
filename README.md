# Air+Sea

An open source ATAK-CIV plugin that displays real-time ship and aircraft positions from AIS and ADS-B data sources as CoT markers.

https://github.com/user-attachments/assets/e23ff31c-7f9e-4da3-b708-9757700f6cca
## Features

- Converts real-time ship and aircraft position information from AIS and ADS-B to CoT standard markers
- Gathers and displays all ships and aircraft within a map-selected area
- Tags ships and aircraft with their current speed and heading (if available)
- Identify which ships or aircraft are underway at a glance; contacts with speed vectors are labeled with an arrow indicating their direction of travel
- CoT markers are labeled with ship name or aircraft callsign
- Includes additional ship information remarks (MMSI, rate of turn, IMO Number, Ship Type, Draught, Destination, ETA)
- Includes additional aircraft information remarks (ICAO, Registration, Type, Altitude, Speed, Squawk)
- User can select update frequency and broadcast status

## Setup
### Maritime Tracking APIs
- This plugin uses [aisstream.io](https://aisstream.io/) as the ship data source.
- This data source is free, but users must register on aisstream.io and generate an API key to input into the plugin.
### Air Traffic APIs
- [adsb.fi](https://adsb.fi/), [airplanes.live](https://airplanes.live/), and [adsb.lol](https://adsb.lol/) are supported without any API key provided.
- [OpenSky](https://opensky-network.org/) is supported with an optional API key to increase request limits.
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
