package com.segaemu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.segaemu.cartridge.Rom;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Round-trip tests for the Phase 10 save-state serialization. */
class SaveStateTest {

    private GenesisSystem sys;

    @BeforeEach
    void setUp() {
        sys = new GenesisSystem(Rom.fromBytes(buildRom()));
    }

    /**
     * A tiny ROM whose program loops {@code ADDQ.W #1,($FF0000)} so work RAM
     * changes deterministically every frame — giving the save-state test real
     * divergence to detect.
     */
    private static byte[] buildRom() {
        byte[] img = new byte[0x8000];
        byte[] name = "SEGA MEGA DRIVE ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, img, 0x100, name.length);
        // Reset vectors: SSP and PC.
        writeLong(img, 0x000000, 0x00FF1000); // initial stack pointer
        writeLong(img, 0x000004, 0x00000200); // initial PC
        // Program at $200: ADDQ.W #1,($00FF0000).L ; BRA back to $200
        int p = 0x200;
        img[p++] = 0x52; img[p++] = 0x79;                 // ADDQ.W #1,(xxx).L
        img[p++] = 0x00; img[p++] = (byte) 0xFF; img[p++] = 0x00; img[p++] = 0x00;
        img[p++] = 0x60; img[p++] = (byte) 0xF8;          // BRA -8 -> $200
        return img;
    }

    private static void writeLong(byte[] a, int off, int v) {
        a[off] = (byte) (v >> 24);
        a[off + 1] = (byte) (v >> 16);
        a[off + 2] = (byte) (v >> 8);
        a[off + 3] = (byte) v;
    }

    private byte[] save() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sys.saveState(out);
        return out.toByteArray();
    }

    private void load(byte[] state) throws Exception {
        sys.loadState(new ByteArrayInputStream(state));
    }

    @Test
    void stateRestoresAfterDivergence() throws Exception {
        sys.stepFrame();
        byte[] snapshot = save();
        int valueAtSave = sys.readWorkRamWord(0x0000);

        // Run on so the machine state changes ...
        for (int i = 0; i < 20; i++) {
            sys.stepFrame();
        }
        assertNotEquals(valueAtSave, sys.readWorkRamWord(0x0000), "state diverged");

        // ... then restoring must rewind exactly.
        load(snapshot);
        assertEquals(valueAtSave, sys.readWorkRamWord(0x0000), "RAM restored");
    }

    @Test
    void saveLoadSaveIsByteStable() throws Exception {
        for (int i = 0; i < 5; i++) {
            sys.stepFrame();
        }
        byte[] first = save();
        load(first);
        byte[] second = save();
        // If every saved field is also loaded, re-saving yields identical bytes.
        assertArrayEquals(first, second);
    }

    @Test
    void continuesDeterministicallyAfterReload() throws Exception {
        sys.stepFrame();
        byte[] snapshot = save();

        // Reference: run 15 frames from the snapshot.
        for (int i = 0; i < 15; i++) sys.stepFrame();
        int reference = sys.readWorkRamWord(0x0000);

        // Reload and run the same 15 frames — must reach the same value.
        load(snapshot);
        for (int i = 0; i < 15; i++) sys.stepFrame();
        assertEquals(reference, sys.readWorkRamWord(0x0000));
    }
}
