package com.segaemu.cpu.m68k;

import com.segaemu.bus.Bus68000;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A Motorola 68000 CPU core implementing a functional subset of the instruction
 * set — enough to execute the boot/initialisation code of real Mega Drive ROMs.
 *
 * <p><b>Registers:</b> eight data registers D0–D7, seven address registers
 * A0–A6, a split stack pointer (USP/SSP) presented as A7, the program counter,
 * and the status register (supervisor/interrupt bits plus the X-N-Z-V-C
 * condition codes).
 *
 * <p><b>Implemented instruction families:</b> MOVE/MOVEA/MOVEQ/MOVEP, MOVEM,
 * LINK/UNLK, LEA/PEA, CLR/TST/NOT/NEG/NEGX/SWAP/EXT, ADD/ADDA/ADDI/ADDQ/ADDX,
 * SUB/SUBA/SUBI/SUBQ/SUBX, AND/ANDI/OR/ORI/EOR/EORI (incl. to CCR/SR),
 * CMP/CMPA/CMPI/CMPM, ABCD/SBCD/NBCD, the shift/rotate group
 * (ASL/ASR/LSL/LSR/ROL/ROR/ROXL/ROXR), the bit group (BTST/BSET/BCLR/BCHG),
 * MULU/MULS, DIVU/DIVS, CHK, Bcc/BRA/BSR/DBcc/Scc, JMP/JSR/RTS/RTE/RTR,
 * TRAP/TRAPV, NOP, STOP, and MOVE to/from SR/CCR.
 *
 * <p>All 14 addressing modes are decoded; line-A ($Axxx) and line-F ($Fxxx)
 * opcodes vector to their emulator traps (10/11) and other unimplemented opcodes
 * raise the illegal-instruction exception (vector 4). Cycle counts are
 * approximate (good enough to pace the system clock, not cycle-exact).
 *
 * <p>Values are kept in {@code int}s and masked to the operand size at the
 * boundaries; address calculations wrap to the 24-bit external bus.
 */
public final class M68000 {

    private final Bus68000 bus;

    // --- registers ---------------------------------------------------------
    private final int[] d = new int[8];
    private final int[] a = new int[8]; // a[7] is the active stack pointer
    private int pc;
    private int usp; // inactive user stack pointer while in supervisor mode
    private int ssp; // inactive supervisor stack pointer while in user mode

    // --- status register ---------------------------------------------------
    private boolean flagC, flagV, flagZ, flagN, flagX;
    private boolean supervisor = true;
    private int interruptMask = 7;
    private boolean stopped = false;

    private long cyclesThisStep;

    // Operand sizes.
    private static final int BYTE = 0, WORD = 1, LONG = 2;

    public M68000(Bus68000 bus) {
        this.bus = bus;
    }

    /** Cold reset: load SSP and PC from the vector table at $000000. */
    public void reset() {
        supervisor = true;
        interruptMask = 7;
        stopped = false;
        a[7] = bus.read32(0x000000);
        ssp = a[7];
        pc = bus.read32(0x000004);
        flagC = flagV = flagZ = flagN = flagX = false;
    }

    // ======================================================================
    //  Top-level step
    // ======================================================================

    /** Execute one instruction; returns the (approximate) cycles it consumed. */
    public long step() {
        if (stopped) {
            return 4;
        }
        cyclesThisStep = 4;
        int opcode = fetch16();
        execute(opcode);
        return cyclesThisStep;
    }

    /**
     * Deliver a level-{@code level} interrupt if it is not masked. The Mega
     * Drive wires the VDP vertical interrupt to level 6 and horizontal to 4.
     * Returns true if the interrupt was accepted; if it was masked the caller
     * should keep it pending (interrupts are level-triggered).
     */
    public boolean interrupt(int level, int vector) {
        if (level > interruptMask || level == 7) {
            stopped = false;
            int sr = packSr();
            enterSupervisor();
            push32(pc);
            push16(sr);
            interruptMask = level;
            pc = bus.read32(vector * 4) & 0xFFFFFF;
            return true;
        }
        return false;
    }

    // ======================================================================
    //  Instruction decode / dispatch
    // ======================================================================

    private void execute(int op) {
        int top = (op >> 12) & 0xF;
        switch (top) {
            case 0x0 -> groupImmediateAndBit(op);
            case 0x1 -> move(op, BYTE);
            case 0x2 -> move(op, LONG);
            case 0x3 -> move(op, WORD);
            case 0x4 -> groupMisc(op);
            case 0x5 -> groupAddqSubqSccDbcc(op);
            case 0x6 -> groupBranch(op);
            case 0x7 -> moveq(op);
            case 0x8 -> groupOrDiv(op);
            case 0x9 -> groupSub(op);
            case 0xB -> groupCmpEor(op);
            case 0xC -> groupAndMul(op);
            case 0xD -> groupAdd(op);
            case 0xE -> groupShift(op);
            case 0xA -> exception(10); // line-A emulator trap
            case 0xF -> exception(11); // line-F emulator trap
            default -> illegal(op);
        }
    }

    // ======================================================================
    //  $0xxx — immediates to EA, bit ops
    // ======================================================================

    private void groupImmediateAndBit(int op) {
        // MOVEP (0000 ddd1 ss 001 aaa) shares the dynamic-bit encoding space and
        // must be decoded before the bit ops.
        if ((op & 0x0138) == 0x0108) {
            movep(op);
            return;
        }
        int rest = (op >> 8) & 0xF;
        if ((op & 0x0100) != 0 || rest == 0x8) {
            bitOp(op);
            return;
        }
        int size = sizeField(op);
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        switch (rest) {
            case 0x0 -> { // ORI / ORI to CCR / ORI to SR
                if (special(op, size, mode, reg, this::orValue)) {
                    return;
                }
                immediateToEa(size, mode, reg, this::orValue);
            }
            case 0x2 -> { // ANDI
                if (special(op, size, mode, reg, this::andValue)) {
                    return;
                }
                immediateToEa(size, mode, reg, this::andValue);
            }
            case 0x4 -> subi(size, mode, reg); // SUBI
            case 0x6 -> addi(size, mode, reg); // ADDI
            case 0xA -> { // EORI
                if (special(op, size, mode, reg, this::eorValue)) {
                    return;
                }
                immediateToEa(size, mode, reg, this::eorValue);
            }
            case 0xC -> cmpi(size, mode, reg); // CMPI
            default -> illegal(op);
        }
    }

