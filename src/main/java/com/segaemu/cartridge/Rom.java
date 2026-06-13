package com.segaemu.cartridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A loaded Mega Drive / Genesis cartridge image plus its decoded header.
 *
 * <p>Mega Drive ROMs are big-endian. The 68000 reset vectors live in the first
 * 8 bytes ({@code $000000–$000007}: initial stack pointer, then initial PC),
 * and a 256-byte cartridge header begins at {@code $000100}. The header opens
 * with an ASCII console name — "SEGA MEGA DRIVE " or "SEGA GENESIS    " — which
 * is what we use to validate the file.
 *
 * <p>Two container formats exist in the wild:
 * <ul>
 *   <li><b>Plain / BIN</b> (.bin, .md, .gen) — a raw byte-for-byte dump. Used
 *       directly.</li>
 *   <li><b>SMD interleaved</b> (.smd) — a 512-byte copier header followed by
 *       16 KB blocks whose even and odd bytes are stored separately. We detect
 *       this and de-interleave it back to a plain image.</li>
 * </ul>
 */
public final class Rom {

    /** Offset of the cartridge header within a plain image. */
    public static final int HEADER_OFFSET = 0x100;

    private final byte[] data;
    private final RomHeader header;

    private Rom(byte[] data, RomHeader header) {
        this.data = data;
        this.header = header;
    }

    /** Load and validate a ROM from disk, de-interleaving SMD images first. */
    public static Rom load(Path path) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new InvalidRomException("Could not read ROM file: " + e.getMessage());
        }
        return fromBytes(raw);
    }

    /** Build a ROM from an in-memory image (used by tests). */
    public static Rom fromBytes(byte[] raw) {
        byte[] plain = looksLikeSmd(raw) ? deinterleaveSmd(raw) : raw;
        if (plain.length < HEADER_OFFSET + 0x100) {
            throw new InvalidRomException(
                    "File too small to be a Mega Drive ROM (" + plain.length + " bytes).");
        }
        RomHeader header = RomHeader.parse(plain);
        if (!header.isValidConsoleName()) {
            throw new InvalidRomException(
                    "Missing 'SEGA' signature at $100 — not a Mega Drive/Genesis ROM. "
                            + "Found: \"" + header.consoleName() + "\"");
        }
        return new Rom(plain, header);
    }

    /**
     * SMD images begin with a 512-byte copier header. Byte 8 is {@code 0xAA}
     * and byte 9 is {@code 0xBB} in the common Super Magic Drive format; we also
     * accept the looser heuristic of a 512-byte-aligned remainder.
     */
    private static boolean looksLikeSmd(byte[] raw) {
        if (raw.length < 512 + 16384) {
            return false;
        }
        boolean magic = (raw[8] & 0xFF) == 0xAA && (raw[9] & 0xFF) == 0xBB;
        boolean aligned = (raw.length - 512) % 16384 == 0;
        return magic && aligned;
    }

    /** De-interleave an SMD image: strip the 512-byte header, then unscramble. */
    private static byte[] deinterleaveSmd(byte[] raw) {
        int body = raw.length - 512;
        byte[] out = new byte[body];
        int blocks = body / 16384;
        for (int b = 0; b < blocks; b++) {
            int base = 512 + b * 16384;
            int outBase = b * 16384;
            for (int i = 0; i < 8192; i++) {
                out[outBase + i * 2 + 1] = raw[base + i];          // odd bytes
                out[outBase + i * 2] = raw[base + 8192 + i];       // even bytes
            }
        }
        return out;
    }

    /** Read one big-endian byte of ROM (0-255); out-of-range returns 0xFF. */
    public int read8(int addr) {
        if (addr < 0 || addr >= data.length) {
            return 0xFF;
        }
        return data[addr] & 0xFF;
    }

    /** Read one big-endian 16-bit word. */
    public int read16(int addr) {
        return (read8(addr) << 8) | read8(addr + 1);
    }

    /** Read one big-endian 32-bit long. */
    public int read32(int addr) {
        return (read16(addr) << 16) | read16(addr + 2);
    }

    public int size() {
        return data.length;
    }

    public RomHeader header() {
        return header;
    }

    /** Initial supervisor stack pointer from the reset vector at $000000. */
    public int initialStackPointer() {
        return read32(0x000000);
    }

    /** Initial program counter from the reset vector at $000004. */
    public int initialProgramCounter() {
        return read32(0x000004);
    }
}
