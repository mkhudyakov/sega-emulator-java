package com.segaemu.sound;

/**
 * The Yamaha YM2612 (OPN2) — the Mega Drive's 6-channel, 4-operator FM synthesis
 * chip, plus its two programmable timers and the channel-6 DAC/PCM mode.
 *
 * <p><b>What is modelled.</b> Each of the six channels has four operators with a
 * phase generator (F-number/block → frequency, with detune and the frequency
 * multiple), an ADSR <b>envelope generator</b> driven by the OPN2 rate tables
 * (attack/decay/sustain/release, key-scale rate, total level, sustain level),
 * key-on/off, the eight <b>algorithms</b> and operator-1 <b>feedback</b>, a basic
 * <b>LFO</b> (AM/PM with the per-channel AMS/FMS depths), and the channel-6
 * <b>DAC</b> sample mode. The two <b>timers</b> A/B and their status-flag bits are
 * modelled accurately enough that SMPS/GEMS drivers — which pace their tempo on
 * the timer-overflow flags — advance and play.
 *
 * <p><b>Accuracy.</b> This is a classic linear-sine FM model with the MAME-style
 * envelope increment table; it produces recognizable music but is not a
 * bit-accurate OPN2 (no SSG-EG, no channel-3 special mode, simplified LFO, and the
 * non-linear DAC quirk is not reproduced). The timers run on a clock independent
 * of audio pull so the driver advances even in headless mode.
 */
public final class Ym2612 {

    // ---- FM clock --------------------------------------------------------
    /** YM2612 master clock on NTSC (= 68000 clock), Hz. */
    public static final double CLOCK = 7_670_454.0;
    /** Internal FM sample rate = clock / 144. */
    public static final double FM_SAMPLE_RATE = CLOCK / 144.0;

    // ---- tables ----------------------------------------------------------
    private static final int[] SINE = new int[1024];      // ±4096
    private static final int[] ATT2LIN = new int[1024];   // attenuation → ±4096 gain
    static {
        for (int i = 0; i < 1024; i++) {
            SINE[i] = (int) Math.round(Math.sin(2.0 * Math.PI * i / 1024.0) * 4096.0);
            // 1024 attenuation units span ~96 dB.
            ATT2LIN[i] = (int) Math.round(4096.0 * Math.pow(10.0, -(i * (96.0 / 1024.0)) / 20.0));
        }
    }

    // EG increment table (19 rows × 8 phases), MAME/Genesis-Plus values.
    private static final int[] EG_INC = {
        /* 0*/ 0,1,0,1,0,1,0,1,
        /* 1*/ 0,1,0,1,1,1,0,1,
        /* 2*/ 0,1,1,1,0,1,1,1,
        /* 3*/ 0,1,1,1,1,1,1,1,
        /* 4*/ 1,1,1,1,1,1,1,1,
        /* 5*/ 1,1,1,2,1,1,1,2,
        /* 6*/ 1,2,1,2,1,2,1,2,
        /* 7*/ 1,2,2,2,1,2,2,2,
        /* 8*/ 2,2,2,2,2,2,2,2,
        /* 9*/ 2,2,2,4,2,2,2,4,
        /*10*/ 2,4,2,4,2,4,2,4,
        /*11*/ 2,4,4,4,2,4,4,4,
        /*12*/ 4,4,4,4,4,4,4,4,
        /*13*/ 4,4,4,8,4,4,4,8,
        /*14*/ 4,8,4,8,4,8,4,8,
        /*15*/ 4,8,8,8,4,8,8,8,
        /*16*/ 8,8,8,8,8,8,8,8,
        /*17*/ 16,16,16,16,16,16,16,16,
        /*18*/ 0,0,0,0,0,0,0,0,
    };

