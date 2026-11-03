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

/** Purchase goals and alert details. */
final class TargetTab extends TabView {
  private final TargetService targets;
  private final JLabel targetSummary = new JLabel();
  private final JTextArea targetDetails = details(4);
  private final JTable targetTable =
      table("상품", "관측일", "90% 기준", "개인 목표", "적용 기준", "현재 충족", "알림", "최근 알림");
  private List<Map<String, Object>> targetRows = new ArrayList<>();
  private boolean targetDirty = false;
  private String editingTargetId = "";
  private final JTextField targetPrice = new JTextField(9);
  private final JTextField targetCondition = new JTextField("일반", 6);
  private final JComboBox<String> targetChannel = combo("온라인", "오프라인");
  private final JCheckBox targetEnabled = new JCheckBox("앱 안 알림 사용");
  private final JLabel targetPriceLabel = new JLabel("선택 상품의 단위 목표가");
  private JButton saveButton;

  TargetTab(LedgerFrame frame, TargetService targets) {
    super(frame);
    this.targets = targets;
    sortNumbers(targetTable, 1, 2, 3, 4);

    add(targetPage(), BorderLayout.CENTER);
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
                    && !frame.discard("저장하지 않은 목표 설정을 버릴까요?")) {
                  restoreSelection(targetTable, targetRows, "product_id", editingTargetId);
                  return;
                }
                loadTargetForm(row);
              }
            });
    JPanel form = flow();
    form.add(targetPriceLabel);
    form.add(targetPrice);
    form.add(targetChannel);
    form.add(targetCondition);
    form.add(targetEnabled);
    saveButton = frame.button("목표 저장", this::saveTarget);
    form.add(saveButton);
    root.add(form);
    JPanel actions = flow();
    actions.add(frame.button("목표 조회", this::refreshTargets));
    actions.add(
        frame.button(
            "현재 가격으로 알림 확인",
            () -> {
              var r =
                  targets.managePurchaseTargets(
                      m(
                          "action",
                          "EVALUATE",
                          "product_ids",
                          frame.repo.snapshot().array("favorites")));
              if (frame.accepted(r)) {
                notifyTargets(r);
                refreshTargets();
              }
            }));
    root.add(actions);
    root.add(new JScrollPane(targetDetails));
    root.add(new JLabel("목표가를 비우고 저장하면 4주 기준을 사용합니다. 앱을 종료한 동안에는 알림이 발생하지 않습니다."));
    return root;
  }

  void refreshTargets() {
    var r =
        targets.managePurchaseTargets(
            m("action", "VIEW", "product_ids", frame.repo.snapshot().array("favorites")));
    if (!frame.accepted(r)) return;
    targetRows = rows(r.get("items"));
    if (targetRows.stream().noneMatch(row -> s(row, "product_id").equals(editingTargetId))) {
      editingTargetId = "";
      targetDirty = false;
    }
    updating = true;
    clear(targetTable);
    for (var row : targetRows) {
      var t = obj(row.get("target"));
      addRow(
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
        updating = true;
        targetPrice.setText("");
        targetCondition.setText("일반");
        targetChannel.setSelectedItem("온라인");
        targetEnabled.setSelected(false);
        updating = false;
        targetPriceLabel.setText("즐겨찾기 상품을 먼저 선택하세요.");
        targetDetails.setText("즐겨찾기 상품을 등록하면 구매 기준과 목표를 설정할 수 있습니다.");
      }
    }
    for (JComponent control : List.of(targetPrice, targetCondition, targetChannel, targetEnabled, saveButton))
      control.setEnabled(!editingTargetId.isEmpty());
    targetSummary.setText(
        "즐겨찾기 "
            + targetRows.size()
            + "개 · 기준 기간 "
            + LocalDate.now(frame.clock).minusDays(28)
            + " ~ "
            + LocalDate.now(frame.clock).minusDays(1));
  }

  private void saveTarget() {
    int row = targetTable.getSelectedRow();
    if (row < 0) {
      frame.error("목표를 설정할 즐겨찾기 상품을 선택하세요.");
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
    if (frame.accepted(r)) {
      targetDirty = false;
      refreshTargets();
    }
  }

  void notifyTargets(Map<String, Object> r) {
    if (!s(r, "status").equals("OK")) {
      frame.status.setText("가격은 저장됐지만 알림 이력 저장에 실패했습니다. 알림을 발행하지 않았습니다.");
      return;
    }
    List<Object> notes = Data.list(r.getOrDefault("notifications", new ArrayList<>()));
    if (!notes.isEmpty()) {
      frame.showText("구매 목표 알림", notificationText(notes));
    }
  }

  private String notificationText(List<Object> notes) {
    StringJoiner text = new StringJoiner("\n\n");
    for (var n : rows(notes)) {
      StringBuilder detail = new StringBuilder(s(n, "name") + " : 목표 단위 가격 도달");
      for (var offer : rows(n.get("matches"))) {
        var p = frame.repo.snapshot().product(s(offer, "product_id"));
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
    var p = frame.repo.snapshot().product(editingTargetId);
    targetPriceLabel.setText(p.name() + " · " + unitLabel(p) + "당 목표 (원)");
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

  @Override
  boolean hasDraft() {
    return targetDirty;
  }

  @Override
  void discardDraft() {
    targetDirty = false;
    refreshTargets();
  }
}
