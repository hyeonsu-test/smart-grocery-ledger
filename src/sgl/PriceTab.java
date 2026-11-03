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

/** Unit-price comparison and history. */
final class PriceTab extends TabView {
  private final PriceService prices;
  private final JLabel priceSummary = new JLabel();
  private final JTextArea compareDetails = details(3);
  private final JTable compareTable = table("순위", "상품", "마트 / 지점", "묶음 가격", "단위 가격", "상태");
  private final JTable historyTable = table("확인 날짜", "단위 가격");
  private List<OfferRow> compareRows = new ArrayList<>();
  private final JTextField priceCondition = new JTextField("일반", 6);
  private final JTextField historyBranch = new JTextField("전체", 7);
  private final JTextField historyStart = new JTextField(10);
  private final JTextField historyEnd = new JTextField(10);
  private final JComboBox<String> priceChannel = combo("온라인", "오프라인");
  private final JComboBox<String> historyStore = new JComboBox<>(STORES.toArray(String[]::new));
  private final JComboBox<SwingViews.Choice> priceProduct = new JComboBox<>();
  private final HistoryChart chart = new HistoryChart();

  PriceTab(LedgerFrame frame, PriceService prices) {
    super(frame);
    this.prices = prices;
    priceProduct.setPreferredSize(new Dimension(220, 30));
    add(pricePage(), BorderLayout.CENTER);
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
    filters.add(frame.button("단위 가격 비교", this::refreshCompare));
    root.add(filters);
    root.add(scroll(compareTable, 145));
    compareTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && compareTable.getSelectedRow() >= 0)
                compareDetails.setText(
                    compareRows
                        .get(compareTable.convertRowIndexToModel(compareTable.getSelectedRow()))
                        .details());
            });
    root.add(new JScrollPane(compareDetails));
    JPanel history = flow();
    history.add(historyStore);
    history.add(historyBranch);
    historyStart.setText(LocalDate.now(frame.clock).minusDays(28).toString());
    historyEnd.setText(LocalDate.now(frame.clock).toString());
    history.add(historyStart);
    history.add(new JLabel("~"));
    history.add(historyEnd);
    history.add(frame.button("가격 이력 조회", this::refreshHistory));
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

  void refreshCompare() {
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
    if (!frame.accepted(r)) return;
    compareRows = new ArrayList<>();
    clear(compareTable);
    AppState state = frame.repo.snapshot();
    for (String group : List.of("ranked", "excluded")) {
      int rank = 1;
      for (var raw : rows(r.get(group))) {
        var row = OfferRow.read(raw, state.product(s(raw, "product_id")));
        compareRows.add(row);
        addRow(
            compareTable,
            group.equals("ranked") ? rank++ : "제외",
            row.product().name(),
            row.offer().store() + " / " + row.offer().branch(),
            money(row.offer().price()),
            money(row.unitPrice()) + " / " + unitLabel(row.product()),
            row.validity());
      }
    }
    compareDetails.setText(compareRows.isEmpty() ? "비교 가능한 가격이 없습니다. 상품과 판매 경로·조건을 확인하세요." : "");
    if (!compareRows.isEmpty()) compareTable.setRowSelectionInterval(0, 0);
  }

  void refreshHistory() {
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
    if (!frame.accepted(r)) return;
    clear(historyTable);
    for (var row : rows(r.get("rows")))
      addRow(historyTable, s(row, "date"), money(row.get("unit_price")));
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
            + unitLabel(frame.repo.snapshot().product(chosen(priceProduct))));
    chart.setRows(rows(r.get("rows")));
  }

  void setProducts(AppState state) {
    fillChoices(priceProduct, state.products());
    if (state.demo() && historyBranch.getText().equals("전체")) historyBranch.setText("시연 지점");
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
