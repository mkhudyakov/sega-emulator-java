package com.segaemu.z80;

/**
 * A Zilog Z80 CPU core — the Mega Drive's sound co-processor.
 *
 * <p>On real hardware the Z80 runs a sound driver (SMPS, GEMS, …) that the 68000
 * uploads into the 8&nbsp;KB sound RAM; the driver programs the YM2612 and SN76489
 * over time. This core implements the full documented instruction set — the main
 * table plus the CB (bit/rotate), ED (extended), DD/FD (IX/IY) and DDCB/FDCB
 * prefix tables — with the S Z Y H X P/V N C flags (including the documented
 * undocumented bits 3/5), the IX/IY index registers, the shadow register set,
 * the I/R registers, the three interrupt modes and the NMI.
 *
 * <p>All register values are kept in {@code int}s masked to 8 or 16 bits at the
 * boundaries. Cycle counts follow the standard tables closely enough to pace the
 * sound driver against the 68000/VDP timing, but are not cycle-exact.
 *
 * <p>The {@link #run(long)} budget executes instructions until the cycle budget
 * is reached, accepting a pending maskable interrupt (raised once per frame at
 * vblank by {@code GenesisSystem}) or NMI at instruction boundaries.
 */
public final class Z80 {

    // Flag bit masks.
    private static final int FLAG_C = 0x01;
    private static final int FLAG_N = 0x02;
    private static final int FLAG_PV = 0x04;
    private static final int FLAG_X3 = 0x08;
    private static final int FLAG_H = 0x10;
    private static final int FLAG_X5 = 0x20;
    private static final int FLAG_Z = 0x40;
    private static final int FLAG_S = 0x80;

    private static final boolean[] PARITY = new boolean[256];
    static {
        for (int i = 0; i < 256; i++) {
            int bits = Integer.bitCount(i);
            PARITY[i] = (bits & 1) == 0; // true = even parity (PV set)
        }
    }

    // 8-bit registers.
    private int a, f, b, c, d, e, h, l;
    // Shadow set.
    private int a2, f2, b2, c2, d2, e2, h2, l2;
    // 16-bit registers.
    private int ix, iy, sp, pc;
    // Interrupt / refresh.
    private int i, r;
    private boolean iff1, iff2;
    private int im;

    private boolean halted;
    private boolean intLine;       // maskable interrupt pending (level-held)
    private boolean nmiPending;
    private boolean eiPending;     // EI delays interrupt enable by one instruction

    private Z80Bus bus;

    public void attachBus(Z80Bus bus) {
        this.bus = bus;
    }

    public void reset() {
        a = f = b = c = d = e = h = l = 0;
        a2 = f2 = b2 = c2 = d2 = e2 = h2 = l2 = 0;
        ix = iy = 0;
        pc = 0;
        sp = 0xFFFF;
        i = r = 0;
        iff1 = iff2 = false;
        im = 0;
        halted = false;      // executes from PC=0; whether it *runs* is gated by
                             // the bus reset / bus-request lines in GenesisSystem
        intLine = false;
        nmiPending = false;
        eiPending = false;
    }

    // ======================================================================
    //  Interrupt lines
    // ======================================================================

    /** Assert the maskable interrupt line (Mega Drive: pulsed at vblank). */
    public void requestInterrupt() {
        intLine = true;
    }

    public void clearInterrupt() {
        intLine = false;
    }

    public void requestNmi() {
        nmiPending = true;
    }

    public void setHalted(boolean halted) {
        this.halted = halted;
    }

    public boolean isHalted() {
        return halted;
    }

    // ======================================================================
    //  Execution
    // ======================================================================

    /** Run until at least {@code cycles} Z80 cycles have elapsed. */
    public long run(long cycles) {
        long elapsed = 0;
        while (elapsed < cycles) {
            elapsed += step();
        }
        return elapsed;
    }

    /** Execute one instruction (or service an interrupt); returns cycles used. */
    public int step() {
        if (nmiPending) {
            nmiPending = false;
            halted = false;
            iff2 = iff1;
            iff1 = false;
            push16(pc);
            pc = 0x0066;
            incR();
            return 11;
        }
        if (intLine && iff1 && !eiPending) {
            return acceptInterrupt();
        }
        eiPending = false;

        if (halted) {
            // HALT executes NOPs until an interrupt arrives.
            incR();
            return 4;
        }
        return execute();
    }

    private int acceptInterrupt() {
        halted = false;
        iff1 = iff2 = false;
        incR();
        push16(pc);
        switch (im) {
            case 1 -> pc = 0x0038;
            case 2 -> {
                int vector = (i << 8) | 0xFF; // device supplies $FF on the MD
                pc = read16(vector);
            }
            default -> pc = 0x0038; // IM 0: treat the bus value as RST 38h
        }
        return 13;
    }

    // ======================================================================
    //  Memory / fetch helpers
    // ======================================================================

    private int read(int addr) {
        return bus.read(addr & 0xFFFF) & 0xFF;
    }

    private void write(int addr, int value) {
        bus.write(addr & 0xFFFF, value & 0xFF);
    }

    private int read16(int addr) {
        return read(addr) | (read(addr + 1) << 8);
    }

    private void write16(int addr, int value) {
        write(addr, value & 0xFF);
        write(addr + 1, (value >> 8) & 0xFF);
    }

    private void incR() {
        r = (r & 0x80) | ((r + 1) & 0x7F);
    }

