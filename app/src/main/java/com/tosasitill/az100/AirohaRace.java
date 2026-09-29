package com.tosasitill.az100;

/**
 * Airoha SPP framing, reimplemented from the wire format of Audio Connect
 * 4.4.0 ({@code com.airoha.liblinker.transport.H4Transport},
 * {@code com.airoha.libbase.RaceCommand.packet.RacePacket}).
 *
 * Over the RFCOMM socket the headset speaks "H4": a one byte channel, one byte
 * packet type, then a length.  Three channel values ever appear on this link:
 *
 * <pre>
 *   2  ACL          04 0f &lt;len LE16&gt; <body>
 *   4  HCI event    &lt;event&gt; &lt;len+3&gt; ...
 *   5  RACE         05 &lt;type&gt; &lt;len LE16&gt; &lt;race id LE16&gt; &lt;payload&gt;
 * </pre>
 *
 * The RACE length counts the 2 id bytes plus the payload, so a full frame is
 * {@code 4 + len} bytes; that is the only non-obvious part of the format (it is
 * what {@code H4Transport.parseRace} implements).
 *
 * MMI commands are RACE frames with type {@code 0x5A} (90, "needs response");
 * the headset answers with {@code 0x5B} (91, response) and pushes state
 * changes as {@code 0x5D} (93, indication).
 */
final class AirohaRace {

    /** RACE packet types (com.airoha.libbase.RaceCommand.constant.RaceType). */
    static final int TYPE_CMD = 0x5A;   // 90, expects an answer
    static final int TYPE_RESP = 0x5B;  // 91, answer to a command
    static final int TYPE_IND = 0x5D;   // 93, unsolicited notification

    /** Race ids used by the module (RaceIdPana, RaceId). */
    static final int RID_GET_OUTSIDE = 10;      // RACE_CUSTOMER_GET_OUTSIDE_CTRL
    static final int RID_SET_OUTSIDE = 11;      // RACE_BLUETOOTH_GET_BOX_BATTERY id reused by pana set
    static final int RID_GET_CRADLE = 64;       // RACE_CUSTOMER_GET_CRADLE_BATTERY
    static final int RID_GET_ADAPTIVE = 103;    // RACE_CUSTOMER_GET_ADAPTIVE_ANC
    static final int RID_SET_ADAPTIVE = 104;    // RACE_CUSTOMER_SET_ADAPTIVE_ANC
    static final int RID_GET_AGENT = 3284;      // RACE_BLUETOOTH_IS_AGENT_RIGHT_DEVICE
    static final int RID_GET_BATTERY = 3286;    // RACE_BLUETOOTH_TWS_GET_BATTERY

    private AirohaRace() {
    }

    /**
     * Build one MMI command frame.  Exactly what {@code RacePacket.getRaw()}
     * produces for a type 90 packet: {@code 05 5A <len LE16> <id LE16> <payload>}
     * with {@code len = 2 + payload.length}.
     */
    static byte[] command(int raceId, byte[] payload) {
        int length = 2 + (payload == null ? 0 : payload.length);
        byte[] frame = new byte[4 + length];
        frame[0] = 5;
        frame[1] = (byte) TYPE_CMD;
        frame[2] = (byte) (length & 0xff);
        frame[3] = (byte) ((length >> 8) & 0xff);
        frame[4] = (byte) (raceId & 0xff);
        frame[5] = (byte) ((raceId >> 8) & 0xff);
        if (payload != null) System.arraycopy(payload, 0, frame, 6, payload.length);
        return frame;
    }

    static int u16le(byte[] data, int index) {
        return (data[index] & 0xff) | ((data[index + 1] & 0xff) << 8);
    }

    static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder builder = new StringBuilder(data.length * 3);
        for (byte value : data) {
            if (builder.length() > 0) builder.append(' ');
            int v = value & 0xff;
            builder.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 0xf, 16));
        }
        return builder.toString();
    }

    /** One parsed H4 packet as it came off the socket. */
    static final class Frame {
        final byte[] raw;

        Frame(byte[] raw) {
            this.raw = raw;
        }

        /** Channel byte: 5/21/7 are RACE, 2 ACL, 4 HCI event. */
        boolean race() {
            int channel = raw[0] & 0xff;
            return channel == 5 || channel == 0x15 || channel == 7;
        }

        /** RACE packet type (0x5A/0x5B/0x5D), 0 for non RACE packets. */
        int type() {
            return raw.length > 1 ? raw[1] & 0xff : 0;
        }

        /** Race id, or -1 when this is not a RACE frame. */
        int raceId() {
            return race() && raw.length >= 6 ? u16le(raw, 4) : -1;
        }

        /** Status byte shared by every MMI response, -1 when absent. */
        int status() {
            return raw.length > 6 ? raw[6] & 0xff : -1;
        }

        int u8(int index) {
            return raw[index] & 0xff;
        }
    }

    interface Sink {
        void onFrame(Frame frame);
    }

    /**
     * Incremental H4 parser.  Mirrors {@code H4Transport.parseRxDataToPacket}
     * byte for byte, including its resynchronisation rule (an unknown channel
     * byte -- or an ACL/HCI length that cannot be right -- drops exactly one
     * byte and starts over, which is how the stock parser recovers from noise).
     */
    static final class Parser {
        private byte[] buffer = new byte[4096];
        private int size;

        void feed(byte[] data, int offset, int count, Sink sink) {
            if (count <= 0) return;
            if (size + count > buffer.length) {
                int capacity = buffer.length;
                while (capacity < size + count) capacity *= 2;
                byte[] grown = new byte[capacity];
                System.arraycopy(buffer, 0, grown, 0, size);
                buffer = grown;
            }
            System.arraycopy(data, offset, buffer, size, count);
            size += count;

            int position = 0;
            while (true) {
                int length = frameLength(position);
                if (length == 0) break;         // need more bytes
                if (length < 0) {               // noise: skip one byte and retry
                    position++;
                    continue;
                }
                byte[] frame = new byte[length];
                System.arraycopy(buffer, position, frame, 0, length);
                position += length;
                sink.onFrame(new Frame(frame));
            }
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, size - position);
                size -= position;
            }
        }

        /** 0 = need more data, &lt;0 = drop one byte, &gt;0 = frame length. */
        private int frameLength(int position) {
            int available = size - position;
            if (available < 1) return 0;
            int channel = buffer[position] & 0xff;

            if (channel == 4) {                 // HCI event
                if (available >= 2) {
                    int event = buffer[position + 1] & 0xff;
                    if (event != 0xff && event != 0x0e && event != 0x0f) return -1;
                }
                if (available < 3) return 0;
                int length = 3 + (buffer[position + 2] & 0xff);
                return available >= length ? length : 0;
            }

            if (channel == 2) {                 // ACL
                if (available >= 3
                        && ((buffer[position + 1] & 0xff) != 0
                            || (buffer[position + 2] & 0xff) != 0x0f)) {
                    return -1;
                }
                if (available < 5) return 0;
                int body = u16le(buffer, position + 3);
                if (body == 0 || body > 1995) return -1;
                int length = 5 + body;
                return available >= length ? length : 0;
            }

            if (channel == 5 || channel == 0x15 || channel == 7) {   // RACE
                if (available < 4) return 0;
                int length = 4 + u16le(buffer, position + 2);
                if (length > 2000 || length < 6) return -1;
                return available >= length ? length : 0;
            }

            return -1;
        }
    }
}
