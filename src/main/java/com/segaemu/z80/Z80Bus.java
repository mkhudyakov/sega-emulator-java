package com.segaemu.z80;

/**
 * The address and I/O space seen by the Z80 sound co-processor. On the Mega Drive
 * this is the 8&nbsp;KB sound RAM, the YM2612 and SN76489 ports, the bank register,
 * and the $8000–$FFFF window into 68000 address space; the Z80's separate I/O
 * space (IN/OUT) is unused but modelled for completeness.
 */
public interface Z80Bus {

    /** Read a byte from the Z80 16-bit memory space. */
    int read(int addr);

    /** Write a byte to the Z80 16-bit memory space. */
    void write(int addr, int value);

    /** Read a byte from the Z80 I/O space (IN). */
    default int readPort(int port) {
        return 0xFF;
    }

    /** Write a byte to the Z80 I/O space (OUT). */
    default void writePort(int port, int value) {
        // The Mega Drive does not decode the Z80 I/O space.
    }
}
