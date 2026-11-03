package sgl;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import javax.swing.BorderFactory;
import javax.swing.JLabel;

/** A Korean tab caption drawn from the bundled outline at the display's actual scale. */
final class TabTitle extends JLabel {
  TabTitle(String text) {
    super(text);
    setFont(SwingAppearance.tabFont());
    setForeground(SwingViews.INK);
    setBorder(BorderFactory.createEmptyBorder(2, 3, 2, 3));
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      TextLayout title = new TextLayout(getText(), getFont(), g.getFontRenderContext());
      Insets insets = getInsets();
      float baseline =
          insets.top
              + (getHeight() - insets.top - insets.bottom + title.getAscent() - title.getDescent())
                  / 2f;
      g.setColor(isEnabled() ? getForeground() : Color.GRAY);
      g.fill(title.getOutline(AffineTransform.getTranslateInstance(insets.left, baseline)));
    } finally {
      g.dispose();
    }
  }
}