    /** ADDI #imm,&lt;ea&gt; — arithmetic flags (X/N/Z/V/C), unlike the logic forms. */
    private void addi(int size, int mode, int reg) {
        int imm = fetchImmediate(size);
        Ea ea = decode(mode, reg, size);
        int v = ea.read();
        int r = (v + imm) & mask(size);
        setAddFlags(v, imm, r, size);
        flagX = flagC;
        ea.write(r);
    }

    /** SUBI #imm,&lt;ea&gt; — arithmetic flags. */
    private void subi(int size, int mode, int reg) {
        int imm = fetchImmediate(size);
        Ea ea = decode(mode, reg, size);
        int v = ea.read();
        int r = (v - imm) & mask(size);
        setSubFlags(v, imm, r, size);
        flagX = flagC;
        ea.write(r);
    }

    /**
     * MOVEP — transfer 2 or 4 bytes between a data register and alternating
     * (every other) bytes of memory at (d16,An). Used for 8-bit peripherals.
     */
    private void movep(int op) {
        int dreg = (op >> 9) & 7;
        int areg = op & 7;
        int opmode = (op >> 6) & 3; // 0=w m→r, 1=l m→r, 2=w r→m, 3=l r→m
        int disp = signExtend(fetch16(), WORD);
        int addr = (a[areg] + disp) & 0xFFFFFF;
        boolean toMemory = (opmode & 2) != 0;
        boolean isLong = (opmode & 1) != 0;
        if (toMemory) {
            if (isLong) {
                bus.write8(addr, (d[dreg] >>> 24) & 0xFF);
                bus.write8(addr + 2, (d[dreg] >>> 16) & 0xFF);
                bus.write8(addr + 4, (d[dreg] >>> 8) & 0xFF);
                bus.write8(addr + 6, d[dreg] & 0xFF);
            } else {
                bus.write8(addr, (d[dreg] >>> 8) & 0xFF);
                bus.write8(addr + 2, d[dreg] & 0xFF);
            }
        } else if (isLong) {
            d[dreg] = (bus.read8(addr) << 24) | (bus.read8(addr + 2) << 16)
                    | (bus.read8(addr + 4) << 8) | bus.read8(addr + 6);
        } else {
            int val = (bus.read8(addr) << 8) | bus.read8(addr + 2);
            d[dreg] = (d[dreg] & 0xFFFF0000) | (val & 0xFFFF);
        }
    }

    /** Handle the "to CCR" / "to SR" forms shared by ORI/ANDI/EORI. */
    private boolean special(int op, int size, int mode, int reg,
                            java.util.function.IntBinaryOperator combine) {
        if (mode == 7 && reg == 4 && size == BYTE) { // #imm to CCR
            int imm = fetch16() & 0xFF;
            int r = combine.applyAsInt(packCcr(), imm) & 0xFF;
            unpackCcr(r);
            return true;
        }
        if (mode == 7 && reg == 4 && size == WORD) { // #imm to SR (privileged)
            if (!supervisor) {
                privilegeViolation();
                return true;
            }
            int imm = fetch16();
            unpackSr(combine.applyAsInt(packSr(), imm) & 0xFFFF);
            return true;
        }
        return false;
    }

    private void immediateToEa(int size, int mode, int reg,
                               java.util.function.IntBinaryOperator combine) {
        int imm = fetchImmediate(size);
        Ea ea = decode(mode, reg, size);
        int v = ea.read();
        int r = combine.applyAsInt(v, imm);
        ea.write(r);
        setLogicFlags(r, size);
    }

    private int orValue(int a, int b) { return a | b; }
    private int andValue(int a, int b) { return a & b; }
    private int eorValue(int a, int b) { return a ^ b; }

    private void cmpi(int size, int mode, int reg) {
        int imm = fetchImmediate(size);
        Ea ea = decode(mode, reg, size);
        int v = ea.read();
        compare(v, imm, size);
    }

    private void bitOp(int op) {
        int type = (op >> 6) & 3; // 0 BTST 1 BCHG 2 BCLR 3 BSET
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        int bit;
        if ((op & 0x0100) != 0) {
            bit = d[(op >> 9) & 7];
        } else {
            bit = fetch16() & 0xFF;
        }
        // Bit number is mod 32 for data registers, mod 8 for memory.
        boolean isReg = mode == 0;
        bit &= isReg ? 31 : 7;
        Ea ea = decode(mode, reg, isReg ? LONG : BYTE);
        int v = ea.read();
        flagZ = ((v >> bit) & 1) == 0;
        switch (type) {
            case 1 -> ea.write(v ^ (1 << bit));   // BCHG
            case 2 -> ea.write(v & ~(1 << bit));  // BCLR
            case 3 -> ea.write(v | (1 << bit));   // BSET
            default -> { /* BTST: no write */ }
        }
    }

    // ======================================================================
    //  MOVE / MOVEA  ($1xxx,$2xxx,$3xxx)
    // ======================================================================

    private void move(int op, int size) {
        int srcMode = (op >> 3) & 7;
        int srcReg = op & 7;
        int dstMode = (op >> 6) & 7;
        int dstReg = (op >> 9) & 7;

        Ea src = decode(srcMode, srcReg, size);
        int value = src.read();

        if (dstMode == 1) { // MOVEA — sign-extend to long, no flags
            int ext = (size == WORD) ? signExtend(value, WORD) : value;
            setA(dstReg, ext);
            return;
        }
        Ea dst = decode(dstMode, dstReg, size);
        dst.write(value);
        setLogicFlags(value, size);
    }

    private void moveq(int op) {
        int reg = (op >> 9) & 7;
        int value = signExtend(op & 0xFF, BYTE);
        d[reg] = value;
        setLogicFlags(value, LONG);
    }

    // ======================================================================
    //  $4xxx — miscellaneous
    // ======================================================================

