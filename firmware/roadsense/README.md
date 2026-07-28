# RoadSense firmware

ESP32-oriented firmware for the optional RoadSense sensor.

The initial scaffold:

- boots without network credentials or cloud endpoints;
- exposes versioned protocol types;
- reserves bounded sampling/detection/storage/transport tasks;
- contains no LoRa, crash/SOS or OBD write behavior.

## Build

Install PlatformIO Core, then:

```text
pio run
```

The `esp32dev` environment is a development fixture only. The production MCU,
board revision and pin map remain hardware decisions backed by a controlled BOM.
