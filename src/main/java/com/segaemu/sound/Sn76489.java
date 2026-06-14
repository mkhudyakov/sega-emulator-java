package com.segaemu.sound;

/**
 * The Texas Instruments SN76489 PSG (programmable sound generator) as used in the
 * Mega Drive — three square-wave tone channels plus one noise channel, inherited
 * from the Master System. It handles beeps, jingles and many sound effects.
 *
 * <p><b>Model.</b> The chip is clocked at the NTSC PSG clock (3.579545&nbsp;MHz)
 * divided by 16. Each tone channel has a 10-bit period counter; when it underflows
 * the channel's output polarity flips, giving a square wave of frequency
 * {@code clock / (32 · period)}. The noise channel runs a 16-bit LFSR clocked at
 * one of three fixed rates or at tone&nbsp;channel&nbsp;2's frequency, producing
 * white noise (XOR feedback) or periodic noise. Each channel has a 4-bit
 * attenuator (0 = loudest, 15 = silent) following the standard 2&nbsp;dB-per-step
 * curve. {@link #mix(int[], int)} advances the chip and writes signed mono samples
 * at {@link #sampleRate()} Hz.
 */
public final class Sn76489 {

    /** PSG clock on an NTSC Mega Drive, Hz. */
    public static final double CLOCK = 3_579_545.0;

    private static final int LFSR_RESET = 0x8000;
    private static final int WHITE_NOISE_TAP = 0x0009; // XOR of bits 0 and 3

    // Per-step amplitude for each 4-bit attenuation value (index 15 = silence).
    private static final int[] VOLUME = new int[16];
    static {
        double level = 2000.0; // per-channel peak; four channels stay within 16-bit
        for (int i = 0; i < 15; i++) {
            VOLUME[i] = (int) Math.round(level);
            level *= 0.7943282347; // -2 dB
        }
        VOLUME[15] = 0;
    }

    private int sampleRate = 44_100;

    // Register state.
    private final int[] tone = new int[3];      // 10-bit periods for channels 0-2
    private final int[] volume = {0xF, 0xF, 0xF, 0xF}; // attenuation, 0xF = off
    private int noiseReg = 0;                    // bits 0-1 = rate, bit 2 = white
    private int latchedChannel = 0;
    private boolean latchedVolume = false;

    // Synthesis state.
    private final int[] counter = new int[3];
    private final int[] polarity = {1, 1, 1};
    private int noiseCounter = 0;
    private boolean noiseFlip = false;
    private int lfsr = LFSR_RESET;

    private double cycleAccum = 0.0;

    public void reset() {
        java.util.Arrays.fill(tone, 0);
        java.util.Arrays.fill(volume, 0xF);
        noiseReg = 0;
        latchedChannel = 0;
        latchedVolume = false;
        java.util.Arrays.fill(counter, 0);
        polarity[0] = polarity[1] = polarity[2] = 1;
        noiseCounter = 0;
        noiseFlip = false;
        lfsr = LFSR_RESET;
        cycleAccum = 0.0;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    // ======================================================================
    //  Register writes (single port, $7F11 from the Z80 / $C00011 from the 68k)
    // ======================================================================

    public void write(int value) {
        value &= 0xFF;
        if ((value & 0x80) != 0) {
            // LATCH/DATA byte: selects a register and writes its low 4 bits.
            latchedChannel = (value >> 5) & 3;
            latchedVolume = (value & 0x10) != 0;
            int data = value & 0x0F;
            if (latchedVolume) {
                volume[latchedChannel] = data;
            } else if (latchedChannel == 3) {
                writeNoise(data);
            } else {
                tone[latchedChannel] = (tone[latchedChannel] & 0x3F0) | data;
            }
        } else {
            // DATA byte: updates the most recently latched register.
            int data = value & 0x3F;
            if (latchedVolume) {
                volume[latchedChannel] = value & 0x0F;
            } else if (latchedChannel == 3) {
                writeNoise(value & 0x0F);
            } else {
                tone[latchedChannel] = (tone[latchedChannel] & 0x00F) | (data << 4);
            }
        }
    }

    private void writeNoise(int data) {
        noiseReg = data & 0x07;
        lfsr = LFSR_RESET; // writing the noise register reseeds the shift register
    }

    // ======================================================================
    //  Synthesis
    // ======================================================================

    /** Fill {@code out[0..count)} with signed mono samples at {@link #sampleRate}. */
    public void mix(int[] out, int count) {
        double cyclesPerSample = (CLOCK / 16.0) / sampleRate;
        for (int i = 0; i < count; i++) {
            cycleAccum += cyclesPerSample;
            while (cycleAccum >= 1.0) {
                tick();
                cycleAccum -= 1.0;
            }
            out[i] = currentSample();
        }
    }

    private void tick() {
        for (int ch = 0; ch < 3; ch++) {
            int period = tone[ch] & 0x3FF;
            if (period == 0) {
                // A zero period holds the output high (no audible tone).
                polarity[ch] = 1;
                continue;
            }
            if (--counter[ch] <= 0) {
                counter[ch] = period;
                polarity[ch] ^= 1;
            }
        }

        int rate = noiseReg & 0x03;
        int noisePeriod = (rate == 3) ? (tone[2] & 0x3FF) : (0x10 << rate);
        if (noisePeriod == 0) {
            noisePeriod = 1;
        }
        if (--noiseCounter <= 0) {
            noiseCounter = noisePeriod;
            noiseFlip = !noiseFlip;
            if (noiseFlip) {
                clockLfsr();
            }
        }
    }

    private void clockLfsr() {
        boolean white = (noiseReg & 0x04) != 0;
        int feedback = white
                ? (Integer.bitCount(lfsr & WHITE_NOISE_TAP) & 1)
                : (lfsr & 1);
        lfsr = (lfsr >> 1) | (feedback << 15);
    }

    private int currentSample() {
        int s = 0;
        for (int ch = 0; ch < 3; ch++) {
            int v = VOLUME[volume[ch]];
            s += polarity[ch] != 0 ? v : -v;
        }
        int nv = VOLUME[volume[3]];
        s += (lfsr & 1) != 0 ? nv : -nv;
        return s;
    }

    // ======================================================================
    //  Test / debug accessors
    // ======================================================================

    /** Square-wave frequency of a tone channel (Hz), or 0 if its period is 0. */
    public double channelFrequency(int ch) {
        int period = tone[ch] & 0x3FF;
        return period == 0 ? 0.0 : CLOCK / (32.0 * period);
    }

    public int tonePeriod(int ch) {
        return tone[ch] & 0x3FF;
    }

    public int attenuation(int ch) {
        return volume[ch] & 0x0F;
    }

    public int noiseRegister() {
        return noiseReg;
    }
}