    private void groupMisc(int op) {
        // Fully-decoded single opcodes first.
        switch (op) {
            case 0x4E70 -> { /* RESET */ return; }
            case 0x4E71 -> { /* NOP */ return; }
            case 0x4E72 -> { stop(); return; }    // STOP #imm
            case 0x4E73 -> { rte(); return; }     // RTE
            case 0x4E75 -> { rts(); return; }     // RTS
            case 0x4E77 -> { rtr(); return; }     // RTR
            case 0x4E76 -> { trapv(); return; }   // TRAPV
            default -> { }
        }
        if ((op & 0xFFF8) == 0x4E50) { // LINK An,#disp
            link(op);
            return;
        }
        if ((op & 0xFFF8) == 0x4E58) { // UNLK An
            unlk(op);
            return;
        }
        if ((op & 0xFFC0) == 0x4800) { // NBCD <ea>
            nbcd((op >> 3) & 7, op & 7);
            return;
        }
        if ((op & 0xFFF0) == 0x4E60) { // MOVE USP
            moveUsp(op);
            return;
        }
        if ((op & 0xFFF0) == 0x4E40) { // TRAP #vector
            trap(op & 0xF);
            return;
        }
        if ((op & 0xFF80) == 0x4E80) { // JSR / JMP
            int mode = (op >> 3) & 7;
            int reg = op & 7;
            int target = decode(mode, reg, LONG).address();
            if ((op & 0x40) == 0) {
                push32(pc); // JSR
            }
            pc = target & 0xFFFFFF;
            return;
        }
        // SWAP ($4840-$4847) has the same size field as PEA but a mode field of
        // 0, so it must be matched first or PEA would steal it and corrupt the
        // stack. Use the exact mask.
        if ((op & 0xFFF8) == 0x4840) { // SWAP Dn
            swap(op);
            return;
        }
        if ((op & 0xFFC0) == 0x4840 && ((op >> 3) & 7) >= 2) { // PEA <ea>
            int ea = decode((op >> 3) & 7, op & 7, LONG).address();
            push32(ea);
            return;
        }
        if ((op & 0xFFB8) == 0x4880) { // EXT
            ext(op);
            return;
        }
        if ((op & 0xFB80) == 0x4880) { // MOVEM
            movem(op);
            return;
        }
        // MOVE to/from SR/CCR. These share a line nibble with NEGX/NEG/NOT and
        // are distinguished only by the size field being %11, so decode them
        // explicitly before the line-based dispatch below.
        if ((op & 0xFFC0) == 0x40C0) { // MOVE from SR -> <ea> (word)
            decode((op >> 3) & 7, op & 7, WORD).write(packSr());
            return;
        }
        if ((op & 0xFFC0) == 0x44C0) { // MOVE <ea> -> CCR
            int v = decode((op >> 3) & 7, op & 7, WORD).read();
            unpackCcr(v & 0xFF);
            return;
        }
        if ((op & 0xFFC0) == 0x46C0) { // MOVE <ea> -> SR (privileged)
            if (!supervisor) {
                privilegeViolation();
                return;
            }
            int v = decode((op >> 3) & 7, op & 7, WORD).read();
            unpackSr(v & 0xFFFF);
            return;
        }
        int line = (op >> 8) & 0xF;
        int size = sizeField(op);
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        switch (line) {
            case 0x0 -> negx(size, mode, reg);
            case 0x2 -> clr(size, mode, reg);
            case 0x4 -> neg(size, mode, reg);
            case 0x6 -> not(size, mode, reg);
            case 0xA -> {
                if (((op >> 6) & 3) == 3) {
                    tas(mode, reg);
                } else {
                    tst(size, mode, reg);
                }
            }
            case 0x8 -> { // NBCD / PEA / EXT handled above; else illegal
                illegal(op);
            }
            case 0xE -> { // LEA / JMP forms with bit pattern 0x41xx are LEA
                illegal(op);
            }
            default -> {
                if ((op & 0x01C0) == 0x01C0) { // LEA  0100 rrr 111 mode reg
                    lea(op);
                } else if ((op & 0x01C0) == 0x0180) { // CHK
                    chk(op);
                } else {
                    illegal(op);
                }
            }
        }
    }

    private void lea(int op) {
        int reg = (op >> 9) & 7;
        int ea = decode((op >> 3) & 7, op & 7, LONG).address();
        setA(reg, ea);
    }

    private void chk(int op) {
        int reg = (op >> 9) & 7;
        int bound = signExtend(decode((op >> 3) & 7, op & 7, WORD).read(), WORD);
        int value = signExtend(d[reg] & 0xFFFF, WORD);
        if (value < 0 || value > bound) {
            exception(6);
        }
    }

    private void clr(int size, int mode, int reg) {
        Ea ea = decode(mode, reg, size);
        ea.read(); // 68000 performs a dummy read
        ea.write(0);
        flagN = false; flagZ = true; flagV = false; flagC = false;
    }

    private void tst(int size, int mode, int reg) {
        int v = decode(mode, reg, size).read();
        setLogicFlags(v, size);
    }

    private void tas(int mode, int reg) {
        Ea ea = decode(mode, reg, BYTE);
        int v = ea.read() & 0xFF;
        flagN = (v & 0x80) != 0;
        flagZ = v == 0;
        flagV = false; flagC = false;
        ea.write(v | 0x80);
    }

    private void not(int size, int mode, int reg) {
        Ea ea = decode(mode, reg, size);
        int r = ~ea.read() & mask(size);
        ea.write(r);
        setLogicFlags(r, size);
    }

    private void neg(int size, int mode, int reg) {
        Ea ea = decode(mode, reg, size);
        int v = ea.read();
        int r = subValueRaw(0, v) & mask(size);
        setSubFlags(0, v, r, size);
        flagX = flagC;
        ea.write(r);
    }

    private void negx(int size, int mode, int reg) {
        Ea ea = decode(mode, reg, size);
        int v = ea.read();
        int x = flagX ? 1 : 0;
        int r = (0 - v - x) & mask(size);
        setSubFlags(0, v, r, size);
        flagX = flagC;
        ea.write(r);
    }

    // ---- LINK / UNLK / STOP ----------------------------------------------

    private void link(int op) {
        int areg = op & 7;
        int disp = signExtend(fetch16(), WORD);
        push32(a[areg]);
        setA(areg, a[7]);
        a[7] = (a[7] + disp) & 0xFFFFFFFF;
    }

    private void unlk(int op) {
        int areg = op & 7;
        a[7] = a[areg];
        setA(areg, pop32());
    }

    private void stop() {
        if (!supervisor) {
            privilegeViolation();
            return;
        }
        unpackSr(fetch16());
        stopped = true;
    }

    // ---- BCD: ABCD / SBCD / NBCD -----------------------------------------

    private void nbcd(int mode, int reg) {
        Ea ea = decode(mode, reg, BYTE);
        ea.write(sbcdByte(0, ea.read()));
    }

    /** ABCD/SBCD: register-register (rm=0) or memory predecrement (rm=1). */
    private void bcdOp(int op, boolean subtract) {
        int rx = (op >> 9) & 7;
        int ry = op & 7;
        boolean memory = (op & 0x08) != 0;
        int src, dst, dstAddr = 0;
        if (memory) {
            a[ry] = (a[ry] - operandStep(ry, BYTE)) & 0xFFFFFFFF;
            src = bus.read8(a[ry] & 0xFFFFFF);
            a[rx] = (a[rx] - operandStep(rx, BYTE)) & 0xFFFFFFFF;
            dstAddr = a[rx] & 0xFFFFFF;
            dst = bus.read8(dstAddr);
        } else {
            src = d[ry] & 0xFF;
            dst = d[rx] & 0xFF;
        }
        int res = subtract ? sbcdByte(dst, src) : abcdByte(dst, src);
        if (memory) {
            bus.write8(dstAddr, res);
        } else {
            d[rx] = (d[rx] & 0xFFFFFF00) | res;
        }
    }

