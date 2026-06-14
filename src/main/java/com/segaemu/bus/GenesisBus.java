package com.segaemu.bus;

import com.segaemu.cartridge.Rom;
import com.segaemu.io.Controller;
import com.segaemu.sound.Sn76489;
import com.segaemu.sound.Ym2612;
import com.segaemu.vdp.Vdp;
import com.segaemu.z80.Z80Bus;

/**
 * The Mega Drive 68000 address space. All accesses are big-endian.
 *
 * <pre>
 *   $000000–$3FFFFF  Cartridge ROM (up to 4 MB)
 *   $A00000–$A0FFFF  Z80 address space (RAM + sound chips), as seen by the 68000
 *   $A10000–$A1001F  I/O area: version register + controller data/control ports
 *   $A11100          Z80 bus-request latch
 *   $A11200          Z80 reset latch
 *   $C00000–$C00003  VDP data port (word)
 *   $C00004–$C00007  VDP control port (word)
 *   $C00008–$C0000F  VDP HV counter
 *   $FF0000–$FFFFFF  68000 work RAM (64 KB, mirrored through the region)
 * </pre>
 *
 * <p>Word/long helpers are built on the byte path so mirroring and region
 * decoding only have to be expressed once.
 */
public final class GenesisBus implements Bus68000, Z80Bus {

    private final Rom rom;
    private final Vdp vdp;
    private final Controller pad1;
    private final Controller pad2;
    private final Ym2612 ym2612;
    private final Sn76489 psg;

    private final byte[] workRam = new byte[0x10000];   // $FF0000–$FFFFFF
    private final byte[] z80Ram = new byte[0x2000];      // $A00000–$A01FFF

    /** True while the 68000 holds the Z80 bus (Z80 halted). Reset asserted = true. */
    private boolean z80BusRequested = true;
    private boolean z80Reset = true;

    /** 9-bit bank register selecting the Z80's $8000–$FFFF 68000 window. */
    private int z80Bank = 0;

    public GenesisBus(Rom rom, Vdp vdp, Controller pad1, Controller pad2,
                      Ym2612 ym2612, Sn76489 psg) {
        this.rom = rom;
        this.vdp = vdp;
        this.pad1 = pad1;
        this.pad2 = pad2;
        this.ym2612 = ym2612;
        this.psg = psg;
    }

    // ---- byte access (the routing happens here) ---------------------------

    @Override
    public int read8(int addr) {
        addr &= 0xFFFFFF;
        if (addr < 0x400000) {
            return rom.read8(addr);
        }
        if (addr >= 0xA04000 && addr <= 0xA04003) {
            // YM2612 ports. Reading returns the status byte; our stub is never
            // busy (bit 7 = 0), so the common BUSY-wait loops fall through.
            return ym2612.readStatus();
        }
        if (addr >= 0xA00000 && addr <= 0xA0FFFF) {
            return z80Read(addr & 0x1FFF);
        }
        if (addr >= 0xA10000 && addr <= 0xA1001F) {
            return ioRead(addr);
        }
        if (addr == 0xA11100 || addr == 0xA11101) {
            // Z80 bus-request status: bit 0 clear means the 68000 has the bus.
            return z80BusRequested ? 0x00 : 0x01;
        }
        if (addr >= 0xC00000 && addr <= 0xC0000F) {
            return vdpRead8(addr);
        }
        if (addr >= 0xFF0000) {
            return workRam[addr & 0xFFFF] & 0xFF;
        }
        return 0xFF; // unmapped
    }

    @Override
    public void write8(int addr, int value) {
        addr &= 0xFFFFFF;
        value &= 0xFF;
        if (addr >= 0xFF0000) {
            workRam[addr & 0xFFFF] = (byte) value;
            return;
        }
        if (addr >= 0xA04000 && addr <= 0xA04003) {
            ym2612.writePort(addr - 0xA04000, value);
            return;
        }
        if (addr >= 0xA00000 && addr <= 0xA0FFFF) {
            z80Write(addr & 0x1FFF, value);
            return;
        }
        if (addr >= 0xA10000 && addr <= 0xA1001F) {
            ioWrite(addr, value);
            return;
        }
        if (addr == 0xA11100 || addr == 0xA11101) {
            // A word write of $0100 lands as $01 in the even byte ($A11100) and
            // $00 in the odd byte; only the even byte (bit 0) drives BUSREQ, so
            // the odd-byte write must be ignored or it clears the request.
            if (addr == 0xA11100) {
                z80BusRequested = (value & 0x01) != 0;
            }
            return;
        }
        if (addr == 0xA11200 || addr == 0xA11201) {
            if (addr == 0xA11200) {
                z80Reset = (value & 0x01) == 0; // writing 0 asserts reset
            }
            return;
        }
        if (addr >= 0xC00000 && addr <= 0xC0000F) {
            vdpWrite8(addr, value);
            return;
        }
        // ROM and unmapped writes are ignored.
    }

    // ---- word / long access (composed from bytes) -------------------------

    @Override
    public int read16(int addr) {
        // The VDP ports are genuinely 16-bit; fast-path them so a word read is
        // one port access rather than two byte accesses with side effects.
        addr &= 0xFFFFFF;
        if (addr >= 0xC00000 && addr <= 0xC00007) {
            return (addr & 0x4) == 0 ? vdp.readData() : vdp.readControl();
        }
        return (read8(addr) << 8) | read8(addr + 1);
    }

