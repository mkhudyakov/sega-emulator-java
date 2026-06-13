package com.segaemu.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RomTest {

    /** Build a minimal but valid 0x4000-byte Mega Drive image. */
    private static byte[] syntheticRom() {
        byte[] data = new byte[0x4000];
        // Reset vectors.
        writeLong(data, 0x000000, 0x00FF0000); // initial SP
        writeLong(data, 0x000004, 0x00000200); // initial PC
        // Console name and titles in the header at $100.
        put(data, 0x100, "SEGA MEGA DRIVE ");
        put(data, 0x120, "TEST GAME (DOMESTIC)");
        put(data, 0x150, "TEST GAME");
        put(data, 0x190, "J6              "); // joypad + 6-button
        // Checksum word at $18E.
        data[0x18E] = (byte) 0xAB;
        data[0x18F] = (byte) 0xCD;
        put(data, 0x1F0, "U");
        return data;
    }

    private static void put(byte[] d, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, d, off, b.length);
    }

    private static void writeLong(byte[] d, int off, int v) {
        d[off] = (byte) (v >> 24);
        d[off + 1] = (byte) (v >> 16);
        d[off + 2] = (byte) (v >> 8);
        d[off + 3] = (byte) v;
    }

    @Test
    void parsesValidHeader() {
        Rom rom = Rom.fromBytes(syntheticRom());
        RomHeader h = rom.header();
        assertTrue(h.isValidConsoleName());
        assertEquals("SEGA MEGA DRIVE", h.consoleName());
        assertEquals("TEST GAME", h.overseasTitle());
        assertEquals(0xABCD, h.checksum());
        assertTrue(h.supportsSixButton());
        assertEquals(0x00FF0000, rom.initialStackPointer());
        assertEquals(0x00000200, rom.initialProgramCounter());
    }

    @Test
    void rejectsNonSegaImage() {
        byte[] junk = new byte[0x4000];
        assertThrows(InvalidRomException.class, () -> Rom.fromBytes(junk));
    }

    @Test
    void rejectsTinyFile() {
        assertThrows(InvalidRomException.class, () -> Rom.fromBytes(new byte[16]));
    }
}