    private int abcdByte(int dst, int src) {
        int res = (src & 0x0F) + (dst & 0x0F) + (flagX ? 1 : 0);
        if (res > 9) {
            res += 6;
        }
        res += (src & 0xF0) + (dst & 0xF0);
        boolean carry = res > 0x99;
        if (carry) {
            res -= 0xA0;
        }
        flagC = carry;
        flagX = carry;
        res &= 0xFF;
        if (res != 0) {
            flagZ = false; // Z is only cleared, never set, by BCD ops
        }
        flagN = (res & 0x80) != 0;
        return res;
    }

    private int sbcdByte(int dst, int src) {
        int res = (dst & 0x0F) - (src & 0x0F) - (flagX ? 1 : 0);
        if (res < 0) {
            res -= 6;
        }
        res += (dst & 0xF0) - (src & 0xF0);
        boolean carry = res < 0;
        if (carry) {
            res += 0xA0;
        }
        flagC = carry;
        flagX = carry;
        res &= 0xFF;
        if (res != 0) {
            flagZ = false;
        }
        flagN = (res & 0x80) != 0;
        return res;
    }

    // ---- ADDX / SUBX / CMPM ----------------------------------------------

    private void addxSubx(int op, boolean subtract) {
        int size = sizeField(op);
        int rx = (op >> 9) & 7;
        int ry = op & 7;
        boolean memory = (op & 0x08) != 0;
        int src, dst, dstAddr = 0;
        if (memory) {
            a[ry] = (a[ry] - operandStep(ry, size)) & 0xFFFFFFFF;
            src = readSized(a[ry] & 0xFFFFFF, size);
            a[rx] = (a[rx] - operandStep(rx, size)) & 0xFFFFFFFF;
            dstAddr = a[rx] & 0xFFFFFF;
            dst = readSized(dstAddr, size);
        } else {
            src = d[ry] & mask(size);
            dst = d[rx] & mask(size);
        }
        int x = flagX ? 1 : 0;
        boolean prevZ = flagZ;
        int r;
        if (subtract) {
            r = (dst - src - x) & mask(size);
            setSubFlags(dst, src + x, r, size);
        } else {
            r = (dst + src + x) & mask(size);
            setAddFlags(dst, src + x, r, size);
        }
        // X-form: Z is only cleared when the result is non-zero.
        flagZ = prevZ && r == 0;
        flagX = flagC;
        if (memory) {
            writeSized(dstAddr, r, size);
        } else {
            storeData(rx, r, size);
        }
    }

    private void cmpm(int op) {
        int size = sizeField(op);
        int rx = (op >> 9) & 7;
        int ry = op & 7;
        int src = readSized(a[ry] & 0xFFFFFF, size);
        a[ry] = (a[ry] + operandStep(ry, size)) & 0xFFFFFFFF;
        int dst = readSized(a[rx] & 0xFFFFFF, size);
        a[rx] = (a[rx] + operandStep(rx, size)) & 0xFFFFFFFF;
        compare(dst, src, size);
    }

    private int readSized(int addr, int size) {
        return switch (size) {
            case BYTE -> bus.read8(addr);
            case WORD -> bus.read16(addr);
            default -> bus.read32(addr);
        };
    }

    private void writeSized(int addr, int v, int size) {
        switch (size) {
            case BYTE -> bus.write8(addr, v);
            case WORD -> bus.write16(addr, v);
            default -> bus.write32(addr, v);
        }
    }

    private void swap(int op) {
        int reg = op & 7;
        int v = d[reg];
        d[reg] = ((v >>> 16) & 0xFFFF) | (v << 16);
        setLogicFlags(d[reg], LONG);
    }

    private void ext(int op) {
        int reg = op & 7;
        if ((op & 0x40) == 0) { // byte -> word
            d[reg] = (d[reg] & 0xFFFF0000) | (signExtend(d[reg] & 0xFF, BYTE) & 0xFFFF);
            setLogicFlags(d[reg] & 0xFFFF, WORD);
        } else { // word -> long
            d[reg] = signExtend(d[reg] & 0xFFFF, WORD);
            setLogicFlags(d[reg], LONG);
        }
    }

    private void movem(int op) {
        int size = (op & 0x40) != 0 ? LONG : WORD;
        int dir = (op >> 10) & 1; // 0 reg->mem, 1 mem->reg
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        int list = fetch16();
        int bytes = size == LONG ? 4 : 2;

        if (dir == 0 && mode == 4) { // -(An): registers stored in reverse order
            int addr = a[reg];
            for (int i = 0; i < 16; i++) {
                if ((list & (1 << i)) != 0) {
                    int value = (i < 8) ? a[7 - i] : d[7 - (i - 8)];
                    addr -= bytes;
                    if (size == LONG) bus.write32(addr, value); else bus.write16(addr, value);
                }
            }
            a[reg] = addr;
            return;
        }

        int addr = decode(mode, reg, size).address();
        for (int i = 0; i < 16; i++) {
            if ((list & (1 << i)) != 0) {
                if (dir == 0) { // reg -> mem
                    int value = (i < 8) ? d[i] : a[i - 8];
                    if (size == LONG) bus.write32(addr, value); else bus.write16(addr, value);
                } else { // mem -> reg
                    int value = size == LONG ? bus.read32(addr)
                            : signExtend(bus.read16(addr), WORD);
                    if (i < 8) d[i] = value; else setA(i - 8, value);
                }
                addr += bytes;
            }
        }
        if (dir == 1 && mode == 3) { // (An)+ writeback
            a[reg] = addr;
        }
    }

    private void moveUsp(int op) {
        if (!supervisor) { privilegeViolation(); return; }
        int reg = op & 7;
        if ((op & 0x08) != 0) { // USP -> An
            setA(reg, usp);
        } else { // An -> USP
            usp = a[reg];
        }
    }

    // ======================================================================
    //  $5xxx — ADDQ / SUBQ / Scc / DBcc
    // ======================================================================