    private int fetchOpcode() {
        int op = read(pc);
        pc = (pc + 1) & 0xFFFF;
        incR();
        return op;
    }

    private int fetch() {
        int v = read(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int fetch16() {
        int lo = fetch();
        int hi = fetch();
        return lo | (hi << 8);
    }

    private void push16(int value) {
        sp = (sp - 1) & 0xFFFF;
        write(sp, (value >> 8) & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        write(sp, value & 0xFF);
    }

    private int pop16() {
        int lo = read(sp);
        sp = (sp + 1) & 0xFFFF;
        int hi = read(sp);
        sp = (sp + 1) & 0xFFFF;
        return lo | (hi << 8);
    }

    // ---- 16-bit register pairs -------------------------------------------

    private int bc() { return (b << 8) | c; }
    private int de() { return (d << 8) | e; }
    private int hl() { return (h << 8) | l; }
    private void setBc(int v) { b = (v >> 8) & 0xFF; c = v & 0xFF; }
    private void setDe(int v) { d = (v >> 8) & 0xFF; e = v & 0xFF; }
    private void setHl(int v) { h = (v >> 8) & 0xFF; l = v & 0xFF; }
    private int af() { return (a << 8) | f; }
    private void setAf(int v) { a = (v >> 8) & 0xFF; f = v & 0xFF; }

    // ======================================================================
    //  Flag helpers
    // ======================================================================

    private boolean fc() { return (f & FLAG_C) != 0; }

    private void setFlag(int mask, boolean on) {
        if (on) f |= mask; else f &= ~mask;
    }

    /** Set S, Z and the undocumented 5/3 bits from an 8-bit result. */
    private int sz53(int v) {
        int r = 0;
        if ((v & 0xFF) == 0) r |= FLAG_Z;
        r |= v & (FLAG_S | FLAG_X5 | FLAG_X3);
        return r;
    }

    private int sz53p(int v) {
        return sz53(v) | (PARITY[v & 0xFF] ? FLAG_PV : 0);
    }

    // ======================================================================
    //  8-bit ALU
    // ======================================================================

    private void add8(int v) {
        int res = a + v;
        int fl = sz53(res & 0xFF);
        if (res > 0xFF) fl |= FLAG_C;
        if (((a & 0xF) + (v & 0xF)) > 0xF) fl |= FLAG_H;
        if (((a ^ v ^ 0x80) & (a ^ res) & 0x80) != 0) fl |= FLAG_PV;
        a = res & 0xFF;
        f = fl;
    }

    private void adc8(int v) {
        int carry = fc() ? 1 : 0;
        int res = a + v + carry;
        int fl = sz53(res & 0xFF);
        if (res > 0xFF) fl |= FLAG_C;
        if (((a & 0xF) + (v & 0xF) + carry) > 0xF) fl |= FLAG_H;
        if (((a ^ v ^ 0x80) & (a ^ res) & 0x80) != 0) fl |= FLAG_PV;
        a = res & 0xFF;
        f = fl;
    }

    private void sub8(int v) {
        int res = a - v;
        int fl = sz53(res & 0xFF) | FLAG_N;
        if ((res & 0x100) != 0) fl |= FLAG_C;
        if (((a & 0xF) - (v & 0xF)) < 0) fl |= FLAG_H;
        if (((a ^ v) & (a ^ res) & 0x80) != 0) fl |= FLAG_PV;
        a = res & 0xFF;
        f = fl;
    }

    private void sbc8(int v) {
        int carry = fc() ? 1 : 0;
        int res = a - v - carry;
        int fl = sz53(res & 0xFF) | FLAG_N;
        if ((res & 0x100) != 0) fl |= FLAG_C;
        if (((a & 0xF) - (v & 0xF) - carry) < 0) fl |= FLAG_H;
        if (((a ^ v) & (a ^ res) & 0x80) != 0) fl |= FLAG_PV;
        a = res & 0xFF;
        f = fl;
    }

    private void and8(int v) {
        a &= v;
        f = sz53p(a) | FLAG_H;
    }

    private void xor8(int v) {
        a ^= v;
        f = sz53p(a);
    }

    private void or8(int v) {
        a |= v;
        f = sz53p(a);
    }

    private void cp8(int v) {
        int res = a - v;
        // CP takes the 5/3 flags from the operand, not the result.
        int fl = (sz53(res & 0xFF) & ~(FLAG_X5 | FLAG_X3)) | (v & (FLAG_X5 | FLAG_X3)) | FLAG_N;
        if ((res & 0x100) != 0) fl |= FLAG_C;
        if (((a & 0xF) - (v & 0xF)) < 0) fl |= FLAG_H;
        if (((a ^ v) & (a ^ res) & 0x80) != 0) fl |= FLAG_PV;
        f = fl;
    }

    private int inc8(int v) {
        int res = (v + 1) & 0xFF;
        int fl = sz53(res) | (f & FLAG_C);
        if ((v & 0xF) == 0xF) fl |= FLAG_H;
        if (v == 0x7F) fl |= FLAG_PV;
        f = fl;
        return res;
    }

    private int dec8(int v) {
        int res = (v - 1) & 0xFF;
        int fl = sz53(res) | (f & FLAG_C) | FLAG_N;
        if ((v & 0xF) == 0) fl |= FLAG_H;
        if (v == 0x80) fl |= FLAG_PV;
        f = fl;
        return res;
    }

    // ======================================================================
    //  16-bit ALU
    // ======================================================================

    private int add16(int a16, int b16) {
        int res = a16 + b16;
        int fl = (f & (FLAG_S | FLAG_Z | FLAG_PV));
        if (((a16 & 0xFFF) + (b16 & 0xFFF)) > 0xFFF) fl |= FLAG_H;
        if (res > 0xFFFF) fl |= FLAG_C;
        fl |= (res >> 8) & (FLAG_X5 | FLAG_X3);
        f = fl;
        return res & 0xFFFF;
    }

    private void adc16(int b16) {
        int a16 = hl();
        int carry = fc() ? 1 : 0;
        int res = a16 + b16 + carry;
        int fl = 0;
        if ((res & 0xFFFF) == 0) fl |= FLAG_Z;
        fl |= (res >> 8) & (FLAG_S | FLAG_X5 | FLAG_X3);
        if (((a16 & 0xFFF) + (b16 & 0xFFF) + carry) > 0xFFF) fl |= FLAG_H;
        if (res > 0xFFFF) fl |= FLAG_C;
        if (((a16 ^ b16 ^ 0x8000) & (a16 ^ res) & 0x8000) != 0) fl |= FLAG_PV;
        f = fl;
        setHl(res & 0xFFFF);
    }

    private void sbc16(int b16) {
        int a16 = hl();
        int carry = fc() ? 1 : 0;
        int res = a16 - b16 - carry;
        int fl = FLAG_N;
        if ((res & 0xFFFF) == 0) fl |= FLAG_Z;
        fl |= (res >> 8) & (FLAG_S | FLAG_X5 | FLAG_X3);
        if (((a16 & 0xFFF) - (b16 & 0xFFF) - carry) < 0) fl |= FLAG_H;
        if ((res & 0x10000) != 0) fl |= FLAG_C;
        if (((a16 ^ b16) & (a16 ^ res) & 0x8000) != 0) fl |= FLAG_PV;
        f = fl;
        setHl(res & 0xFFFF);
    }

    // ======================================================================
    //  Rotates / shifts
    // ======================================================================

    private void rlca() {
        int carry = (a >> 7) & 1;
        a = ((a << 1) | carry) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_PV)) | (a & (FLAG_X5 | FLAG_X3)) | (carry != 0 ? FLAG_C : 0);
    }

    private void rrca() {
        int carry = a & 1;
        a = ((a >> 1) | (carry << 7)) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_PV)) | (a & (FLAG_X5 | FLAG_X3)) | (carry != 0 ? FLAG_C : 0);
    }

    private void rla() {
        int carry = (a >> 7) & 1;
        a = ((a << 1) | (fc() ? 1 : 0)) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_PV)) | (a & (FLAG_X5 | FLAG_X3)) | (carry != 0 ? FLAG_C : 0);
    }

    private void rra() {
        int carry = a & 1;
        a = ((a >> 1) | (fc() ? 0x80 : 0)) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_PV)) | (a & (FLAG_X5 | FLAG_X3)) | (carry != 0 ? FLAG_C : 0);
    }

    private int rlc(int v) {
        int carry = (v >> 7) & 1;
        int res = ((v << 1) | carry) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private int rrc(int v) {
        int carry = v & 1;
        int res = ((v >> 1) | (carry << 7)) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private int rl(int v) {
        int carry = (v >> 7) & 1;
        int res = ((v << 1) | (fc() ? 1 : 0)) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private int rr(int v) {
        int carry = v & 1;
        int res = ((v >> 1) | (fc() ? 0x80 : 0)) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private int sla(int v) {
        int carry = (v >> 7) & 1;
        int res = (v << 1) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private int sra(int v) {
        int carry = v & 1;
        int res = ((v >> 1) | (v & 0x80)) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private int sll(int v) { // undocumented: shift left, bit 0 = 1
        int carry = (v >> 7) & 1;
        int res = ((v << 1) | 1) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private int srl(int v) {
        int carry = v & 1;
        int res = (v >> 1) & 0xFF;
        f = sz53p(res) | (carry != 0 ? FLAG_C : 0);
        return res;
    }

    private void bit(int n, int v) {
        boolean set = (v & (1 << n)) != 0;
        int fl = (f & FLAG_C) | FLAG_H;
        if (!set) fl |= FLAG_PV | FLAG_Z;
        if (n == 7 && set) fl |= FLAG_S;
        fl |= v & (FLAG_X5 | FLAG_X3);
        f = fl;
    }

    /** BIT n,(HL) takes the 5/3 flags from the address high byte (memptr). */
    private void bitMem(int n, int v, int addrHi) {
        boolean set = (v & (1 << n)) != 0;
        int fl = (f & FLAG_C) | FLAG_H;
        if (!set) fl |= FLAG_PV | FLAG_Z;
        if (n == 7 && set) fl |= FLAG_S;
        fl |= addrHi & (FLAG_X5 | FLAG_X3);
        f = fl;
    }

    // ======================================================================
    //  DAA / misc accumulator ops
    // ======================================================================

    private void daa() {
        int adjust = 0;
        int carry = fc() ? 1 : 0;
        boolean n = (f & FLAG_N) != 0;
        if ((f & FLAG_H) != 0 || (a & 0x0F) > 9) {
            adjust |= 0x06;
        }
        if (carry == 1 || a > 0x99) {
            adjust |= 0x60;
            carry = 1;
        }
        int res;
        if (n) {
            res = (a - adjust) & 0xFF;
        } else {
            res = (a + adjust) & 0xFF;
        }
        int fl = sz53p(res) | (n ? FLAG_N : 0) | (carry != 0 ? FLAG_C : 0);
        if (((a ^ res) & 0x10) != 0) fl |= FLAG_H;
        a = res;
        f = fl;
    }

    private void cpl() {
        a = (~a) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_PV | FLAG_C)) | FLAG_H | FLAG_N | (a & (FLAG_X5 | FLAG_X3));
    }

    private void neg() {
        int v = a;
        a = 0;
        sub8(v);
    }

    private void scf() {
        f = (f & (FLAG_S | FLAG_Z | FLAG_PV)) | FLAG_C | (a & (FLAG_X5 | FLAG_X3));
    }

    private void ccf() {
        int oldC = f & FLAG_C;
        f = (f & (FLAG_S | FLAG_Z | FLAG_PV)) | (oldC != 0 ? FLAG_H : 0) | (oldC ^ FLAG_C)
                | (a & (FLAG_X5 | FLAG_X3));
    }

    // ======================================================================
    //  Register file by index (0=B 1=C 2=D 3=E 4=H 5=L 6=(HL) 7=A)
    // ======================================================================

    private int getReg(int idx) {
        return switch (idx) {
            case 0 -> b;
            case 1 -> c;
            case 2 -> d;
            case 3 -> e;
            case 4 -> h;
            case 5 -> l;
            case 6 -> read(hl());
            default -> a;
        };
    }

    private void setReg(int idx, int v) {
        v &= 0xFF;
        switch (idx) {
            case 0 -> b = v;
            case 1 -> c = v;
            case 2 -> d = v;
            case 3 -> e = v;
            case 4 -> h = v;
            case 5 -> l = v;
            case 6 -> write(hl(), v);
            default -> a = v;
        }
    }

    private boolean condition(int cc) {
        return switch (cc) {
            case 0 -> (f & FLAG_Z) == 0;   // NZ
            case 1 -> (f & FLAG_Z) != 0;   // Z
            case 2 -> (f & FLAG_C) == 0;   // NC
            case 3 -> (f & FLAG_C) != 0;   // C
            case 4 -> (f & FLAG_PV) == 0;  // PO
            case 5 -> (f & FLAG_PV) != 0;  // PE
            case 6 -> (f & FLAG_S) == 0;   // P
            default -> (f & FLAG_S) != 0;  // M
        };
    }

    private void alu(int op, int v) {
        switch (op) {
            case 0 -> add8(v);
            case 1 -> adc8(v);
            case 2 -> sub8(v);
            case 3 -> sbc8(v);
            case 4 -> and8(v);
            case 5 -> xor8(v);
            case 6 -> or8(v);
            default -> cp8(v);
        }
    }

    // ======================================================================
    //  Main instruction decode
    // ======================================================================

    private int execute() {
        int op = fetchOpcode();
        switch (op) {
            case 0xCB -> { return executeCB(); }
            case 0xED -> { return executeED(); }
            case 0xDD -> { return executeIndex(ix, true); }
            case 0xFD -> { return executeIndex(iy, false); }
            default -> { return executeMain(op); }
        }
    }

    private int executeMain(int op) {
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;

        switch (x) {
            case 1 -> { // LD r,r' (0x40-0x7F), 0x76 = HALT
                if (op == 0x76) {
                    halted = true;
                    return 4;
                }
                setReg(y, getReg(z));
                return (y == 6 || z == 6) ? 7 : 4;
            }
            case 2 -> { // ALU A,r
                alu(y, getReg(z));
                return z == 6 ? 7 : 4;
            }
            default -> { }
        }
        if (x == 0) {
            return executeMainX0(op, y, z);
        }
        return executeMainX3(op, y, z);
    }

    private int executeMainX0(int op, int y, int z) {
        switch (z) {
            case 0 -> {
                switch (y) {
                    case 0 -> { return 4; }                       // NOP
                    case 1 -> { int t = af(); setAf((a2 << 8) | f2); a2 = (t >> 8) & 0xFF; f2 = t & 0xFF; return 4; } // EX AF,AF'
                    case 2 -> {                                   // DJNZ d
                        int d8 = (byte) fetch();
                        b = (b - 1) & 0xFF;
                        if (b != 0) { pc = (pc + d8) & 0xFFFF; return 13; }
                        return 8;
                    }
                    case 3 -> { int d8 = (byte) fetch(); pc = (pc + d8) & 0xFFFF; return 12; } // JR d
                    default -> {                                  // JR cc,d (y=4..7)
                        int d8 = (byte) fetch();
                        if (condition(y - 4)) { pc = (pc + d8) & 0xFFFF; return 12; }
                        return 7;
                    }
                }
            }
            case 1 -> {
                if ((y & 1) == 0) { setRegPairSP(y >> 1, fetch16()); return 10; } // LD rr,nn
                setHl(add16(hl(), getRegPairSP(y >> 1))); return 11;              // ADD HL,rr
            }
            case 2 -> { return ldIndirect(y); }
            case 3 -> {
                if ((y & 1) == 0) { setRegPairSP(y >> 1, (getRegPairSP(y >> 1) + 1) & 0xFFFF); } // INC rr
                else { setRegPairSP(y >> 1, (getRegPairSP(y >> 1) - 1) & 0xFFFF); }              // DEC rr
                return 6;
            }
            case 4 -> { setReg(y, inc8(getReg(y))); return y == 6 ? 11 : 4; }     // INC r
            case 5 -> { setReg(y, dec8(getReg(y))); return y == 6 ? 11 : 4; }     // DEC r
            case 6 -> { setReg(y, fetch()); return y == 6 ? 10 : 7; }             // LD r,n
            default -> {                                                          // z==7
                switch (y) {
                    case 0 -> rlca();
                    case 1 -> rrca();
                    case 2 -> rla();
                    case 3 -> rra();
                    case 4 -> daa();
                    case 5 -> cpl();
                    case 6 -> scf();
                    default -> ccf();
                }
                return 4;
            }
        }
    }

    private int ldIndirect(int y) {
        switch (y) {
            case 0 -> { write(bc(), a); return 7; }              // LD (BC),A
            case 1 -> { a = read(bc()); return 7; }              // LD A,(BC)
            case 2 -> { write(de(), a); return 7; }              // LD (DE),A
            case 3 -> { a = read(de()); return 7; }              // LD A,(DE)
            case 4 -> { write16(fetch16(), hl()); return 16; }   // LD (nn),HL
            case 5 -> { setHl(read16(fetch16())); return 16; }   // LD HL,(nn)
            case 6 -> { write(fetch16(), a); return 13; }        // LD (nn),A
            default -> { a = read(fetch16()); return 13; }       // LD A,(nn)
        }
    }

    private int executeMainX3(int op, int y, int z) {
        switch (z) {
            case 0 -> { if (condition(y)) { pc = pop16(); return 11; } return 5; } // RET cc
            case 1 -> {
                if ((y & 1) == 0) { setRegPairAF(y >> 1, pop16()); return 10; }    // POP rr
                switch (y >> 1) {
                    case 0 -> { pc = pop16(); return 10; }                         // RET
                    case 1 -> { exx(); return 4; }                                 // EXX
                    case 2 -> { pc = hl(); return 4; }                             // JP (HL)
                    default -> { sp = hl(); return 6; }                            // LD SP,HL
                }
            }
            case 2 -> { int nn = fetch16(); if (condition(y)) pc = nn; return 10; } // JP cc,nn
            case 3 -> { return executeX3Z3(y); }
            case 4 -> { int nn = fetch16(); if (condition(y)) { push16(pc); pc = nn; return 17; } return 10; } // CALL cc,nn
            case 5 -> {
                if ((y & 1) == 0) { push16(getRegPairAF(y >> 1)); return 11; }     // PUSH rr
                if (y == 1) { int nn = fetch16(); push16(pc); pc = nn; return 17; }// CALL nn
                return 4; // DD/FD/ED handled earlier; unreachable
            }
            case 6 -> { alu(y, fetch()); return 7; }                               // ALU A,n
            default -> { push16(pc); pc = y * 8; return 11; }                      // RST
        }
    }

    private int executeX3Z3(int y) {
        switch (y) {
            case 0 -> { pc = fetch16(); return 10; }                 // JP nn
            case 1 -> { return executeCB(); }                        // CB prefix (unreachable here)
            case 2 -> { int port = fetch(); bus.writePort((a << 8) | port, a); return 11; } // OUT (n),A
            case 3 -> { int port = fetch(); a = bus.readPort((a << 8) | port) & 0xFF; return 11; } // IN A,(n)
            case 4 -> { int t = hl(); int sploc = read16(sp); setHl(sploc); write16(sp, t); return 19; } // EX (SP),HL
            case 5 -> { int t = de(); setDe(hl()); setHl(t); return 4; }           // EX DE,HL
            case 6 -> { iff1 = iff2 = false; return 4; }                           // DI
            default -> { iff1 = iff2 = true; eiPending = true; return 4; }         // EI
        }
    }

    private void exx() {
        int t;
        t = b; b = b2; b2 = t; t = c; c = c2; c2 = t;
        t = d; d = d2; d2 = t; t = e; e = e2; e2 = t;
        t = h; h = h2; h2 = t; t = l; l = l2; l2 = t;
    }

    // rr index 0=BC 1=DE 2=HL 3=SP
    private int getRegPairSP(int idx) {
        return switch (idx) {
            case 0 -> bc();
            case 1 -> de();
            case 2 -> hl();
            default -> sp;
        };
    }

    private void setRegPairSP(int idx, int v) {
        switch (idx) {
            case 0 -> setBc(v);
            case 1 -> setDe(v);
            case 2 -> setHl(v);
            default -> sp = v & 0xFFFF;
        }
    }

    // rr index 0=BC 1=DE 2=HL 3=AF (for PUSH/POP)
    private int getRegPairAF(int idx) {
        return idx == 3 ? af() : getRegPairSP(idx);
    }

    private void setRegPairAF(int idx, int v) {
        if (idx == 3) setAf(v); else setRegPairSP(idx, v);
    }

    // ======================================================================
    //  CB-prefixed: rotates / shifts / BIT / RES / SET
    // ======================================================================

    private int executeCB() {
        int op = fetchOpcode();
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;
        int v = getReg(z);
        switch (x) {
            case 0 -> { setReg(z, rotShift(y, v)); return z == 6 ? 15 : 8; }
            case 1 -> { // BIT
                if (z == 6) { bitMem(y, v, (hl() >> 8) & 0xFF); return 12; }
                bit(y, v); return 8;
            }
            case 2 -> { setReg(z, v & ~(1 << y)); return z == 6 ? 15 : 8; }       // RES
            default -> { setReg(z, v | (1 << y)); return z == 6 ? 15 : 8; }       // SET
        }
    }

    private int rotShift(int y, int v) {
        return switch (y) {
            case 0 -> rlc(v);
            case 1 -> rrc(v);
            case 2 -> rl(v);
            case 3 -> rr(v);
            case 4 -> sla(v);
            case 5 -> sra(v);
            case 6 -> sll(v);
            default -> srl(v);
        };
    }

    // ======================================================================
    //  ED-prefixed: extended ops
    // ======================================================================

    private int executeED() {
        int op = fetchOpcode();
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;

        if (x == 1) {
            switch (z) {
                case 0 -> { // IN r,(C)
                    int val = bus.readPort(bc()) & 0xFF;
                    f = sz53p(val) | (f & FLAG_C);
                    if (y != 6) setReg(y, val);
                    return 12;
                }
                case 1 -> { // OUT (C),r
                    bus.writePort(bc(), y == 6 ? 0 : getReg(y));
                    return 12;
                }
                case 2 -> {
                    if ((y & 1) == 0) sbc16(getRegPairSP(y >> 1)); // SBC HL,rr
                    else adc16(getRegPairSP(y >> 1));              // ADC HL,rr
                    return 15;
                }
                case 3 -> {
                    if ((y & 1) == 0) { write16(fetch16(), getRegPairSP(y >> 1)); } // LD (nn),rr
                    else { setRegPairSP(y >> 1, read16(fetch16())); }               // LD rr,(nn)
                    return 20;
                }
                case 4 -> { neg(); return 8; }
                case 5 -> { // RETN / RETI
                    iff1 = iff2;
                    pc = pop16();
                    return 14;
                }
                case 6 -> { im = (y == 0 || y == 4) ? 0 : (y == 2 || y == 6) ? 1 : 2; return 8; } // IM
                default -> { return edAssorted(y); }                               // z==7
            }
        }
        if (x == 2 && z <= 3 && y >= 4) {
            return blockOp(y, z);
        }
        return 8; // undefined ED opcode → NOP-like
    }

    private int edAssorted(int y) {
        switch (y) {
            case 0 -> { i = a; return 9; }                       // LD I,A
            case 1 -> { r = a; return 9; }                       // LD R,A
            case 2 -> {                                          // LD A,I
                a = i;
                f = sz53(a) | (f & FLAG_C) | (iff2 ? FLAG_PV : 0);
                return 9;
            }
            case 3 -> {                                          // LD A,R
                a = r & 0xFF;
                f = sz53(a) | (f & FLAG_C) | (iff2 ? FLAG_PV : 0);
                return 9;
            }
            case 4 -> { rrd(); return 18; }
            case 5 -> { rld(); return 18; }
            default -> { return 9; }                             // 6,7: NOP
        }
    }

    private void rrd() {
        int m = read(hl());
        int newM = ((m >> 4) | (a << 4)) & 0xFF;
        a = (a & 0xF0) | (m & 0x0F);
        write(hl(), newM);
        f = sz53p(a) | (f & FLAG_C);
    }

    private void rld() {
        int m = read(hl());
        int newM = ((m << 4) | (a & 0x0F)) & 0xFF;
        a = (a & 0xF0) | ((m >> 4) & 0x0F);
        write(hl(), newM);
        f = sz53p(a) | (f & FLAG_C);
    }

    // Block transfer / search / I-O. y: 4=I 5=D 6=IR 7=DR; z: 0=LD 1=CP 2=IN 3=OUT
    private int blockOp(int y, int z) {
        boolean inc = (y & 1) == 0;       // y 4/6 increment, 5/7 decrement
        boolean repeat = (y & 2) != 0;    // y 6/7 repeat
        return switch (z) {
            case 0 -> blockLoad(inc, repeat);
            case 1 -> blockCompare(inc, repeat);
            case 2 -> blockIn(inc, repeat);
            default -> blockOut(inc, repeat);
        };
    }

    private int blockLoad(boolean inc, boolean repeat) {
        int val = read(hl());
        write(de(), val);
        setHl((hl() + (inc ? 1 : -1)) & 0xFFFF);
        setDe((de() + (inc ? 1 : -1)) & 0xFFFF);
        setBc((bc() - 1) & 0xFFFF);
        int n = (a + val) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_C)) | (bc() != 0 ? FLAG_PV : 0)
                | ((n & 0x02) != 0 ? FLAG_X5 : 0) | ((n & 0x08) != 0 ? FLAG_X3 : 0);
        if (repeat && bc() != 0) { pc = (pc - 2) & 0xFFFF; return 21; }
        return 16;
    }

    private int blockCompare(boolean inc, boolean repeat) {
        int val = read(hl());
        int res = (a - val) & 0xFF;
        setHl((hl() + (inc ? 1 : -1)) & 0xFFFF);
        setBc((bc() - 1) & 0xFFFF);
        int fl = (f & FLAG_C) | FLAG_N | (sz53(res) & (FLAG_S | FLAG_Z));
        boolean hcarry = ((a & 0xF) - (val & 0xF)) < 0;
        if (hcarry) fl |= FLAG_H;
        int n = (res - (hcarry ? 1 : 0)) & 0xFF;
        if ((n & 0x02) != 0) fl |= FLAG_X5;
        if ((n & 0x08) != 0) fl |= FLAG_X3;
        if (bc() != 0) fl |= FLAG_PV;
        f = fl;
        if (repeat && bc() != 0 && res != 0) { pc = (pc - 2) & 0xFFFF; return 21; }
        return 16;
    }

    private int blockIn(boolean inc, boolean repeat) {
        int val = bus.readPort(bc()) & 0xFF;
        write(hl(), val);
        b = (b - 1) & 0xFF;
        setHl((hl() + (inc ? 1 : -1)) & 0xFFFF);
        f = sz53(b) | FLAG_N;
        if (repeat && b != 0) { pc = (pc - 2) & 0xFFFF; return 21; }
        return 16;
    }

    private int blockOut(boolean inc, boolean repeat) {
        int val = read(hl());
        b = (b - 1) & 0xFF;
        bus.writePort(bc(), val);
        setHl((hl() + (inc ? 1 : -1)) & 0xFFFF);
        f = sz53(b) | FLAG_N;
        if (repeat && b != 0) { pc = (pc - 2) & 0xFFFF; return 21; }
        return 16;
    }

    // ======================================================================
    //  DD / FD-prefixed: IX / IY
    // ======================================================================

    private int executeIndex(int idxReg, boolean isIx) {
        int op = fetchOpcode();

        if (op == 0xCB) {
            return executeIndexCB(idxReg, isIx);
        }
        if (op == 0xDD || op == 0xFD || op == 0xED) {
            // A redundant prefix: ignore this one and process the next.
            pc = (pc - 1) & 0xFFFF;
            return 4;
        }

        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;

        // Does this opcode use the (HL) memory slot (→ (IX+d))?
        boolean memDst = (x == 1) && y == 6 && op != 0x76;
        boolean memSrc = ((x == 1 && z == 6) || (x == 2 && z == 6)) && op != 0x76;
        boolean memMisc = (x == 0) && (op == 0x34 || op == 0x35 || op == 0x36); // INC/DEC/LD (HL),n
        boolean usesMem = memDst || memSrc || memMisc;

        int addr = 0;
        if (usesMem) {
            int d = (byte) fetch();
            addr = (idxReg + d) & 0xFFFF;
        }

        // When memory is used the other operand is a *real* register; otherwise
        // H/L (reg 4/5) become the index register's halves.
        int result = executeIndexOp(op, x, y, z, idxReg, addr, usesMem, isIx);
        return result;
    }

    private int idxHigh(int idxReg) { return (idxReg >> 8) & 0xFF; }
    private int idxLow(int idxReg) { return idxReg & 0xFF; }

    private void storeIndex(boolean isIx, int v) {
        if (isIx) ix = v & 0xFFFF; else iy = v & 0xFFFF;
    }

    private int getRegIdx(int idx, int idxReg, boolean halves) {
        if (halves && idx == 4) return idxHigh(idxReg);
        if (halves && idx == 5) return idxLow(idxReg);
        return getReg(idx);
    }

    private int setRegIdx(int idx, int v, int idxReg, boolean halves, boolean isIx) {
        v &= 0xFF;
        if (halves && idx == 4) { idxReg = (idxReg & 0x00FF) | (v << 8); storeIndex(isIx, idxReg); return idxReg; }
        if (halves && idx == 5) { idxReg = (idxReg & 0xFF00) | v; storeIndex(isIx, idxReg); return idxReg; }
        setReg(idx, v);
        return idxReg;
    }

    private int executeIndexOp(int op, int x, int y, int z, int idxReg, int addr,
                               boolean usesMem, boolean isIx) {
        // index register pair for the rr-tables (HL slot → IX/IY).
        switch (x) {
            case 1 -> { // LD
                if (usesMem) {
                    if (y == 6) { write(addr, getReg(z)); return 19; }   // LD (IX+d),r
                    setReg(y, read(addr)); return 19;                     // LD r,(IX+d)
                }
                // register-register with halves
                int src = getRegIdx(z, idxReg, true);
                setRegIdx(y, src, idxReg, true, isIx);
                return 8;
            }
            case 2 -> { // ALU
                if (usesMem) { alu(y, read(addr)); return 19; }
                alu(y, getRegIdx(z, idxReg, true));
                return 8;
            }
            default -> { }
        }
        if (x == 0) {
            return indexX0(op, y, z, idxReg, addr, usesMem, isIx);
        }
        return indexX3(op, y, z, idxReg, isIx);
    }

    private int indexX0(int op, int y, int z, int idxReg, int addr, boolean usesMem, boolean isIx) {
        switch (z) {
            case 1 -> {
                if ((y & 1) == 0) {
                    if (y >> 1 == 2) { storeIndex(isIx, fetch16()); return 14; }   // LD IX,nn
                    setRegPairSP(y >> 1, fetch16()); return 10;
                }
                // ADD IX,rr  (rr: HL slot = IX itself)
                int rr = indexPair(y >> 1, idxReg);
                int res = add16(idxReg, rr);
                storeIndex(isIx, res);
                return 11;
            }
            case 2 -> { return ldIndirectIndex(y, idxReg, isIx); }
            case 3 -> {
                if (y >> 1 == 2) { // INC/DEC IX
                    storeIndex(isIx, (idxReg + ((y & 1) == 0 ? 1 : -1)) & 0xFFFF);
                } else if ((y & 1) == 0) {
                    setRegPairSP(y >> 1, (getRegPairSP(y >> 1) + 1) & 0xFFFF);
                } else {
                    setRegPairSP(y >> 1, (getRegPairSP(y >> 1) - 1) & 0xFFFF);
                }
                return 10;
            }
            case 4 -> { // INC
                if (usesMem) { write(addr, inc8(read(addr))); return 23; }
                int v = inc8(getRegIdx(y, idxReg, true));
                setRegIdx(y, v, idxReg, true, isIx);
                return 8;
            }
            case 5 -> { // DEC
                if (usesMem) { write(addr, dec8(read(addr))); return 23; }
                int v = dec8(getRegIdx(y, idxReg, true));
                setRegIdx(y, v, idxReg, true, isIx);
                return 8;
            }
            case 6 -> { // LD r,n  / LD (IX+d),n
                if (usesMem) { write(addr, fetch()); return 19; }
                setRegIdx(y, fetch(), idxReg, true, isIx);
                return 11;
            }
            default -> {
                // 0x07/0x0F/... rotates etc. behave as the main op (no index effect).
                return executeMainX0(op, y, z);
            }
        }
    }

    private int indexPair(int idx, int idxReg) {
        return switch (idx) {
            case 0 -> bc();
            case 1 -> de();
            case 2 -> idxReg;
            default -> sp;
        };
    }

    private int ldIndirectIndex(int y, int idxReg, boolean isIx) {
        switch (y) {
            case 4 -> { write16(fetch16(), idxReg); return 20; }          // LD (nn),IX
            case 5 -> { storeIndex(isIx, read16(fetch16())); return 20; } // LD IX,(nn)
            default -> { return ldIndirect(y); }
        }
    }

    private int indexX3(int op, int y, int z, int idxReg, boolean isIx) {
        switch (op) {
            case 0xE1 -> { storeIndex(isIx, pop16()); return 14; }        // POP IX
            case 0xE5 -> { push16(idxReg); return 15; }                  // PUSH IX
            case 0xE9 -> { pc = idxReg; return 8; }                      // JP (IX)
            case 0xF9 -> { sp = idxReg; return 10; }                     // LD SP,IX
            case 0xE3 -> {                                               // EX (SP),IX
                int t = read16(sp);
                write16(sp, idxReg);
                storeIndex(isIx, t);
                return 23;
            }
            default -> { return executeMainX3(op, y, z); }               // others ignore the prefix
        }
    }

    private int executeIndexCB(int idxReg, boolean isIx) {
        int d = (byte) fetch();
        int op = fetchOpcode();
        int addr = (idxReg + d) & 0xFFFF;
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;
        int v = read(addr);

        if (x == 1) { // BIT n,(IX+d)
            bitMem(y, v, (addr >> 8) & 0xFF);
            return 20;
        }
        int res = switch (x) {
            case 0 -> rotShift(y, v);
            case 2 -> v & ~(1 << y);
            default -> v | (1 << y);
        };
        write(addr, res);
        // Undocumented: unless z==6, also copy the result into register z.
        if (z != 6) setReg(z, res);
        return 23;
    }

    // ======================================================================
    //  Test / debug accessors
    // ======================================================================

    public int getA() { return a; }
    public int getF() { return f; }
    public int getB() { return b; }
    public int getC() { return c; }
    public int getD() { return d; }
    public int getE() { return e; }
    public int getH() { return h; }
    public int getL() { return l; }
    public int getBC() { return bc(); }
    public int getDE() { return de(); }
    public int getHL() { return hl(); }
    public int getIX() { return ix; }
    public int getIY() { return iy; }
    public int getSP() { return sp; }
    public int getPC() { return pc; }
    public int getI() { return i; }
    public int getIM() { return im; }
    public boolean getIff1() { return iff1; }

    public void setPC(int v) { pc = v & 0xFFFF; }
    public void setSP(int v) { sp = v & 0xFFFF; }
    public void pokeA(int v) { a = v & 0xFF; }
    public void pokeF(int v) { f = v & 0xFF; }
    public void pokeBC(int v) { setBc(v); }
    public void pokeDE(int v) { setDe(v); }
    public void pokeHL(int v) { setHl(v); }
    public void pokeSP(int v) { sp = v & 0xFFFF; }

    // ---- save state -------------------------------------------------------

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        int[] regs = {a, f, b, c, d, e, h, l, a2, f2, b2, c2, d2, e2, h2, l2,
                ix, iy, sp, pc, i, r, im};
        for (int v : regs) o.writeInt(v);
        o.writeBoolean(iff1);
        o.writeBoolean(iff2);
        o.writeBoolean(halted);
        o.writeBoolean(intLine);
        o.writeBoolean(nmiPending);
        o.writeBoolean(eiPending);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        a = in.readInt(); f = in.readInt(); b = in.readInt(); c = in.readInt();
        d = in.readInt(); e = in.readInt(); h = in.readInt(); l = in.readInt();
        a2 = in.readInt(); f2 = in.readInt(); b2 = in.readInt(); c2 = in.readInt();
        d2 = in.readInt(); e2 = in.readInt(); h2 = in.readInt(); l2 = in.readInt();
        ix = in.readInt(); iy = in.readInt(); sp = in.readInt(); pc = in.readInt();
        i = in.readInt(); r = in.readInt(); im = in.readInt();
        iff1 = in.readBoolean();
        iff2 = in.readBoolean();
        halted = in.readBoolean();
        intLine = in.readBoolean();
        nmiPending = in.readBoolean();
        eiPending = in.readBoolean();
    }
}
