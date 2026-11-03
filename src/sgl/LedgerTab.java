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

/** Income and expense editor. */
final class LedgerTab extends TabView {
  private final LedgerService ledger;
  private final JTextField month = new JTextField(YearMonth.now().toString(), 8);
  private final JLabel totals = new JLabel();
  private final JTable recordTable = table("날짜", "구분", "분류", "금액 (원)", "메모", "마트");
  private List<Map<String, Object>> recordRows = new ArrayList<>();
  private boolean recordDirty = false;
  private String recordId = "";
  private String purchaseId = "";
  private final JTextField recordDate = new JTextField(10);
  private final JTextField recordAmount = new JTextField(10);
  private final JTextField recordMemo = new JTextField(23);
  private final JTextField recordStore = new JTextField(10);
  private final JTextField recordQuantity = new JTextField("1", 4);
  private final JComboBox<String> kind = new JComboBox<>(new String[] {"지출", "수입"});
  private final JComboBox<String> recordCategory =
      new JComboBox<>(new String[] {"식료품", "외식", "교통", "주거", "용돈", "급여", "기타"});
  private final JTextField search = new JTextField(16);
  private final JComboBox<String> searchCategory =
      combo("전체", "식료품", "외식", "교통", "주거", "용돈", "급여", "기타");

  LedgerTab(LedgerFrame frame, LedgerService ledger) {
    super(frame);
    this.ledger = ledger;
    month.setText(frame.loadedMonth);
    add(ledgerPage(), BorderLayout.CENTER);
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
  }

  private JPanel ledgerPage() {
    JPanel root = page("실제 수입과 지출", "검색 결과와 관계없이 월 합계는 해당 월의 전체 내역을 기준으로 계산합니다.");
    JPanel filters = flow();
    filters.add(new JLabel("월"));
    filters.add(month);
    filters.add(searchCategory);
    filters.add(search);
    filters.add(frame.button("조회", this::changeMonth));
    root.add(filters);
    root.add(metric(totals));
    root.add(scroll(recordTable, 240));
    recordTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && !updating && recordTable.getSelectedRow() >= 0) {
                int index = recordTable.convertRowIndexToModel(recordTable.getSelectedRow());
                if (recordDirty && !frame.discard("입력 중인 내용을 버리고 선택 내역을 불러올까요?")) {
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
    actions.add(frame.button("내역 저장", this::saveRecord));
    actions.add(
        frame.button(
            "새 내역 / 취소",
            () -> {
              if (!recordDirty || frame.discard("입력 내용을 취소할까요?")) clearRecord();
            }));
    actions.add(frame.button("선택 내역 삭제", this::deleteRecord));
    root.add(actions);
    return root;
  }

  private void changeMonth() {
    frame.run(
        () -> {
          String requested = month(month.getText());
          if (!requested.equals(frame.loadedMonth)
              && (recordDirty || frame.budgetTab.hasDraft())
              && !frame.discard("월을 변경하면 저장하지 않은 입력과 계획을 취소합니다. 계속할까요?")) {
            month.setText(frame.loadedMonth);
            return;
          }
          if (!requested.equals(frame.loadedMonth)) {
            frame.loadedMonth = requested;
            clearRecord();
            frame.budgetTab.loadPlan();
          }
          refreshLedger();
        });
  }

  void refreshLedger() {
    var r =
        ledger.manageLedger(
            m(
                "action",
                "SEARCH",
                "month",
                frame.loadedMonth,
                "category",
                filter(searchCategory),
                "keyword",
                search.getText()));
    if (!frame.accepted(r)) return;
    recordRows = rows(r.get("records"));
    updating = true;
    clear(recordTable);
    for (var x : recordRows)
      addRow(
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
            + frame.loadedMonth
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

  void clearRecord() {
    updating = true;
    recordId = "";
    purchaseId = "";
    recordDate.setText(LocalDate.now(frame.clock).toString());
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
    if (frame.accepted(r)) {
      clearRecord();
      refreshLedger();
      frame.budgetTab.updatePlanEstimate();
      frame.status.setText("실제 결제 내역을 저장했습니다.");
    }
  }

  private void deleteRecord() {
    if (recordId.isEmpty()) {
      frame.error("삭제할 내역을 선택하세요.");
      return;
    }
    if (!frame.discard("선택한 내역을 삭제할까요?")) return;
    var r = ledger.manageLedger(m("action", "DELETE", "recordId", recordId, "confirmed", true));
    if (frame.accepted(r)) {
      clearRecord();
      refreshLedger();
      frame.budgetTab.updatePlanEstimate();
    }
  }

  void beginPurchase(Product p, PriceObservation o) {
    if (recordDirty && !frame.discard("가계부의 미저장 입력을 버리고 구매를 기록할까요?")) return;
    clearRecord();
    purchaseId = p.id();
    recordMemo.setText(p.name());
    recordStore.setText(o.store());
    recordAmount.setText(o.price().toPlainString());
    frame.navigate(0);
    recordDirty = true;
    frame.status.setText("실제 결제 총액과 수량을 확인한 뒤 내역 저장을 누르세요. 배송비는 직접 반영하세요.");
  }

  @Override
  boolean hasDraft() {
    return recordDirty;
  }

  @Override
  void discardDraft() {
    clearRecord();
  }
}
