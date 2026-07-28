# RoadSense hardware

This directory is the controlled source for future electrical, mechanical and
manufacturing artifacts.

## Required before a hardware alpha

- selected MCU, IMU, power input and BLE antenna;
- schematic and PCB revision;
- automotive input protection and fuse strategy;
- connector and mounting design;
- BOM with manufacturer part numbers and approved alternates;
- programming/manufacturing test fixture;
- calibration process;
- enclosure thermal/vibration review;
- revision-specific assembly and test instructions.

The existing owner ESP32 assembly may be documented as a development fixture,
but it is not a production design.

LoRa, emergency energy reserve and cached-phone-GPS behavior belong to a future
mesh-capable hardware revision after v1 evidence gates pass.