    private void groupAddqSubqSccDbcc(int op) {
        if (((op >> 6) & 3) == 3) { // Scc / DBcc
            int mode = (op >> 3) & 7;
            int reg = op & 7;
            int cc = (op >> 8) & 0xF;
            if (mode == 1) { // DBcc
                dbcc(cc, reg);
            } else {
                scc(cc, mode, reg);
            }
            return;
        }
        int size = sizeField(op);
        int data = (op >> 9) & 7;
        if (data == 0) data = 8;
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        boolean sub = (op & 0x0100) != 0;
        if (mode == 1) { // ADDQ/SUBQ to An — full 32-bit, no flags
            setA(reg, a[reg] + (sub ? -data : data));
            return;
        }
        Ea ea = decode(mode, reg, size);
        int v = ea.read();
        int r;
        if (sub) {
            r = subValueRaw(v, data) & mask(size);
            setSubFlags(v, data, r, size);
        } else {
            r = addValueRaw(v, data) & mask(size);
            setAddFlags(v, data, r, size);
        }
        flagX = flagC;
        ea.write(r);
    }

    private void scc(int cc, int mode, int reg) {
        Ea ea = decode(mode, reg, BYTE);
        ea.write(testCondition(cc) ? 0xFF : 0x00);
    }

    private void dbcc(int cc, int reg) {
        int disp = signExtend(fetch16(), WORD);
        if (!testCondition(cc)) {
            int counter = (d[reg] - 1) & 0xFFFF;
            d[reg] = (d[reg] & 0xFFFF0000) | counter;
            if (counter != 0xFFFF) {
                pc = (pc - 2 + disp) & 0xFFFFFF;
            }
        }
    }

    // ======================================================================
    //  $6xxx — Bcc / BRA / BSR
    // ======================================================================

    private void groupBranch(int op) {
        int cc = (op >> 8) & 0xF;
        int disp = signExtend(op & 0xFF, BYTE);
        int base = pc;
        if ((op & 0xFF) == 0) {
            disp = signExtend(fetch16(), WORD);
        } else if ((op & 0xFF) == 0xFF) {
            disp = fetch32();
        }
        if (cc == 1) { // BSR (condition field 0001)
            push32(pc);
            pc = (base + disp) & 0xFFFFFF;
            return;
        }
        if (cc == 0 || testCondition(cc)) { // BRA (0000) or taken Bcc
            pc = (base + disp) & 0xFFFFFF;
        }
    }

    // ======================================================================
    //  $8xxx — OR / DIV / SBCD
    // ======================================================================

    private void groupOrDiv(int op) {
        if ((op & 0xF1F0) == 0x8100) { bcdOp(op, true); return; } // SBCD
        int opmode = (op >> 6) & 7;
        int dreg = (op >> 9) & 7;
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        if (opmode == 3) { divu(dreg, mode, reg); return; }
        if (opmode == 7) { divs(dreg, mode, reg); return; }
        int size = opmode & 3;
        Ea ea = decode(mode, reg, size);
        if ((op & 0x0100) == 0) { // <ea> | Dn -> Dn
            int r = (d[dreg] | ea.read()) & mask(size);
            storeData(dreg, r, size);
            setLogicFlags(r, size);
        } else { // Dn | <ea> -> <ea>
            int r = (ea.read() | d[dreg]) & mask(size);
            ea.write(r);
            setLogicFlags(r, size);
        }
    }

    // ======================================================================
    //  $9xxx — SUB / SUBA / SUBX
    // ======================================================================

    private void groupSub(int op) {
        if ((op & 0xF130) == 0x9100) { addxSubx(op, true); return; } // SUBX
        int opmode = (op >> 6) & 7;
        int dreg = (op >> 9) & 7;
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        if (opmode == 3 || opmode == 7) { // SUBA.w / SUBA.l
            int size = opmode == 3 ? WORD : LONG;
            int v = decode(mode, reg, size).read();
            if (size == WORD) v = signExtend(v, WORD);
            setA(dreg, a[dreg] - v);
            return;
        }
        int size = opmode & 3;
        Ea ea = decode(mode, reg, size);
        if ((op & 0x0100) == 0) { // Dn - <ea> -> Dn
            int v = ea.read();
            int r = subValueRaw(d[dreg], v) & mask(size);
            setSubFlags(d[dreg], v, r, size);
            flagX = flagC;
            storeData(dreg, r, size);
        } else {
            int v = ea.read();
            int r = subValueRaw(v, d[dreg]) & mask(size);
            setSubFlags(v, d[dreg], r, size);
            flagX = flagC;
            ea.write(r);
        }
    }

    // ======================================================================
    //  $Bxxx — CMP / CMPA / EOR
    // ======================================================================

    private void groupCmpEor(int op) {
        if ((op & 0xF138) == 0xB108) { cmpm(op); return; } // CMPM (Ay)+,(Ax)+
        int opmode = (op >> 6) & 7;
        int dreg = (op >> 9) & 7;
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        if (opmode == 3 || opmode == 7) { // CMPA
            int size = opmode == 3 ? WORD : LONG;
            int v = decode(mode, reg, size).read();
            if (size == WORD) v = signExtend(v, WORD);
            compare(a[dreg], v, LONG);
            return;
        }
        int size = opmode & 3;
        if ((op & 0x0100) == 0) { // CMP
            int v = decode(mode, reg, size).read();
            compare(d[dreg], v, size);
        } else { // EOR
            Ea ea = decode(mode, reg, size);
            int r = (ea.read() ^ d[dreg]) & mask(size);
            ea.write(r);
            setLogicFlags(r, size);
        }
    }

    // ======================================================================
    //  $Cxxx — AND / MUL / ABCD / EXG
    // ======================================================================

    private void groupAndMul(int op) {
        if ((op & 0xF1F0) == 0xC100) { bcdOp(op, false); return; } // ABCD
        int opmode = (op >> 6) & 7;
        int dreg = (op >> 9) & 7;
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        if (opmode == 3) { mulu(dreg, mode, reg); return; }
        if (opmode == 7) { muls(dreg, mode, reg); return; }
        if (((op & 0x0130) == 0x0100) && (mode == 0 || mode == 1)) { // EXG
            exg(op);
            return;
        }
        int size = opmode & 3;
        Ea ea = decode(mode, reg, size);
        if ((op & 0x0100) == 0) { // <ea> & Dn -> Dn
            int r = (d[dreg] & ea.read()) & mask(size);
            storeData(dreg, r, size);
            setLogicFlags(r, size);
        } else { // Dn & <ea> -> <ea>
            int r = (ea.read() & d[dreg]) & mask(size);
            ea.write(r);
            setLogicFlags(r, size);
        }
    }

    private void exg(int op) {
        int rx = (op >> 9) & 7;
        int ry = op & 7;
        switch (op & 0xF8) {
            case 0x40 -> { int t = d[rx]; d[rx] = d[ry]; d[ry] = t; }       // D,D
            case 0x48 -> { int t = a[rx]; a[rx] = a[ry]; a[ry] = t; }       // A,A
            case 0x88 -> { int t = d[rx]; d[rx] = a[ry]; a[ry] = t; }       // D,A
            default -> illegal(op);
        }
    }