    // Detune table [dt&3][keycode 0..31], magnitudes; sign from dt bit 2.
    private static final int[][] DT_TAB = {
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {0,0,0,0,0,0,0,0,1,1,1,1,1,1,1,1,2,2,2,2,2,3,3,3,4,4,4,5,5,6,6,7},
        {1,1,1,1,2,2,2,2,2,3,3,3,4,4,4,5,5,6,6,7,8,8,9,10,11,12,13,14,16,16,16,16},
        {2,2,2,2,2,3,3,3,4,4,4,5,5,6,6,7,8,8,9,10,11,12,13,14,16,17,19,20,22,22,22,22},
    };

    private static final int EG_ATTACK = 0;
    private static final int EG_DECAY = 1;
    private static final int EG_SUSTAIN = 2;
    private static final int EG_RELEASE = 3;
    private static final int EG_MAX = 1023;

    // ---- registers -------------------------------------------------------
    private final int[][] registers = new int[2][0x100];
    private final int[] latchedAddr = new int[2];

    // ---- per-operator state (24 operators: ch*4 + slot) ------------------
    private final int[] opDt = new int[24];
    private final int[] opMul = new int[24];
    private final int[] opTl = new int[24];
    private final int[] opKs = new int[24];
    private final int[] opAr = new int[24];
    private final int[] opDr = new int[24];
    private final int[] opSr = new int[24]; // D2R / sustain rate
    private final int[] opRr = new int[24];
    private final int[] opSl = new int[24];
    private final boolean[] opAm = new boolean[24];
    private final int[] opEgState = new int[24];
    private final int[] opEnv = new int[24];
    private final long[] opPhase = new long[24];

    // ---- per-channel state ----------------------------------------------
    private final int[] chFnum = new int[6];
    private final int[] chBlock = new int[6];
    private final int[] chAlg = new int[6];
    private final int[] chFb = new int[6];
    private final boolean[] chL = new boolean[6];
    private final boolean[] chR = new boolean[6];
    private final int[] chAms = new int[6];
    private final int[] chFms = new int[6];
    private final int[] chFnumLatch = new int[2]; // 0xA4 high-byte latch per bank
    private final int[] fbOut1 = new int[6];
    private final int[] fbOut2 = new int[6];

    // Channel→operator register slot order is S1,S3,S2,S4.
    private static final int[] SLOT_MAP = {0, 2, 1, 3};

    // ---- DAC -------------------------------------------------------------
    private boolean dacEnabled;
    private int dacData = 0x80;

    // ---- LFO -------------------------------------------------------------
    private boolean lfoEnabled;
    private int lfoFreq;
    private double lfoPhase;

    // ---- timers ----------------------------------------------------------
    private int timerAReg;          // 10-bit
    private int timerBReg;          // 8-bit
    private boolean timerARunning, timerBRunning;
    private boolean timerAEnable, timerBEnable;
    private boolean flagA, flagB;
    private int timerACount;
    private int timerBCount;
    private int timerBSub;
    private double timerSampleAccum;

    // ---- envelope clock --------------------------------------------------
    private int egCounter;
    private int egPrescaler;
    private double fmSampleAccum;
    private int outputSampleRate = 44_100;

    public Ym2612() {
        reset();
    }

    public void reset() {
        for (int[] bank : registers) {
            java.util.Arrays.fill(bank, 0);
        }
        latchedAddr[0] = latchedAddr[1] = 0;
        for (int op = 0; op < 24; op++) {
            opEgState[op] = EG_RELEASE;
            opEnv[op] = EG_MAX;
            opPhase[op] = 0;
            opMul[op] = 0;
        }
        java.util.Arrays.fill(chFnum, 0);
        java.util.Arrays.fill(chBlock, 0);
        dacEnabled = false;
        dacData = 0x80;
        lfoEnabled = false;
        lfoFreq = 0;
        lfoPhase = 0;
        timerAReg = timerBReg = 0;
        timerARunning = timerBRunning = false;
        timerAEnable = timerBEnable = false;
        flagA = flagB = false;
        timerACount = timerBCount = timerBSub = 0;
        timerSampleAccum = 0;
        egCounter = egPrescaler = 0;
        fmSampleAccum = 0;
    }

