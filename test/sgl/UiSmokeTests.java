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
            SwingAppearance.install();
            frame = new LedgerFrame(repo, clock, false);
            JTabbedPane tabs = field(frame, "tabs");
            JTable priceHistory = field(frame.priceTab, "historyTable");
            JTextField amountInput = field(frame.ledgerTab, "recordAmount");
            String family = new JLabel().getFont().getFamily();
            check(
                java.util.List.of(
                            tabs,
                            priceHistory,
                            priceHistory.getTableHeader(),
                            amountInput,
                            button(frame, "내역 저장"),
                            new JTextArea(),
                            new JComboBox<>())
                        .stream()
                        .allMatch(c -> c.getFont().getFamily().equals(family))
                    && UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING) != null
                    && UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING)
                        != RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                "tabs, tables, headers, controls and text inputs share the smoothed UI font");
            check(
                "IBM Plex Sans KR"
                        .equals(new JLabel().getFont().getFamily(java.util.Locale.ENGLISH))
                    && tabs.getTabComponentAt(0) instanceof JLabel header
                    && header.getFont().getPSName().equals("IBMPlexSansKR-Regular")
                    && header.getFont().getSize() == 16
                    && header.getFont().canDisplayUpTo("가계부 예산 장보기 상품 할인 가격 비교 이력 구매 목표 알림") == -1
                    && SwingAppearance.heading(20).getPSName().equals("IBMPlexSansKR-Bold"),
                "actual tab header uses bundled Korean typeface and bundled bold face is"
                    + " available");
            check(tabs.getTabCount() == 5, "five Swing use-case screens");
            check(
                tabs.getComponentAt(0) instanceof LedgerTab
                    && tabs.getComponentAt(1) instanceof BudgetTab
                    && tabs.getComponentAt(2) instanceof ProductTab
                    && tabs.getComponentAt(3) instanceof PriceTab
                    && tabs.getComponentAt(4) instanceof TargetTab,
                "each screen is owned by its own tab");
            JTable history = field(frame.priceTab, "historyTable");
            check(history.getRowCount() == 9, "history renders nine observed dates");
            JTextField amount = field(frame.ledgerTab, "recordAmount"),
                memo = field(frame.ledgerTab, "recordMemo");
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
            JTextField qty = field(frame.budgetTab, "planQty");
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
            JTable target = field(frame.targetTab, "targetTable");
            check(target.getRowCount() == 3, "favorites populate purchase target screen");
            target.setRowSelectionInterval(0, 0);
            JTextField goal = field(frame.targetTab, "targetPrice");
            goal.setText("250");
            button(frame, "목표 저장").doClick();
            decimalTarget(repo);
            check(!frame.isVisible(), "test does not claim a visible screenshot");
            goal.setText("251");
            invoke(frame.targetTab, "refreshTargets");
            check(
                target.getSelectedRow() >= 0 && goal.getText().equals("251"),
                "background target refresh preserves selected row and unsaved input");
            button(frame, "목표 저장").doClick();
            check(
                s(obj(repo.snapshot().map("targets").get("milk")), "price").equals("251"),
                "preserved target draft can still be saved");
            JTextField budgetAmount = field(frame.budgetTab, "budgetAmount");
            budgetAmount.setText("123456");
            check(
                hasDraft(frame, 1),
                "unsaved budget input participates in leave/close confirmation");
            invoke(frame.budgetTab, "updatePlanEstimate");
            check(
                budgetAmount.getText().equals("123456"),
                "price estimate refresh preserves unsaved budget input");
            invoke(frame.budgetTab, "loadPlan");
            qty.setText("7");
            check(
                hasDraft(frame, 1),
                "unapplied plan quantity participates in leave/close confirmation");
            invoke(frame.budgetTab, "loadPlan");
            check(!hasDraft(frame, 1), "cancel restores saved plan and clears unapplied edits");
            AppState state = repo.snapshot();
            var json = state.toMap();
            list(json.get("offers")).removeIf(o -> s(obj(o), "product_id").equals("rice"));
            repo.saveAtomic(new AppState(json));
            invoke(frame.budgetTab, "updatePlanEstimate");
            JLabel summary = field(frame.budgetTab, "planSummary");
            check(
                summary.getText().contains("확인된 소계")
                    && summary.getText().contains("미확정 1개")
                    && summary.getText().contains("미확정</b>원"),
                "unknown-price plan displays confirmed subtotal and unknown total separately");
            JTextArea productDetails = field(frame.productTab, "productDetails");
            check(
                productDetails.getText().contains("시연식품")
                    && productDetails.getText().contains("1000ml")
                    && productDetails.getText().contains("행사")
                    && productDetails.getText().contains("https://example.com/")
                    && productDetails.getText().contains("최근 성공"),
                "product view exposes specification event dates URL and source status");
            JTable comparison = field(frame.priceTab, "compareTable");
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
                TargetTab.class.getDeclaredMethod("notificationText", java.util.List.class);
            method.setAccessible(true);
            String notification =
                (String) method.invoke(frame.targetTab, list(notifications.get("notifications")));
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
                list(field(frame.budgetTab, "planDraft")).size() == savedPlan + 1
                    && list(repo.snapshot().map("plans").get("2026-11")).size() == savedPlan,
                "product-to-plan button only creates a draft");
            check(
                repo.snapshot().records().size() == 6,
                "product-to-plan navigation never creates a payment");
            invoke(frame.budgetTab, "loadPlan");
            tabs.setSelectedIndex(2);
            button(frame, "실제 구매 기록").doClick();
            check(
                tabs.getSelectedIndex() == 0
                    && hasDraft(frame, 0)
                    && repo.snapshot().records().size() == 6,
                "selected product opens an unsaved ledger entry");
            button(frame, "내역 저장").doClick();
            check(
                repo.snapshot().records().size() == 7 && !hasDraft(frame, 0),
                "saving a cross-tab purchase records exactly one expense");

          } catch (Throwable e) {
            failure[0] = e;
          } finally {
            if (frame != null) frame.dispose();
          }
        });
    if (failure[0] == null) checkFeedRefresh(dir, clock);
    if (failure[0] == null) checkWorkflows(dir, clock);
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

  private static Object invoke(Object tab, String name) throws Exception {
    var method = tab.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    return method.invoke(tab);
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
            .map(PriceObservation::toMap)
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
                java.util.List.of(demo.product("milk").toMap()),
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
              var m = ProductTab.class.getDeclaredMethod("refreshFeed", boolean.class);
              m.setAccessible(true);
              m.invoke(holder[0].productTab, false);
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
                busy[0] = field(holder[0].productTab, "busy");
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
              JComboBox<?> plans = field(holder[0].budgetTab, "planProduct"),
                  prices = field(holder[0].priceTab, "priceProduct");
              check(
                  plans.getItemCount() == 1 && prices.getItemCount() == 1,
                  "new feed product immediately appears in plan and comparison selectors");
              JTable table = field(holder[0].priceTab, "compareTable");
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

  private static void checkWorkflows(Path dir, Clock clock) throws Exception {
    Repository repo = new Repository(dir.resolve("workflow.json"));
    repo.saveAtomic(DemoData.create(clock));
    Throwable[] failure = {null};
    SwingUtilities.invokeAndWait(() -> {
      LedgerFrame frame = null;
      try {
        frame = new LedgerFrame(repo, clock, false);
        JComboBox<String> kind = field(frame.ledgerTab, "kind");
        JComboBox<String> category = field(frame.ledgerTab, "recordCategory");
        JTextField amount = field(frame.ledgerTab, "recordAmount");
        JTextField store = field(frame.ledgerTab, "recordStore");
        JTextField quantity = field(frame.ledgerTab, "recordQuantity");
        JPanel quantityField = field(frame.ledgerTab, "quantityField");
        JLabel storeLabel = field(frame.ledgerTab, "storeLabel");
        JTable ledger = field(frame.ledgerTab, "recordTable");
        check(ledger.getColumnName(5).equals("거래처") && !quantityField.isVisible(),
            "general ledger uses counterparty and hides product-only quantity");
        check(!frame.status.getText().contains("출처") && !frame.status.getText().contains("15"),
            "product source count stays within product tab instead of ledger footer");
        category.setSelectedItem("외식");
        check(storeLabel.getText().contains("식당") && !quantity.isEnabled(),
            "dining shows a restaurant field without product quantity");
        amount.setText("9500");
        store.setText("동네식당");
        button(frame.ledgerTab, "내역 저장").doClick();
        var dining = repo.snapshot().records().get(5);
        check(s(dining, "category").equals("외식") && s(dining, "store").equals("동네식당")
            && s(dining, "product_id").isEmpty()
            && monthly(repo.snapshot(), "2026-11").get("food").toString().equals("38100"),
            "dining saves restaurant independently of grocery spending");
        kind.setSelectedItem("수입");
        check(category.getItemCount() == 3 && storeLabel.getText().contains("입금처")
            && !quantityField.isVisible(), "income only offers income categories and payer field");
        category.setSelectedItem("급여");
        store.setText("근무처");
        amount.setText("50000");
        quantity.setText("not a purchase quantity");
        button(frame.ledgerTab, "내역 저장").doClick();
        check(s(repo.snapshot().records().get(6), "kind").equals("수입"),
            "hidden purchase quantity cannot block ordinary income");
        var state = repo.snapshot();
        Product milk = state.product("milk");
        PriceObservation current = state.latest().stream().filter(o -> o.productId().equals("milk")).findFirst().orElseThrow();
        frame.ledgerTab.beginPurchase(milk, current);
        check(!kind.isEnabled() && !category.isEnabled() && quantityField.isVisible(),
            "linked product shows package quantity and locks food-expense classification");
        button(frame.ledgerTab, "상품 연결 해제").doClick();
        category.setSelectedItem("외식");
        check(kind.isEnabled() && category.isEnabled() && !quantityField.isVisible() && store.getText().isEmpty(),
            "detaching a product and choosing dining clears inherited retailer");
        frame.ledgerTab.clearRecord();
        var old = current.toMap();
        old.put("end", "2026-11-01");
        frame.ledgerTab.beginPurchase(milk, PriceObservation.fromMap(old));
        check(amount.getText().isEmpty(),
            "expired displayed price is never prefilled as actual payment");
        frame.ledgerTab.clearRecord();
        frame.loadedMonth = "2026-10";
        frame.ledgerTab.clearRecord();
        JTextField date = field(frame.ledgerTab, "recordDate");
        check(date.getText().equals("2026-10-01"), "new entry date belongs to selected ledger month");
        frame.loadedMonth = "2026-11";
        frame.ledgerTab.clearRecord();
        ledger.setRowSelectionInterval(0, 0);
        amount.setText("3333");
        declineNextConfirmation(() -> ledger.setRowSelectionInterval(1, 1));
        check(ledger.getSelectedRow() == 0 && amount.getText().equals("3333"),
            "declining ledger selection change preserves draft without another confirmation");
        frame.ledgerTab.clearRecord();
        JTable draftTable = field(frame.budgetTab, "planTable");
        JTextField draftQuantity = field(frame.budgetTab, "planQty");
        draftTable.setRowSelectionInterval(0, 0);
        draftQuantity.setText("7");
        declineNextConfirmation(() -> draftTable.setRowSelectionInterval(1, 1));
        check(draftTable.getSelectedRow() == 0 && draftQuantity.getText().equals("7"),
            "declining plan selection change preserves quantity without repeated confirmation");
        button(frame.budgetTab, "항목 적용").doClick();
        JComboBox<?> resetStore = field(frame.budgetTab, "planStore");
        check(draftTable.getSelectedRow() == -1 && draftQuantity.getText().equals("1")
            && resetStore.getSelectedItem().equals("전체"),
            "applying a plan item clears editing identity and retailer defaults for the next item");
        frame.budgetTab.loadPlan();

        JComboBox<?> selectedProduct = field(frame.priceTab, "priceProduct");
        JTable compare = field(frame.priceTab, "compareTable");
        JTable history = field(frame.priceTab, "historyTable");
        selectedProduct.setSelectedIndex(1);
        check(compare.getRowCount() == 0 && history.getRowCount() == 0,
            "changing selected product clears both earlier prices and earlier history");
        frame.priceTab.refreshCompare();
        check(compare.getRowCount() == 3 && compare.getValueAt(0, 1).toString().contains("계란"),
            "fresh comparison uses newly selected product");
        compare.setRowSelectionInterval(1, 1);
        JComboBox<?> historyStore = field(frame.priceTab, "historyStore");
        JTextField branch = field(frame.priceTab, "historyBranch");
        check(compare.getValueAt(1, 2).toString().equals(historyStore.getSelectedItem()+" / "+branch.getText())
            && history.getRowCount() == 9, "selected store price and displayed history use the same branch");
        branch.setText("사용자 지정 지점");
        frame.priceTab.refreshCompare();
        check(branch.getText().equals("사용자 지정 지점") && compare.getSelectedRow() == 1,
            "price refresh preserves selected offer and independently selected history branch");
        JTextField condition = field(frame.priceTab, "priceCondition");
        condition.setText("다른 회원 조건");
        check(compare.getRowCount() == 0 && history.getRowCount() == 0,
            "editing price conditions removes previous-condition results");
        condition.setText("일반");

        JTable goals = field(frame.targetTab, "targetTable");
        goals.setRowSelectionInterval(1, 1);
        JLabel targetLabel = field(frame.targetTab, "targetPriceLabel");
        check(targetLabel.getText().contains("계란") && targetLabel.getText().contains("1개"),
            "target editor names selected product and comparison unit");
        var emptyFavorites = repo.snapshot();
        emptyFavorites.array("favorites").clear();
        repo.saveAtomic(emptyFavorites);
        frame.targetTab.refreshTargets();
        JTextField targetPrice = field(frame.targetTab, "targetPrice");
        check(targetPrice.getText().isEmpty() && !targetPrice.isEnabled()
            && !button(frame.targetTab, "목표 저장").isEnabled(), "empty favorites clear and disable target editor");

        var catalog = repo.snapshot();
        catalog.addProduct(new Product("missing", "두부", "가격 미확인 두부", "테스트", "기본형",
            new java.math.BigDecimal("300"), "g", 1));
        repo.saveAtomic(catalog);
        frame.refreshAfterPriceChange();
        JTable products = field(frame.productTab, "productTable");
        int missing = -1;
        for (int i = 0; i < products.getRowCount(); i++)
          if (products.getValueAt(i, 1).equals("가격 미확인 두부")) missing = i;
        check(missing >= 0, "catalog-only product is visible");
        products.setRowSelectionInterval(missing, missing);
        JTextArea details = field(frame.productTab, "productDetails");
        check(products.getValueAt(missing, 4).equals("미확인") && details.getText().contains("가격 미확인"),
            "catalog-only detail contains no fictional store, URL or zero price");
        button(frame.productTab, "출처 원문 열기").doClick();
        check(frame.status.getText().contains("아직 등록된 가격 출처가 없습니다"),
            "missing source gives inline explanation without opening a browser");
        button(frame.productTab, "장보기 추가").doClick();
        JTable plan = field(frame.budgetTab, "planTable");
        check(plan.getValueAt(plan.getRowCount()-1, 6).equals("미확정"),
            "unpriced product can enter a shopping draft with unknown estimate");
        frame.budgetTab.loadPlan();
        button(frame.productTab, "실제 구매 기록").doClick();
        check(amount.getText().isEmpty() && store.getText().isEmpty() && quantityField.isVisible(),
            "unpriced purchase asks for actual payment without inventing a retailer");
        frame.ledgerTab.clearRecord();

        var offer = current.toMap();
        offer.putAll(m("condition", "회원", "start", "2026-11-01", "end", "2026-11-03"));
        var service = new ProductService(repo, new MartAdapter(), null, clock);
        service.manageProducts(m("action", "UPSERT_MANUAL", "product", milk.toMap(), "offer", offer));
        var ended = copyMap(offer);
        ended.putAll(m("condition", "종료 행사", "end", "2026-11-01"));
        service.manageProducts(m("action", "UPSERT_MANUAL", "product", milk.toMap(), "offer", ended));
        JCheckBox active = field(frame.productTab, "promotionsOnly");
        active.doClick();
        check(products.getRowCount() == 1 && products.getValueAt(0, 3).toString().contains("회원")
            && products.getValueAt(0, 7).equals("현재 유효"), "current-promotions switch displays only eligible promotions");
        active.doClick();
        check(products.getRowCount() > 1, "full catalog remains available after promotion filter");
        ledger.getRowSorter().toggleSortOrder(3);
        java.math.BigDecimal previous = java.math.BigDecimal.ZERO;
        for (int i = 0; i < ledger.getRowCount(); i++) {
          java.math.BigDecimal value = new java.math.BigDecimal(ledger.getValueAt(i,3).toString().replace(",", ""));
          if (value.compareTo(previous) < 0) throw new AssertionError("lexical amount ordering");
          previous = value;
        }
        check(true, "formatted amounts sort numerically across all ledger rows");
      } catch (Throwable e) {
        failure[0] = e;
      } finally {
        if (frame != null) frame.dispose();
      }
    });
    if (failure[0] != null) throw new AssertionError("Workflow checks failed", failure[0]);
  }

  private static void declineNextConfirmation(Runnable selection) {
    int[] confirmations = {0};
    javax.swing.Timer answer = new javax.swing.Timer(100, e -> {
      for (Window window : Window.getWindows()) {
        if (window instanceof JDialog dialog && dialog.isShowing()) {
          for (Component component : dialog.getContentPane().getComponents()) {
            if (component instanceof JOptionPane pane) {
              confirmations[0]++;
              pane.setValue(JOptionPane.NO_OPTION);
            }
          }
        }
      }
    });
    answer.start();
    try {
      selection.run();
      if (confirmations[0] != 1) throw new AssertionError("expected one confirmation");
    } finally {
      answer.stop();
    }
  }
}
