package sgl;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.RenderingHints;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.FontUIResource;

/** Configures text before any application components are created. */
final class SwingAppearance {
  private static final float BODY_SIZE = 15f;
  private static final Font REGULAR = readFace("Regular");
  private static final Font BOLD = readFace("Bold");

  private SwingAppearance() {}

  static void install() {
    try {
      UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
    } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {
      // Use the available look and feel with the same text settings.
    }
    UIManager.put("swing.boldMetal", false);
    var defaults = UIManager.getLookAndFeelDefaults();
    // Whole-pixel grayscale smoothing also works when Windows scales a window or an image.
    defaults.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    Font face = REGULAR.deriveFont(BODY_SIZE);
    defaults.put("defaultFont", new FontUIResource(face));
    for (Object key : Collections.list(defaults.keys())) {
      if (defaults.get(key) instanceof Font previous) {
        defaults.put(
            key,
            new FontUIResource(
                (previous.isBold() ? BOLD : REGULAR).deriveFont(previous.getStyle(), BODY_SIZE)));
      }
    }
  }

  static Font heading(float size) {
    return BOLD.deriveFont(Font.BOLD, size);
  }

  static Font tabFont() {
    return REGULAR.deriveFont(16f);
  }

  private static Font readFace(String weight) {
    String path = "/fonts/IBMPlexSansKR-" + weight + ".ttf";
    try (InputStream stream = SwingAppearance.class.getResourceAsStream(path)) {
      if (stream == null) throw new IllegalStateException("글꼴 파일을 찾을 수 없습니다: " + path);
      return Font.createFont(Font.TRUETYPE_FONT, stream);
    } catch (IOException | FontFormatException ex) {
      throw new IllegalStateException("글꼴을 읽을 수 없습니다: " + path, ex);
    }
  }
}