    public void setSampleRate(int sampleRate) {
        this.outputSampleRate = sampleRate;
    }

    // ======================================================================
    //  Register write path
    // ======================================================================

    public void writePort(int port, int value) {
        value &= 0xFF;
        int bank = (port >> 1) & 1;
        if ((port & 1) == 0) {
            latchedAddr[bank] = value;
        } else {
            writeRegister(bank, latchedAddr[bank], value);
        }
    }

    public void writeRegister(int bank, int reg, int value) {
        bank &= 1;
        reg &= 0xFF;
        value &= 0xFF;
        registers[bank][reg] = value;

        if (bank == 0 && reg < 0x30) {
            writeGlobal(reg, value);
            return;
        }
        if (reg >= 0x30 && reg <= 0x9F) {
            writeOperator(bank, reg, value);
        } else if (reg >= 0xA0 && reg <= 0xB6) {
            writeChannel(bank, reg, value);
        }
    }

    private void writeGlobal(int reg, int value) {
        switch (reg) {
            case 0x22 -> { lfoEnabled = (value & 0x08) != 0; lfoFreq = value & 0x07; }
            case 0x24 -> timerAReg = (timerAReg & 0x03) | (value << 2);
            case 0x25 -> timerAReg = (timerAReg & 0x3FC) | (value & 0x03);
            case 0x26 -> timerBReg = value & 0xFF;
            case 0x27 -> writeTimerControl(value);
            case 0x28 -> writeKeyOnOff(value);
            case 0x2A -> dacData = value;
            case 0x2B -> dacEnabled = (value & 0x80) != 0;
            default -> { }
        }
    }

    private void writeTimerControl(int value) {
        boolean startA = (value & 0x01) != 0;
        boolean startB = (value & 0x02) != 0;
        if (startA && !timerARunning) {
            timerACount = timerAReg;
        }
        if (startB && !timerBRunning) {
            timerBCount = timerBReg;
            timerBSub = 0;
        }
        timerARunning = startA;
        timerBRunning = startB;
        timerAEnable = (value & 0x04) != 0;
        timerBEnable = (value & 0x08) != 0;
        if ((value & 0x10) != 0) flagA = false; // reset overflow A
        if ((value & 0x20) != 0) flagB = false; // reset overflow B
    }

    private void writeKeyOnOff(int value) {
        int sel = value & 0x07;
        int ch = switch (sel) {
            case 0, 1, 2 -> sel;
            case 4, 5, 6 -> sel - 1; // bank-1 channels 3,4,5
            default -> -1;
        };
        if (ch < 0) {
            return;
        }
        for (int slot = 0; slot < 4; slot++) {
            int op = ch * 4 + slot;
            boolean on = (value & (0x10 << slot)) != 0;
            if (on) {
                keyOn(op);
            } else {
                keyOff(op);
            }
        }
    }

    private void keyOn(int op) {
        opPhase[op] = 0;
        opEgState[op] = EG_ATTACK;
        // An instant attack jumps straight to the decay phase at full volume.
        if (effectiveRate(op, opAr[op]) >= 62) {
            opEnv[op] = 0;
            opEgState[op] = EG_DECAY;
        }
    }

    private void keyOff(int op) {
        opEgState[op] = EG_RELEASE;
    }

    private void writeOperator(int bank, int reg, int value) {
        int chInBank = reg & 0x03;
        if (chInBank == 3) {
            return; // invalid
        }
        int ch = chInBank + bank * 3;
        int op = ch * 4 + SLOT_MAP[(reg >> 2) & 0x03];
        switch (reg & 0xF0) {
            case 0x30 -> { opDt[op] = (value >> 4) & 0x07; opMul[op] = value & 0x0F; }
            case 0x40 -> opTl[op] = value & 0x7F;
            case 0x50 -> { opKs[op] = (value >> 6) & 0x03; opAr[op] = value & 0x1F; }
            case 0x60 -> { opAm[op] = (value & 0x80) != 0; opDr[op] = value & 0x1F; }
            case 0x70 -> opSr[op] = value & 0x1F;
            case 0x80 -> { opSl[op] = (value >> 4) & 0x0F; opRr[op] = value & 0x0F; }
            default -> { } // 0x90 SSG-EG: not modelled
        }
    }

