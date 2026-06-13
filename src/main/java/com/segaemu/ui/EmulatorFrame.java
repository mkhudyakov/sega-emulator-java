package com.segaemu.ui;

import com.segaemu.GenesisSystem;
import com.segaemu.cartridge.InvalidRomException;
import com.segaemu.cartridge.Rom;
import com.segaemu.io.Controller;
import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The main application window: a menu bar for loading ROMs and controlling
 * emulation, the {@link ScreenPanel} display, keyboard input handling, and the
 * background thread that drives the {@link GenesisSystem} at ~60 frames/second.
 *
 * <h2>Controls</h2>
 * <pre>
 *   Arrow keys  D-pad      A  A button    S  B button    D  C button
 *   Enter       Start
 * </pre>
 */
public final class EmulatorFrame extends JFrame {

    private static final double TARGET_FPS = GenesisSystem.FPS;
    private static final long FRAME_NANOS = (long) (1_000_000_000L / TARGET_FPS);

    private final ScreenPanel screen = new ScreenPanel();

    private GenesisSystem genesis;
    private volatile boolean running = false;
    private volatile boolean romLoaded = false;
    private Thread emulationThread;

    public EmulatorFrame() {
        super("Sega Mega Drive Emulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(screen, BorderLayout.CENTER);
        setJMenuBar(buildMenuBar());
        installKeyHandling();
        pack();
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem open = new JMenuItem("Open ROM…");
        open.addActionListener(e -> openRom());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        fileMenu.add(open);
        fileMenu.addSeparator();
        fileMenu.add(exit);

        JMenu emuMenu = new JMenu("Emulation");
        JMenuItem reset = new JMenuItem("Reset");
        reset.addActionListener(e -> resetSystem());
        JMenuItem pause = new JMenuItem("Pause / Resume");
        pause.addActionListener(e -> togglePause());
        emuMenu.add(reset);
        emuMenu.add(pause);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem controls = new JMenuItem("Controls");
        controls.addActionListener(e -> showControls());
        JMenuItem info = new JMenuItem("ROM Info");
        info.addActionListener(e -> showRomInfo());
        helpMenu.add(controls);
        helpMenu.add(info);

        bar.add(fileMenu);
        bar.add(emuMenu);
        bar.add(helpMenu);
        return bar;
    }

    private void openRom() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Mega Drive ROM");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Mega Drive ROM (*.md, *.bin, *.gen, *.smd)", "md", "bin", "gen", "smd"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        loadRom(file.toPath());
    }

    /** Load a ROM from disk, reporting any problem in a dialog. */
    public void loadRom(Path path) {
        stopEmulation();
        try {
            Rom rom = Rom.load(path);
            genesis = new GenesisSystem(rom);
            romLoaded = true;
            setTitle("Sega Mega Drive Emulator — " + rom.header().displayTitle());
            startEmulation();
        } catch (InvalidRomException ex) {
            romLoaded = false;
            showError("Invalid ROM", ex.getMessage());
        } catch (RuntimeException ex) {
            romLoaded = false;
            showError("Failed to load ROM", String.valueOf(ex.getMessage()));
        }
    }

    private void resetSystem() {
        if (romLoaded && genesis != null) {
            genesis.reset();
        }
    }

    private void togglePause() {
        if (!romLoaded) {
            return;
        }
        if (running) {
            stopEmulation();
        } else {
            startEmulation();
        }
    }

    // ------------------------------------------------------------------
    // Emulation thread
    // ------------------------------------------------------------------

    private void startEmulation() {
        if (running || !romLoaded || genesis == null) {
            return;
        }
        running = true;
        emulationThread = new Thread(this::emulationLoop, "genesis-emulation");
        emulationThread.setDaemon(true);
        emulationThread.start();
    }

    private void stopEmulation() {
        running = false;
        Thread t = emulationThread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            emulationThread = null;
        }
    }

    private void emulationLoop() {
        long nextFrame = System.nanoTime();
        while (running) {
            try {
                genesis.stepFrame();
            } catch (RuntimeException ex) {
                // A bug in the partial CPU core should not kill the UI thread.
                showError("Emulation error", String.valueOf(ex));
                running = false;
                return;
            }
            screen.updateFrame(genesis.framebuffer());

            // No audio yet, so pace to ~60 fps with a sleep-based clock.
            nextFrame += FRAME_NANOS;
            long sleep = nextFrame - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                nextFrame = System.nanoTime();
            }
        }
    }

    // ------------------------------------------------------------------
    // Keyboard input
    // ------------------------------------------------------------------

    private void installKeyHandling() {
        KeyAdapter adapter = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                setButton(e, true);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                setButton(e, false);
            }
        };
        addKeyListener(adapter);
        screen.addKeyListener(adapter);
        screen.requestFocusInWindow();
    }

    private void setButton(KeyEvent e, boolean pressed) {
        if (!romLoaded || genesis == null) {
            return;
        }
        Controller pad = genesis.pad1();
        Controller.Button b = switch (e.getKeyCode()) {
            case KeyEvent.VK_UP -> Controller.Button.UP;
            case KeyEvent.VK_DOWN -> Controller.Button.DOWN;
            case KeyEvent.VK_LEFT -> Controller.Button.LEFT;
            case KeyEvent.VK_RIGHT -> Controller.Button.RIGHT;
            case KeyEvent.VK_A -> Controller.Button.A;
            case KeyEvent.VK_S -> Controller.Button.B;
            case KeyEvent.VK_D -> Controller.Button.C;
            case KeyEvent.VK_ENTER -> Controller.Button.START;
            default -> null;
        };
        if (b == null) {
            return;
        }
        if (pressed) {
            pad.press(b);
        } else {
            pad.release(b);
        }
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showError(String title, String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE));
    }

    private void showControls() {
        String msg = """
                D-pad         Arrow keys
                A button      A
                B button      S
                C button      D
                Start         Enter

                Load a ROM with File > Open ROM…
                You must supply your own legally-obtained ROM file.""";
        JOptionPane.showMessageDialog(this, msg, "Controls", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showRomInfo() {
        if (!romLoaded || genesis == null) {
            JOptionPane.showMessageDialog(this, "No ROM loaded.", "ROM Info",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        var h = genesis.rom().header();
        String msg = """
                Console:    %s
                Title:      %s
                Copyright:  %s
                Serial:     %s
                Region:     %s
                Checksum:   $%04X
                ROM size:   %d KB
                6-button:   %s""".formatted(
                h.consoleName(), h.displayTitle(), h.copyright(), h.serial(),
                h.region(), h.checksum(), genesis.rom().size() / 1024,
                h.supportsSixButton() ? "yes" : "no");
        JOptionPane.showMessageDialog(this, msg, "ROM Info", JOptionPane.INFORMATION_MESSAGE);
    }
}
