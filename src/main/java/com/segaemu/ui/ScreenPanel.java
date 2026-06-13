package com.segaemu.ui;

import com.segaemu.vdp.Vdp;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * A Swing panel that draws the VDP's 320&times;224 ARGB framebuffer.
 *
 * <p>The emulation thread fills an {@code int[]} every frame; this panel copies
 * that into a {@link BufferedImage} and scales it to fill the component while
 * preserving the Mega Drive's 320:224 aspect ratio. Nearest-neighbour scaling
 * keeps the pixels crisp.
 */
public final class ScreenPanel extends JPanel {

    public static final int MD_WIDTH = Vdp.SCREEN_W;
    public static final int MD_HEIGHT = Vdp.SCREEN_H;

    private final BufferedImage image =
            new BufferedImage(MD_WIDTH, MD_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    private final int[] imageData =
            ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();

    public ScreenPanel() {
        setPreferredSize(new Dimension(MD_WIDTH * 2, MD_HEIGHT * 2));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);
    }

    /** Copy a fresh framebuffer in and request a repaint (call from any thread). */
    public void updateFrame(int[] framebuffer) {
        System.arraycopy(framebuffer, 0, imageData, 0, imageData.length);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        int panelW = getWidth();
        int panelH = getHeight();

        // Fit while preserving the 320:224 aspect ratio; letterbox the remainder.
        double scale = Math.min(panelW / (double) MD_WIDTH, panelH / (double) MD_HEIGHT);
        int drawW = (int) (MD_WIDTH * scale);
        int drawH = (int) (MD_HEIGHT * scale);
        int x = (panelW - drawW) / 2;
        int y = (panelH - drawH) / 2;

        g2.drawImage(image, x, y, drawW, drawH, null);
    }
}