    private void writeChannel(int bank, int reg, int value) {
        if (reg >= 0xA0 && reg <= 0xA2) {
            int ch = (reg & 0x03) + bank * 3;
            int high = chFnumLatch[bank];
            chFnum[ch] = ((high & 0x07) << 8) | value;
            chBlock[ch] = (high >> 3) & 0x07;
        } else if (reg >= 0xA4 && reg <= 0xA6) {
            chFnumLatch[bank] = value; // latched until the matching 0xA0 write
        } else if (reg >= 0xB0 && reg <= 0xB2) {
            int ch = (reg & 0x03) + bank * 3;
            chFb[ch] = (value >> 3) & 0x07;
            chAlg[ch] = value & 0x07;
        } else if (reg >= 0xB4 && reg <= 0xB6) {
            int ch = (reg & 0x03) + bank * 3;
            chL[ch] = (value & 0x80) != 0;
            chR[ch] = (value & 0x40) != 0;
            chAms[ch] = (value >> 4) & 0x03;
            chFms[ch] = value & 0x07;
        }
    }

    // ======================================================================
    //  Status / timers
    // ======================================================================

    public int readStatus() {
        int s = 0;
        if (flagA) s |= 0x01;
        if (flagB) s |= 0x02;
        return s; // bit 7 (busy) is always reported idle
    }

    /**
     * Advance the timers by the given number of 68000 cycles. This runs
     * independently of audio so SMPS/GEMS drivers that wait on the timer-overflow
     * flags keep their tempo even when no audio is being pulled.
     */
    public void stepTimers(int m68kCycles) {
        timerSampleAccum += m68kCycles / 144.0; // 68000 cycle == FM clock here
        while (timerSampleAccum >= 1.0) {
            timerSampleAccum -= 1.0;
            tickTimers();
        }
    }

    private void tickTimers() {
        if (timerARunning) {
            timerACount++;
            if (timerACount >= 0x400) {
                timerACount = timerAReg;
                if (timerAEnable) flagA = true;
            }
        }
        if (timerBRunning) {
            timerBSub++;
            if (timerBSub >= 16) {
                timerBSub = 0;
                timerBCount++;
                if (timerBCount >= 0x100) {
                    timerBCount = timerBReg;
                    if (timerBEnable) flagB = true;
                }
            }
        }
    }

    // ======================================================================
    //  Audio synthesis
    // ======================================================================

    /** Produce {@code count} stereo samples at the configured output rate. */
    public void mix(int[] left, int[] right, int count) {
        double fmPerOut = FM_SAMPLE_RATE / outputSampleRate;
        for (int i = 0; i < count; i++) {
            fmSampleAccum += fmPerOut;
            while (fmSampleAccum >= 1.0) {
                fmSampleAccum -= 1.0;
                advanceEnvelopes();
                lastL = 0;
                lastR = 0;
                renderFmSample();
            }
            left[i] = clamp(lastL);
            right[i] = clamp(lastR);
        }
    }

    private int lastL, lastR;

    private void advanceEnvelopes() {
        // The EG runs at the FM sample rate / 3.
        if (++egPrescaler < 3) {
            return;
        }
        egPrescaler = 0;
        egCounter = (egCounter + 1) & 0xFFFFFFF;
        for (int op = 0; op < 24; op++) {
            updateEnvelope(op);
        }
    }

