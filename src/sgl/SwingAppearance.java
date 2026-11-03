package sgl;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.FontUIResource;

/** Configures text before any application components are created. */
final class SwingAppearance {
  private static final String SAMPLE = "가계부 월별 예산 장보기 가격 확인 한글 0123456789";

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

    Font face = koreanFont();
    defaults.put("defaultFont", new FontUIResource(face));
    for (Object key : Collections.list(defaults.keys())) {
      if (defaults.get(key) instanceof Font previous) {
        defaults.put(key, new FontUIResource(face.deriveFont(previous.getStyle())));
      }
    }
  }

  private static Font koreanFont() {
    var installed =
        Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ENGLISH));
    for (String family :
        List.of(
            "Malgun Gothic",
            "Apple SD Gothic Neo",
            "Noto Sans CJK KR",
            "Noto Sans KR",
            "NanumGothic")) {
      if (installed.contains(family)) {
        Font candidate = new Font(family, Font.PLAIN, 14);
        if (candidate.canDisplayUpTo(SAMPLE) == -1) return candidate;
      }
    }
    return new Font(Font.DIALOG, Font.PLAIN, 14);
  }
}