    // ======================================================================
    //  $Dxxx — ADD / ADDA / ADDX
    // ======================================================================

    private void groupAdd(int op) {
        if ((op & 0xF130) == 0xD100) { addxSubx(op, false); return; } // ADDX
        int opmode = (op >> 6) & 7;
        int dreg = (op >> 9) & 7;
        int mode = (op >> 3) & 7;
        int reg = op & 7;
        if (opmode == 3 || opmode == 7) { // ADDA
            int size = opmode == 3 ? WORD : LONG;
            int v = decode(mode, reg, size).read();
            if (size == WORD) v = signExtend(v, WORD);
            setA(dreg, a[dreg] + v);
            return;
        }
        int size = opmode & 3;
        Ea ea = decode(mode, reg, size);
        if ((op & 0x0100) == 0) { // Dn + <ea> -> Dn
            int v = ea.read();
            int r = addValueRaw(d[dreg], v) & mask(size);
            setAddFlags(d[dreg], v, r, size);
            flagX = flagC;
            storeData(dreg, r, size);
        } else {
            int v = ea.read();
            int r = addValueRaw(v, d[dreg]) & mask(size);
            setAddFlags(v, d[dreg], r, size);
            flagX = flagC;
            ea.write(r);
        }
    }

    // ======================================================================
    //  $Exxx — shifts / rotates
    // ======================================================================

    private void groupShift(int op) {
        if (((op >> 6) & 3) == 3) { // memory shift by 1
            int type = (op >> 9) & 7;
            boolean left = (op & 0x0100) != 0;
            Ea ea = decode((op >> 3) & 7, op & 7, WORD);
            int v = ea.read();
            ea.write(doShift(type, left, v, 1, WORD));
            return;
        }
        int size = sizeField(op);
        int count;
        if ((op & 0x20) != 0) { // count in Dn
            count = d[(op >> 9) & 7] & 63;
        } else {
            count = (op >> 9) & 7;
            if (count == 0) count = 8;
        }
        int type = (op >> 3) & 3;
        boolean left = (op & 0x0100) != 0;
        int reg = op & 7;
        int v = d[reg] & mask(size);
        int r = doShift(type, left, v, count, size);
        storeData(reg, r, size);
    }

    private int doShift(int type, boolean left, int v, int count, int size) {
        int bits = size == BYTE ? 8 : size == WORD ? 16 : 32;
        v &= mask(size);
        boolean lastC = flagC;
        flagV = false;
        for (int i = 0; i < count; i++) {
            switch (type) {
                case 0 -> { // ASL/ASR
                    if (left) {
                        lastC = (v & (1 << (bits - 1))) != 0;
                        int before = v;
                        v = (v << 1) & mask(size);
                        if (((before ^ v) & (1 << (bits - 1))) != 0) flagV = true;
                    } else {
                        lastC = (v & 1) != 0;
                        int sign = v & (1 << (bits - 1));
                        v = (v >>> 1) | sign;
                    }
                }
                case 1 -> { // LSL/LSR
                    if (left) {
                        lastC = (v & (1 << (bits - 1))) != 0;
                        v = (v << 1) & mask(size);
                    } else {
                        lastC = (v & 1) != 0;
                        v = v >>> 1;
                    }
                }
                case 3 -> { // ROL/ROR
                    if (left) {
                        lastC = (v & (1 << (bits - 1))) != 0;
                        v = ((v << 1) | (lastC ? 1 : 0)) & mask(size);
                    } else {
                        lastC = (v & 1) != 0;
                        v = (v >>> 1) | (lastC ? (1 << (bits - 1)) : 0);
                    }
                }
                case 2 -> { // ROXL/ROXR (through X)
                    if (left) {
                        boolean hi = (v & (1 << (bits - 1))) != 0;
                        v = ((v << 1) | (flagX ? 1 : 0)) & mask(size);
                        flagX = hi; lastC = hi;
                    } else {
                        boolean lo = (v & 1) != 0;
                        v = (v >>> 1) | (flagX ? (1 << (bits - 1)) : 0);
                        flagX = lo; lastC = lo;
                    }
                }
                default -> { }
            }
        }
        if (count > 0) {
            flagC = lastC;
            if (type != 2) flagX = lastC;
        } else {
            flagC = false;
        }
        flagN = (v & (1 << (bits - 1))) != 0;
        flagZ = v == 0;
        return v;
    }

    // ======================================================================
    //  Multiply / divide
    // ======================================================================

    private void mulu(int dreg, int mode, int reg) {
        int v = decode(mode, reg, WORD).read() & 0xFFFF;
        int r = (d[dreg] & 0xFFFF) * v;
        d[dreg] = r;
        setLogicFlags(r, LONG);
    }

    private void muls(int dreg, int mode, int reg) {
        int v = signExtend(decode(mode, reg, WORD).read() & 0xFFFF, WORD);
        int r = signExtend(d[dreg] & 0xFFFF, WORD) * v;
        d[dreg] = r;
        setLogicFlags(r, LONG);
    }

    private void divu(int dreg, int mode, int reg) {
        int divisor = decode(mode, reg, WORD).read() & 0xFFFF;
        if (divisor == 0) { exception(5); return; }
        long dividend = d[dreg] & 0xFFFFFFFFL;
        long q = dividend / divisor;
        long rem = dividend % divisor;
        if (q > 0xFFFF) { flagV = true; return; }
        d[dreg] = (int) ((rem << 16) | (q & 0xFFFF));
        flagV = false;
        flagN = (q & 0x8000) != 0;
        flagZ = q == 0;
        flagC = false;
    }

    private void divs(int dreg, int mode, int reg) {
        int divisor = signExtend(decode(mode, reg, WORD).read() & 0xFFFF, WORD);
        if (divisor == 0) { exception(5); return; }
        int dividend = d[dreg];
        int q = dividend / divisor;
        int rem = dividend % divisor;
        if (q > 32767 || q < -32768) { flagV = true; return; }
        d[dreg] = ((rem & 0xFFFF) << 16) | (q & 0xFFFF);
        flagV = false;
        flagN = (q & 0x8000) != 0;
        flagZ = q == 0;
        flagC = false;
    }

    // ======================================================================
    //  Control flow returns
    // ======================================================================

    private void rts() {
        pc = pop32() & 0xFFFFFF;
    }

    private void rtr() {
        unpackCcr(pop16());
        pc = pop32() & 0xFFFFFF;
    }

