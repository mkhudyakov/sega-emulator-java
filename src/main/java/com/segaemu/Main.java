package com.segaemu;

import com.segaemu.cartridge.Rom;
import com.segaemu.cartridge.RomHeader;
import com.segaemu.debug.Debugger;
import com.segaemu.ui.EmulatorFrame;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Application entry point.
 *
 * <p>Interactive (default): creates the Swing window and, if a ROM path is given,
 * boots it.
 *
 * <pre>
 *   ./run.sh                       # open the window, then File > Open ROM…
 *   ./run.sh sonic.md              # boot straight into a ROM
 *   gradle run --args="sonic.md"   # same, via Gradle
 * </pre>
 *
 * <p>Headless (for development / CI): runs without a window, optionally writes a
 * screenshot after N frames. Useful for golden-image checks per development
 * phase.
 *
 * <pre>
 *   ./run.sh --headless --frames 600 --shot out.png sonic.md
 *   ./run.sh --info sonic.md       # print the decoded cartridge header
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Options opt = Options.parse(args);

        if (opt.info) {
            printInfo(opt.romPath);
            return;
        }
        if (opt.headless) {
            runHeadless(opt);
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to the default look and feel.
        }
        SwingUtilities.invokeLater(() -> {
            EmulatorFrame frame = new EmulatorFrame();
            frame.setVisible(true);
            if (opt.romPath != null) {
                frame.loadRom(opt.romPath);
            }
        });
    }

    private static void printInfo(Path romPath) {
        if (romPath == null) {
            System.err.println("--info requires a ROM path.");
            return;
        }
        RomHeader h = Rom.load(romPath).header();
        System.out.printf("Console : %s%n", h.consoleName());
        System.out.printf("Title   : %s%n", h.displayTitle());
        System.out.printf("Region  : %s%n", h.region());
        System.out.printf("Serial  : %s%n", h.serial());
        System.out.printf("Checksum: $%04X%n", h.checksum());
        System.out.printf("6-button: %s%n", h.supportsSixButton() ? "yes" : "no");
    }

    private static void runHeadless(Options opt) {
        if (opt.romPath == null) {
            System.err.println("--headless requires a ROM path.");
            return;
        }
        Debugger dbg = Debugger.load(opt.romPath);
        long start = System.nanoTime();
        dbg.runFrames(opt.frames);
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf("Ran %d frames in %.2fs (%.0f fps), %d instructions.%n",
                opt.frames, secs, opt.frames / secs, dbg.instructionCount());
        System.out.printf("Non-background pixels: %d  frameHash=%016X%n",
                dbg.nonBackgroundPixels(), dbg.frameHash());
        if (opt.shotPath != null) {
            try {
                dbg.screenshot(opt.shotPath);
                System.out.println("Wrote screenshot: " + opt.shotPath);
            } catch (Exception e) {
                System.err.println("Failed to write screenshot: " + e.getMessage());
            }
        }
    }

    /** Minimal command-line option holder. */
    private static final class Options {
        Path romPath;
        boolean headless;
        boolean info;
        int frames = 600;
        Path shotPath;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--headless" -> o.headless = true;
                    case "--info" -> o.info = true;
                    case "--frames" -> o.frames = Integer.parseInt(args[++i]);
                    case "--shot" -> o.shotPath = Path.of(args[++i]);
                    default -> o.romPath = Path.of(args[i]);
                }
            }
            return o;
        }
    }
}
