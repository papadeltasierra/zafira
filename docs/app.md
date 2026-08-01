# Android App

## Requirements
- Accepts streamed input from HCISnoop, ideally without needing any manual interaction but existing configuration is OK
- Identifies flows between the phone and an identified Pioneer radio; identification can be via name and/or MAC address and this can be a one-time set-up for the app
- Accepts the name of a BLE device to which BLE writes-no-response will be sent; this is another one-time set-up for the app
- Parses the Pioneer applink protocol looking for notifications of media being played either via the radio or the phone.  The information require is:
  - Radio station Id if radio is playing
  - Artist and track if phone is streaming audio
  - Caller/callee if a phone calll is being made/taken
- Sends the media information to the BLE device using a custom profile and custom attributes
- Additionally sends the Unix time to the BLE device when it first connects and then every 30 minutes later
- The app must handle connecting to the BLE device.
