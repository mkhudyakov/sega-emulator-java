package com.segaemu.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControllerTest {

    private Controller pad;

    @BeforeEach
    void setUp() {
        pad = new Controller();
    }

    private void th(int level) {
        pad.writeData(level << 6);
    }

    @Test
    void threeButtonGroupsMultiplexOnTh() {
        pad.press(Controller.Button.A);
        pad.press(Controller.Button.LEFT);

        th(1); // high group: Left in bit2
        assertEquals(0, pad.readData() & 0x04, "Left pressed reads 0 in high group");
        th(0); // low group: A in bit4
        assertEquals(0, pad.readData() & 0x10, "A pressed reads 0 in low group");
    }

    @Test
    void sixButtonRevealsExtraButtonsOnFourthCycle() {
        pad.setSixButton(true);
        pad.startFrame();
        pad.press(Controller.Button.Z);
        pad.press(Controller.Button.MODE);

        // Three TH high→low cycles, then read the signature and the extras.
        th(1); pad.readData();
        th(0); pad.readData();
        th(1); pad.readData();
        th(0); pad.readData();
        th(1); pad.readData();
        th(0);
        assertEquals(0, pad.readData() & 0x0F, "3rd low: signature nibble is 0");
        th(1);
        int extra = pad.readData();
        assertEquals(0, extra & 0x01, "Z pressed reads 0 (bit0)");
        assertEquals(0, extra & 0x08, "Mode pressed reads 0 (bit3)");
    }

    @Test
    void sixButtonResetsEachFrame() {
        pad.setSixButton(true);
        pad.startFrame();
        for (int i = 0; i < 3; i++) {
            th(1);
            th(0);
        }
        // After three cycles a read at TH=1 would be the extras; a new frame resets.
        pad.startFrame();
        th(1);
        // Fresh sequence -> the normal high group (bit6 echoes TH), not extras.
        assertNotEquals(0x76, pad.readData() & 0x7F);
    }
}
