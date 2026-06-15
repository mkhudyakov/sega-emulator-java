# PLAN.md — Roadmap to a fully working Sega Mega Drive / Genesis emulator

This is the development plan. The original build-out (CPU/VDP/Z80/sound/UI, phases
0–10) is **complete**; the focus now is **game compatibility** — getting the
reference titles to run correctly, starting with the two we test against most:
*Sonic The Hedgehog* (✅ working) and *Ultimate Mortal Kombat 3* (❌ crashes on
START GAME).

Work **one item at a time**, keep the JUnit suite green, run the 68000 vector
harness after CPU changes, and keep `README.md` / `CLAUDE.md` / class javadocs
honest after every change.

---

## 1. Definition of done (v1.0)

1. Boots and plays through level 1 of each reference game with correct graphics
   and sound: *Sonic 1*, *Sonic 2*, *Streets of Rage*, *Golden Axe*,
   *Castlevania Bloodlines*, *Gunstar Heroes*, *Ultimate Mortal Kombat 3*.
2. Background planes, the window plane, and sprites composite with correct
   priority, flipping, scrolling, and palettes.
3. YM2612 FM + SN76489 PSG produce recognizable music/effects, mixed to 44.1 kHz
   without underruns.
4. The Z80 sound CPU runs real driver code uploaded by the 68000.
5. 3- and 6-button controllers work; battery SRAM games save and reload.
6. Documented pass rates on the public 68000 (and Z80) instruction test suites.
7. Full speed (60 fps NTSC / 50 fps PAL) on a typical laptop, with
   pause/reset/save-state in the UI.

**Non-goals:** Sega CD, 32X, the SVP chip, cycle-perfect sub-pixel timing,
Game Genie, netplay.

---

## 2. Current status

**Working foundation, plays real games.** The machine boots commercial ROMs,
renders the full VDP feature set, runs both CPUs, and plays mixed FM+PSG+DAC audio
paced by the sound card.

- ✅ **Sonic The Hedgehog** — boots through the SEGA logo and title into
  **Green Hill Zone gameplay**: scrolling level with correct parallax, HUD,
  sprites, and its FM+DAC title/level music. (Input + scroll-direction bugs fixed.)
- ❌ **Ultimate Mortal Kombat 3** — boots to the intro and the START GAME / OPTIONS
  menu correctly, then **crashes** when a match is started. Its indirect "current
  routine" pointer at `$FF9F34` ends up holding garbage (`$AAAAAA20`) and the state
  machine `JMP`s through it. Every *instruction* UMK3 executes is verified correct
  against the 68000 vectors, so the cause is a **non-instruction accuracy gap**
  (timing / VDP / DMA / interrupt) or a control-flow divergence — not arithmetic.

**CPU accuracy:** the 68000 passes **98.41%** of the MAME-generated
SingleStepTests vectors (127 instructions × 2500 cases). The remaining ~1.6% are
officially *undefined* flags (BCD V/N, DIV-overflow N/Z), a harness artifact
(STOP's PC under MAME's prefetch model — our STOP is correct), and unimplemented
odd-address bus-error exceptions — **none of which the reference games rely on**.

---

## 3. What is complete (foundation, phases 0–10)

These shipped and are covered by the JUnit suite; treat them as the stable base.

- **68000 core** — broad instruction set, all 14 addressing modes, exceptions,
  interrupts. Validated against SingleStepTests; fixed during validation:
  ROL/ROR/ROXL/ROXR X-flag, 32-bit add/sub unsigned carry, CMPA/ADDA/SUBA-vs-
  CMPM/ADDX/SUBX decode collisions, LEA/PEA/MOVEM/MOVEA 32-bit addresses,
  ADDX/SUBX/NEGX carry+overflow, CHK flags, the SR trace bit.
- **VDP** — control/data ports, all 24 registers, VRAM/CRAM/VSRAM, V/H interrupts,
  DMA (68k→VDP, fill, copy), planes A/B + window + sprites with per-pixel priority,
  per-line/per-cell H-scroll and whole-screen/per-column V-scroll (correct
  direction), shadow/highlight, H40/H32.
- **Z80** — full Zilog core (main + CB/ED/DD/FD/DDCB/FDCB), runs Sonic's SMPS driver.
- **Sound** — SN76489 PSG synthesis; YM2612 FM (6×4 operators, envelopes,
  algorithms, LFO, timers, channel-6 DAC); `AudioMixer` → `javax.sound`, audio
  back-pressure paces the loop.
- **System** — controllers (3- and 6-button), battery SRAM (`.srm`), SSF2 mapper,
  region/PAL timing, save states (`.mds`), Swing UI (FPS, mute, F5/F7), headless
  debug harness (`debug/Debugger`, `Main --headless`).

---

## 4. Active roadmap — getting all games to run

Effort: S = small, M = medium, L = large. Priority order top-to-bottom.