    private void rte() {
        if (!supervisor) { privilegeViolation(); return; }
        int sr = pop16();
        pc = pop32() & 0xFFFFFF;
        unpackSr(sr);
    }

    private void trap(int vector) {
        exception(32 + vector);
    }

    private void trapv() {
        if (flagV) exception(7);
    }

    // ======================================================================
    //  Exceptions
    // ======================================================================

    // Debug instrumentation for diagnosing unimplemented opcodes.
    public long exceptionCount;
    public int lastIllegalOpcode;
    public int lastIllegalPc;

    private void illegal(int op) {
        lastIllegalOpcode = op;
        lastIllegalPc = (pc - 2) & 0xFFFFFF;
        exception(4);
    }

    private void privilegeViolation() {
        exception(8);
    }

    private void exception(int vector) {
        exceptionCount++;
        int sr = packSr();
        enterSupervisor();
        push32(pc);
        push16(sr);
        pc = bus.read32(vector * 4) & 0xFFFFFF;
    }

    private void enterSupervisor() {
        if (!supervisor) {
            usp = a[7];
            a[7] = ssp;
            supervisor = true;
        }
    }

    // ======================================================================
    //  Effective-address decoding
    // ======================================================================

    /** A resolved operand: a register, an immediate, or a memory location. */
    private final class Ea {
        final int kind;        // 0 Dn, 1 An, 2 memory, 3 immediate
        final int index;       // register number (for Dn/An)
        final int addr;        // resolved address (for memory)
        final int imm;         // immediate value
        final int size;

        Ea(int kind, int index, int addr, int imm, int size) {
            this.kind = kind;
            this.index = index;
            this.addr = addr;
            this.imm = imm;
            this.size = size;
        }

        int read() {
            return switch (kind) {
                case 0 -> d[index] & mask(size);
                case 1 -> a[index] & mask(size);
                case 3 -> imm & mask(size);
                default -> switch (size) {
                    case BYTE -> bus.read8(addr);
                    case WORD -> bus.read16(addr);
                    default -> bus.read32(addr);
                };
            };
        }

        void write(int value) {
            value &= mask(size);
            switch (kind) {
                case 0 -> storeData(index, value, size);
                case 1 -> setA(index, value);
                case 2 -> {
                    switch (size) {
                        case BYTE -> bus.write8(addr, value);
                        case WORD -> bus.write16(addr, value);
                        default -> bus.write32(addr, value);
                    }
                }
                default -> { /* immediates are not writable */ }
            }
        }

        int address() {
            return addr;
        }
    }

    private Ea decode(int mode, int reg, int size) {
        switch (mode) {
            case 0: return new Ea(0, reg, 0, 0, size);                 // Dn
            case 1: return new Ea(1, reg, 0, 0, size);                 // An
            case 2: return new Ea(2, 0, a[reg] & 0xFFFFFF, 0, size);   // (An)
            case 3: {                                                   // (An)+
                int addr = a[reg] & 0xFFFFFF;
                a[reg] = (a[reg] + operandStep(reg, size)) & 0xFFFFFFFF;
                return new Ea(2, 0, addr, 0, size);
            }
            case 4: {                                                   // -(An)
                a[reg] = (a[reg] - operandStep(reg, size)) & 0xFFFFFFFF;
                return new Ea(2, 0, a[reg] & 0xFFFFFF, 0, size);
            }
            case 5: {                                                   // (d16,An)
                int disp = signExtend(fetch16(), WORD);
                return new Ea(2, 0, (a[reg] + disp) & 0xFFFFFF, 0, size);
            }
            case 6:                                                     // (d8,An,Xn)
                return new Ea(2, 0, indexed(a[reg]), 0, size);
            case 7:
                switch (reg) {
                    case 0: return new Ea(2, 0, signExtend(fetch16(), WORD) & 0xFFFFFF, 0, size); // (xxx).W
                    case 1: return new Ea(2, 0, fetch32() & 0xFFFFFF, 0, size);                   // (xxx).L
                    case 2: {                                                                      // (d16,PC)
                        int base = pc;
                        int disp = signExtend(fetch16(), WORD);
                        return new Ea(2, 0, (base + disp) & 0xFFFFFF, 0, size);
                    }
                    case 3: {                                                                      // (d8,PC,Xn)
                        int base = pc;
                        return new Ea(2, 0, indexed(base), 0, size);
                    }
                    case 4: return new Ea(3, 0, 0, fetchImmediate(size), size);                   // #imm
                    default: illegal(0); return new Ea(3, 0, 0, 0, size);
                }
            default:
                illegal(0);
                return new Ea(3, 0, 0, 0, size);
        }
    }

    /** Resolve a brief-extension-word indexed address (d8,base,Xn). */
    private int indexed(int base) {
        int ext = fetch16();
        int disp = signExtend(ext & 0xFF, BYTE);
        int xn = (ext >> 12) & 7;
        boolean addrReg = (ext & 0x8000) != 0;
        boolean longIndex = (ext & 0x0800) != 0;
        int idx = addrReg ? a[xn] : d[xn];
        if (!longIndex) {
            idx = signExtend(idx & 0xFFFF, WORD);
        }
        return (base + disp + idx) & 0xFFFFFF;
    }

    private int operandStep(int reg, int size) {
        if (size == BYTE) {
            return reg == 7 ? 2 : 1; // keep the stack pointer word-aligned
        }
        return size == WORD ? 2 : 4;
    }

    // ======================================================================
    //  Arithmetic helpers and flags
    // ======================================================================

    private int addValueRaw(int a, int b) { return a + b; }
    private int subValueRaw(int a, int b) { return a - b; }

    private void compare(int a, int b, int size) {
        int r = (a - b) & mask(size);
        setSubFlags(a, b, r, size);
    }

    private void setAddFlags(int a, int b, int r, int size) {
        int m = signBit(size);
        flagN = (r & m) != 0;
        flagZ = (r & mask(size)) == 0;
        boolean sa = (a & m) != 0, sb = (b & m) != 0, sr = (r & m) != 0;
        flagV = (sa == sb) && (sr != sa);
        flagC = ((a & mask(size)) + (b & mask(size))) > mask(size);
    }

    private void setSubFlags(int a, int b, int r, int size) {
        int m = signBit(size);
        flagN = (r & m) != 0;
        flagZ = (r & mask(size)) == 0;
        boolean sa = (a & m) != 0, sb = (b & m) != 0, sr = (r & m) != 0;
        flagV = (sa != sb) && (sr != sa);
        flagC = (a & mask(size)) < (b & mask(size));
    }

    private void setLogicFlags(int r, int size) {
        flagN = (r & signBit(size)) != 0;
        flagZ = (r & mask(size)) == 0;
        flagV = false;
        flagC = false;
    }

