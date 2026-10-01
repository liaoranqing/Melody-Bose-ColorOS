package com.tosasitill.bosemelody;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Host-runnable self test for BMAP packet encoding, framing, and Bose battery fixtures. */
public final class BoseBmapTest {
    private BoseBmapTest() {
    }

    public static void main(String[] args) {
        assertBytes(new byte[]{2, 2, 1, 0}, BoseBmap.packet(2, 2, BoseBmap.OP_GET, null));
        assertBytes(new byte[]{31, 3, 5, 2, 0, 0},
                BoseBmap.packet(31, 3, BoseBmap.OP_START, new byte[]{0, 0}));
        assertBytes(new byte[]{31, 3, 5, 2, 2, 0},
                BoseBmap.packet(31, 3, BoseBmap.OP_START, new byte[]{2, 0}));
        assertCncSetgetFixture();
        assertBatteryFixture();
        assertFragmentedFrames();
        System.out.println("BoseBmapTest OK");
    }

    private static void assertCncSetgetFixture() {
        // Preserve autoCNC/spatial/wind/ANC and update only CNC=7 at [31.10].
        byte[] current = {3, 0, 2, 0, 1};
        byte[] payload = current.clone();
        payload[0] = 7;
        assertBytes(new byte[]{31, 10, 2, 5, 7, 0, 2, 0, 1},
                BoseBmap.packet(31, 10, BoseBmap.OP_SETGET, payload));
        if (current[0] != 3 || payload[1] != 0 || payload[2] != 2
                || payload[3] != 0 || payload[4] != 1) {
            throw new AssertionError("CNC update changed unrelated AudioModes settings");
        }
    }

    private static void assertBatteryFixture() {
        byte[] fixture = hex("3cffff013cffff023cffff0450ffff03");
        int[] battery = parseBattery(fixture);
        if (battery[0] != 60 || battery[1] != 60 || battery[2] != 80 || battery[3] != 60) {
            throw new AssertionError("battery fixture mismatch");
        }
    }

    private static void assertFragmentedFrames() {
        final List<BoseBmap.Frame> frames = new ArrayList<>();
        BoseBmap.Parser parser = new BoseBmap.Parser();
        byte[] bytes = BoseBmap.packet(31, 3, BoseBmap.OP_RESULT, new byte[]{2, 0});
        parser.feed(bytes, 0, 3, frames::add);
        if (!frames.isEmpty()) throw new AssertionError("partial frame emitted");
        parser.feed(bytes, 3, bytes.length - 3, frames::add);
        if (frames.size() != 1 || !frames.get(0).matches(31, 3)
                || frames.get(0).operator != BoseBmap.OP_RESULT || frames.get(0).u8(0) != 2) {
            throw new AssertionError("fragmented frame parse mismatch");
        }
    }

    private static int[] parseBattery(byte[] payload) {
        int[] values = {-1, -1, -1, -1};
        for (int offset = 0; offset + 3 < payload.length; offset += 4) {
            int level = payload[offset] & 0xff;
            int component = payload[offset + 3] & 0xff;
            if (level > 100) continue;
            if (component == 1) values[1] = level;
            else if (component == 2) values[0] = level;
            else if (component == 3) values[2] = level;
            else if (component == 4) values[3] = level;
        }
        return values;
    }

    private static byte[] hex(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i += 2) {
            out.write(Integer.parseInt(value.substring(i, i + 2), 16));
        }
        return out.toByteArray();
    }

    private static void assertBytes(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) throw new AssertionError("frame length mismatch");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) throw new AssertionError("frame byte mismatch at " + i);
        }
    }
}
