package com.segaemu.bus;

/**
 * The interface the 68000 core uses to reach the rest of the machine. The
 * Mega Drive bus is big-endian and word-oriented; the CPU can read/write bytes,
 * words and longs. Implemented by {@link GenesisBus}, which routes addresses to
 * cartridge ROM, work RAM, the VDP, the Z80 sub-system and I/O.
 */
public interface Bus68000 {
    /** Read an unsigned byte (0-255) from a 24-bit address. */
    int read8(int addr);

    /** Read an unsigned 16-bit word (big-endian). */
    int read16(int addr);

    /** Read a 32-bit long (big-endian). */
    int read32(int addr);

    /** Write the low 8 bits of {@code value} to {@code addr}. */
    void write8(int addr, int value);

    /** Write the low 16 bits of {@code value} to {@code addr} (big-endian). */
    void write16(int addr, int value);

    /** Write 32 bits to {@code addr} (big-endian, high word first). */
    void write32(int addr, int value);
}
