package sgl;

import static sgl.Data.*;

import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.swing.*;

/** Executes Swing action handlers on the EDT. This is not a substitute for visual screen review. */
public final class UiSmokeTests {
  @SuppressWarnings("unchecked")
  private static <T> T field(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return (T) f.get(owner);
  }

  private static JButton button(Container parent, String text) {
    for (Component c : parent.getComponents()) {
      if (c instanceof JButton b && b.getText().equals(text)) return b;
      if (c instanceof Container child) {
        JButton found = button(child, text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void check(boolean yes, String description) {
    if (!yes) throw new AssertionError(description);
    System.out.println("PASS " + description);
  }

  public static void main(String[] args) throws Exception {
    if (GraphicsEnvironment.isHeadless())
      throw new IllegalStateException("A display is required for Swing checks");
    Path dir = Files.createTempDirectory("sgl-ui-");
    Clock clock = Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    Repository repo = new Repository(dir.resolve("ui.json"));
    repo.saveAtomic(DemoData.create(clock));
    Throwable[] failure = {null};
    SwingUtilities.invokeAndWait(
        () -> {
          LedgerFrame frame = null;
          try {
            frame = new LedgerFrame(repo, clock, false);
            JTabbedPane tabs = field(frame, "tabs");
            check(tabs.getTabCount() == 5, "five Swing use-case screens");
            JTable history = field(frame, "historyTable");
            check(history.getRowCount() == 9, "history renders nine observed dates");
            JTextField amount = field(frame, "recordAmount"), memo = field(frame, "recordMemo");
            amount.setText("3456");
            memo.setText("Swing EDT action test");
            button(frame, "내역 저장").doClick();
            check(
                repo.snapshot().records().size() == 6,
                "ledger save button persists actual payment");
            check(
                new Repository(repo.path()).snapshot().records().size() == 6,
                "saved Swing entry survives repository restart");
            tabs.setSelectedIndex(1);
            JTextField qty = field(frame, "planQty");
            qty.setText("3");
            button(frame, "항목 적용").doClick();
            int original = list(repo.snapshot().map("plans").get("2026-11")).size();
            check(original == 3, "plan edit remains an unsaved draft");
            button(frame, "계획 저장").doClick();
            check(
                list(repo.snapshot().map("plans").get("2026-11")).size() == 4,
                "plan save button commits draft");
            check(repo.snapshot().records().size() == 6, "plan save never creates an expense");
            tabs.setSelectedIndex(4);
            JTable target = field(frame, "targetTable");
            check(target.getRowCount() == 3, "favorites populate purchase target screen");
            target.setRowSelectionInterval(0, 0);
            JTextField goal = field(frame, "targetPrice");
            goal.setText("250");
            button(frame, "목표 저장").doClick();
            decimalTarget(repo);
            check(!frame.isVisible(), "test does not claim a visible screenshot");
          } catch (Throwable e) {
            failure[0] = e;
          } finally {
            if (frame != null) frame.dispose();
          }
        });
    try (var files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
    }
    if (failure[0] != null) throw new AssertionError("Swing checks failed", failure[0]);
    System.out.println("RESULT Swing action checks passed=10 failed=0");
    System.out.println(
        "OS=" + System.getProperty("os.name") + " Java=" + System.getProperty("java.version"));
  }

  private static void decimalTarget(Repository repo) {
    check(
        new java.math.BigDecimal(
                    obj(repo.snapshot().map("targets").get("milk")).get("price").toString())
                .compareTo(new java.math.BigDecimal("250"))
            == 0,
        "target save button persists chosen unit price");
  }
}