    @Override
    public int read32(int addr) {
        return (read16(addr) << 16) | (read16(addr + 2) & 0xFFFF);
    }

    @Override
    public void write16(int addr, int value) {
        addr &= 0xFFFFFF;
        value &= 0xFFFF;
        if (addr >= 0xC00000 && addr <= 0xC00007) {
            if ((addr & 0x4) == 0) {
                vdp.writeData(value);
            } else {
                vdp.writeControl(value);
                runPendingDma();
            }
            return;
        }
        write8(addr, value >> 8);
        write8(addr + 1, value & 0xFF);
    }

    @Override
    public void write32(int addr, int value) {
        write16(addr, value >>> 16);
        write16(addr + 2, value & 0xFFFF);
    }

    // ---- sub-system routing ----------------------------------------------

    private int z80Read(int addr) {
        return z80Ram[addr & 0x1FFF] & 0xFF;
    }

    private void z80Write(int addr, int value) {
        z80Ram[addr & 0x1FFF] = (byte) value;
    }

    // ---- Z80 address space (Z80Bus) --------------------------------------
    //
    //   $0000–$1FFF  8 KB sound RAM   ($2000–$3FFF mirrors it)
    //   $4000–$5FFF  YM2612 (low two address bits select the port)
    //   $6000–$60FF  bank register (one bit shifted in per write)
    //   $7F11        SN76489 PSG
    //   $8000–$FFFF  32 KB window into 68000 space at (bank << 15)

    @Override
    public int read(int addr) {
        addr &= 0xFFFF;
        if (addr < 0x4000) {
            return z80Ram[addr & 0x1FFF] & 0xFF;
        }
        if (addr < 0x6000) {
            return ym2612.readStatus();
        }
        if (addr >= 0x8000) {
            return read8(bankAddress(addr));
        }
        return 0xFF;
    }

    @Override
    public void write(int addr, int value) {
        addr &= 0xFFFF;
        value &= 0xFF;
        if (addr < 0x4000) {
            z80Ram[addr & 0x1FFF] = (byte) value;
            return;
        }
        if (addr < 0x6000) {
            ym2612.writePort(addr & 0x03, value);
            return;
        }
        if (addr >= 0x6000 && addr <= 0x60FF) {
            // Bank register: each write supplies one bit (LSB), filling from the
            // top, so nine writes build the 9-bit bank number.
            z80Bank = ((z80Bank >> 1) | ((value & 1) << 8)) & 0x1FF;
            return;
        }
        if (addr == 0x7F11) {
            psg.write(value);
            return;
        }
        if (addr >= 0x8000) {
            write8(bankAddress(addr), value);
        }
    }

    private int bankAddress(int z80Addr) {
        return ((z80Bank << 15) | (z80Addr & 0x7FFF)) & 0xFFFFFF;
    }

    private int ioRead(int addr) {
        return switch (addr & 0x1F) {
            // $A10000/01 — version register. Bit 7: 0 = domestic (JP), 1 = export.
            // Bit 6: 0 = NTSC, 1 = PAL. Low nibble: hardware version.
            case 0x00, 0x01 -> 0xA0; // export, NTSC, version 0
            case 0x02, 0x03 -> pad1.readData();
            case 0x04, 0x05 -> pad2.readData();
            default -> 0x00;
        };
    }

    private void ioWrite(int addr, int value) {
        switch (addr & 0x1F) {
            case 0x02, 0x03 -> pad1.writeData(value);
            case 0x04, 0x05 -> pad2.writeData(value);
            case 0x08, 0x09 -> pad1.writeControl(value);
            case 0x0A, 0x0B -> pad2.writeControl(value);
            default -> { /* timing / TH-INT registers: not modelled */ }
        }
    }

    private int vdpRead8(int addr) {
        int word = switch (addr & 0xE) {
            case 0x0, 0x2 -> vdp.readData();
            case 0x4, 0x6 -> vdp.readControl();
            case 0x8, 0xA -> vdp.readHvCounter();
            default -> 0;
        };
        return (addr & 1) == 0 ? (word >> 8) & 0xFF : word & 0xFF;
    }

    private void vdpWrite8(int addr, int value) {
        // Byte writes to the 16-bit VDP ports duplicate the byte into both halves,
        // matching how real hardware latches an 8-bit bus cycle.
        int word = (value << 8) | value;
        switch (addr & 0xE) {
            case 0x0, 0x2 -> vdp.writeData(word);
            case 0x4, 0x6 -> {
                vdp.writeControl(word);
                runPendingDma();
            }
            default -> { /* HV counter is read-only */ }
        }
    }

    /**
     * Execute a pending 68000-&gt;VDP DMA: the VDP cannot read the 68000 bus, so
     * we read each source word here and feed it through the data port, which
     * honours the destination (VRAM/CRAM/VSRAM) and the auto-increment.
     */
    private void runPendingDma() {
        if (!vdp.isDma68kPending()) {
            return;
        }
        int src = vdp.dmaSourceAddress();
        int len = vdp.dmaLengthWords();
        for (int i = 0; i < len; i++) {
            vdp.writeData(read16(src));
            src = (src + 2) & 0xFFFFFF;
        }
        vdp.clearDma68k();
    }

    public boolean isZ80Reset() {
        return z80Reset;
    }

    public boolean isZ80BusRequested() {
        return z80BusRequested;
    }

    /** Read a work-RAM byte directly, bypassing region decoding (for debugging). */
    public int peekWorkRam(int addr) {
        return workRam[addr & 0xFFFF] & 0xFF;
    }
}
