package sgl;

import static sgl.Data.*;
import static sgl.SwingViews.*;

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

/** Product search, sources and price entry. */
final class ProductTab extends TabView {
  private final ProductService products;
  private final OnlineCollectionService online;
  private JButton collectButton;
  private String collectionSummary = "";
  private String collectionDetails = "아직 수집하지 않았습니다.";
  private final JTextArea productDetails = details(5);
  private final JLabel productStatus = new JLabel();
  private final JTable productTable =
      table("관심", "상품", "마트 / 지점", "경로 / 조건", "가격 (원)", "단위 가격", "확인 시각", "상태");
  private List<OfferRow> productRows = new ArrayList<>();
  private boolean busy = false;
  private boolean closed;
  private SwingWorker<?, ?> worker;

  void close() {
    closed = true;
    online.close();
    if (worker != null) worker.cancel(true);
    busy = false;
  }
  private final JComboBox<String> filterCategory = all(CATEGORIES);
  private final JComboBox<String> filterStore = all(STORES);
  private final JComboBox<String> filterChannel = combo("전체", "온라인", "오프라인");
  private final JCheckBox promotionsOnly = new JCheckBox("현재 행사만");

  ProductTab(LedgerFrame frame, ProductService products, OnlineCollectionService online) {
    super(frame);
    this.products = products;
    this.online = online;
    sortNumbers(productTable, 4, 5);

    add(productPage(), BorderLayout.CENTER);
  }

