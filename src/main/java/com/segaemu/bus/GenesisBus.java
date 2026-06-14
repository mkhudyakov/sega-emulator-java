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

    // Battery-backed save RAM (null when the cartridge has none).
    private final byte[] sram;
    private final int sramStart;
    private final int sramEnd;
    private boolean sramEnabled;
    private boolean sramWriteProtect;

    // SSF2 / SEGA mapper: eight 512 KB ROM banks over $000000–$3FFFFF.
    private final int[] romBank = {0, 1, 2, 3, 4, 5, 6, 7};

    public GenesisBus(Rom rom, Vdp vdp, Controller pad1, Controller pad2,
                      Ym2612 ym2612, Sn76489 psg) {
        this.rom = rom;
        this.vdp = vdp;
        this.pad1 = pad1;
        this.pad2 = pad2;
        this.ym2612 = ym2612;
        this.psg = psg;

        var hdr = rom.header();
        if (hdr.hasSram()) {
            this.sramStart = hdr.sramStart() & 0xFFFFFF;
            this.sramEnd = hdr.sramEnd() & 0xFFFFFF;
            int size = Math.min(sramEnd - sramStart + 1, 0x40000); // cap at 256 KB
            this.sram = new byte[Math.max(size, 1)];
            this.sramEnabled = true; // accessible by default; $A130F1 can gate it
        } else {
            this.sram = null;
            this.sramStart = 0;
            this.sramEnd = -1;
        }
    }

    private boolean inSram(int addr) {
        return sram != null && sramEnabled && addr >= sramStart && addr <= sramEnd;
    }

    private int mapRom(int addr) {
        int window = (addr >> 19) & 7;        // 512 KB windows
        return (romBank[window] << 19) | (addr & 0x7FFFF);
    }

    // ---- byte access (the routing happens here) ---------------------------

    @Override
    public int read8(int addr) {
        addr &= 0xFFFFFF;
        if (addr < 0x400000) {
            if (inSram(addr)) {
                return sram[addr - sramStart] & 0xFF;
            }
            return rom.read8(mapRom(addr));
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
        if (addr < 0x400000) {
            if (sram != null && sramEnabled && !sramWriteProtect
                    && addr >= sramStart && addr <= sramEnd) {
                sram[addr - sramStart] = (byte) value;
            }
            return; // ROM is otherwise read-only
        }
        if (addr >= 0xA13000 && addr <= 0xA130FF) {
            writeMapperRegister(addr, value);
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
            // Bit 6: 0 = NTSC, 1 = PAL. Bit 5: 1 = no expansion. Low nibble: version.
            case 0x00, 0x01 -> versionRegister();
            case 0x02, 0x03 -> pad1.readData();
            case 0x04, 0x05 -> pad2.readData();
            default -> 0x00;
        };
    }

    private int versionRegister() {
        var hdr = rom.header();
        String region = hdr.region().toUpperCase();
        boolean overseas = !(region.contains("J") && !region.contains("U") && !region.contains("E"));
        int v = 0x20; // expansion-unit bit
        if (overseas) v |= 0x80;
        if (hdr.isPal()) v |= 0x40;
        return v;
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

    /**
     * The SEGA/SSF2 mapper registers at $A130F1–$A130FF. $A130F1 is the SRAM
     * control latch (bit0 = SRAM enabled, bit1 = write protect); the odd registers
     * $A130F3..$A130FF each select the 512 KB ROM bank shown in windows 1..7.
     */
    private void writeMapperRegister(int addr, int value) {
        int off = addr & 0xFF;
        if (off == 0xF1) {
            sramEnabled = (value & 0x01) != 0;
            sramWriteProtect = (value & 0x02) != 0;
        } else if (off >= 0xF3 && off <= 0xFF && (off & 1) == 1) {
            int window = (off - 0xF1) >> 1; // F3→1 … FF→7
            romBank[window & 7] = value & 0x3F;
        }
    }

    /** Read a work-RAM byte directly, bypassing region decoding (for debugging). */
    public int peekWorkRam(int addr) {
        return workRam[addr & 0xFFFF] & 0xFF;
    }

    // ---- battery SRAM persistence ----------------------------------------

    public boolean hasSram() {
        return sram != null;
    }

    /** A copy of the current SRAM contents (for writing a .srm file). */
    public byte[] sramSnapshot() {
        return sram == null ? new byte[0] : sram.clone();
    }

    /** Load previously-saved SRAM contents (from a .srm file). */
    public void loadSram(byte[] data) {
        if (sram != null && data != null) {
            System.arraycopy(data, 0, sram, 0, Math.min(data.length, sram.length));
        }
    }

    // ---- save state -------------------------------------------------------

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        o.write(workRam);
        o.write(z80Ram);
        o.writeBoolean(z80BusRequested);
        o.writeBoolean(z80Reset);
        o.writeInt(z80Bank);
        o.writeBoolean(sramEnabled);
        o.writeBoolean(sramWriteProtect);
        for (int v : romBank) o.writeInt(v);
        if (sram != null) {
            o.write(sram);
        }
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        in.readFully(workRam);
        in.readFully(z80Ram);
        z80BusRequested = in.readBoolean();
        z80Reset = in.readBoolean();
        z80Bank = in.readInt();
        sramEnabled = in.readBoolean();
        sramWriteProtect = in.readBoolean();
        for (int i = 0; i < romBank.length; i++) romBank[i] = in.readInt();
        if (sram != null) {
            in.readFully(sram);
        }
    }
}
