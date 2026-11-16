# Changelog

## 1.1.0

- **RPM bar:** engine RPM as flat segments along the top rim of the map, like the stock dash (white on dark maps,
  dark on light maps, red near the rev limit). Needs a Bluetooth OBD-II adapter (ELM327 type, Bluetooth LE or
  classic) on the bike's diagnostic port: Settings › RPM bar › Choose OBD adapter. Hidden when there's no live reading.
- **Bike data test:** a button that records everything the dash sends while you flip through its screens, asks the
  dash for data with test requests (bike parked), and reads the standard engine values from the OBD adapter.
  Saves a report to Download/Dash Nav, so the next version can show odometer, range and more.

## 1.0.1

Updates over 1.0.0 in place (same signing key, settings kept). Android 14+, dash firmware 11.61+.

- Sharper picture on the dash: back to the keyframe and frame-rate settings that read well on real dashes
- Starting navigation in Google Maps after the dash is already running now shows the map (it could stay black)
- If the map ever goes black, Dash Nav notices and brings Maps back by itself
- Android 14 phones supported
- Ride log now records stream health, what the dash sends back, and phone temperature (still no locations, names or numbers)
- Test mode removed from the release app

## 1.0.0

First release. Android 15+, dash firmware 11.61+.

- Live Google Maps on the dash; moves over by itself when you start navigation
- Song and time on the dash's band; caller name and call timer during calls
- Joystick menu (Down, Down): play/pause, next, previous, phone screen
- Wallpaper for the idle dash; optional ride videos and logs for bug reports
- Nothing leaves your phone: no account, no servers, no analytics
