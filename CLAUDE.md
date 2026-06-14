# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and run commands

```bash
# Build (compile + test + assemble jar)
gradle build

# Run with a ROM
gradle run --args="path/to/game.md"

# Run tests
gradle test

# Run a specific test class
gradle test --tests "com.segaemu.cpu.M68000Test"

# Run without Gradle (compiles to out/)
./run.sh path/to/game.md
```

Requires **JDK 21+**.

## Status

This is a **working foundation, not a complete emulator** (see README for the
full breakdown). It boots commercial ROMs and renders their title screens with
background planes **and sprites** (Sonic appears on his title emblem) via VDP
DMA. The **Z80 sound CPU is a full core** that runs the uploaded sound driver, and
both the **SN76489 PSG and the YM2612 FM** chip synthesize samples (the YM2612
including its timers and the channel-6 DAC, so *Sonic*'s SMPS driver advances and
plays the title theme) — but **nothing is routed to the speakers yet** (the audio
mixer/output is Phase 7), and the FM core is an approximation. **`PLAN.md` is the
phased development roadmap — follow it one phase at a time and keep it, the
README, and class javadocs honest after each phase.**

## Architecture

`GenesisSystem` is the central object (analogous to `NesSystem` in the NES
emulator). It owns the master clock and wires the 68000, VDP, Z80 and sound chips
to the `GenesisBus`.

**68000 memory map** (in `bus/GenesisBus.java`, big-endian):
- `$000000–$3FFFFF` — Cartridge ROM (up to 4 MB)
- `$A00000–$A0FFFF` — Z80 address space (8 KB sound RAM + sound chips)
- `$A10000–$A1001F` — I/O: version register + controller data/control ports
- `$A11100` — Z80 bus-request latch; `$A11200` — Z80 reset latch
- `$C00000–$C00003` — VDP data port (word); `$C00004–$C00007` — VDP control port
- `$C00008–$C0000F` — VDP HV counter
- `$FF0000–$FFFFFF` — 68000 work RAM (64 KB, mirrored)

**CPU** (`cpu/m68k/M68000.java`): instruction-stepped with approximate cycle
counts (not cycle-exact). All values are kept in `int`s and masked to the operand
size at the boundaries (`& 0xFF` / `& 0xFFFF`); addresses wrap to the 24-bit
external bus. Decode is a switch on the top nibble of the opcode, dispatching to
per-group handlers. Effective addresses are resolved once into a small `Ea`
record (register / immediate / memory) so pre-decrement and post-increment side
effects happen exactly once per operand. The status register splits into the
supervisor/interrupt bits plus the X-N-Z-V-C condition flags, packed/unpacked via
`packSr`/`unpackSr`. Reset loads SSP and PC from the vector table at `$000000`.
Unimplemented opcodes raise the illegal-instruction exception (vector 4).

**Bus interface** (`bus/Bus68000.java`): `read8/16/32` and `write8/16/32`. Word
and long accesses are composed from the byte path so region decoding and work-RAM
mirroring are expressed once; the VDP ports are fast-pathed as genuine 16-bit
accesses. `GenesisBus` is its sole production implementation; tests use a flat
`TestBus`.

**VDP** (`vdp/Vdp.java`): the control port implements the two-write command
protocol that builds a 32-bit address+code word (register writes are the
`$8xxx` form). The data port reads/writes VRAM/CRAM/VSRAM per the current code and
auto-increments the address by register 15. `stepScanline()` is called once per
line by `GenesisSystem`; for each active line it builds three layers
(`computePlaneRow` for planes A/B, `computeSpriteRow` for the SAT linked list)
and `resolvePixel` composites them by the per-pixel priority rules; on entering
vblank at line 224 it raises the level-6 vertical interrupt, and a register-10
counter raises the level-4 horizontal interrupt across the active display.
`GenesisSystem` delivers V-int then H-int and keeps each pending until the CPU
accepts it (`M68000.interrupt` returns acceptance). Colours are 9-bit
BGR in CRAM, expanded to ARGB via a 3-bit→8-bit level table. DMA (68k→VDP, fill,
copy) is driven from `GenesisBus.runPendingDma()`. The **window plane**
(registers 17/18, overlaying plane A), **per-column vscroll** (register 11 bit 2),
**shadow/highlight** (register 12 bit 3, approximate), and **H40/H32** width
(register 12 → `activeWidth()` 320/256) are implemented. **Interlace and
sub-line HV timing are not yet implemented.**

**Z80** (`z80/Z80.java`): a full Zilog Z80 core — the main table plus the CB, ED,
DD/FD and DDCB/FDCB prefix groups, the S-Z-Y-H-X-P/V-N-C flags (with the
undocumented 5/3 bits), IX/IY, the shadow set, I/R, the three interrupt modes and
NMI. It talks to memory through the `z80/Z80Bus` interface, which `GenesisBus`
implements (`read`/`write`): `$0000–$3FFF` sound RAM (8 KB mirrored), `$4000–$5FFF`
YM2612, `$6000` the 9-bit bank register (one bit shifted in per write), `$7F11`
the PSG, and `$8000–$FFFF` the 68000 window at `(bank << 15)`. `GenesisSystem`
runs it at `Z80_CYCLES_PER_LINE` only while it is out of reset and holds its own
bus; the reset line's falling edge re-resets the core (PC→0), and the Z80 INT line
is pulsed at vblank (`requestInterrupt`/`clearInterrupt`), independent of the
68000's interrupt enable.

**Debug harness** (`debug/Debugger.java`): headless run-frames / PC-breakpoint /
RAM-peek / screenshot tool used to diagnose boot bugs; also exposed via
`Main --headless`/`--info`. `GenesisSystem.setInstructionHook` + `requestBreak`
back it.

**Timing model** (`GenesisSystem.stepFrame`): NTSC reference clock ~7.67 MHz, 262
scanlines at ~59.92 fps → ~488 CPU cycles per scanline. The CPU runs a cycle
budget per line, then the VDP steps one line; the vertical interrupt is delivered
when the VDP signals vblank. The emulation thread (in `ui/EmulatorFrame`) paces
itself with a sleep-based 60 fps clock (no audio back-pressure yet).

**Controller** (`io/Controller.java`): the 3-button pad multiplexes eight buttons
onto six data lines using the TH select line (bit 6); buttons are active-low.

## Key implementation details

- Mega Drive data is **big-endian**; the reset vectors (SSP, PC) are the first two
  longs of the ROM and the cartridge header is at `$100`.
- `.smd` copier images are interleaved; `cartridge/Rom.java` detects and
  de-interleaves them back to a plain image before parsing.
- When adding 68000 instructions, add a focused case to the relevant
  `groupXxx(int op)` handler and a JUnit test in `M68000Test` that pokes the
  opcode words into a `TestBus` and asserts register/flag results.
- The CPU's public `pokeD`/`pokeA`/`setPc` and flag accessors exist for tests;
  the internal `setA(int,int)` is the address-register writer used by execution.
