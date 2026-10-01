package com.tosasitill.bosemelody;

import java.util.ArrayList;
import java.util.List;

/** Pure BMAP packet codec; no Bluetooth or Android dependencies. */
final class BoseBmap {
    static final int OP_SET = 0;
    static final int OP_GET = 1;
    static final int OP_SETGET = 2;
    static final int OP_STATUS = 3;
    static final int OP_ERROR = 4;
    static final int OP_START = 5;
    static final int OP_RESULT = 6;
    static final int OP_PROCESSING = 7;

    static final int BLOCK_BATTERY = 2;
    static final int FUNC_BATTERY = 2;
    static final int BLOCK_AUDIO_MODES = 31;
    static final int FUNC_CURRENT_MODE = 3;
    static final int BLOCK_SETTINGS = 1;
    static final int FUNC_CNC = 5;

    private BoseBmap() {
    }

    static byte[] packet(int block, int function, int operator, byte[] payload) {
        int length = payload == null ? 0 : payload.length;
        if (length > 255) throw new IllegalArgumentException("BMAP payload too large");
        byte[] frame = new byte[4 + length];
        frame[0] = (byte) block;
        frame[1] = (byte) function;
        frame[2] = (byte) (operator & 0x0f);
        frame[3] = (byte) length;
        if (payload != null) System.arraycopy(payload, 0, frame, 4, length);
        return frame;
    }

    static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder out = new StringBuilder(data.length * 3);
        for (byte value : data) {
            if (out.length() > 0) out.append(' ');
            int v = value & 0xff;
            out.append(Character.forDigit(v >>> 4, 16));
            out.append(Character.forDigit(v & 0x0f, 16));
        }
        return out.toString();
    }

    static final class Frame {
        final int block;
        final int function;
        final int operator;
        final byte[] payload;

        Frame(int block, int function, int operator, byte[] payload) {
            this.block = block;
            this.function = function;
            this.operator = operator;
            this.payload = payload;
        }

        boolean matches(int wantedBlock, int wantedFunction) {
            return block == wantedBlock && function == wantedFunction;
        }

        int u8(int index) {
            return payload[index] & 0xff;
        }
    }

    /** Incremental parser for concatenated BMAP frames and Bluetooth chunks. */
    static final class Parser {
        private byte[] buffer = new byte[512];
        private int size;

        void feed(byte[] data, int offset, int count, Sink sink) {
            if (data == null || count <= 0) return;
            ensure(size + count);
            System.arraycopy(data, offset, buffer, size, count);
            size += count;
            int position = 0;
            while (size - position >= 4) {
                int length = buffer[position + 3] & 0xff;
                int frameLength = 4 + length;
                if (frameLength > 259) {
                    position++;
                    continue;
                }
                if (size - position < frameLength) break;
                byte[] raw = new byte[frameLength];
                System.arraycopy(buffer, position, raw, 0, frameLength);
                position += frameLength;
                int operator = raw[2] & 0x0f;
                byte[] payload = new byte[length];
                System.arraycopy(raw, 4, payload, 0, length);
                sink.onFrame(new Frame(raw[0] & 0xff, raw[1] & 0xff,
                        operator, payload));
            }
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, size - position);
                size -= position;
            }
        }

        private void ensure(int wanted) {
            if (wanted > 64 * 1024) throw new IllegalArgumentException("BMAP stream buffer limit exceeded");
            if (wanted <= buffer.length) return;
            int capacity = buffer.length;
            while (capacity < wanted) capacity *= 2;
            byte[] grown = new byte[capacity];
            System.arraycopy(buffer, 0, grown, 0, size);
            buffer = grown;
        }
    }

    interface Sink {
        void onFrame(Frame frame);
    }

    static Frame find(List<Frame> frames, int block, int function) {
        for (Frame frame : frames) {
            if (frame.matches(block, function)) return frame;
        }
        return null;
    }

    static List<Frame> list(Frame... frames) {
        List<Frame> result = new ArrayList<>();
        for (Frame frame : frames) if (frame != null) result.add(frame);
        return result;
    }
}
