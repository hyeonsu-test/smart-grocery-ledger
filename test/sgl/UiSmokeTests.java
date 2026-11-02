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
  private static int passed;

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
    passed++;
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
            goal.setText("251");
            invoke(frame, "refreshTargets");
            check(
                target.getSelectedRow() >= 0 && goal.getText().equals("251"),
                "background target refresh preserves selected row and unsaved input");
            button(frame, "목표 저장").doClick();
            check(
                s(obj(repo.snapshot().map("targets").get("milk")), "price").equals("251"),
                "preserved target draft can still be saved");
            JTextField budgetAmount = field(frame, "budgetAmount");
            budgetAmount.setText("123456");
            check(
                hasDraft(frame, 1),
                "unsaved budget input participates in leave/close confirmation");
            invoke(frame, "updatePlanEstimate");
            check(
                budgetAmount.getText().equals("123456"),
                "price estimate refresh preserves unsaved budget input");
            invoke(frame, "loadPlan");
            qty.setText("7");
            check(
                hasDraft(frame, 1),
                "unapplied plan quantity participates in leave/close confirmation");
            invoke(frame, "loadPlan");
            check(!hasDraft(frame, 1), "cancel restores saved plan and clears unapplied edits");
            AppState state = repo.snapshot();
            state.array("offers").removeIf(o -> s(obj(o), "product_id").equals("rice"));
            repo.saveAtomic(state);
            invoke(frame, "updatePlanEstimate");
            JLabel summary = field(frame, "planSummary");
            check(
                summary.getText().contains("확인된 소계")
                    && summary.getText().contains("미확정 1개")
                    && summary.getText().contains("미확정</b>원"),
                "unknown-price plan displays confirmed subtotal and unknown total separately");
            JTextArea productDetails = field(frame, "productDetails");
            check(
                productDetails.getText().contains("시연식품")
                    && productDetails.getText().contains("1000ml")
                    && productDetails.getText().contains("행사")
                    && productDetails.getText().contains("https://example.com/")
                    && productDetails.getText().contains("최근 성공"),
                "product view exposes specification event dates URL and source status");
            JTable comparison = field(frame, "compareTable");
            check(
                comparison.getColumnName(1).equals("상품")
                    && comparison.getValueAt(0, 4).toString().contains("100ml"),
                "comparison identifies product and price unit");
            TargetService targetService = new TargetService(repo, clock);
            targetService.managePurchaseTargets(
                m("action", "SET_TARGET", "product_id", "milk", "price", 270, "enabled", true));
            var notifications =
                targetService.managePurchaseTargets(m("action", "EVALUATE", "product_id", "milk"));
            var method =
                LedgerFrame.class.getDeclaredMethod("notificationText", java.util.List.class);
            method.setAccessible(true);
            String notification =
                (String) method.invoke(frame, list(notifications.get("notifications")));
            check(
                notification.contains("데일리 우유")
                    && notification.contains("이마트")
                    && notification.contains("260")
                    && notification.contains("100ml")
                    && notification.contains("https://example.com/"),
                "notification contains product store unit price and source URL");
            int savedPlan = list(repo.snapshot().map("plans").get("2026-11")).size();
            button(frame, "장보기 추가").doClick();
            check(
                list(field(frame, "planDraft")).size() == savedPlan + 1
                    && list(repo.snapshot().map("plans").get("2026-11")).size() == savedPlan,
                "product-to-plan button only creates a draft");
            check(
                repo.snapshot().records().size() == 6,
                "product-to-plan navigation never creates a payment");
            invoke(frame, "loadPlan");
          } catch (Throwable e) {
            failure[0] = e;
          } finally {
            if (frame != null) frame.dispose();
          }
        });
    if (failure[0] == null) checkFeedRefresh(dir, clock);
    try (var files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
    }
    if (failure[0] != null) throw new AssertionError("Swing checks failed", failure[0]);
    System.out.println("RESULT Swing action checks passed=" + passed + " failed=0");
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

  private static Object invoke(LedgerFrame frame, String name) throws Exception {
    var method = LedgerFrame.class.getDeclaredMethod(name);
    method.setAccessible(true);
    return method.invoke(frame);
  }

  private static boolean hasDraft(LedgerFrame frame, int tab) throws Exception {
    var method = LedgerFrame.class.getDeclaredMethod("tabHasDraft", int.class);
    method.setAccessible(true);
    return (Boolean) method.invoke(frame, tab);
  }

  private static void checkFeedRefresh(Path dir, Clock clock) throws Exception {
    Repository repo = new Repository(dir.resolve("empty.json"));
    AppState demo = DemoData.create(clock);
    var offer =
        demo.offers().stream()
            .filter(
                o ->
                    s(o, "product_id").equals("milk")
                        && s(o, "store").equals("이마트")
                        && s(o, "checked_date").equals("2026-11-02"))
            .findFirst()
            .orElseThrow();
    Path feed = dir.resolve("feed.json");
    Files.writeString(
        feed,
        Json.stringify(
            m(
                "products",
                java.util.List.of(demo.product("milk")),
                "offers",
                java.util.List.of(offer))));
    new ProductService(repo, new MartAdapter(), null, clock)
        .manageProducts(m("action", "CONFIGURE_FEED", "store", "이마트", "path", feed.toString()));
    LedgerFrame[] holder = {null};
    Throwable[] problem = {null};
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              holder[0] = new LedgerFrame(repo, clock, false);
              var m = LedgerFrame.class.getDeclaredMethod("refreshFeed", boolean.class);
              m.setAccessible(true);
              m.invoke(holder[0], false);
            } catch (Exception e) {
              problem[0] = e;
            }
          });
      long deadline = System.nanoTime() + 10_000_000_000L;
      boolean[] busy = {true};
      while (busy[0] && problem[0] == null && System.nanoTime() < deadline) {
        Thread.sleep(30);
        SwingUtilities.invokeAndWait(
            () -> {
              try {
                busy[0] = field(holder[0], "busy");
              } catch (Exception e) {
                problem[0] = e;
              }
            });
      }
      if (problem[0] != null) throw new AssertionError(problem[0]);
      check(!busy[0], "asynchronous connected feed refresh completes");
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              JComboBox<?> plans = field(holder[0], "planProduct"),
                  prices = field(holder[0], "priceProduct");
              check(
                  plans.getItemCount() == 1 && prices.getItemCount() == 1,
                  "new feed product immediately appears in plan and comparison selectors");
              JTable table = field(holder[0], "compareTable");
              check(table.getRowCount() == 1, "new feed price is compared without restarting app");
            } catch (Throwable e) {
              problem[0] = e;
            }
          });
      if (problem[0] != null) throw new AssertionError(problem[0]);
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            if (holder[0] != null) holder[0].dispose();
          });
    }
  }
}
