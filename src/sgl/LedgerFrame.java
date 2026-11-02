package sgl;

import static sgl.Data.*;

import java.awt.*;
import java.awt.event.*;
import java.math.*;
import java.net.URI;
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
  private static final Color INK = new Color(28, 50, 66),
      ACCENT = new Color(16, 110, 111),
      BG = new Color(245, 248, 250);
  private final Repository repo;
  private final Clock clock;
  private final LedgerService ledger;
  private final BudgetService budget;
  private final ProductService products;
  private final PriceService prices;
  private final TargetService targets;
  private final JTabbedPane tabs = new JTabbedPane();
  private final JLabel status = new JLabel("준비됨");
  private final JTextField month = new JTextField(YearMonth.now().toString(), 8);
  private final JLabel totals = new JLabel();
  private final JLabel planSummary = new JLabel();
  private final JLabel priceSummary = new JLabel();
  private final JLabel targetSummary = new JLabel();
  private final JTextArea productDetails = details(5),
      compareDetails = details(3),
      targetDetails = details(4);
  private final JTable recordTable = table("날짜", "구분", "분류", "금액 (원)", "메모", "마트"),
      planTable = table("상품", "수량", "마트", "지점", "경로", "조건", "예상액 (원)"),
      productTable = table("관심", "상품", "마트 / 지점", "경로 / 조건", "가격 (원)", "단위 가격", "확인 시각", "상태"),
      compareTable = table("순위", "상품", "마트 / 지점", "묶음 가격", "단위 가격", "상태"),
      historyTable = table("확인 날짜", "단위 가격"),
      targetTable = table("상품", "관측일", "90% 기준", "개인 목표", "적용 기준", "현재 충족", "알림", "최근 알림");
  private List<Map<String, Object>> recordRows = new ArrayList<>(),
      productRows = new ArrayList<>(),
      compareRows = new ArrayList<>(),
      targetRows = new ArrayList<>();
  private List<Object> planDraft = new ArrayList<>();
  private boolean planDirty = false,
      recordDirty = false,
      budgetDirty = false,
      planFormDirty = false,
      targetDirty = false,
      updating = false,
      changingTab = false,
      busy = false;
  private int previousTab = 0;
  private String recordId = "", purchaseId = "", planItemId = "", loadedMonth;
  private String editingTargetId = "";
  private final JTextField recordDate = new JTextField(10),
      recordAmount = new JTextField(10),
      recordMemo = new JTextField(23),
      recordStore = new JTextField(10),
      recordQuantity = new JTextField("1", 4);
  private final JComboBox<String> kind = new JComboBox<>(new String[] {"지출", "수입"}),
      recordCategory = new JComboBox<>(new String[] {"식료품", "외식", "교통", "주거", "용돈", "급여", "기타"});
  private final JTextField search = new JTextField(16),
      budgetAmount = new JTextField(9),
      planQty = new JTextField("1", 4),
      planCondition = new JTextField("일반", 6),
      planBranch = new JTextField(7),
      priceCondition = new JTextField("일반", 6),
      historyBranch = new JTextField("전체", 7),
      historyStart = new JTextField(10),
      historyEnd = new JTextField(10),
      targetPrice = new JTextField(9),
      targetCondition = new JTextField("일반", 6);
  private final JComboBox<String>
      searchCategory = combo("전체", "식료품", "외식", "교통", "주거", "용돈", "급여", "기타"),
      filterCategory = all(CATEGORIES),
      filterStore = all(STORES),
      filterChannel = combo("전체", "온라인", "오프라인"),
      planStore = all(STORES),
      planChannel = combo("온라인", "오프라인"),
      priceChannel = combo("온라인", "오프라인"),
      historyStore = new JComboBox<>(STORES.toArray(String[]::new)),
      targetChannel = combo("온라인", "오프라인");
  private final JComboBox<Choice> planProduct = new JComboBox<>(), priceProduct = new JComboBox<>();
  private final JCheckBox targetEnabled = new JCheckBox("앱 안 알림 사용");
  private final HistoryChart chart = new HistoryChart();
  private final javax.swing.Timer timer;

  private record Choice(String id, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  /**
   * Creates all five application screens without opening the window.
   *
   * @param repository shared storage
   * @param clock time source
   * @param autoRefresh enables daily configured-feed refresh while the app runs
   */
  public LedgerFrame(Repository repository, Clock clock, boolean autoRefresh) {
    super("Smart Grocery Ledger | Java Swing Beta");
    this.repo = repository;
    this.clock = clock;
    ledger = new LedgerService(repo, clock);
    budget = new BudgetService(repo, clock);
    targets = new TargetService(repo, clock);
    products = new ProductService(repo, new MartAdapter(), targets, clock);
    prices = new PriceService(repo, clock);
    month.setText(YearMonth.now(clock).toString());
    loadedMonth = month.getText();
    planProduct.setPreferredSize(new Dimension(220, 30));
    priceProduct.setPreferredSize(new Dimension(220, 30));
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
    title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
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
    tabs.addTab("01  가계부", ledgerPage());
    tabs.addTab("02  예산 · 장보기", budgetPage());
    tabs.addTab("03  상품 · 할인", productPage());
    tabs.addTab("04  가격 비교 · 이력", pricePage());
    tabs.addTab("05  구매 목표 · 알림", targetPage());
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
          if (tabHasDraft(previousTab)) {
            changingTab = true;
            tabs.setSelectedIndex(previousTab);
            if (discard("저장하지 않은 입력을 버리고 이동할까요?")) {
              if (previousTab == 0) clearRecord();
              else if (previousTab == 1) loadPlan();
              else {
                targetDirty = false;
                refreshTargets();
              }
              tabs.setSelectedIndex(next);
              previousTab = next;
            }
            changingTab = false;
          } else previousTab = next;
        });
    for (JTextField field :
        List.of(recordDate, recordAmount, recordMemo, recordStore, recordQuantity))
      field
          .getDocument()
          .addDocumentListener(
              change(
                  () -> {
                    if (!updating) recordDirty = true;
                  }));
    kind.addActionListener(
        e -> {
          if (!updating) recordDirty = true;
        });
    recordCategory.addActionListener(
        e -> {
          if (!updating) recordDirty = true;
        });
    budgetAmount
        .getDocument()
        .addDocumentListener(
            change(
                () -> {
                  if (!updating) budgetDirty = true;
                }));
    for (JTextField field : List.of(planQty, planCondition, planBranch))
      field
          .getDocument()
          .addDocumentListener(
              change(
                  () -> {
                    if (!updating) planFormDirty = true;
                  }));
    for (JComboBox<?> box : List.of(planProduct, planStore, planChannel))
      box.addActionListener(
          e -> {
            if (!updating) planFormDirty = true;
          });
    for (JTextField field : List.of(targetPrice, targetCondition))
      field
          .getDocument()
          .addDocumentListener(
              change(
                  () -> {
                    if (!updating) targetDirty = true;
                  }));
    targetEnabled.addActionListener(
        e -> {
          if (!updating) targetDirty = true;
        });
    targetChannel.addActionListener(
        e -> {
          if (!updating) targetDirty = true;
        });
    timer =
        new javax.swing.Timer(
            60000,
            e -> {
              if (!busy
                  && !repo.snapshot().value("auto_attempt").equals(LocalDate.now(clock).toString())
                  && !repo.snapshot().map("feed_paths").isEmpty()) refreshFeed(true);
            });
    if (autoRefresh) timer.start();
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if ((recordDirty || planDirty || budgetDirty || planFormDirty || targetDirty)
                && !discard("저장하지 않은 입력을 버리고 종료할까요?")) return;
            dispose();
          }
        });
    clearRecord();
    if (repo.readError().isEmpty()) refreshAll();
    if (!repo.readError().isEmpty())
      SwingUtilities.invokeLater(
          () ->
              error(
                  "데이터 읽기 실패. 파일을 덮어쓰지 않습니다.\n"
                      + repo.readError()
                      + "\n실행 시 --data-dir로 정상 폴더를 지정하세요."));
    if (autoRefresh && !repo.snapshot().map("feed_paths").isEmpty())
      SwingUtilities.invokeLater(() -> refreshFeed(true));
  }

  private JPanel ledgerPage() {
    JPanel root = page("실제 수입과 지출", "검색 결과와 관계없이 월 합계는 해당 월의 전체 내역을 기준으로 계산합니다.");
    JPanel filters = flow();
    filters.add(new JLabel("월"));
    filters.add(month);
    filters.add(searchCategory);
    filters.add(search);
    filters.add(button("조회", this::changeMonth));
    root.add(filters);
    root.add(metric(totals));
    root.add(scroll(recordTable, 240));
    recordTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && !updating && recordTable.getSelectedRow() >= 0) {
                int index = recordTable.convertRowIndexToModel(recordTable.getSelectedRow());
                if (recordDirty && !discard("입력 중인 내용을 버리고 선택 내역을 불러올까요?")) {
                  restoreSelection(recordTable, recordRows, "id", recordId);
                  return;
                }
                loadRecord(recordRows.get(index));
              }
            });
    JPanel form = new JPanel(new GridLayout(2, 4, 12, 8));
    form.setOpaque(false);
    form.add(field("날짜 YYYY-MM-DD", recordDate));
    form.add(field("구분", kind));
    form.add(field("분류", recordCategory));
    form.add(field("실제 결제 총액 (원)", recordAmount));
    form.add(field("메모", recordMemo));
    form.add(field("마트", recordStore));
    form.add(field("구매 수량", recordQuantity));
    form.add(new JLabel("상품 구매는 식료품 지출로 저장"));
    root.add(form);
    JPanel actions = flow();
    actions.add(button("내역 저장", this::saveRecord));
    actions.add(
        button(
            "새 내역 / 취소",
            () -> {
              if (!recordDirty || discard("입력 내용을 취소할까요?")) clearRecord();
            }));
    actions.add(button("선택 내역 삭제", this::deleteRecord));
    root.add(actions);
    return root;
  }

  private JPanel budgetPage() {
    JPanel root = page("식비 예산과 장보기 계획", "장보기 계획은 실제 지출에 합산되지 않습니다. 수량·예산을 입력한 뒤 각각 저장하세요.");
    JPanel budgetBar = flow();
    budgetBar.add(new JLabel("월 식비 예산: "));
    budgetBar.add(budgetAmount);
    budgetBar.add(new JLabel("원"));
    budgetBar.add(
        button(
            "월 식비 예산 저장",
            () -> {
              var r =
                  budget.manageBudgetPlan(
                      m(
                          "action",
                          "SET_BUDGET",
                          "month",
                          loadedMonth,
                          "budget",
                          budgetAmount.getText(),
                          "draftPlan",
                          planDraft));
              if (accepted(r)) {
                budgetDirty = false;
                showPlan(r);
                refreshLedger();
              }
            }));
    root.add(budgetBar);
    root.add(metric(planSummary));
    root.add(scroll(planTable, 270));
    planTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && !updating && planTable.getSelectedRow() >= 0) {
                if (planFormDirty && !discard("아직 적용하지 않은 항목 입력을 버릴까요?")) {
                  restoreSelection(planTable, rows(planDraft), "itemId", planItemId);
                  return;
                }
                var item =
                    obj(
                        planDraft.get(
                            planTable.convertRowIndexToModel(planTable.getSelectedRow())));
                planItemId = s(item, "itemId");
                updating = true;
                select(planProduct, s(item, "product_id"));
                planQty.setText(s(item, "quantity"));
                planStore.setSelectedItem(s(item, "store").isEmpty() ? "전체" : s(item, "store"));
                planChannel.setSelectedItem(def(item, "channel", "온라인"));
                planCondition.setText(def(item, "condition", "일반"));
                planBranch.setText(s(item, "branch"));
                updating = false;
                planFormDirty = false;
              }
            });
    JPanel form = flow();
    form.add(planProduct);
    form.add(new JLabel("수량"));
    form.add(planQty);
    form.add(planStore);
    form.add(planChannel);
    form.add(planCondition);
    planBranch.setToolTipText("지점 조건: 비우면 해당 마트의 모든 지점");
    form.add(new JLabel("지점"));
    form.add(planBranch);
    form.add(
        button(
            "항목 적용",
            () -> {
              Map<String, Object> req =
                  m(
                      "action",
                      "UPSERT_ITEM",
                      "month",
                      loadedMonth,
                      "draftPlan",
                      planDraft,
                      "product_id",
                      chosen(planProduct),
                      "quantity",
                      planQty.getText(),
                      "store",
                      filter(planStore),
                      "channel",
                      selected(planChannel),
                      "condition",
                      planCondition.getText(),
                      "branch",
                      planBranch.getText());
              if (!planItemId.isEmpty()) req.put("itemId", planItemId);
              var r = budget.manageBudgetPlan(req);
              if (accepted(r)) {
                showPlan(r);
                planDirty = true;
                planFormDirty = false;
                planItemId = "";
                planTable.clearSelection();
              }
            }));
    root.add(form);
    JPanel buttons = flow();
    buttons.add(
        button(
            "선택 항목 삭제",
            () -> {
              int row = planTable.getSelectedRow();
              if (row < 0) {
                error("계획 항목을 선택하세요.");
                return;
              }
              var item = obj(planDraft.get(planTable.convertRowIndexToModel(row)));
              var r =
                  budget.manageBudgetPlan(
                      m(
                          "action",
                          "REMOVE_ITEM",
                          "month",
                          loadedMonth,
                          "draftPlan",
                          planDraft,
                          "itemId",
                          s(item, "itemId")));
              if (accepted(r)) {
                showPlan(r);
                planDirty = true;
                planItemId = "";
              }
            }));
    buttons.add(
        button(
            "계획 저장",
            () -> {
              if (planFormDirty) {
                error("수정한 상품·수량·조건은 먼저 항목 적용을 누른 뒤 계획을 저장하세요.");
                return;
              }
              var r =
                  budget.manageBudgetPlan(
                      m("action", "SAVE_PLAN", "month", loadedMonth, "draftPlan", planDraft));
              if (accepted(r)) {
                planDirty = false;
                showPlan(r);
                status.setText("장보기 계획을 저장했습니다. 실제 지출은 변경되지 않았습니다.");
              }
            }));
    buttons.add(
        button(
            "변경 취소",
            () -> {
              if (!tabHasDraft(1) || discard("저장하지 않은 예산과 계획 입력을 취소할까요?")) loadPlan();
            }));
    root.add(buttons);
    return root;
  }

  private JPanel productPage() {
    JPanel root =
        page("상품과 가격 정보", "우유 · 계란 · 라면 · 쌀 · 두부   /   직접 입력 또는 JSON 피드   /   마트 웹 자동 수집 미구현");
    JPanel filters = flow();
    filters.add(filterCategory);
    filters.add(filterStore);
    filters.add(filterChannel);
    filters.add(button("상품 조회", this::refreshProducts));
    filters.add(button("가격 직접 입력", this::manualDialog));
    filters.add(button("JSON 피드 연결", this::configureFeed));
    filters.add(button("연결 피드 갱신", () -> refreshFeed(false)));
    root.add(filters);
    root.add(scroll(productTable, 270));
    productTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && productTable.getSelectedRow() >= 0)
                productDetails.setText(
                    describeOffer(
                        productRows.get(
                            productTable.convertRowIndexToModel(productTable.getSelectedRow())),
                        true));
            });
    root.add(new JScrollPane(productDetails));
    JPanel buttons = flow();
    buttons.add(
        button(
            "즐겨찾기 전환",
            () -> {
              var row = selectedProduct();
              if (row == null) return;
              var r =
                  products.manageProducts(
                      m(
                          "action",
                          "SET_FAVORITE",
                          "product_id",
                          s(row, "product_id"),
                          "isFavorite",
                          !yes(row, "favorite")));
              if (accepted(r)) {
                refreshProducts();
                refreshTargets();
              }
            }));
    buttons.add(
        button(
            "출처 원문 열기",
            () -> {
              var row = selectedProduct();
              if (row != null) openSource(row);
            }));
    buttons.add(
        button(
            "출처 주소 복사",
            () -> {
              var row = selectedProduct();
              if (row != null) {
                Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(s(row, "url")), null);
                status.setText("선택 상품의 출처 주소를 복사했습니다.");
              }
            }));
    buttons.add(button("실제 구매 기록", this::purchase));
    buttons.add(button("장보기 추가", this::addProductToPlan));
    root.add(buttons);
    JTextArea note =
        new JTextArea(
            "가격은 배송비를 제외한 상품 금액입니다. 회원·쿠폰·행사 조건을 구분해 비교하세요.\n"
                + "확인 후 24시간이 지났거나 확인 시각이 없으면 현재 비교와 예상액에서 제외됩니다.\n"
                + "피드 갱신은 원래 확인 시각을 유지합니다. 시연 가격은 실제 판매가가 아닙니다.");
    note.setEditable(false);
    note.setOpaque(false);
    root.add(note);
    return root;
  }

  private JPanel pricePage() {
    JPanel root =
        page(
            "같은 조건의 단위 가격 비교",
            "우유 100ml · 계란 1개 · 라면 1봉 · 쌀/두부 100g 기준. 브랜드·세부 규격·경로·가격 조건을 맞춥니다.");
    JPanel filters = flow();
    filters.add(priceProduct);
    filters.add(priceChannel);
    filters.add(priceCondition);
    filters.add(button("단위 가격 비교", this::refreshCompare));
    root.add(filters);
    root.add(scroll(compareTable, 145));
    compareTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && compareTable.getSelectedRow() >= 0)
                compareDetails.setText(
                    describeOffer(
                        compareRows.get(
                            compareTable.convertRowIndexToModel(compareTable.getSelectedRow())),
                        false));
            });
    root.add(new JScrollPane(compareDetails));
    JPanel history = flow();
    history.add(historyStore);
    history.add(historyBranch);
    historyStart.setText(LocalDate.now(clock).minusDays(28).toString());
    historyEnd.setText(LocalDate.now(clock).toString());
    history.add(historyStart);
    history.add(new JLabel("~"));
    history.add(historyEnd);
    history.add(button("가격 이력 조회", this::refreshHistory));
    root.add(history);
    root.add(metric(priceSummary));
    JPanel plots = new JPanel(new GridLayout(1, 2, 12, 0));
    plots.setOpaque(false);
    plots.add(scroll(historyTable, 170));
    chart.setPreferredSize(new Dimension(430, 170));
    plots.add(chart);
    root.add(plots);
    return root;
  }

  private JPanel targetPage() {
    JPanel root =
        page("구매 기준과 목표 알림", "직전 28일의 날짜별 평균 × 90% · 최소 관측 4일 · 개인 목표 우선 · 상품별 하루 한 번 앱 안 알림");
    root.add(metric(targetSummary));
    root.add(scroll(targetTable, 240));
    targetTable.setDefaultRenderer(
        Object.class,
        new DefaultTableCellRenderer() {
          @Override
          public Component getTableCellRendererComponent(
              JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            Component c =
                super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            if (!selected) {
              boolean reached =
                  !Data.list(targetRows.get(table.convertRowIndexToModel(row)).get("matches"))
                      .isEmpty();
              c.setBackground(reached ? new Color(220, 241, 226) : Color.WHITE);
              c.setForeground(INK);
            }
            return c;
          }
        });
    targetTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && !updating && targetTable.getSelectedRow() >= 0) {
                var row =
                    targetRows.get(
                        targetTable.convertRowIndexToModel(targetTable.getSelectedRow()));
                if (targetDirty
                    && !s(row, "product_id").equals(editingTargetId)
                    && !discard("저장하지 않은 목표 설정을 버릴까요?")) {
                  restoreSelection(targetTable, targetRows, "product_id", editingTargetId);
                  return;
                }
                loadTargetForm(row);
              }
            });
    JPanel form = flow();
    form.add(new JLabel("선택 상품의 단위 목표가"));
    form.add(targetPrice);
    form.add(targetChannel);
    form.add(targetCondition);
    form.add(targetEnabled);
    form.add(button("목표 저장", this::saveTarget));
    root.add(form);
    JPanel actions = flow();
    actions.add(button("목표 조회", this::refreshTargets));
    actions.add(
        button(
            "현재 가격으로 알림 확인",
            () -> {
              var r =
                  targets.managePurchaseTargets(
                      m("action", "EVALUATE", "product_ids", repo.snapshot().array("favorites")));
              if (accepted(r)) {
                notifyTargets(r);
                refreshTargets();
              }
            }));
    root.add(actions);
    root.add(new JScrollPane(targetDetails));
    root.add(new JLabel("목표가를 비우고 저장하면 4주 기준을 사용합니다. 앱을 종료한 동안에는 알림이 발생하지 않습니다."));
    return root;
  }

  private void changeMonth() {
    run(
        () -> {
          String requested = month(month.getText());
          if (!requested.equals(loadedMonth)
              && (recordDirty || planDirty || budgetDirty || planFormDirty)
              && !discard("월을 변경하면 저장하지 않은 입력과 계획을 취소합니다. 계속할까요?")) {
            month.setText(loadedMonth);
            return;
          }
          if (!requested.equals(loadedMonth)) {
            loadedMonth = requested;
            clearRecord();
            loadPlan();
          }
          refreshLedger();
        });
  }

  private void refreshLedger() {
    var r =
        ledger.manageLedger(
            m(
                "action",
                "SEARCH",
                "month",
                loadedMonth,
                "category",
                filter(searchCategory),
                "keyword",
                search.getText()));
    if (!accepted(r)) return;
    recordRows = rows(r.get("records"));
    updating = true;
    clear(recordTable);
    for (var x : recordRows)
      add(
          recordTable,
          s(x, "date"),
          s(x, "kind"),
          s(x, "category"),
          money(x.get("amount")),
          s(x, "memo"),
          s(x, "store"));
    updating = false;
    var a = obj(r.get("summary"));
    totals.setText(
        "<html>"
            + loadedMonth
            + "  &nbsp; 수입 <b>"
            + money(a.get("income"))
            + "</b>원 &nbsp; 지출 <b>"
            + money(a.get("expense"))
            + "</b>원 &nbsp; 잔액 <b>"
            + money(a.get("balance"))
            + "</b>원 &nbsp; 식비 <b>"
            + money(a.get("food"))
            + "</b>원<br>분류별 지출: "
            + html(categoryText(obj(a.get("categories"))))
            + "</html>");
  }

  private String categoryText(Map<String, Object> cats) {
    StringJoiner j = new StringJoiner("  ·  ");
    for (var e : cats.entrySet()) j.add(e.getKey() + " " + money(e.getValue()) + "원");
    return j.toString();
  }

  private void clearRecord() {
    updating = true;
    recordId = "";
    purchaseId = "";
    recordDate.setText(LocalDate.now(clock).toString());
    recordAmount.setText("");
    recordMemo.setText("");
    recordStore.setText("");
    recordQuantity.setText("1");
    kind.setSelectedItem("지출");
    recordCategory.setSelectedItem("식료품");
    recordTable.clearSelection();
    updating = false;
    recordDirty = false;
  }

  private void loadRecord(Map<String, Object> r) {
    updating = true;
    recordId = s(r, "id");
    purchaseId = s(r, "product_id");
    recordDate.setText(s(r, "date"));
    recordAmount.setText(s(r, "amount"));
    recordMemo.setText(s(r, "memo"));
    recordStore.setText(s(r, "store"));
    recordQuantity.setText(s(r, "quantity"));
    kind.setSelectedItem(s(r, "kind"));
    recordCategory.setSelectedItem(s(r, "category"));
    updating = false;
    recordDirty = false;
  }

  private void saveRecord() {
    var r =
        ledger.manageLedger(
            m(
                "action",
                recordId.isEmpty() ? "CREATE" : "UPDATE",
                "recordId",
                recordId,
                "date",
                recordDate.getText(),
                "kind",
                selected(kind),
                "category",
                selected(recordCategory),
                "amount",
                recordAmount.getText(),
                "memo",
                recordMemo.getText(),
                "store",
                recordStore.getText(),
                "product_id",
                purchaseId,
                "quantity",
                recordQuantity.getText()));
    if (accepted(r)) {
      clearRecord();
      refreshLedger();
      updatePlanEstimate();
      status.setText("실제 결제 내역을 저장했습니다.");
    }
  }

  private void deleteRecord() {
    if (recordId.isEmpty()) {
      error("삭제할 내역을 선택하세요.");
      return;
    }
    if (!discard("선택한 내역을 삭제할까요?")) return;
    var r = ledger.manageLedger(m("action", "DELETE", "recordId", recordId, "confirmed", true));
    if (accepted(r)) {
      clearRecord();
      refreshLedger();
      updatePlanEstimate();
    }
  }

  private void loadPlan() {
    var r = budget.manageBudgetPlan(m("action", "GET", "month", loadedMonth));
    if (accepted(r)) {
      showPlan(r);
      planDirty = false;
      planItemId = "";
      updating = true;
      Object amount = obj(r.get("summary")).get("budget");
      budgetAmount.setText(amount == null ? "" : amount.toString());
      planQty.setText("1");
      planStore.setSelectedItem("전체");
      planChannel.setSelectedItem("온라인");
      planCondition.setText("일반");
      planBranch.setText("");
      updating = false;
      budgetDirty = false;
      planFormDirty = false;
    }
  }

  private void updatePlanEstimate() {
    var r =
        budget.manageBudgetPlan(m("action", "GET", "month", loadedMonth, "draftPlan", planDraft));
    if (accepted(r)) showPlan(r);
  }

  private void showPlan(Map<String, Object> r) {
    planDraft = Data.list(r.get("draftPlan"));
    updating = true;
    clear(planTable);
    for (var item : rows(r.get("items")))
      add(
          planTable,
          s(item, "name"),
          s(item, "quantity"),
          s(item, "store").isEmpty() ? "최저 유효가" : s(item, "store"),
          s(item, "branch").isEmpty() ? "전체 지점" : s(item, "branch"),
          def(item, "channel", "온라인"),
          def(item, "condition", "일반"),
          money(item.get("estimate")));
    restoreSelection(planTable, rows(planDraft), "itemId", planItemId);
    updating = false;
    var a = obj(r.get("summary"));
    planSummary.setText(
        "<html>"
            + loadedMonth
            + " 예산 <b>"
            + (a.get("budget") == null ? "미설정" : money(a.get("budget")) + "원")
            + "</b> · 식비 잔여 <b>"
            + money(a.get("remaining"))
            + "</b>원<br>확인된 소계 <b>"
            + money(r.get("validSubtotal"))
            + "</b>원 · 가격 미확정 "
            + r.get("unknownCount")
            + "개<br>계획 총액 <b>"
            + money(r.get("totalEstimate"))
            + "</b>원 · 예상 잔액 <b>"
            + money(r.get("projectedBalance"))
            + "</b>원 · 초과액 <b>"
            + money(r.get("excess"))
            + "</b>원</html>");
  }

  private void refreshProducts() {
    var r =
        products.manageProducts(
            m(
                "action",
                "QUERY",
                "category",
                filter(filterCategory),
                "store",
                filter(filterStore),
                "channel",
                filter(filterChannel)));
    if (!accepted(r)) return;
    productRows = rows(r.get("offers"));
    clear(productTable);
    for (var o : productRows) {
      var p = obj(o.get("product"));
      add(
          productTable,
          yes(o, "favorite") ? "★" : "",
          s(p, "name"),
          s(o, "store") + " / " + s(o, "branch"),
          s(o, "channel") + " / " + def(o, "condition", "일반"),
          money(o.get("price")),
          money(o.get("unit_price")) + " / " + unitLabel(p),
          s(o, "checked_at"),
          s(o, "validity"));
    }
    StringJoiner states = new StringJoiner(" | ");
    for (var e : obj(r.get("sources")).entrySet()) {
      var source = obj(e.getValue());
      states.add(
          e.getKey()
              + ": "
              + (yes(source, "failed") ? "갱신 실패" : "성공")
              + " / 최근 성공 "
              + (s(source, "last_success").isEmpty() ? "없음" : s(source, "last_success")));
    }
    status.setText(
        states.length() > 0
            ? "가격 정보 " + productRows.size() + "개 · 출처별 갱신 결과와 최근 성공 시각은 선택 상품 상세에서 확인"
            : "가격 정보 " + productRows.size() + "개 · 직접 입력 / JSON 피드");
    status.setToolTipText(states.toString());
    productDetails.setText(
        productRows.isEmpty()
            ? "조회 결과가 없습니다. 조건을 바꾸거나 가격을 직접 입력하세요."
            : "상품 행을 선택하면 규격·행사·출처를 확인할 수 있습니다.");
    if (!productRows.isEmpty()) productTable.setRowSelectionInterval(0, 0);
  }

  private Map<String, Object> selectedProduct() {
    int row = productTable.getSelectedRow();
    if (row < 0) {
      error("상품 가격을 선택하세요.");
      return null;
    }
    return productRows.get(productTable.convertRowIndexToModel(row));
  }

  private void purchase() {
    var row = selectedProduct();
    if (row == null) return;
    if (recordDirty && !discard("가계부의 미저장 입력을 버리고 구매를 기록할까요?")) return;
    clearRecord();
    purchaseId = s(row, "product_id");
    recordMemo.setText(s(obj(row.get("product")), "name"));
    recordStore.setText(s(row, "store"));
    recordAmount.setText(s(row, "price"));
    changingTab = true;
    tabs.setSelectedIndex(0);
    previousTab = 0;
    changingTab = false;
    recordDirty = true;
    status.setText("실제 결제 총액과 수량을 확인한 뒤 내역 저장을 누르세요. 배송비는 직접 반영하세요.");
  }

  private void addProductToPlan() {
    var row = selectedProduct();
    if (row == null) return;
    var r =
        budget.manageBudgetPlan(
            m(
                "action",
                "UPSERT_ITEM",
                "month",
                loadedMonth,
                "draftPlan",
                planDraft,
                "product_id",
                s(row, "product_id"),
                "quantity",
                1,
                "store",
                s(row, "store"),
                "branch",
                s(row, "branch"),
                "channel",
                s(row, "channel"),
                "condition",
                def(row, "condition", "일반")));
    if (accepted(r)) {
      showPlan(r);
      planDirty = true;
      changingTab = true;
      tabs.setSelectedIndex(1);
      previousTab = 1;
      changingTab = false;
      status.setText("선택 상품을 장보기 초안에 추가했습니다. 계획 저장 전에는 파일을 변경하지 않습니다.");
    }
  }

  private void configureFeed() {
    String store =
        (String)
            JOptionPane.showInputDialog(
                this,
                "피드를 연결할 마트",
                "JSON 피드 연결",
                JOptionPane.PLAIN_MESSAGE,
                null,
                STORES.toArray(),
                STORES.get(0));
    if (store == null) return;
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("products와 offers를 포함한 JSON 파일");
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      var r =
          products.manageProducts(
              m(
                  "action",
                  "CONFIGURE_FEED",
                  "store",
                  store,
                  "path",
                  chooser.getSelectedFile().getAbsolutePath()));
      if (accepted(r)) status.setText(store + " 피드를 연결했습니다. 갱신 버튼으로 불러오세요.");
    }
  }

  private void refreshFeed(boolean auto) {
    if (busy) return;
    if (repo.snapshot().map("feed_paths").isEmpty()) {
      status.setText("연결된 JSON 피드가 없습니다. 가격 직접 입력 또는 피드 연결을 사용하세요.");
      return;
    }
    busy = true;
    status.setText("가격 정보를 읽는 중...");
    new SwingWorker<Map<String, Object>, Void>() {
      @Override
      protected Map<String, Object> doInBackground() {
        return products.manageProducts(m("action", auto ? "AUTO_REFRESH" : "REFRESH"));
      }

      @Override
      protected void done() {
        busy = false;
        try {
          var r = get();
          fillProducts();
          refreshProducts();
          updatePlanEstimate();
          refreshCompare();
          refreshHistory();
          refreshTargets();
          String st = s(r, "status");
          if (!List.of("OK", "PARTIAL_FAILURE", "REFRESH_FAILED").contains(st)) accepted(r);
          else if (!st.equals("OK"))
            status.setText("일부 출처 갱신 실패: " + Json.stringify(r.get("failures")));
          else status.setText("피드 갱신 완료 · 원래 확인 시각을 유지했습니다.");
          if (r.get("target_result") instanceof Map<?, ?>)
            notifyTargets(obj(r.get("target_result")));
        } catch (Exception e) {
          error("갱신 실패: " + e.getMessage());
        }
      }
    }.execute();
  }

  private void fillProducts() {
    AppState st = repo.snapshot();
    boolean previous = updating;
    updating = true;
    for (JComboBox<Choice> box : List.of(planProduct, priceProduct)) {
      String selected = chosen(box);
      box.removeAllItems();
      for (Object o : st.map("products").values()) {
        var p = obj(o);
        box.addItem(new Choice(s(p, "id"), s(p, "name")));
      }
      select(box, selected);
    }
    if (st.demo() && historyBranch.getText().equals("전체")) historyBranch.setText("시연 지점");
    updating = previous;
  }

  private void refreshCompare() {
    if (priceProduct.getItemCount() == 0) {
      clear(compareTable);
      return;
    }
    var r =
        prices.analyzePrices(
            m(
                "mode",
                "COMPARE",
                "product_id",
                chosen(priceProduct),
                "channel",
                selected(priceChannel),
                "condition",
                priceCondition.getText()));
    if (!accepted(r)) return;
    compareRows = new ArrayList<>();
    clear(compareTable);
    int rank = 1;
    for (var o : rows(r.get("ranked"))) {
      compareRows.add(o);
      add(
          compareTable,
          rank++,
          s(o, "name"),
          s(o, "store") + " / " + s(o, "branch"),
          money(o.get("price")),
          money(o.get("unit_price"))
              + " / "
              + unitLabel(repo.snapshot().product(s(o, "product_id"))),
          s(o, "validity"));
    }
    for (var o : rows(r.get("excluded"))) {
      compareRows.add(o);
      add(
          compareTable,
          "제외",
          s(o, "name"),
          s(o, "store") + " / " + s(o, "branch"),
          money(o.get("price")),
          money(o.get("unit_price"))
              + " / "
              + unitLabel(repo.snapshot().product(s(o, "product_id"))),
          s(o, "validity"));
    }
    compareDetails.setText(compareRows.isEmpty() ? "비교 가능한 가격이 없습니다. 상품과 판매 경로·조건을 확인하세요." : "");
    if (!compareRows.isEmpty()) compareTable.setRowSelectionInterval(0, 0);
  }

  private void refreshHistory() {
    if (priceProduct.getItemCount() == 0) {
      clear(historyTable);
      return;
    }
    var r =
        prices.analyzePrices(
            m(
                "mode",
                "HISTORY",
                "product_id",
                chosen(priceProduct),
                "store",
                selected(historyStore),
                "branch",
                historyBranch.getText(),
                "channel",
                selected(priceChannel),
                "condition",
                priceCondition.getText(),
                "start",
                historyStart.getText(),
                "end",
                historyEnd.getText()));
    if (!accepted(r)) return;
    clear(historyTable);
    for (var row : rows(r.get("rows")))
      add(historyTable, s(row, "date"), money(row.get("unit_price")));
    priceSummary.setText(
        "관측 "
            + r.get("count")
            + "일 · 최저 "
            + money(r.get("min"))
            + " · 최고 "
            + money(r.get("max"))
            + " · 평균 "
            + money(r.get("mean"))
            + "원 / "
            + unitLabel(repo.snapshot().product(chosen(priceProduct))));
    chart.setRows(rows(r.get("rows")));
  }

  private void refreshTargets() {
    var r =
        targets.managePurchaseTargets(
            m("action", "VIEW", "product_ids", repo.snapshot().array("favorites")));
    if (!accepted(r)) return;
    targetRows = rows(r.get("items"));
    updating = true;
    clear(targetTable);
    for (var row : targetRows) {
      var t = obj(row.get("target"));
      add(
          targetTable,
          s(row, "name"),
          s(row, "days"),
          money(row.get("basis")),
          money(t.get("price")),
          money(row.get("applied")),
          Data.list(row.get("matches")).isEmpty() ? "미충족" : "충족",
          yes(t, "enabled") ? "켜짐" : "꺼짐",
          s(row, "last_alert"));
    }
    restoreSelection(targetTable, targetRows, "product_id", editingTargetId);
    updating = false;
    if (!targetDirty) {
      if (targetTable.getSelectedRow() >= 0)
        loadTargetForm(
            targetRows.get(targetTable.convertRowIndexToModel(targetTable.getSelectedRow())));
      else if (!targetRows.isEmpty()) targetTable.setRowSelectionInterval(0, 0);
      else {
        editingTargetId = "";
        targetDetails.setText("즐겨찾기 상품을 등록하면 구매 기준과 목표를 설정할 수 있습니다.");
      }
    }
    targetSummary.setText(
        "즐겨찾기 "
            + targetRows.size()
            + "개 · 기준 기간 "
            + LocalDate.now(clock).minusDays(28)
            + " ~ "
            + LocalDate.now(clock).minusDays(1));
  }

  private void saveTarget() {
    int row = targetTable.getSelectedRow();
    if (row < 0) {
      error("목표를 설정할 즐겨찾기 상품을 선택하세요.");
      return;
    }
    String pid = s(targetRows.get(targetTable.convertRowIndexToModel(row)), "product_id");
    var r =
        targets.managePurchaseTargets(
            m(
                "action",
                "SET_TARGET",
                "product_id",
                pid,
                "price",
                targetPrice.getText(),
                "enabled",
                targetEnabled.isSelected(),
                "channel",
                selected(targetChannel),
                "condition",
                targetCondition.getText()));
    if (accepted(r)) {
      targetDirty = false;
      refreshTargets();
    }
  }

  private void notifyTargets(Map<String, Object> r) {
    if (!s(r, "status").equals("OK")) {
      status.setText("가격은 저장됐지만 알림 이력 저장에 실패했습니다. 알림을 발행하지 않았습니다.");
      return;
    }
    List<Object> notes = Data.list(r.getOrDefault("notifications", new ArrayList<>()));
    if (!notes.isEmpty()) {
      showText("구매 목표 알림", notificationText(notes));
    }
  }

  private String notificationText(List<Object> notes) {
    StringJoiner text = new StringJoiner("\n\n");
    for (var n : rows(notes)) {
      StringBuilder detail = new StringBuilder(s(n, "name") + " : 목표 단위 가격 도달");
      for (var offer : rows(n.get("matches"))) {
        var p = repo.snapshot().product(s(offer, "product_id"));
        detail
            .append("\n")
            .append(s(offer, "store"))
            .append(" / ")
            .append(s(offer, "branch"))
            .append(" · ")
            .append(money(offer.get("unit_price")))
            .append("원 / ")
            .append(unitLabel(p))
            .append(" (상품 ")
            .append(money(offer.get("price")))
            .append("원)")
            .append("\n")
            .append(s(offer, "url"));
      }
      text.add(detail);
    }
    return text + "\n\n당일 같은 상품은 다시 알리지 않습니다. 주소를 선택해 복사할 수 있습니다.";
  }

  private void loadTargetForm(Map<String, Object> row) {
    updating = true;
    var t = obj(row.get("target"));
    editingTargetId = s(row, "product_id");
    targetPrice.setText(s(t, "price"));
    targetEnabled.setSelected(yes(t, "enabled"));
    targetChannel.setSelectedItem(def(t, "channel", "온라인"));
    targetCondition.setText(def(t, "condition", "일반"));
    updating = false;
    targetDirty = false;
    var p = repo.snapshot().product(editingTargetId);
    String explanation =
        row.get("basis") == null
            ? "관측 4일 미만: 평균 기준을 만들지 않습니다. 개인 목표가 있으면 우선 적용합니다."
            : "관측일 평균 " + money(row.get("mean")) + "원 × 90% = " + money(row.get("basis")) + "원";
    StringJoiner matches = new StringJoiner(" · ");
    for (var offer : rows(row.get("matches")))
      matches.add(s(offer, "store") + " " + money(offer.get("unit_price")) + "원");
    targetDetails.setText(
        s(row, "name")
            + " / "
            + unitLabel(p)
            + " 단위\n"
            + explanation
            + "\n"
            + (matches.length() == 0
                ? "현재 기준 충족 가격 없음: 유효성·판매 경로·조건·목표를 확인하세요."
                : "기준 충족: " + matches)
            + "\n미확인·만료·오래됨·갱신 실패 가격은 비교와 알림에서 제외합니다.");
  }

  private String describeOffer(Map<String, Object> offer, boolean sourceState) {
    var p = repo.snapshot().product(s(offer, "product_id"));
    String text =
        s(p, "name")
            + " · "
            + s(p, "brand")
            + " / "
            + s(p, "spec")
            + " / "
            + s(p, "size")
            + s(p, "unit")
            + " × "
            + s(p, "pack_count")
            + "묶음\n"
            + s(offer, "store")
            + " / "
            + s(offer, "branch")
            + " · "
            + s(offer, "channel")
            + " / "
            + def(offer, "condition", "일반")
            + " · "
            + money(offer.get("price"))
            + "원 ("
            + money(offer.get("unit_price"))
            + "원 / "
            + unitLabel(p)
            + ")\n행사 "
            + (s(offer, "start").isEmpty() ? "시작일 없음" : s(offer, "start"))
            + " ~ "
            + (s(offer, "end").isEmpty() ? "종료일 없음" : s(offer, "end"))
            + " · 확인 "
            + (s(offer, "checked_at").isEmpty()
                ? s(offer, "checked_date") + " (시각 없음)"
                : s(offer, "checked_at"))
            + " · "
            + s(offer, "method")
            + "\n출처 "
            + s(offer, "url");
    if (sourceState) {
      Object state = repo.snapshot().map("sources").get(s(offer, "store"));
      if (state instanceof Map<?, ?>) {
        var source = obj(state);
        text +=
            "\n피드 최근 성공 "
                + (s(source, "last_success").isEmpty() ? "없음" : s(source, "last_success"))
                + (yes(source, "failed") ? " · 갱신 실패: " + s(source, "error") : " · 갱신 성공");
      } else text += "\n연결 피드 갱신 기록 없음 · 직접 확인 가격은 자체 확인 시각으로 판정합니다.";
    }
    return text;
  }

  private void openSource(Map<String, Object> row) {
    try {
      if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        throw new IllegalStateException("브라우저 실행을 지원하지 않습니다.");
      Desktop.getDesktop().browse(URI.create(url(row.get("url"))));
    } catch (Exception e) {
      showText(
          "링크를 열지 못했습니다",
          "다음 주소를 선택해 복사하거나 출처 주소 복사 버튼을 사용하세요.\n" + s(row, "url") + "\n" + e.getMessage());
    }
  }

  private void showText(String title, String text) {
    JTextArea area = details(10);
    area.setText(text);
    area.setCaretPosition(0);
    JScrollPane pane = new JScrollPane(area);
    pane.setPreferredSize(new Dimension(680, 300));
    JOptionPane.showMessageDialog(this, pane, title, JOptionPane.INFORMATION_MESSAGE);
  }

  private boolean tabHasDraft(int tab) {
    return tab == 0
        ? recordDirty
        : tab == 1 ? planDirty || budgetDirty || planFormDirty : tab == 4 && targetDirty;
  }

  private void restoreSelection(
      JTable table, List<Map<String, Object>> rows, String key, String id) {
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

  private void manualDialog() {
    JDialog dialog = new JDialog(this, "가격 직접 입력", true);
    JPanel panel = new JPanel(new GridLayout(0, 2, 12, 7));
    panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
    Map<String, JTextField> fields = new LinkedHashMap<>();
    String[][] labels = {
      {"id", "상품 ID (기존 ID의 규격은 변경 불가)"},
      {"name", "상품명"},
      {"brand", "브랜드"},
      {"spec", "세부 규격"},
      {"size", "낱개 용량 / 개수"},
      {"pack_count", "묶음 수"},
      {"branch", "지점"},
      {"price", "상품 전체 가격 (원)"},
      {"condition", "가격 조건"},
      {"url", "출처 URL"},
      {"checked_date", "확인 날짜 YYYY-MM-DD"},
      {"checked_at", "확인 시각 (시간대 포함, 없으면 빈칸)"},
      {"start", "행사 시작일 (선택)"},
      {"end", "행사 종료일 (선택)"}
    };
    JComboBox<String> category = new JComboBox<>(CATEGORIES.toArray(String[]::new)),
        store = new JComboBox<>(STORES.toArray(String[]::new)),
        channel = combo("온라인", "오프라인");
    panel.add(new JLabel("품목 (ml / 개 / 봉 / g 자동 적용)"));
    panel.add(category);
    panel.add(new JLabel("마트"));
    panel.add(store);
    panel.add(new JLabel("판매 경로"));
    panel.add(channel);
    for (String[] pair : labels) {
      JTextField input = new JTextField(24);
      fields.put(pair[0], input);
      panel.add(new JLabel(pair[1]));
      panel.add(input);
    }
    fields.get("id").setText("product-" + id().substring(0, 8));
    fields.get("pack_count").setText("1");
    fields.get("branch").setText("전체");
    fields.get("condition").setText("일반");
    fields.get("checked_date").setText(LocalDate.now(clock).toString());
    fields.get("checked_at").setText(OffsetDateTime.now(clock).withNano(0).toString());
    int selected = productTable.getSelectedRow();
    if (selected >= 0) {
      var row = productRows.get(productTable.convertRowIndexToModel(selected));
      var p = obj(row.get("product"));
      category.setSelectedItem(s(p, "category"));
      store.setSelectedItem(s(row, "store"));
      channel.setSelectedItem(s(row, "channel"));
      for (String k : List.of("id", "name", "brand", "spec", "size", "pack_count"))
        fields.get(k).setText(s(p, k));
      for (String k : List.of("branch", "price", "condition", "url", "start", "end"))
        fields.get(k).setText(s(row, k));
    }
    JPanel actions = flow();
    actions.add(
        button(
            "가격 저장",
            () -> {
              Map<String, Object>
                  p = m("category", selected(category), "unit", unit(selected(category))),
                  o = m("store", selected(store), "channel", selected(channel));
              for (var e : fields.entrySet()) {
                if (List.of("id", "name", "brand", "spec", "size", "pack_count")
                    .contains(e.getKey())) p.put(e.getKey(), e.getValue().getText());
                else o.put(e.getKey(), e.getValue().getText());
              }
              var r =
                  products.manageProducts(m("action", "UPSERT_MANUAL", "product", p, "offer", o));
              if (accepted(r)) {
                dialog.dispose();
                fillProducts();
                refreshProducts();
                updatePlanEstimate();
                refreshCompare();
                refreshHistory();
                refreshTargets();
                if (r.get("target_result") instanceof Map<?, ?>)
                  notifyTargets(obj(r.get("target_result")));
              }
            }));
    actions.add(button("취소", dialog::dispose));
    dialog.add(new JScrollPane(panel));
    dialog.add(actions, BorderLayout.SOUTH);
    dialog.pack();
    dialog.setSize(740, Math.min(780, Toolkit.getDefaultToolkit().getScreenSize().height - 80));
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
  }

  private void refreshAll() {
    fillProducts();
    refreshLedger();
    loadPlan();
    refreshProducts();
    refreshCompare();
    refreshHistory();
    refreshTargets();
  }

  private boolean accepted(Map<String, Object> r) {
    String s = s(r, "status");
    if (List.of("OK", "NO_DATA", "CANCELLED").contains(s)) return !s.equals("CANCELLED");
    error(s + ": " + def(r, "message", "입력 또는 선택 항목을 확인하세요."));
    return false;
  }

  private void error(String message) {
    status.setText(message);
    JOptionPane.showMessageDialog(this, message, "확인 필요", JOptionPane.WARNING_MESSAGE);
  }

  private boolean discard(String message) {
    return JOptionPane.showConfirmDialog(this, message, "입력 확인", JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION;
  }

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }

  private void run(Action action) {
    try {
      action.run();
    } catch (Exception e) {
      error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private JButton button(String label, Runnable action) {
    JButton b = new JButton(label);
    b.setFocusPainted(false);
    b.addActionListener(e -> run(action::run));
    return b;
  }

  private static JPanel flow() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
    p.setOpaque(false);
    return p;
  }

  private static JPanel field(String label, JComponent input) {
    JPanel p = new JPanel(new BorderLayout(0, 4));
    p.setOpaque(false);
    p.add(new JLabel(label), BorderLayout.NORTH);
    p.add(input);
    return p;
  }

  private static JPanel metric(JLabel label) {
    JPanel p = new JPanel(new BorderLayout());
    p.setBackground(new Color(231, 242, 241));
    p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    label.setForeground(ACCENT);
    p.add(label);
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
    return p;
  }

  private static JPanel page(String title, String hint) {
    JPanel p =
        new JPanel() {
          private static final long serialVersionUID = 1L;

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
    t.setFont(t.getFont().deriveFont(Font.BOLD, 20f));
    t.setForeground(INK);
    p.add(t);
    p.add(Box.createVerticalStrut(6));
    JLabel h = new JLabel(hint);
    h.setForeground(new Color(90, 105, 115));
    p.add(h);
    p.add(Box.createVerticalStrut(12));
    return p;
  }

  private static JTable table(String... cols) {
    JTable t =
        new JTable(
            new DefaultTableModel(cols, 0) {
              private static final long serialVersionUID = 1L;

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

  private static JScrollPane scroll(JTable table, int height) {
    JScrollPane s = new JScrollPane(table);
    s.setPreferredSize(new Dimension(1000, height));
    s.setMaximumSize(new Dimension(Integer.MAX_VALUE, height + 70));
    return s;
  }

  private static void clear(JTable t) {
    ((DefaultTableModel) t.getModel()).setRowCount(0);
  }

  private static void add(JTable t, Object... values) {
    ((DefaultTableModel) t.getModel()).addRow(values);
  }

  private static JComboBox<String> combo(String... values) {
    JComboBox<String> box = new JComboBox<>(values);
    box.setPreferredSize(new Dimension(Math.max(105, box.getPreferredSize().width + 18), 30));
    return box;
  }

  private static JComboBox<String> all(List<String> values) {
    List<String> v = new ArrayList<>(List.of("전체"));
    v.addAll(values);
    return combo(v.toArray(String[]::new));
  }

  private static JTextArea details(int lines) {
    JTextArea area = new JTextArea(lines, 70);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setMargin(new Insets(7, 9, 7, 9));
    return area;
  }

  private static String selected(JComboBox<?> c) {
    return c.getSelectedItem() == null ? "" : c.getSelectedItem().toString();
  }

  private static String filter(JComboBox<?> c) {
    return selected(c).equals("전체") ? "" : selected(c);
  }

  private static String chosen(JComboBox<Choice> c) {
    return c.getSelectedItem() instanceof Choice x ? x.id() : "";
  }

  private static void select(JComboBox<Choice> c, String id) {
    for (int i = 0; i < c.getItemCount(); i++)
      if (c.getItemAt(i).id().equals(id)) {
        c.setSelectedIndex(i);
        return;
      }
  }

  private static String money(Object value) {
    if (value == null) return "미확정";
    try {
      return String.format(Locale.KOREA, "%,.2f", new BigDecimal(value.toString()))
          .replaceAll("\\.00$", "");
    } catch (NumberFormatException e) {
      return value.toString();
    }
  }

  private static String unitLabel(Map<String, Object> p) {
    return unitBase(s(p, "category")).toPlainString() + s(p, "unit");
  }

  private static String html(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static DocumentListener change(Runnable r) {
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

  /** Stops the refresh timer and disposes this window. */
  @Override
  public void dispose() {
    timer.stop();
    super.dispose();
  }

  private static final class HistoryChart extends JPanel {
    private static final long serialVersionUID = 1L;
    private List<Map<String, Object>> rows = new ArrayList<>();

    void setRows(List<Map<String, Object>> rows) {
      this.rows = rows;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D d = (Graphics2D) g.create();
      d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      d.setColor(Color.WHITE);
      d.fillRect(0, 0, getWidth(), getHeight());
      d.setColor(INK);
      d.drawString("날짜별 단위 가격", 16, 23);
      if (rows.isEmpty()) {
        d.drawString("조회 기간의 가격 정보가 없습니다.", 20, 70);
        d.dispose();
        return;
      }
      int left = 60, top = 45, w = Math.max(1, getWidth() - 85), h = Math.max(1, getHeight() - 85);
      double
          min =
              rows.stream()
                  .mapToDouble(x -> Double.parseDouble(s(x, "unit_price")))
                  .min()
                  .orElse(0),
          max =
              rows.stream()
                  .mapToDouble(x -> Double.parseDouble(s(x, "unit_price")))
                  .max()
                  .orElse(1),
          range = Math.max(1, max - min);
      d.setColor(Color.LIGHT_GRAY);
      d.drawLine(left, top, left, top + h);
      d.drawLine(left, top + h, left + w, top + h);
      d.setColor(INK);
      d.drawString(money(BigDecimal.valueOf(max)), 4, top + 5);
      d.drawString(money(BigDecimal.valueOf(min)), 4, top + h);
      d.setColor(ACCENT);
      int px = 0, py = 0;
      for (int i = 0; i < rows.size(); i++) {
        long first = LocalDate.parse(s(rows.get(0), "date")).toEpochDay();
        long last = LocalDate.parse(s(rows.get(rows.size() - 1), "date")).toEpochDay();
        long current = LocalDate.parse(s(rows.get(i), "date")).toEpochDay();
        int x = left + (last == first ? w / 2 : (int) ((current - first) * w / (last - first))),
            y =
                top
                    + h
                    - (int) ((Double.parseDouble(s(rows.get(i), "unit_price")) - min) / range * h);
        if (i > 0) d.drawLine(px, py, x, y);
        d.fillOval(x - 3, y - 3, 6, 6);
        px = x;
        py = y;
      }
      d.setColor(INK);
      d.drawString(s(rows.get(0), "date").substring(5), left, top + h + 21);
      d.drawString(s(rows.get(rows.size() - 1), "date").substring(5), left + w - 35, top + h + 21);
      d.dispose();
    }
  }
}
