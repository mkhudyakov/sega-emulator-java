package com.segaemu.cartridge;

import java.nio.charset.StandardCharsets;

/**
 * The decoded 256-byte Mega Drive cartridge header located at {@code $000100}.
 *
 * <p>Layout (offsets relative to {@code $100}):
 * <pre>
 *   $000  16  Console name      ("SEGA MEGA DRIVE " / "SEGA GENESIS    ")
 *   $010  16  Copyright / release date
 *   $020  48  Domestic (Japanese) game title
 *   $050  48  Overseas (international) game title
 *   $080  14  Serial number / version
 *   $08E   2  Checksum (big-endian word)
 *   $090  16  I/O device support codes (e.g. "J" joypad, "6" 6-button)
 *   $0A0   8  ROM start address
 *   $0A4   8  ROM end address
 *   $0A8   8  RAM start address
 *   $0AC   8  RAM end address
 *   $0B0  12  SRAM info
 *   $0F0  16  Region codes ("JUE", or newer single-char "U"/"E"/"J")
 * </pre>
 */
public record RomHeader(
        String consoleName,
        String copyright,
        String domesticTitle,
        String overseasTitle,
        String serial,
        int checksum,
        String ioSupport,
        int romStart,
        int romEnd,
        String region) {

    public static RomHeader parse(byte[] data) {
        int h = Rom.HEADER_OFFSET;
        return new RomHeader(
                ascii(data, h + 0x000, 16),
                ascii(data, h + 0x010, 16),
                ascii(data, h + 0x020, 48),
                ascii(data, h + 0x050, 48),
                ascii(data, h + 0x080, 14),
                word(data, h + 0x08E),
                ascii(data, h + 0x090, 16),
                dword(data, h + 0x0A0),
                dword(data, h + 0x0A4),
                ascii(data, h + 0x0F0, 16));
    }

    /** True when the console-name field carries the expected SEGA signature. */
    public boolean isValidConsoleName() {
        String name = consoleName.toUpperCase();
        return name.contains("SEGA");
    }

    /** A best-effort human title, preferring the overseas name. */
    public String displayTitle() {
        String t = overseasTitle.isBlank() ? domesticTitle : overseasTitle;
        return t.isBlank() ? "(untitled)" : t;
    }

    /** True if the I/O support string advertises a 6-button controller. */
    public boolean supportsSixButton() {
        return ioSupport.contains("6");
    }

    private static String ascii(byte[] data, int off, int len) {
        if (off + len > data.length) {
            len = Math.max(0, data.length - off);
        }
        return new String(data, off, len, StandardCharsets.US_ASCII).trim();
    }

    private static int word(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static int dword(byte[] data, int off) {
        return ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
                | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
    }
}
