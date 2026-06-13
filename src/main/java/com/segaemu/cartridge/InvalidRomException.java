package com.segaemu.cartridge;

/** Thrown when a file is not a recognizable Mega Drive / Genesis ROM image. */
public class InvalidRomException extends RuntimeException {
    public InvalidRomException(String message) {
        super(message);
    }
}
