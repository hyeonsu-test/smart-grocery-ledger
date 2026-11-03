package sgl;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.Clock;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Renders this application's own live Swing component tree for legible documentation. No Robot, OS
 * input injection, accessibility access or existing-window control is used. Native Windows
 * screenshots are retained separately as execution evidence.
 */
public final class UiRenderEvidence {
  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("source demo JSON and output directory required");
    Path source = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[1]).toAbsolutePath();
    Repository original = new Repository(source);
    if (!original.readError().isEmpty() || !original.snapshot().demo())
      throw new IllegalArgumentException("Only valid fictional demo data may be rendered");
    Files.createDirectories(output);
    Path temp = Files.createTempDirectory("sgl-render-");
    Repository copy = new Repository(temp.resolve("demo.json"));
    copy.saveAtomic(original.snapshot());
    Throwable[] error = {null};
    SwingUtilities.invokeAndWait(
        () -> {
          LedgerFrame frame = null;
          try {
            SwingAppearance.install();
            frame = new LedgerFrame(copy, Clock.systemDefaultZone(), false);
            frame.setVisible(true);
            frame.validate();
            Field field = LedgerFrame.class.getDeclaredField("tabs");
            field.setAccessible(true);
            JTabbedPane tabs = (JTabbedPane) field.get(frame);
            String[] names = {"01-ledger", "02-budget", "03-products", "04-prices", "05-targets"};
            for (int i = 0; i < names.length; i++) {
              tabs.setSelectedIndex(i);
              frame.validate();
              JComponent surface = (JComponent) frame.getContentPane();
              BufferedImage image =
                  new BufferedImage(
                      surface.getWidth() * 2, surface.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
              Graphics2D graphics = image.createGraphics();
              graphics.scale(2, 2);
              graphics.setColor(Color.WHITE);
              graphics.fillRect(0, 0, surface.getWidth(), surface.getHeight());
              surface.printAll(graphics);
              graphics.dispose();
              ImageIO.write(image, "png", output.resolve(names[i] + ".png").toFile());
              System.out.println("RENDERED live Swing component tree: " + names[i] + " (2x scale)");
            }
          } catch (Throwable problem) {
            error[0] = problem;
          } finally {
            if (frame != null) frame.dispose();
          }
        });
    Files.deleteIfExists(temp.resolve("demo.json"));
    Files.delete(temp);
    if (error[0] != null) throw new AssertionError(error[0]);
  }
}
