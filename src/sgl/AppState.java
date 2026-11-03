package sgl;

import static sgl.Data.*;

import java.util.*;

/** Versioned, JSON-compatible application state. Services mutate copies before committing. */
public final class AppState {
  private final Map<String, Object> data;
  private final Map<String, Product> catalog = new LinkedHashMap<>();
  private final List<PriceObservation> observations = new ArrayList<>();

  /** Constructs an empty version 1 ledger. */
  public AppState() {
    data =
        m(
            "version",
            1,
            "records",
            new ArrayList<>(),
            "budgets",
            m(),
            "plans",
            m(),
            "favorites",
            new ArrayList<>(),
            "targets",
            m(),
            "alerts",
            m(),
            "sources",
            m(),
            "feed_paths",
            m(),
            "auto_attempt",
            "",
            "demo",
            false);
  }

  AppState(Map<String, Object> data) {
    this.data = new LinkedHashMap<>(data);
    for (var e : obj(this.data.remove("products")).entrySet()) {
      Product p = Product.fromMap(obj(e.getValue()));
      if (!e.getKey().equals(p.id())) throw new IllegalArgumentException("상품 ID 오류");
      catalog.put(p.id(), p);
    }
    for (var row : rows(this.data.remove("offers"))) {
      var offer = PriceObservation.fromMap(row);
      product(offer.productId());
      observations.add(offer);
    }
    this.data.putIfAbsent("feed_paths", m());
  }

  /**
   * Returns an independent state copy.
   *
   * @return deep copy with no shared mutable collections
   */
  public AppState copy() {
    AppState copy = new AppState();
    copy.data.clear();
    copy.data.putAll(copyMap(data));
    copy.catalog.putAll(catalog);
    copy.observations.addAll(observations);
    return copy;
  }

  /**
   * Exports a copy for JSON interchange.
   *
   * @return JSON-compatible tree
   */
  public Map<String, Object> toMap() {
    var json = copyMap(data);
    json.put("products", productJson());
    json.put(
        "offers", new ArrayList<>(observations.stream().map(PriceObservation::toMap).toList()));
    return json;
  }

  Map<String, Object> map(String key) {
    return obj(data.get(key));
  }

  List<Object> array(String key) {
    return list(data.get(key));
  }

  List<Map<String, Object>> records() {
    return rows(data.get("records"));
  }

  List<PriceObservation> offers() {
    return Collections.unmodifiableList(observations);
  }

  Collection<Product> products() {
    return Collections.unmodifiableCollection(catalog.values());
  }

  Product product(String id) {
    Product result = catalog.get(id);
    if (result == null) throw new IllegalArgumentException("등록되지 않은 상품입니다: " + id);
    return result;
  }

  void addProduct(Product p) {
    Product old = catalog.putIfAbsent(p.id(), p);
    if (old != null && !old.equals(p)) throw new IllegalArgumentException("기존 상품 정보는 새 ID로 등록하세요.");
  }

  void record(PriceObservation offer) {
    product(offer.productId());
    observations.removeIf(old -> old.sameReading(offer));
    observations.add(offer);
  }

  void failFeed(String store) {
    observations.replaceAll(
        o -> o.store().equals(store) && !o.method().equals("직접 입력") ? o.withFailedFeed() : o);
  }

  void failOnline(String url) {
    observations.replaceAll(o -> o.method().equals("웹 자동 수집") && o.url().equals(url)
        ? o.withFailedFeed() : o);
  }

  List<PriceObservation> latest() {
    Map<PriceObservation.Key, PriceObservation> last = new LinkedHashMap<>();
    for (var o : observations)
      last.merge(o.key(), o, (prev, next) -> {
        if (prev.failed() != next.failed()) return prev.failed() ? next : prev;
        int order = next.compareTime(prev);
        if (order == 0 && prev.method().equals("직접 입력")) return prev;
        return order >= 0 ? next : prev;
      });
    return List.copyOf(last.values());
  }

  Map<String, Object> productJson() {
    Map<String, Object> result = new LinkedHashMap<>();
    catalog.forEach((id, p) -> result.put(id, p.toMap()));
    return result;
  }

  String value(String key) {
    return s(data, key);
  }

  void put(String key, Object value) {
    data.put(key, value);
  }

  boolean demo() {
    return yes(data, "demo");
  }

  void validate() {
    if (integer(data.get("version"), false) != 1)
      throw new IllegalArgumentException("지원하지 않는 데이터 버전입니다.");
    for (String k : List.of("budgets", "plans", "targets", "alerts", "sources", "feed_paths"))
      map(k);
    for (String k : List.of("records", "favorites")) array(k);
    bool(data.get("demo"));
    Set<String> recordIds = new HashSet<>();
    for (var r : records()) {
      if (!recordIds.add(text(r, "id"))) throw new IllegalArgumentException("중복 내역 ID");
      day(r.get("date"));
      if (!List.of("수입", "지출").contains(s(r, "kind")))
        throw new IllegalArgumentException("내역 구분 오류");
      text(r, "category");
      integer(r.get("amount"), false);
      integer(r.get("quantity"), false);
      for (String k : List.of("memo", "store", "product_id"))
        if (!(r.get(k) instanceof String)) throw new IllegalArgumentException("내역 설명 형식 오류");
    }
    for (var e : map("budgets").entrySet()) {
      month(e.getKey());
      integer(e.getValue(), true);
    }
    for (var e : map("plans").entrySet()) {
      month(e.getKey());
      Set<String> ids = new HashSet<>();
      for (var item : rows(e.getValue())) {
        product(s(item, "product_id"));
        integer(item.get("quantity"), false);
        conditions(item);
        if (!s(item, "store").isEmpty() && !STORES.contains(s(item, "store")))
          throw new IllegalArgumentException("계획의 마트 정보 오류");
        if (!ids.add(text(item, "itemId"))) throw new IllegalArgumentException("중복 계획 ID");
      }
    }
    for (Object id : array("favorites")) product(String.valueOf(id));
    for (var e : map("targets").entrySet()) {
      product(e.getKey());
      var t = obj(e.getValue());
      if (t.get("price") != null) num(t.get("price"), false);
      bool(t.get("enabled"));
      conditions(t);
    }
    for (var e : map("alerts").entrySet()) {
      product(e.getKey());
      day(e.getValue());
    }
    for (var e : map("sources").entrySet()) {
      if (!STORES.contains(e.getKey())) throw new IllegalArgumentException("출처 마트 정보 오류");
      var source = obj(e.getValue());
      bool(source.get("failed"));
      if (!(source.get("error") instanceof String))
        throw new IllegalArgumentException("출처 오류 설명 형식 오류");
      if (!s(source, "last_success").isEmpty())
        java.time.OffsetDateTime.parse(s(source, "last_success"));
    }
    if (!value("auto_attempt").isEmpty()) day(value("auto_attempt"));
    for (var e : map("feed_paths").entrySet())
      if (!STORES.contains(e.getKey()) || !(e.getValue() instanceof String))
        throw new IllegalArgumentException("피드 경로 오류");
  }
}
