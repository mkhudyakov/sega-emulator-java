package com.segaemu.sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Mixes the YM2612 (stereo FM) and SN76489 (mono PSG) into 16-bit stereo PCM and
 * plays it through the host sound system.
 *
 * <p>Like the NES emulator's audio path, the {@link SourceDataLine}'s internal
 * buffer provides the <b>back-pressure that paces emulation</b>: once the buffer
 * fills, {@link #play} blocks until the sound card drains it, locking the loop to
 * ~60&nbsp;fps. If no audio line is available the chips are still ticked (so the
 * music driver keeps time) but nothing is written, and the caller falls back to a
 * sleep-based clock. The mixing itself ({@link #mix}) is independent of the line
 * so it can be unit-tested without an audio device.
 */
public final class AudioMixer {

    public static final int SAMPLE_RATE = 44_100;

    // Relative chip levels, chosen so a busy mix stays inside 16-bit range.
    private static final double YM_GAIN = 0.65;
    private static final double PSG_GAIN = 0.50;
    private static final double DC_R = 0.995; // high-pass pole for DC removal

    private SourceDataLine line;
    private boolean available;

    private double fps;
    private double sampleAccum;

    // Reusable per-frame buffers.
    private int[] ymL = new int[0];
    private int[] ymR = new int[0];
    private int[] psgMono = new int[0];
    private byte[] bytes = new byte[0];

    // DC-filter state.
    private double prevInL, prevOutL, prevInR, prevOutR;

    public AudioMixer(double fps) {
        this.fps = fps;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            int bufferBytes = (SAMPLE_RATE / 10) * 4; // ~100 ms of stereo latency
            line.open(format, bufferBytes);
            line.start();
            available = true;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Set the frame rate used to size each frame's audio (NTSC 59.92 / PAL 49.7). */
    public void setFps(double fps) {
        this.fps = fps;
    }

    /** Number of samples for the next frame, keeping the long-run rate exact. */
    public int nextFrameSampleCount() {
        sampleAccum += SAMPLE_RATE / fps;
        int n = (int) sampleAccum;
        sampleAccum -= n;
        return n;
    }

    /**
     * Render and play one frame's audio, blocking on the audio line for pacing.
     * Returns true if it wrote to a real line (so the caller can skip its own
     * sleep clock), false if no device is available.
     */
    public boolean play(Ym2612 ym, Sn76489 psg) {
        int count = nextFrameSampleCount();
        ensureCapacity(count);
        mix(ym, psg, ymL, ymR, count); // reuse ymL/ymR as the final mix buffers
        if (!available) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            putSample(i * 4, ymL[i]);
            putSample(i * 4 + 2, ymR[i]);
        }
        line.write(bytes, 0, count * 4);
        return true;
    }

    /**
     * Mix {@code count} samples of YM2612 + PSG into {@code left}/{@code right}.
     * Pure (no audio device needed); used by {@link #play} and by tests.
     */
    public void mix(Ym2612 ym, Sn76489 psg, int[] left, int[] right, int count) {
        ensureCapacity(count);
        ym.mix(ymBufL(count), ymBufR(count), count);
        psg.mix(psgBuf(count), count);
        for (int i = 0; i < count; i++) {
            double l = ymL[i] * YM_GAIN + psgMono[i] * PSG_GAIN;
            double r = ymR[i] * YM_GAIN + psgMono[i] * PSG_GAIN;
            // First-order DC blocker per channel.
            double outL = l - prevInL + DC_R * prevOutL;
            prevInL = l;
            prevOutL = outL;
            double outR = r - prevInR + DC_R * prevOutR;
            prevInR = r;
            prevOutR = outR;
            left[i] = clamp((int) Math.round(outL));
            right[i] = clamp((int) Math.round(outR));
        }
    }

    private int[] ymBufL(int count) { ensureCapacity(count); return ymL; }
    private int[] ymBufR(int count) { ensureCapacity(count); return ymR; }
    private int[] psgBuf(int count) { ensureCapacity(count); return psgMono; }

    private void ensureCapacity(int count) {
        if (ymL.length < count) {
            ymL = new int[count];
            ymR = new int[count];
            psgMono = new int[count];
            bytes = new byte[count * 4];
        }
    }

    private void putSample(int offset, int s) {
        bytes[offset] = (byte) (s & 0xFF);
        bytes[offset + 1] = (byte) ((s >> 8) & 0xFF);
    }

    private static int clamp(int v) {
        if (v > 32767) return 32767;
        if (v < -32768) return -32768;
        return v;
    }

    /** Discard buffered audio and reset the DC filter (on reset / pause). */
    public void flush() {
        if (available) {
            line.flush();
        }
        prevInL = prevOutL = prevInR = prevOutR = 0;
    }

    public void close() {
        if (available) {
            line.drain();
            line.stop();
            line.close();
            available = false;
        }
    }
}
