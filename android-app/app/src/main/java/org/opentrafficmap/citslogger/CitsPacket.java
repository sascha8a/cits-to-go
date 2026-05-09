package org.opentrafficmap.citslogger;

final class CitsPacket {
    final long seconds;
    final int microseconds;
    final int frequencyMhz;
    final int rssiDbm;
    final int caplen;
    final int originalLength;
    final boolean truncated;
    final byte[] payload;

    CitsPacket(long seconds, int microseconds, int frequencyMhz, int rssiDbm,
               int caplen, int originalLength, boolean truncated, byte[] payload) {
        this.seconds = seconds;
        this.microseconds = microseconds;
        this.frequencyMhz = frequencyMhz;
        this.rssiDbm = rssiDbm;
        this.caplen = caplen;
        this.originalLength = originalLength;
        this.truncated = truncated;
        this.payload = payload;
    }
}