    private boolean testCondition(int cc) {
        return switch (cc) {
            case 0x0 -> true;                       // T
            case 0x1 -> false;                      // F
            case 0x2 -> !flagC && !flagZ;           // HI
            case 0x3 -> flagC || flagZ;             // LS
            case 0x4 -> !flagC;                     // CC/HS
            case 0x5 -> flagC;                      // CS/LO
            case 0x6 -> !flagZ;                     // NE
            case 0x7 -> flagZ;                      // EQ
            case 0x8 -> !flagV;                     // VC
            case 0x9 -> flagV;                      // VS
            case 0xA -> !flagN;                     // PL
            case 0xB -> flagN;                      // MI
            case 0xC -> flagN == flagV;             // GE
            case 0xD -> flagN != flagV;             // LT
            case 0xE -> (flagN == flagV) && !flagZ; // GT
            default -> (flagN != flagV) || flagZ;   // LE
        };
    }

    // ======================================================================
    //  Fetch / stack / register helpers
    // ======================================================================

    private int fetch16() {
        int v = bus.read16(pc);
        pc = (pc + 2) & 0xFFFFFF;
        return v & 0xFFFF;
    }

    private int fetch32() {
        int v = bus.read32(pc);
        pc = (pc + 4) & 0xFFFFFF;
        return v;
    }

    private int fetchImmediate(int size) {
        return switch (size) {
            case BYTE -> fetch16() & 0xFF;      // byte immediate occupies a word
            case WORD -> fetch16();
            default -> fetch32();
        };
    }

    private void push32(int value) {
        a[7] = (a[7] - 4) & 0xFFFFFFFF;
        bus.write32(a[7] & 0xFFFFFF, value);
    }

    private void push16(int value) {
        a[7] = (a[7] - 2) & 0xFFFFFFFF;
        bus.write16(a[7] & 0xFFFFFF, value);
    }

    private int pop32() {
        int v = bus.read32(a[7] & 0xFFFFFF);
        a[7] = (a[7] + 4) & 0xFFFFFFFF;
        return v;
    }

    private int pop16() {
        int v = bus.read16(a[7] & 0xFFFFFF);
        a[7] = (a[7] + 2) & 0xFFFFFFFF;
        return v;
    }

    private void storeData(int reg, int value, int size) {
        switch (size) {
            case BYTE -> d[reg] = (d[reg] & 0xFFFFFF00) | (value & 0xFF);
            case WORD -> d[reg] = (d[reg] & 0xFFFF0000) | (value & 0xFFFF);
            default -> d[reg] = value;
        }
    }

    private void setA(int reg, int value) {
        a[reg] = value; // address registers are always full 32-bit
    }

    // ======================================================================
    //  Status-register packing
    // ======================================================================

    private int packCcr() {
        return (flagX ? 0x10 : 0) | (flagN ? 0x08 : 0) | (flagZ ? 0x04 : 0)
                | (flagV ? 0x02 : 0) | (flagC ? 0x01 : 0);
    }

    private void unpackCcr(int v) {
        flagX = (v & 0x10) != 0;
        flagN = (v & 0x08) != 0;
        flagZ = (v & 0x04) != 0;
        flagV = (v & 0x02) != 0;
        flagC = (v & 0x01) != 0;
    }

    private int packSr() {
        int sr = packCcr();
        sr |= (interruptMask & 7) << 8;
        if (supervisor) sr |= 0x2000;
        return sr;
    }

    private void unpackSr(int v) {
        unpackCcr(v & 0xFF);
        interruptMask = (v >> 8) & 7;
        boolean wantSupervisor = (v & 0x2000) != 0;
        if (wantSupervisor != supervisor) {
            if (wantSupervisor) {
                usp = a[7];
                a[7] = ssp;
            } else {
                ssp = a[7];
                a[7] = usp;
            }
            supervisor = wantSupervisor;
        }
    }

    // ======================================================================
    //  Small numeric helpers
    // ======================================================================

    private static int sizeField(int op) {
        return (op >> 6) & 3;
    }

    private static int mask(int size) {
        return switch (size) {
            case BYTE -> 0xFF;
            case WORD -> 0xFFFF;
            default -> 0xFFFFFFFF;
        };
    }

    private static int signBit(int size) {
        return switch (size) {
            case BYTE -> 0x80;
            case WORD -> 0x8000;
            default -> 0x80000000;
        };
    }

    private static int signExtend(int value, int size) {
        return switch (size) {
            case BYTE -> (byte) value;
            case WORD -> (short) value;
            default -> value;
        };
    }

    // ======================================================================
    //  Test / debug accessors
    // ======================================================================

    public int getD(int i) { return d[i]; }
    public int getA(int i) { return a[i]; }
    public int getPc() { return pc; }
    public int getSr() { return packSr(); }
    public boolean isSupervisor() { return supervisor; }
    public boolean isStopped() { return stopped; }

    public void pokeD(int i, int v) { d[i] = v; }
    public void pokeA(int i, int v) { a[i] = v; }
    public void setPc(int v) { pc = v & 0xFFFFFF; }
    public void setSrFlags(boolean x, boolean n, boolean z, boolean v, boolean c) {
        flagX = x; flagN = n; flagZ = z; flagV = v; flagC = c;
    }
    public boolean cc() { return flagC; }
    public boolean zz() { return flagZ; }
    public boolean nn() { return flagN; }
    public boolean vv() { return flagV; }
    public boolean xx() { return flagX; }

    // ---- save state -------------------------------------------------------

    public void saveState(DataOutputStream o) throws IOException {
        for (int v : d) o.writeInt(v);
        for (int v : a) o.writeInt(v);
        o.writeInt(pc);
        o.writeInt(usp);
        o.writeInt(ssp);
        o.writeBoolean(flagC);
        o.writeBoolean(flagV);
        o.writeBoolean(flagZ);
        o.writeBoolean(flagN);
        o.writeBoolean(flagX);
        o.writeBoolean(supervisor);
        o.writeInt(interruptMask);
        o.writeBoolean(stopped);
    }

    public void loadState(DataInputStream in) throws IOException {
        for (int i = 0; i < 8; i++) d[i] = in.readInt();
        for (int i = 0; i < 8; i++) a[i] = in.readInt();
        pc = in.readInt();
        usp = in.readInt();
        ssp = in.readInt();
        flagC = in.readBoolean();
        flagV = in.readBoolean();
        flagZ = in.readBoolean();
        flagN = in.readBoolean();
        flagX = in.readBoolean();
        supervisor = in.readBoolean();
        interruptMask = in.readInt();
        stopped = in.readBoolean();
    }
}
