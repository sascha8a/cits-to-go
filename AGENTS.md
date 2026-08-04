# Android App

* For each new feature, increase the minor version
* For each bugfix, increase the fix version

# Packet Generation

* Every new or changed over-the-air packet generator must include a JVM unit test that writes the complete frame to a PCAP and validates it with the Wireshark `tshark` dissector.
* The test must assert that Wireshark recognizes the expected protocol and message identifier and that `_ws.malformed` does not match the packet.
* `tshark` is a required test dependency. From `android-app/`, run the suite with `nix shell nixpkgs#wireshark-cli --command ./gradlew testDebugUnitTest` when it is not installed globally.
