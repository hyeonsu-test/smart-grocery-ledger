package sgl;

import static sgl.Data.*;
import static sgl.SwingViews.*;

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

/** Shared selection guards for the five screens. Each tab owns its own editing state. */
abstract class TabView extends JPanel {
  final LedgerFrame frame;
  boolean updating;

  TabView(LedgerFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
  }

  boolean hasDraft() {
    return false;
  }

  void discardDraft() {}

  final void restoreSelection(JTable table, List<Map<String, Object>> rows, String key, String id) {
    boolean previous = updating;
    updating = true;
    table.clearSelection();
    for (int i = 0; i < rows.size(); i++)
      if (!id.isEmpty() && s(rows.get(i), key).equals(id)) {
        int view = table.convertRowIndexToView(i);
        table.setRowSelectionInterval(view, view);
        break;
      }
    updating = previous;
  }
}
