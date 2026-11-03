package sgl;

import static sgl.Data.*;

import java.awt.*;
import java.awt.event.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

/** Shared Swing formatting and layout helpers. */
final class SwingViews {
  private SwingViews() {}

  static final Color INK = new Color(28, 50, 66),
      ACCENT = new Color(16, 110, 111),
      BG = new Color(245, 248, 250);

  record Choice(String id, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  static JPanel flow() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
    p.setOpaque(false);
    return p;
  }

  static JPanel field(String label, JComponent input) {
    JPanel p = new JPanel(new BorderLayout(0, 4));
    p.setOpaque(false);
    p.add(new JLabel(label), BorderLayout.NORTH);
    p.add(input);
    return p;
  }

  static JPanel metric(JLabel label) {
    JPanel p = new JPanel(new BorderLayout());
    p.setBackground(new Color(231, 242, 241));
    p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    label.setForeground(ACCENT);
    p.add(label);
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
    return p;
  }

  static JPanel page(String title, String hint) {
    JPanel p =
        new JPanel() {
          static final long serialVersionUID = 1L;

          @Override
          protected void addImpl(Component component, Object constraints, int index) {
            if (component instanceof JComponent child)
              child.setAlignmentX(Component.LEFT_ALIGNMENT);
            super.addImpl(component, constraints, index);
          }
        };
    p.setBackground(BG);
    p.setBorder(BorderFactory.createEmptyBorder(14, 12, 12, 12));
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    JLabel t = new JLabel(title);
    t.setFont(SwingAppearance.heading(20f));
    t.setForeground(INK);
    p.add(t);
    p.add(Box.createVerticalStrut(6));
    JLabel h = new JLabel(hint);
    h.setForeground(new Color(90, 105, 115));
    p.add(h);
    p.add(Box.createVerticalStrut(12));
    return p;
  }

  static JTable table(String... cols) {
    JTable t =
        new JTable(
            new DefaultTableModel(cols, 0) {
              static final long serialVersionUID = 1L;

              @Override
              public boolean isCellEditable(int r, int c) {
                return false;
              }
            });
    t.setRowHeight(28);
    t.setFillsViewportHeight(true);
    t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    t.getTableHeader().setReorderingAllowed(false);
    t.setGridColor(new Color(224, 231, 235));
    t.setSelectionBackground(new Color(199, 229, 225));
    t.setSelectionForeground(INK);
    t.setAutoCreateRowSorter(true);
    return t;
  }

  static JScrollPane scroll(JTable table, int height) {
    JScrollPane s = new JScrollPane(table);
    s.setPreferredSize(new Dimension(1000, height));
    s.setMaximumSize(new Dimension(Integer.MAX_VALUE, height + 70));
    return s;
  }

  static void clear(JTable t) {
    ((DefaultTableModel) t.getModel()).setRowCount(0);
  }

  static void addRow(JTable t, Object... values) {
    ((DefaultTableModel) t.getModel()).addRow(values);
  }

  static JComboBox<String> combo(String... values) {
    JComboBox<String> box = new JComboBox<>(values);
    box.setPreferredSize(new Dimension(Math.max(105, box.getPreferredSize().width + 18), 30));
    return box;
  }

  static JComboBox<String> all(List<String> values) {
    List<String> v = new ArrayList<>(List.of("전체"));
    v.addAll(values);
    return combo(v.toArray(String[]::new));
  }

  static JTextArea details(int lines) {
    JTextArea area = new JTextArea(lines, 70);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setMargin(new Insets(7, 9, 7, 9));
    return area;
  }

  static String selected(JComboBox<?> c) {
    return c.getSelectedItem() == null ? "" : c.getSelectedItem().toString();
  }

  static String filter(JComboBox<?> c) {
    return selected(c).equals("전체") ? "" : selected(c);
  }

  static String chosen(JComboBox<Choice> c) {
    return c.getSelectedItem() instanceof Choice x ? x.id() : "";
  }

  static void select(JComboBox<Choice> c, String id) {
    for (int i = 0; i < c.getItemCount(); i++)
      if (c.getItemAt(i).id().equals(id)) {
        c.setSelectedIndex(i);
        return;
      }
  }

  static String money(Object value) {
    if (value == null) return "미확정";
    try {
      return String.format(Locale.KOREA, "%,.2f", new BigDecimal(value.toString()))
          .replaceAll("\\.00$", "");
    } catch (NumberFormatException e) {
      return value.toString();
    }
  }

  static String unitLabel(Product p) {
    return unitBase(p.category()).toPlainString() + p.unit();
  }

  static String html(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  static DocumentListener change(Runnable r) {
    return new DocumentListener() {
      public void insertUpdate(DocumentEvent e) {
        r.run();
      }

      public void removeUpdate(DocumentEvent e) {
        r.run();
      }

      public void changedUpdate(DocumentEvent e) {
        r.run();
      }
    };
  }

  static void fillChoices(JComboBox<Choice> box, Collection<Product> products) {
    String previous = chosen(box);
    box.removeAllItems();
    for (var product : products) box.addItem(new Choice(product.id(), product.name()));
    select(box, previous);
  }
}
