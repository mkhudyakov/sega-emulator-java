package com.segaemu.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the SN76489 PSG synthesis added in Phase 5. */
class Sn76489Test {

    private Sn76489 psg;

    @BeforeEach
    void setUp() {
        psg = new Sn76489();
    }

    private void setTonePeriod(int ch, int period) {
        psg.write(0x80 | (ch << 5) | (period & 0x0F)); // latch tone, low nibble
        psg.write((period >> 4) & 0x3F);               // data byte, high 6 bits
    }

    private void setVolume(int ch, int attenuation) {
        psg.write(0x80 | (ch << 5) | 0x10 | (attenuation & 0x0F));
    }

    @Test
    void periodRegisterAssemblesFromTwoWrites() {
        setTonePeriod(0, 0x1AC);
        assertEquals(0x1AC, psg.tonePeriod(0));
    }

    @Test
    void frequencyMatchesPeriod() {
        setTonePeriod(0, 0x1AC); // 428
        double expected = Sn76489.CLOCK / (32.0 * 0x1AC);
        assertEquals(expected, psg.channelFrequency(0), 0.001);
    }

    @Test
    void silentWhenAllChannelsAttenuated() {
        // Default attenuation is 0xF (off) for every channel.
        int[] out = new int[1000];
        psg.mix(out, out.length);
        for (int s : out) {
            assertEquals(0, s);
        }
    }

    @Test
    void toneProducesSquareWaveAtRoughlyTheRightFrequency() {
        setVolume(0, 0);          // loudest
        setTonePeriod(0, 0x1AC);  // ~261 Hz
        for (int ch = 1; ch <= 3; ch++) {
            setVolume(ch, 0xF);   // keep the others silent
        }

        int sr = psg.sampleRate();
        int[] out = new int[sr];  // one second
        psg.mix(out, out.length);

        boolean sawPositive = false;
        boolean sawNegative = false;
        int transitions = 0;
        int prevSign = Integer.signum(out[0]);
        for (int s : out) {
            if (s > 0) sawPositive = true;
            if (s < 0) sawNegative = true;
            int sign = Integer.signum(s);
            if (sign != 0 && sign != prevSign && prevSign != 0) {
                transitions++;
            }
            if (sign != 0) prevSign = sign;
        }

        assertTrue(sawPositive && sawNegative, "a square wave swings both ways");
        // ~2 transitions per cycle; expected frequency ~261 Hz -> ~522 transitions.
        double expectedFreq = psg.channelFrequency(0);
        double measuredFreq = transitions / 2.0;
        assertTrue(Math.abs(measuredFreq - expectedFreq) < expectedFreq * 0.1,
                "measured " + measuredFreq + " Hz vs expected " + expectedFreq + " Hz");
    }

    @Test
    void louderAttenuationGivesLargerAmplitude() {
        setTonePeriod(0, 0x100);
        setVolume(0, 0);
        int[] loud = new int[2000];
        psg.mix(loud, loud.length);
        int loudPeak = peak(loud);

        psg.reset();
        setTonePeriod(0, 0x100);
        setVolume(0, 4);          // ~8 dB quieter
        int[] quiet = new int[2000];
        psg.mix(quiet, quiet.length);
        int quietPeak = peak(quiet);

        assertTrue(loudPeak > quietPeak, "lower attenuation = louder");
        assertTrue(quietPeak > 0, "still audible at attenuation 4");
    }

    @Test
    void noiseRegisterReseedsAndConfigures() {
        psg.write(0x80 | (3 << 5) | 0x04); // latch noise: white, rate 0
        assertEquals(0x04, psg.noiseRegister());
        setVolume(3, 0);
        int[] out = new int[2000];
        psg.mix(out, out.length);
        assertTrue(peak(out) > 0, "noise channel produces output");
    }

    private static int peak(int[] samples) {
        int p = 0;
        for (int s : samples) {
            p = Math.max(p, Math.abs(s));
        }
        return p;
    }
}
