package sgl;

import static sgl.Data.*;

import java.util.*;

/** Versioned, JSON-compatible application state. Services mutate copies before committing. */
public final class AppState {
  private final Map<String, Object> data;

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
            "products",
            m(),
            "offers",
            new ArrayList<>(),
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
    this.data = data;
    data.putIfAbsent("feed_paths", m());
  }

  /**
   * Returns an independent state copy.
   *
   * @return deep copy with no shared mutable collections
   */
  public AppState copy() {
    return new AppState(copyMap(data));
  }

  /**
   * Exports a copy for JSON interchange.
   *
   * @return JSON-compatible tree
   */
  public Map<String, Object> toMap() {
    return copyMap(data);
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

  List<Map<String, Object>> offers() {
    return rows(data.get("offers"));
  }

  Map<String, Object> product(String id) {
    return obj(map("products").get(id));
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
    for (String k :
        List.of("budgets", "plans", "products", "targets", "alerts", "sources", "feed_paths"))
      map(k);
    for (String k : List.of("records", "offers", "favorites")) array(k);
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
    for (var e : map("products").entrySet()) {
      var p = obj(e.getValue());
      Data.product(p);
      if (!e.getKey().equals(s(p, "id"))) throw new IllegalArgumentException("상품 ID 오류");
    }
    for (var o : offers()) offer(o, product(s(o, "product_id")));
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
