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

/**
 * Swing UI01-UI05. User edits stay in forms until explicit save; feed reads use SwingWorker.
 *
 * @serial exclude
 */
public final class LedgerFrame extends JFrame {
  private static final long serialVersionUID = 1L;

  final Repository repo;
  final Clock clock;
  final LedgerTab ledgerTab;
  final BudgetTab budgetTab;
  final ProductTab productTab;
  final PriceTab priceTab;
  final TargetTab targetTab;
  String loadedMonth;
  private final JTabbedPane tabs = new JTabbedPane();
  final JLabel status = new JLabel("준비됨");
  private final List<JButton> collectionButtons = new ArrayList<>();
  private int previousTab;
  private boolean changingTab;
  private final javax.swing.Timer timer;

  /**
   * Creates all five application screens without opening the window.
   *
   * @param repository shared storage
   * @param clock time source
   * @param autoRefresh enables daily configured-feed refresh while the app runs
   */
  public LedgerFrame(Repository repository, Clock clock, boolean autoRefresh) {
    this(repository, clock, autoRefresh, new OnlineCatalog(clock));
  }

  LedgerFrame(Repository repository, Clock clock, boolean autoRefresh, ProductCollector collector) {
    super("Smart Grocery Ledger | Java Swing Beta");
    this.repo = repository;
    this.clock = clock;

    loadedMonth = YearMonth.now(clock).toString();
    var targets = new TargetService(repo, clock);
    ledgerTab = new LedgerTab(this, new LedgerService(repo, clock));
    budgetTab = new BudgetTab(this, new BudgetService(repo, clock));
    productTab = new ProductTab(this, new ProductService(repo, new MartAdapter(), targets, clock),
        new OnlineCollectionService(repo, collector, targets, clock));
    priceTab = new PriceTab(this, new PriceService(repo, clock));
    targetTab = new TargetTab(this, targets);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setSize(1240, 850);
    setMinimumSize(new Dimension(1030, 720));
    setLocationRelativeTo(null);
    getContentPane().setBackground(BG);
    setLayout(new BorderLayout(0, 10));
    JPanel heading = new JPanel(new BorderLayout(20, 5));
    heading.setBackground(INK);
    heading.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
    JLabel title = new JLabel("Smart Grocery Ledger");
    title.setFont(SwingAppearance.heading(25f));
    title.setForeground(Color.WHITE);
    heading.add(title, BorderLayout.WEST);
    JPanel tools = flow();
    tools.setOpaque(false);
    JLabel mode =
        new JLabel(repo.snapshot().demo() ? "시연 모드 · 가상 가격 / 실제 판매가 아님" : "개인 가계부 · 로컬 저장");
    mode.setForeground(new Color(153, 224, 216));
    tools.add(mode);
    tools.add(
        button(
            "Javadoc API 매뉴얼",
            () -> run(() -> Main.openManual(repo.path().getParent().resolve("cache")))));
    heading.add(tools, BorderLayout.EAST);
    add(heading, BorderLayout.NORTH);
    tabs.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
    tabs.addTab("01  가계부", ledgerTab);
    tabs.addTab("02  예산 · 장보기", budgetTab);
    tabs.addTab("03  상품 · 할인", productTab);
    tabs.addTab("04  가격 비교 · 이력", priceTab);
    tabs.addTab("05  구매 목표 · 알림", targetTab);
    for (int i = 0; i < tabs.getTabCount(); i++) {
      tabs.setTabComponentAt(i, new TabTitle(tabs.getTitleAt(i)));
    }
    add(tabs, BorderLayout.CENTER);
    JPanel footer = new JPanel(new BorderLayout());
    footer.setBorder(BorderFactory.createEmptyBorder(4, 22, 12, 22));
    footer.add(status, BorderLayout.CENTER);
    JLabel version = new JLabel(Main.VERSION + "   |   " + repo.path().getFileName());
    version.setToolTipText(repo.path().toString());
    footer.add(version, BorderLayout.EAST);
    add(footer, BorderLayout.SOUTH);
    tabs.addChangeListener(
        e -> {
          if (changingTab) return;
          int next = tabs.getSelectedIndex();
          TabView old = (TabView) tabs.getComponentAt(previousTab);
          if (old.hasDraft()) {
            navigate(previousTab);
            if (!discard("저장하지 않은 입력을 버리고 이동할까요?")) return;
            old.discardDraft();
            navigate(next);
          } else previousTab = next;
        });
    timer =
        new javax.swing.Timer(
            60000,
            e -> {
              if (!productTab.refreshing()
                  && repo.readError().isEmpty()
                  && !repo.snapshot().demo()
                  && !repo.snapshot().value("online_attempt").equals(LocalDate.now(clock).toString()))
                productTab.collectOnline(true);
            });
    if (autoRefresh) timer.start();
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if ((tabHasDraft(0) || tabHasDraft(1) || tabHasDraft(4))
                && !discard("저장하지 않은 입력을 버리고 종료할까요?")) return;
            dispose();
          }
        });
    ledgerTab.clearRecord();
    if (repo.readError().isEmpty()) refreshAll();
    if (!repo.readError().isEmpty())
      SwingUtilities.invokeLater(
          () ->
              error(
                  "데이터 읽기 실패. 파일을 덮어쓰지 않습니다.\n"
                      + repo.readError()
                      + "\n실행 시 --data-dir로 정상 폴더를 지정하세요."));
    if (autoRefresh && !repo.snapshot().demo() && repo.readError().isEmpty())
      SwingUtilities.invokeLater(() -> productTab.collectOnline(true));
  }

  void navigate(int index) {
    changingTab = true;
    try {
      tabs.setSelectedIndex(index);
      previousTab = index;
    } finally {
      changingTab = false;
    }
  }

  private boolean tabHasDraft(int i) {
    return ((TabView) tabs.getComponentAt(i)).hasDraft();
  }

  void fillProducts() {
    AppState state = repo.snapshot();
    budgetTab.setProducts(state.products());
    priceTab.setProducts(state);
  }

  void showText(String title, String text) {
    JTextArea area = details(10);
    area.setText(text);
    area.setCaretPosition(0);
    JScrollPane pane = new JScrollPane(area);
    pane.setPreferredSize(new Dimension(680, 300));
    JOptionPane.showMessageDialog(this, pane, title, JOptionPane.INFORMATION_MESSAGE);
  }

  void refreshAfterPriceChange() {
    fillProducts();
    productTab.refreshProducts();
    budgetTab.updatePlanEstimate();
    priceTab.refreshCompare();
    priceTab.refreshHistory();
    targetTab.refreshTargets();
  }

  void refreshAll() {
    fillProducts();
    ledgerTab.refreshLedger();
    budgetTab.loadPlan();
    productTab.refreshProducts();
    priceTab.refreshCompare();
    priceTab.refreshHistory();
    targetTab.refreshTargets();
  }

  boolean accepted(Map<String, Object> r) {
    String s = s(r, "status");
    if (List.of("OK", "NO_DATA", "CANCELLED").contains(s)) return !s.equals("CANCELLED");
    error(s + ": " + def(r, "message", "입력 또는 선택 항목을 확인하세요."));
    return false;
  }

  void error(String message) {
    status.setText(message);
    JOptionPane.showMessageDialog(this, message, "확인 필요", JOptionPane.WARNING_MESSAGE);
  }

  boolean discard(String message) {
    return JOptionPane.showConfirmDialog(this, message, "입력 확인", JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION;
  }

  @FunctionalInterface
  interface Action {
    void run() throws Exception;
  }

  void run(Action action) {
    try {
      action.run();
    } catch (Exception e) {
      error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  JButton button(String label, Runnable action) {
    JButton b = new JButton(label);
    b.setFocusPainted(false);
    b.addActionListener(e -> run(action::run));
    return b;
  }

  JButton collectionButton() {
    JButton button = button("상품 정보 가져오기", () -> productTab.collectOnline(false));
    collectionButtons.add(button);
    return button;
  }

  void collecting(boolean running) {
    for (JButton button : collectionButtons) {
      button.setEnabled(!running);
      button.setText(running ? "상품 수집 중…" : "상품 정보 가져오기");
    }
  }

  @Override
  public void dispose() {
    timer.stop();
    super.dispose();
  }
}