    private void updateEnvelope(int op) {
        int rate;
        switch (opEgState[op]) {
            case EG_ATTACK -> rate = opAr[op];
            case EG_DECAY -> rate = opDr[op];
            case EG_SUSTAIN -> rate = opSr[op];
            default -> rate = (opRr[op] << 1) | 1; // release rate is (RR*2+1)
        }
        if (rate == 0) {
            return;
        }
        int effRate = effectiveRate(op, rate);
        int shift = effRate < 48 ? (11 - (effRate >> 2)) : 0;
        if ((egCounter & ((1 << shift) - 1)) != 0) {
            return;
        }
        int row = egRow(effRate);
        int inc = EG_INC[row * 8 + ((egCounter >> shift) & 7)];

        switch (opEgState[op]) {
            case EG_ATTACK -> {
                // Exponential approach toward 0 (maximum volume).
                opEnv[op] += (~opEnv[op] * inc) >> 4;
                if (opEnv[op] <= 0) {
                    opEnv[op] = 0;
                    opEgState[op] = EG_DECAY;
                }
            }
            case EG_DECAY -> {
                opEnv[op] += inc;
                int sl = opSl[op] == 15 ? 0x3E0 : (opSl[op] << 5);
                if (opEnv[op] >= sl) {
                    opEnv[op] = sl;
                    opEgState[op] = EG_SUSTAIN;
                }
            }
            default -> { // sustain / release both ramp toward silence
                opEnv[op] += inc;
                if (opEnv[op] >= EG_MAX) {
                    opEnv[op] = EG_MAX;
                }
            }
        }
    }

    private int egRow(int effRate) {
        if (effRate < 48) {
            return effRate & 3;
        }
        int g = effRate >> 2;
        return g >= 15 ? 16 : (4 + (g - 12) * 4 + (effRate & 3));
    }

    private int effectiveRate(int op, int rate) {
        int ch = op / 4;
        int kc = keyCode(ch);
        int ksr = kc >> (3 - opKs[op]);
        int eff = rate * 2 + ksr;
        return Math.min(eff, 63);
    }

    private int keyCode(int ch) {
        int block = chBlock[ch];
        int fnum = chFnum[ch];
        int f11 = (fnum >> 10) & 1;
        int f10 = (fnum >> 9) & 1;
        // Standard N-bit key-code derivation.
        int n = f11 & (f10 | ((fnum >> 8) & 1) | ((fnum >> 7) & 1));
        n |= f11;
        return (block << 2) | (f11 << 1) | n;
    }

    private void renderFmSample() {
        int lfoAm = 0;
        int lfoPm = 0;
        if (lfoEnabled) {
            lfoPhase += LFO_RATE[lfoFreq] / FM_SAMPLE_RATE;
            if (lfoPhase >= 1.0) lfoPhase -= 1.0;
            // Triangle 0..63 for AM; signed -1..1 for PM.
            double tri = lfoPhase < 0.5 ? (lfoPhase * 2) : (2 - lfoPhase * 2);
            lfoAm = (int) (tri * 63);
            lfoPm = (int) (Math.sin(2 * Math.PI * lfoPhase) * 16);
        }

        for (int ch = 0; ch < 6; ch++) {
            int sample;
            if (ch == 5 && dacEnabled) {
                sample = (dacData - 0x80) << 6;
            } else {
                sample = renderChannel(ch, lfoAm, lfoPm);
            }
            if (chL[ch]) lastL += sample;
            if (chR[ch]) lastR += sample;
        }
    }

    private static final double[] LFO_RATE = {3.98, 5.56, 6.02, 6.37, 6.88, 9.63, 48.1, 72.2};

