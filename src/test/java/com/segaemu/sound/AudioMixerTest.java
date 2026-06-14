package com.segaemu.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the device-independent mixing path of {@link AudioMixer}. */
class AudioMixerTest {

    private AudioMixer mixer;
    private Ym2612 ym;
    private Sn76489 psg;

    @BeforeEach
    void setUp() {
        mixer = new AudioMixer(GenesisSystemFps());
        ym = new Ym2612();
        psg = new Sn76489();
    }

    private static double GenesisSystemFps() {
        return 59.92;
    }

    private static int peak(int[] a) {
        int p = 0;
        for (int s : a) {
            p = Math.max(p, Math.abs(s));
        }
        return p;
    }

    @Test
    void silenceInGivesSilenceOut() {
        int[] l = new int[1000];
        int[] r = new int[1000];
        mixer.mix(ym, psg, l, r, l.length);
        assertEquals(0, peak(l));
        assertEquals(0, peak(r));
    }

    @Test
    void psgToneReachesTheMix() {
        psg.write(0x80 | (0 << 5) | 0x00); // tone ch0 low nibble
        psg.write(0x10);                   // high bits -> a mid period
        psg.write(0x80 | (0 << 5) | 0x10); // volume ch0 = 0 (loudest)

        int[] l = new int[4000];
        int[] r = new int[4000];
        mixer.mix(ym, psg, l, r, l.length);
        assertTrue(peak(l) > 0, "PSG tone present in left");
        assertTrue(peak(r) > 0, "PSG tone present in right");
    }

    @Test
    void fmAndPsgStayWithin16Bit() {
        // Loud FM channel (algorithm 7) ...
        ym.writeRegister(0, 0xB0, 0x07);
        for (int slot = 0; slot < 4; slot++) {
            ym.writeRegister(0, 0x40 + slot * 4, 0x00);
            ym.writeRegister(0, 0x50 + slot * 4, 0x1F);
            ym.writeRegister(0, 0x30 + slot * 4, 0x01);
        }
        ym.writeRegister(0, 0xA4, (4 << 3) | 0x02);
        ym.writeRegister(0, 0xA0, 0x80);
        ym.writeRegister(0, 0xB4, 0xC0);
        ym.writeRegister(0, 0x28, 0xF0);
        // ... plus a loud PSG tone and noise.
        psg.write(0x80 | 0x00);
        psg.write(0x08);
        psg.write(0x80 | 0x10);          // ch0 loud
        psg.write(0x80 | (3 << 5) | 0x04); // white noise
        psg.write(0x80 | (3 << 5) | 0x10); // noise loud

        int[] l = new int[8000];
        int[] r = new int[8000];
        mixer.mix(ym, psg, l, r, l.length);

        for (int i = 0; i < l.length; i++) {
            assertTrue(l[i] >= -32768 && l[i] <= 32767, "left in range");
            assertTrue(r[i] >= -32768 && r[i] <= 32767, "right in range");
        }
        assertTrue(peak(l) > 0, "a busy mix produces output");
    }

    @Test
    void frameSampleCountAveragesToSampleRate() {
        long total = 0;
        int frames = 600;
        for (int i = 0; i < frames; i++) {
            total += mixer.nextFrameSampleCount();
        }
        double perSecond = total / (frames / 59.92);
        assertTrue(Math.abs(perSecond - AudioMixer.SAMPLE_RATE) < 50,
                "long-run rate ~44100, got " + perSecond);
    }
}
