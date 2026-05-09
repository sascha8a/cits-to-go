package org.opentrafficmap.citslogger;

final class CitsLineParser {
    static final class Meta {
        final String nodeId;
        final String packetTopic;
        final String hardwareVariant;
        final String firmwareVersion;

        Meta(String nodeId, String packetTopic, String hardwareVariant, String firmwareVersion) {
            this.nodeId = nodeId;
            this.packetTopic = packetTopic;
            this.hardwareVariant = hardwareVariant;
            this.firmwareVersion = firmwareVersion;
        }
    }

    static Meta parseMeta(String line) {
        int at = line.indexOf("CITSMETA,");
        if (at < 0) return null;
        line = line.substring(at);
        String[] parts = line.split(",", 5);
        if (parts.length < 5) return null;
        return new Meta(parts[1].trim(), parts[2].trim(), parts[3].trim(), parts[4].trim());
    }

    static CitsPacket parsePacket(String line) throws IllegalArgumentException {
        int at = line.indexOf("CITS,");
        if (at < 0) return null;
        line = line.substring(at);
        String[] parts = line.split(",", 8);
        if (parts.length != 8) throw new IllegalArgumentException("Malformed CITS line");

        long seconds = Long.parseLong(parts[1].trim());
        int usec = Integer.parseInt(parts[2].trim());
        int freq = Integer.parseInt(parts[3].trim());
        int rssi = Integer.parseInt(parts[4].trim());
        int caplen = Integer.parseInt(parts[5].trim());
        boolean truncated = !"0".equals(parts[6].trim());
        byte[] payload = decodeHex(parts[7].trim());
        if (payload.length != caplen) {
            throw new IllegalArgumentException("caplen=" + caplen + " but decoded " + payload.length + " bytes");
        }
        return new CitsPacket(seconds, usec, freq, rssi, caplen, caplen, truncated, payload);
    }

    private static byte[] decodeHex(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("Odd-length hex payload");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Non-hex payload");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