    private int renderChannel(int ch, int lfoAm, int lfoPm) {
        int base = ch * 4;
        int fb = chFb[ch];
        int mod1 = 0;
        if (fb != 0) {
            mod1 = (fbOut1[ch] + fbOut2[ch]) >> (10 - fb);
        }
        int o1 = op(base + 0, mod1, ch, lfoAm, lfoPm);
        fbOut2[ch] = fbOut1[ch];
        fbOut1[ch] = o1;

        int o2, o3, o4, out;
        switch (chAlg[ch]) {
            case 0 -> { o2 = op(base + 1, o1, ch, lfoAm, lfoPm); o3 = op(base + 2, o2, ch, lfoAm, lfoPm); o4 = op(base + 3, o3, ch, lfoAm, lfoPm); out = o4; }
            case 1 -> { o2 = op(base + 1, 0, ch, lfoAm, lfoPm); o3 = op(base + 2, o1 + o2, ch, lfoAm, lfoPm); o4 = op(base + 3, o3, ch, lfoAm, lfoPm); out = o4; }
            case 2 -> { o2 = op(base + 1, 0, ch, lfoAm, lfoPm); o3 = op(base + 2, o2, ch, lfoAm, lfoPm); o4 = op(base + 3, o1 + o3, ch, lfoAm, lfoPm); out = o4; }
            case 3 -> { o2 = op(base + 1, o1, ch, lfoAm, lfoPm); o3 = op(base + 2, 0, ch, lfoAm, lfoPm); o4 = op(base + 3, o2 + o3, ch, lfoAm, lfoPm); out = o4; }
            case 4 -> { o2 = op(base + 1, o1, ch, lfoAm, lfoPm); o3 = op(base + 2, 0, ch, lfoAm, lfoPm); o4 = op(base + 3, o3, ch, lfoAm, lfoPm); out = o2 + o4; }
            case 5 -> { o2 = op(base + 1, o1, ch, lfoAm, lfoPm); o3 = op(base + 2, o1, ch, lfoAm, lfoPm); o4 = op(base + 3, o1, ch, lfoAm, lfoPm); out = o2 + o3 + o4; }
            case 6 -> { o2 = op(base + 1, o1, ch, lfoAm, lfoPm); o3 = op(base + 2, 0, ch, lfoAm, lfoPm); o4 = op(base + 3, 0, ch, lfoAm, lfoPm); out = o2 + o3 + o4; }
            default -> { o2 = op(base + 1, 0, ch, lfoAm, lfoPm); o3 = op(base + 2, 0, ch, lfoAm, lfoPm); o4 = op(base + 3, 0, ch, lfoAm, lfoPm); out = o1 + o2 + o3 + o4; }
        }
        return out;
    }

    private int op(int op, int mod, int ch, int lfoAm, int lfoPm) {
        long inc = phaseIncrement(op, ch, lfoPm);
        opPhase[op] = (opPhase[op] + inc) & 0xFFFFF;
        int idx = (int) (((opPhase[op] >> 10) + mod) & 0x3FF);
        int att = opEnv[op] + (opTl[op] << 3);
        if (opAm[op] && lfoEnabled) {
            att += lfoAm << (chAms[ch] == 0 ? 8 : (3 - chAms[ch]));
        }
        if (att > EG_MAX) att = EG_MAX;
        if (att < 0) att = 0;
        return (SINE[idx] * ATT2LIN[att]) >> 12;
    }

    private long phaseIncrement(int op, int ch, int lfoPm) {
        int fnum = chFnum[ch];
        int block = chBlock[ch];
        if (lfoEnabled && chFms[ch] != 0) {
            fnum += (lfoPm * chFms[ch]) >> 4;
            if (fnum < 0) fnum = 0;
        }
        long base = ((long) (fnum << block)) >> 1;
        int pm = opMul[op] == 0 ? 1 : opMul[op] * 2;
        long inc = (base * pm) >> 1;
        int kc = keyCode(ch);
        int detune = DT_TAB[opDt[op] & 3][kc & 0x1F];
        inc += (opDt[op] & 0x04) != 0 ? -detune : detune;
        return inc & 0xFFFFF;
    }

    private static int clamp(int v) {
        if (v > 32767) return 32767;
        if (v < -32768) return -32768;
        return v;
    }

    // ======================================================================
    //  Test / debug accessors
    // ======================================================================

    public boolean timerAOverflow() { return flagA; }
    public boolean timerBOverflow() { return flagB; }
    public int channelEnvelope(int ch, int slot) { return opEnv[ch * 4 + slot]; }
    public int channelEgState(int ch, int slot) { return opEgState[ch * 4 + slot]; }
    public boolean isDacEnabled() { return dacEnabled; }
}
