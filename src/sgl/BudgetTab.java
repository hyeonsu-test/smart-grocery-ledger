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

/** Monthly budget and shopping draft editor. */
final class BudgetTab extends TabView {
  private final BudgetService budget;
  private final JLabel planSummary = new JLabel();
  private final JTable planTable = table("상품", "수량", "마트", "지점", "경로", "조건", "예상액 (원)");
  private List<Object> planDraft = new ArrayList<>();
  private boolean planDirty = false;
  private boolean budgetDirty = false;
  private boolean planFormDirty = false;
  private String planItemId = "";
  private final JTextField budgetAmount = new JTextField(9);
  private final JTextField planQty = new JTextField("1", 4);
  private final JTextField planCondition = new JTextField("일반", 6);
  private final JTextField planBranch = new JTextField(7);
  private final JComboBox<String> planStore = all(STORES);
  private final JComboBox<String> planChannel = combo("온라인", "오프라인");
  private final JComboBox<SwingViews.Choice> planProduct = new JComboBox<>();

  BudgetTab(LedgerFrame frame, BudgetService budget) {
    super(frame);
    this.budget = budget;
    planProduct.setPreferredSize(new Dimension(220, 30));
    add(budgetPage(), BorderLayout.CENTER);
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
  }

  private JPanel budgetPage() {
    JPanel root = page("식비 예산과 장보기 계획", "장보기 계획은 실제 지출에 합산되지 않습니다. 수량·예산을 입력한 뒤 각각 저장하세요.");
    JPanel budgetBar = flow();
    budgetBar.add(new JLabel("월 식비 예산: "));
    budgetBar.add(budgetAmount);
    budgetBar.add(new JLabel("원"));
    budgetBar.add(
        frame.button(
            "월 식비 예산 저장",
            () -> {
              var r =
                  budget.manageBudgetPlan(
                      m(
                          "action",
                          "SET_BUDGET",
                          "month",
                          frame.loadedMonth,
                          "budget",
                          budgetAmount.getText(),
                          "draftPlan",
                          planDraft));
              if (frame.accepted(r)) {
                budgetDirty = false;
                showPlan(r);
                frame.ledgerTab.refreshLedger();
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
                if (planFormDirty && !frame.discard("아직 적용하지 않은 항목 입력을 버릴까요?")) {
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
        frame.button(
            "항목 적용",
            () -> {
              Map<String, Object> req =
                  m(
                      "action",
                      "UPSERT_ITEM",
                      "month",
                      frame.loadedMonth,
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
              if (frame.accepted(r)) {
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
        frame.button(
            "선택 항목 삭제",
            () -> {
              int row = planTable.getSelectedRow();
              if (row < 0) {
                frame.error("계획 항목을 선택하세요.");
                return;
              }
              var item = obj(planDraft.get(planTable.convertRowIndexToModel(row)));
              var r =
                  budget.manageBudgetPlan(
                      m(
                          "action",
                          "REMOVE_ITEM",
                          "month",
                          frame.loadedMonth,
                          "draftPlan",
                          planDraft,
                          "itemId",
                          s(item, "itemId")));
              if (frame.accepted(r)) {
                showPlan(r);
                planDirty = true;
                planItemId = "";
              }
            }));
    buttons.add(
        frame.button(
            "계획 저장",
            () -> {
              if (planFormDirty) {
                frame.error("수정한 상품·수량·조건은 먼저 항목 적용을 누른 뒤 계획을 저장하세요.");
                return;
              }
              var r =
                  budget.manageBudgetPlan(
                      m("action", "SAVE_PLAN", "month", frame.loadedMonth, "draftPlan", planDraft));
              if (frame.accepted(r)) {
                planDirty = false;
                showPlan(r);
                frame.status.setText("장보기 계획을 저장했습니다. 실제 지출은 변경되지 않았습니다.");
              }
            }));
    buttons.add(
        frame.button(
            "변경 취소",
            () -> {
              if (!hasDraft() || frame.discard("저장하지 않은 예산과 계획 입력을 취소할까요?")) loadPlan();
            }));
    root.add(buttons);
    return root;
  }

  void loadPlan() {
    var r = budget.manageBudgetPlan(m("action", "GET", "month", frame.loadedMonth));
    if (frame.accepted(r)) {
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

  void updatePlanEstimate() {
    var r =
        budget.manageBudgetPlan(
            m("action", "GET", "month", frame.loadedMonth, "draftPlan", planDraft));
    if (frame.accepted(r)) showPlan(r);
  }

  private void showPlan(Map<String, Object> r) {
    planDraft = Data.list(r.get("draftPlan"));
    updating = true;
    clear(planTable);
    for (var item : rows(r.get("items")))
      addRow(
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
            + frame.loadedMonth
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

  void addProduct(PriceObservation offer) {
    var r =
        budget.manageBudgetPlan(
            m(
                "action",
                "UPSERT_ITEM",
                "month",
                frame.loadedMonth,
                "draftPlan",
                planDraft,
                "product_id",
                offer.productId(),
                "quantity",
                1,
                "store",
                offer.store(),
                "branch",
                offer.branch(),
                "channel",
                offer.channel(),
                "condition",
                offer.condition()));
    if (frame.accepted(r)) {
      showPlan(r);
      planDirty = true;
      frame.navigate(1);
      frame.status.setText("선택 상품을 장보기 초안에 추가했습니다. 계획 저장 전에는 파일을 변경하지 않습니다.");
    }
  }

  void setProducts(Collection<Product> products) {
    updating = true;
    try {
      fillChoices(planProduct, products);
    } finally {
      updating = false;
    }
  }

  @Override
  boolean hasDraft() {
    return planDirty || budgetDirty || planFormDirty;
  }

  @Override
  void discardDraft() {
    loadPlan();
  }
}
