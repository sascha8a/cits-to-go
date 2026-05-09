# ITS-G5 Receiver Firmware

## Flashing a pre-compiled firmware

If you can't or don't want to build the firmware yourself, you can download the latest versions of the firmware, bootloader, and partition table `.bin` files, and then flash them with a command like the following:
```
esptool --chip esp32c5 -p /dev/ttyACM0 -b 921600 --before=default-reset --after=hard-reset write-flash --flash-mode dio --flash-freq 80m --flash-size 8MB 0x2000 bootloader_0.1.0.bin 0x8000 partition-table_8M_0.1.0.bin 0x20000 its-g5-receiver-firmware_i5r-r1_0.3.0.bin
```

This still requires the esp-idf for the `esptool` command. Make sure to replace the device name of the serial port with the one where the ESP is connected.

## Cloning

```
git clone https://codeberg.org/opentrafficmap/its-g5-receiver-firmware.git
cd its-g5-receiver-firmware
git submodule update --init --recursive
```

## ESP32-C5 Devboard

We recommend using our custom hardware, which can be found in [this repo](https://codeberg.org/opentrafficmap/its-g5-receiver).
However, you can also use a ESP32-C5-WIFI6-KIT devboard.

If you are using our custom hardware, you can skip this section.

If you are using the devboard, make the following connections from the Ethernet module (left) to the ESP32 devboard (right):
```
MOSI <-> GPIO7
MISO <-> GPIO2
SCLK <-> GPIO6
CS   <-> GPIO4
INT  <-> GPIO9
```

The ESP32-C5-WIFI6-KIT has a 100 nF capacitor on GPIO6, which will prevent the SPI communication from working.
This can be solved by de-soldering the zero-ohm resistor to the right of the 5V pin (named R39 in [the schematics](https://files.waveshare.com/wiki/ESP32-C5-WIFI6-KIT-NXRX/ESP32-C5-WIFI6-KIT-NXRX-Schematic.pdf)).
Alternatively, you can edit the sdkconfig to use a different pin.

Copy the correct sdkconfig file over the default one:
```
# For W5500
cp sdkconfig.proto-w5500 sdkconfig
# or alternatively, for ENC28J60:
cp sdkconfig.proto-enc28j60 sdkconfig
```

If you make any changes to the hardware/sdkconfig, make sure to set `HW_VARIANT` to `custom` in the sdkconfig,
otherwise you may receive incompatible OTA updates if you connect to the official MQTT server.

## Building

The `install.sh` script needs to be run once to install the ESP32 toolchain files:
```
esp-idf/install.sh
```

To enter the ESP-IDF environment, you need to source the `export.sh` script once per shell:
```
. esp-idf/export.sh
```

Afterwards, you can build the project:
```
idf.py build
```

To flash the firmware and monitor the output, you can use the following command:
```
idf.py -p /dev/ttyACM0 -b 921600 flash monitor -b 115200
```

## Seeed Studio XIAO ESP32-C5 USER LED

This XIAO build uses the onboard L/USER LED directly, not the original firmware's external WS2812 LED strip. The LED is configured as GPIO27 and active-low. Each accepted/logged C-ITS packet pulses the LED for `CONFIG_CITS_RX_LED_PULSE_MS` milliseconds.