### Phase A — Pin the UMK3 divergence · M  ← do first
**Objective:** turn "deep mystery crash" into "one specific divergent instruction."
- **A1. Reference-trace diff.** Run UMK3's START GAME in a known-good emulator
  (BlastEm or Genesis Plus GX) with instruction-trace logging, and diff against our
  trace (the `debug.Debugger` instruction hook already logs PC/regs) to find the
  **first divergent instruction or state read**. This is the definitive technique.
- **A2. Subsystem bisect (if no reference emulator is available).** Trace UMK3 and
  find the branch whose input our emulator computes differently — check VDP status
  reads, DMA-busy polls, the HV counter, and interrupt timing (Phase B) in turn.
**Exit:** the exact cause of the `$FF9F34` corruption is identified.

### Phase B — Non-instruction accuracy · M/L  ← most likely UMK3 cause; helps all hard games
**Objective:** make the timing-sensitive subsystems behave like hardware. Each is
a plausible UMK3 culprit and benefits every demanding title.
- **B1. VDP status-register timing.** Return VBLANK/HBLANK/DMA-busy/FIFO bits at the
  correct scanline; games poll these and branch.
- **B2. DMA timing + DMA-busy.** Currently DMA is instantaneous and always reports
  "not busy." Model a realistic transfer duration so busy-polls terminate correctly.
- **B3. HV counter horizontal position.** Currently H=0; some games use it for
  timing and RNG.
- **B4. Interrupt timing/ordering.** Exact V-int/H-int delivery relative to
  instructions and the vblank window.
- **B5. Address-error exceptions** (odd-address word/long faults). UMK3 *does* jump
  to an odd address. **Regression risk** to working games — implement carefully and
  re-verify Sonic and the JUnit suite; gate on Phase A confirming it matters.
**Verify after each:** Sonic unchanged (frameHash), JUnit green, UMK3 progress.
**Exit:** UMK3 starts a match and renders the fight; the change set is regression-
tested.

### Phase C — Remaining CPU accuracy · S  ← low game impact
**Objective:** suite cleanliness; no expected game change.
- **C1.** BCD V/N and DIV-overflow N/Z undefined flags → match MAME (vectors → ~100%).
- **C2.** Per-instruction cycle counts toward the documented tables (needed before
  any cycle-tight raster effect).

### Phase D — Validation & breadth · M
**Objective:** confirm accuracy and surface the next real bugs.
- **D1.** Run **zexdoc/zexall** against the Z80 (reuse the vector-harness pattern).
- **D2.** Bring up the other reference games (*Sonic 2*, *Streets of Rage*,
  *Golden Axe*, *Gunstar Heroes*, *Castlevania Bloodlines*); fix what surfaces,
  pinning each with a regression test.
- **D3.** Re-check the v1.0 definition-of-done checklist.

---

## 5. Tools & testing strategy

- **JUnit suite** — every fix adds a focused regression test; keep it green.
- **68000 SingleStepTests harness** — the MAME-generated `m68000` JSON vectors
  (`github.com/SingleStepTests/m68000`) decoded to a flat-bus runner. **Not
  committed** (the vectors are large); fetch on demand. It found and pins every
  CPU-accuracy bug fixed so far. Re-run after any `M68000` change. Skips
  address-error cases (a known unimplemented feature, item B5).
- **Z80** — zexdoc/zexall against a flat Z80 memory (item D1).
- **Headless harness** (`debug.Debugger`, `Main --headless --frames N --shot out.png`)
  — frame stepping, PC breakpoints, RAM/VRAM peek, instruction hook, screenshots,
  frame hashing. This is the primary game-bug diagnosis tool; use it for the
  Phase A trace and golden-frame regression checks (never guess).
- **Reference emulator** (BlastEm / Genesis Plus GX) — for the Phase A trace diff.

---

## 6. Risk register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| UMK3 cause is subtle timing, hard to find without a reference trace | High | High | Phase A trace diff is decisive; Phase B bisect as fallback. |
| Address errors (B5) regress working games | Medium | High | Gate on Phase A; re-verify Sonic + JUnit after; back out if it regresses. |
| DMA/VDP timing model too coarse for some games | Medium | Medium | Deepen only when a target game demands it; keep the model swappable. |
| Chasing undefined flags (C1) for no game benefit | — | — | Explicitly low priority; do only for suite cleanliness. |
| Scope creep (Sega CD/32X/SVP) | Medium | Medium | Explicit non-goals in §1. |

---

## 7. Working agreement per item

1. Reproduce the failure headlessly (golden frame / trace / vector) before changing code.
2. Implement the smallest slice that moves a reference game forward.
3. Diagnose with the harness and reference trace — never guess.
4. Add a regression test for every concrete bug fixed.
5. Re-run the JUnit suite and (for CPU changes) the vector harness; confirm Sonic
   still renders (frameHash) and no game regressed.
6. Update `README.md` / `CLAUDE.md` / class javadocs.
7. Keep this file current: check off items and append a short "what shipped / what's
   still approximate" note as each lands.
