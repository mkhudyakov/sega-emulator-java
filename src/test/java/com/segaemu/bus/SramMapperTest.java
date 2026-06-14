package com.segaemu.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.segaemu.cartridge.Rom;
import com.segaemu.io.Controller;
import com.segaemu.sound.Sn76489;
import com.segaemu.sound.Ym2612;
import com.segaemu.vdp.Vdp;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for battery SRAM, the SSF2 mapper and the region/version register. */
class SramMapperTest {

    private static void putSegaName(byte[] img) {
        byte[] name = "SEGA MEGA DRIVE ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, img, 0x100, name.length);
    }

    private static GenesisBus bus(Rom rom) {
        return new GenesisBus(rom, new Vdp(), new Controller(), new Controller(),
                new Ym2612(), new Sn76489());
    }

    private static byte[] withSram(int size) {
        byte[] img = new byte[size];
        putSegaName(img);
        int h = 0x100;
        img[h + 0xB0] = 'R';
        img[h + 0xB1] = 'A';
        // SRAM start $200000, end $200FFF (big-endian dwords).
        img[h + 0xB4] = 0x00; img[h + 0xB5] = 0x20; img[h + 0xB6] = 0x00; img[h + 0xB7] = 0x00;
        img[h + 0xB8] = 0x00; img[h + 0xB9] = 0x20; img[h + 0xBA] = 0x0F; img[h + 0xBB] = (byte) 0xFF;
        return img;
    }

    @Test
    void sramReadsBackWhatWasWritten() {
        GenesisBus b = bus(Rom.fromBytes(withSram(0x4000)));
        assertTrue(b.hasSram());
        b.write8(0x200000, 0xAB);
        b.write8(0x200001, 0xCD);
        assertEquals(0xAB, b.read8(0x200000));
        assertEquals(0xCD, b.read8(0x200001));
    }

    @Test
    void sramSnapshotAndReloadRoundTrip() {
        GenesisBus b = bus(Rom.fromBytes(withSram(0x4000)));
        b.write8(0x200010, 0x42);
        byte[] snap = b.sramSnapshot();

        GenesisBus b2 = bus(Rom.fromBytes(withSram(0x4000)));
        assertEquals(0x00, b2.read8(0x200010));
        b2.loadSram(snap);
        assertEquals(0x42, b2.read8(0x200010));
    }

    @Test
    void sramDisableExposesRomBeneath() {
        GenesisBus b = bus(Rom.fromBytes(withSram(0x4000)));
        b.write8(0x200000, 0xAB);
        b.write8(0xA130F1, 0x00); // disable SRAM
        assertNotEquals(0xAB, b.read8(0x200000), "with SRAM off the ROM shows through");
        b.write8(0xA130F1, 0x01); // re-enable
        assertEquals(0xAB, b.read8(0x200000));
    }

    @Test
    void ssf2MapperRemapsRomBank() {
        // 1 MB image: bank 0 ($00000-$7FFFF) = $11, bank 1 ($80000-$FFFFF) = $22.
        byte[] img = new byte[0x100000];
        java.util.Arrays.fill(img, 0, 0x80000, (byte) 0x11);
        java.util.Arrays.fill(img, 0x80000, 0x100000, (byte) 0x22);
        putSegaName(img);
        GenesisBus b = bus(Rom.fromBytes(img));

        assertEquals(0x22, b.read8(0x080000), "window 1 defaults to bank 1");
        b.write8(0xA130F3, 0x00);          // map window 1 -> bank 0
        assertEquals(0x11, b.read8(0x080000), "window 1 now reads bank 0");
    }

    @Test
    void versionRegisterReportsPalForEuropeRom() {
        byte[] img = new byte[0x4000];
        putSegaName(img);
        byte[] region = "E               ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(region, 0, img, 0x100 + 0xF0, region.length);
        GenesisBus b = bus(Rom.fromBytes(img));
        assertEquals(0x40, b.read8(0xA10001) & 0x40, "PAL bit set in version register");
    }

    @Test
    void versionRegisterReportsNtscForUsaRom() {
        byte[] img = new byte[0x4000];
        putSegaName(img);
        byte[] region = "U               ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(region, 0, img, 0x100 + 0xF0, region.length);
        GenesisBus b = bus(Rom.fromBytes(img));
        assertEquals(0, b.read8(0xA10001) & 0x40, "PAL bit clear for NTSC");
        assertEquals(0x80, b.read8(0xA10001) & 0x80, "overseas bit set");
    }
}