  private JPanel productPage() {
    JPanel root =
        page("상품과 가격 정보", "원하는 상품을 검색해 자동 수집 목록에 추가하세요. 저장한 목록을 하루 한 번 자동 갱신합니다.");
    JPanel filters = flow();
    filters.add(filterCategory);
    filters.add(filterStore);
    filters.add(filterChannel);
    filters.add(promotionsOnly);
    promotionsOnly.addActionListener(e -> refreshProducts());
    filters.add(frame.button("상품 조회", this::refreshProducts));
    filters.add(frame.button("가격 직접 입력", this::manualDialog));
    root.add(filters);
    JPanel collection=flow();
    collection.add(frame.button("상품 검색 · 수집 목록", () -> {
      if(frame.repo.snapshot().demo()) {
        frame.status.setText("일반 모드에서 상품 검색과 자동 수집 목록을 사용할 수 있습니다.");
        return;
      }
      new CollectionDialog(frame,new ProductDiscovery(frame.clock),new CollectionListService(frame.repo,frame.clock)).setVisible(true);
    }));
    collectButton = frame.collectionButton();
    collection.add(collectButton);
    collection.add(frame.button("수집 결과", () -> frame.showText("상품 정보 수집 결과", collectionDetails)));
    root.add(collection);
    root.add(scroll(productTable, 270));
    productTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && productTable.getSelectedRow() >= 0)
                productDetails.setText(
                    describeOffer(
                        productRows.get(
                            productTable.convertRowIndexToModel(productTable.getSelectedRow()))));
            });
    root.add(new JScrollPane(productDetails));
    JPanel buttons = flow();
    buttons.add(
        frame.button(
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
                          row.product().id(),
                          "isFavorite",
                          !row.favorite()));
              if (frame.accepted(r)) {
                refreshProducts();
                frame.targetTab.refreshTargets();
              }
            }));
    buttons.add(
        frame.button(
            "출처 원문 열기",
            () -> {
              var row = selectedProduct();
              if (row != null) openSource(row);
            }));
    buttons.add(
        frame.button(
            "출처 주소 복사",
            () -> {
              var row = selectedProduct();
              if (row != null && requireSource(row)) {
                Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(
                        new java.awt.datatransfer.StringSelection(row.offer().url()), null);
                frame.status.setText("선택 상품의 출처 주소를 복사했습니다.");
              }
            }));
    buttons.add(frame.button("실제 구매 기록", this::purchase));
    buttons.add(frame.button("장보기 추가", this::addProductToPlan));
    root.add(buttons);
    root.add(productStatus);
    JTextArea note =
        new JTextArea(
            "공개 온라인 가격이며 배송지·매장 가격과 다를 수 있습니다. 코스트코는 회원 가격·배송비 포함입니다.\n"
                + "확인 후 24시간이 지났거나 확인 시각이 없으면 현재 비교와 예상액에서 제외됩니다.\n"
                + "수집한 날부터 가격 이력이 쌓입니다. 관심 상품은 즐겨찾기에 추가해 구매 목표를 설정하세요.");
    note.setEditable(false);
    note.setOpaque(false);
    root.add(note);
    return root;
  }

  void refreshProducts() {
    int previousIndex = productTable.getSelectedRow();
    OfferRow previous = previousIndex < 0 ? null
        : productRows.get(productTable.convertRowIndexToModel(previousIndex));
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
                filter(filterChannel),
                "promotions_only",
                promotionsOnly.isSelected()));
    if (!frame.accepted(r)) return;
    productRows =
        rows(r.get("offers")).stream()
            .map(raw -> OfferRow.read(raw, Product.fromMap(obj(raw.get("product")))))
            .toList();
    clear(productTable);
    for (var row : productRows) {
      var offer = row.offer();
      addRow(
          productTable,
          row.favorite() ? "★" : "",
          row.product().name(),
          offer == null ? "미확인" : offer.store() + " / " + offer.branch(),
          offer == null ? "미확인" : offer.channel() + " / " + offer.condition(),
          offer == null ? "미확인" : money(offer.price()),
          row.unitPrice() == null ? "미확인" : money(row.unitPrice()) + " / " + unitLabel(row.product()),
          offer == null || offer.checkedAt() == null ? "" : offer.checkedAt().toString(),
          row.validity());
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
    productStatus.setText(
        states.length() > 0
            ? "조회 결과 " + productRows.size() + "건 · 출처별 갱신 결과와 최근 성공 시각은 선택 상품 상세에서 확인"
            : "조회 결과 " + productRows.size() + "건 · 상품 정보 가져오기로 공식 가격을 수집하세요.");
    if (!collectionSummary.isEmpty()) productStatus.setText(collectionSummary);
    productStatus.setToolTipText(states.toString());
    if (collectionSummary.isEmpty() && states.length() > 0) {
      StringBuilder details = new StringBuilder("저장된 최근 수집 결과\n\n");
      for (var entry : obj(r.get("sources")).entrySet()) {
        var source = obj(entry.getValue());
        details.append(entry.getKey()).append(": ").append(yes(source, "failed") ? "일부 또는 전체 실패" : "성공")
            .append("\n최근 성공 ").append(def(source, "last_success", "없음"));
        if (yes(source, "failed")) details.append("\n사유 ").append(s(source, "error"));
        details.append("\n\n");
      }
      details.append("상품 정보 가져오기를 누르면 다시 수집합니다.");
      collectionDetails = details.toString();
    }
    productDetails.setText(
        productRows.isEmpty()
            ? "조회 결과가 없습니다. 상품 정보 가져오기를 누르거나 조회 조건을 확인하세요."
            : "상품 행을 선택하면 규격·행사·출처를 확인할 수 있습니다.");
    if (!productRows.isEmpty()) {
      int selected = 0;
      for (int i = 0; previous != null && i < productRows.size(); i++) {
        OfferRow row = productRows.get(i);
        if (row.product().id().equals(previous.product().id())
            && Objects.equals(row.offer() == null ? null : row.offer().key(),
                previous.offer() == null ? null : previous.offer().key())) {
          selected = i;
          break;
        }
      }
      int view = productTable.convertRowIndexToView(selected);
      productTable.setRowSelectionInterval(view, view);
    }
  }

  private String describeOffer(OfferRow row) {
    if (row.offer() == null) return row.details();
    Object raw = frame.repo.snapshot().map("sources").get(row.offer().store());
    if (raw == null) return row.details() + "\n수집 기록 없음 · 직접 확인 가격은 자체 확인 시각으로 판정합니다.";
    var source = obj(raw);
    return row.details()
        + "\n출처 최근 성공 "
        + (s(source, "last_success").isEmpty() ? "없음" : s(source, "last_success"))
        + (yes(source, "failed") ? " · 갱신 실패: " + s(source, "error") : " · 갱신 성공");
  }

  private OfferRow selectedProduct() {
    int row = productTable.getSelectedRow();
    if (row < 0) {
      frame.error("상품 가격을 선택하세요.");
      return null;
    }
    return productRows.get(productTable.convertRowIndexToModel(row));
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
      if (frame.accepted(r)) frame.status.setText(store + " 피드를 연결했습니다. 갱신 버튼으로 불러오세요.");
    }
  }

  void collectOnline(boolean auto) {
    if (closed || busy) return;
    if (frame.repo.snapshot().demo()) {
      frame.status.setText("시연 모드는 가상 가격입니다. 실제 수집은 일반 모드에서 사용하세요.");
      return;
    }
    busy = true;
    frame.collecting(true);
    frame.status.setText("공식 마트 상품 정보를 수집하는 중…");
    worker = new SwingWorker<Map<String, Object>, String>() {
      @Override
      protected Map<String, Object> doInBackground() {
        return online.collect(auto, message -> publish(message));
      }

      @Override
      protected void process(List<String> messages) {
        if (!closed && !messages.isEmpty()) frame.status.setText("수집 중 · " + messages.get(messages.size() - 1));
      }

      @Override
      protected void done() {
        if (closed || isCancelled()) return;
        busy = false;
        frame.collecting(false);
        try {
          var result = get();
          if (yes(result, "skipped")) {
            frame.status.setText(def(result,"message","오늘 자동 수집을 시도했습니다. 상품 정보 가져오기로 다시 수집할 수 있습니다."));
            return;
          }
          if (!List.of("OK", "PARTIAL_FAILURE", "REFRESH_FAILED").contains(s(result, "status"))) {
            frame.accepted(result);
            return;
          }
          collectionSummary = "수집 성공 " + def(result, "successful", "0") + "건 · 실패 "
              + def(result, "failed", "0") + "건 · 자세한 내용은 수집 결과에서 확인";
          StringBuilder description = new StringBuilder(collectionSummary).append("\n\n");
          for (var entry : obj(result.getOrDefault("sources", m())).entrySet()) {
            var source = obj(entry.getValue());
            description.append(entry.getKey()).append(": 성공 ").append(s(source, "success"))
                .append(" / 실패 ").append(s(source, "failed_count")).append('\n');
          }
          for (var entry : obj(result.getOrDefault("failures", m())).entrySet())
            description.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
          if (!s(result, "message").isEmpty()) description.append(s(result, "message")).append('\n');
          description.append("\n실패한 출처는 상품 정보 가져오기로 재시도하세요.\n"
              + "기존 이력은 보존하며, 수집하지 못한 가격은 현재 비교에서 제외합니다.\n"
              + "05에서는 관심 상품을 선택해 추가하세요. 과거 가격은 수집한 날짜부터 쌓입니다.");
          collectionDetails = description.toString();
          frame.refreshAfterPriceChange();
          frame.status.setText(collectionSummary);
          if (result.get("target_result") instanceof Map<?, ?>)
            frame.targetTab.notifyTargets(obj(result.get("target_result")));
        } catch (Exception e) {
          collectionSummary = "수집 실패 · 상품 정보 가져오기로 재시도하세요.";
          collectionDetails = e.getMessage();
          frame.status.setText(collectionSummary);
        }
      }
    };
    worker.execute();
  }

  void refreshFeed(boolean auto) {
    if (closed || busy) return;
    if (frame.repo.snapshot().map("feed_paths").isEmpty()) {
      frame.status.setText("연결된 JSON 피드가 없습니다. 가격 직접 입력 또는 피드 연결을 사용하세요.");
      return;
    }
    busy = true;
    frame.status.setText("가격 정보를 읽는 중...");
    worker = new SwingWorker<Map<String, Object>, Void>() {
      @Override
      protected Map<String, Object> doInBackground() {
        return products.manageProducts(m("action", auto ? "AUTO_REFRESH" : "REFRESH"));
      }

      @Override
      protected void done() {
        if (closed || isCancelled()) return;
        busy = false;
        try {
          var r = get();
          frame.refreshAfterPriceChange();
          String st = s(r, "status");
          if (!List.of("OK", "PARTIAL_FAILURE", "REFRESH_FAILED").contains(st)) frame.accepted(r);
          else if (!st.equals("OK"))
            frame.status.setText("일부 출처 갱신 실패: " + Json.stringify(r.get("failures")));
          else frame.status.setText("피드 갱신 완료 · 원래 확인 시각을 유지했습니다.");
          if (r.get("target_result") instanceof Map<?, ?>)
            frame.targetTab.notifyTargets(obj(r.get("target_result")));
        } catch (Exception e) {
          frame.error("갱신 실패: " + e.getMessage());
        }
      }
    };
    worker.execute();
  }

  private void openSource(OfferRow row) {
    if (!requireSource(row)) return;
    try {
      if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        throw new IllegalStateException("브라우저 실행을 지원하지 않습니다.");
      Desktop.getDesktop().browse(URI.create(row.offer().url()));
    } catch (Exception e) {
      frame.showText(
          "링크를 열지 못했습니다",
          "다음 주소를 선택해 복사하거나 출처 주소 복사 버튼을 사용하세요.\n" + row.offer().url() + "\n" + e.getMessage());
    }
  }

  private boolean requireSource(OfferRow row) {
    if (row.offer() != null) return true;
    frame.status.setText("아직 등록된 가격 출처가 없습니다. 가격 직접 입력에서 확인한 출처를 등록하세요.");
    return false;
  }

  private void manualDialog() {
    JDialog dialog = new JDialog(frame, "가격 직접 입력", true);
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
    panel.add(new JLabel("품목 (우유 ml / 계란 개 / 라면 봉 / 쌀·두부 g)"));
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
    fields.get("checked_date").setText(LocalDate.now(frame.clock).toString());
    fields.get("checked_at").setText(OffsetDateTime.now(frame.clock).withNano(0).toString());
    int selected = productTable.getSelectedRow();
    if (selected >= 0) {
      var row = productRows.get(productTable.convertRowIndexToModel(selected));
      category.setSelectedItem(row.product().category());
      var productFields = row.product().toMap();
      for (String k : List.of("id", "name", "brand", "spec", "size", "pack_count"))
        fields.get(k).setText(s(productFields, k));
      if (row.offer() != null) {
        store.setSelectedItem(row.offer().store());
        channel.setSelectedItem(row.offer().channel());
        var priceFields = row.offer().toMap();
        for (String k : List.of("branch", "price", "condition", "url", "start", "end"))
          fields.get(k).setText(s(priceFields, k));
      }
    }
    JPanel actions = flow();
    actions.add(
        frame.button(
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
              if (frame.accepted(r)) {
                dialog.dispose();
                frame.refreshAfterPriceChange();
                if (r.get("target_result") instanceof Map<?, ?>)
                  frame.targetTab.notifyTargets(obj(r.get("target_result")));
              }
            }));
    actions.add(frame.button("취소", dialog::dispose));
    dialog.add(new JScrollPane(panel));
    dialog.add(actions, BorderLayout.SOUTH);
    dialog.pack();
    dialog.setSize(740, Math.min(780, Toolkit.getDefaultToolkit().getScreenSize().height - 80));
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
  }

  boolean refreshing() {
    return busy;
  }

  private void purchase() {
    var row = selectedProduct();
    if (row != null) frame.ledgerTab.beginPurchase(row.product(), row.offer());
  }

  private void addProductToPlan() {
    var row = selectedProduct();
    if (row != null) frame.budgetTab.addProduct(row.product(), row.offer());
  }
}
