package com.segaemu.cpu;

import com.segaemu.bus.Bus68000;

/** A flat 16 MB big-endian memory, used to exercise the 68000 core in isolation. */
final class TestBus implements Bus68000 {
    final byte[] mem = new byte[0x1000000];

    @Override
    public int read8(int addr) {
        return mem[addr & 0xFFFFFF] & 0xFF;
    }

    @Override
    public int read16(int addr) {
        return (read8(addr) << 8) | read8(addr + 1);
    }

    @Override
    public int read32(int addr) {
        return (read16(addr) << 16) | (read16(addr + 2) & 0xFFFF);
    }

    @Override
    public void write8(int addr, int value) {
        mem[addr & 0xFFFFFF] = (byte) value;
    }

    @Override
    public void write16(int addr, int value) {
        write8(addr, value >> 8);
        write8(addr + 1, value & 0xFF);
    }

    @Override
    public void write32(int addr, int value) {
        write16(addr, value >>> 16);
        write16(addr + 2, value & 0xFFFF);
    }

    /** Write a sequence of 16-bit words starting at {@code addr} (program loader). */
    void poke(int addr, int... words) {
        for (int w : words) {
            write16(addr, w);
            addr += 2;
        }
    }
}
