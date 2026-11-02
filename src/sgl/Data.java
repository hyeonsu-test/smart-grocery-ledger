package sgl;

import java.math.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Shared validation, money and comparison rules. Monetary calculations use decimal arithmetic. */
final class Data {
  static final List<String> STORES = List.of("이마트", "홈플러스", "코스트코");
  static final List<String> CATEGORIES = List.of("우유", "계란", "라면", "쌀", "두부");
  static final MathContext MC = MathContext.DECIMAL128;

  private Data() {}

  static Map<String, Object> m(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Object x) {
    if (!(x instanceof Map<?, ?>)) throw new IllegalArgumentException("객체 형식이 필요합니다.");
    return (Map<String, Object>) x;
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Object x) {
    if (!(x instanceof List<?>)) throw new IllegalArgumentException("목록 형식이 필요합니다.");
    return (List<Object>) x;
  }

  static List<Map<String, Object>> rows(Object x) {
    List<Map<String, Object>> a = new ArrayList<>();
    for (Object r : list(x)) a.add(obj(r));
    return a;
  }

  static String s(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v == null ? "" : v.toString();
  }

  static String def(Map<String, Object> m, String k, String d) {
    return m.containsKey(k) ? s(m, k) : d;
  }

  static String text(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (!(v instanceof String t) || t.isBlank() || t.length() > 2000)
      throw new IllegalArgumentException(k + " 값을 확인하세요.");
    return t.trim();
  }

  static boolean bool(Object x) {
    if (!(x instanceof Boolean b)) throw new IllegalArgumentException("참/거짓 값이 필요합니다.");
    return b;
  }

  static boolean yes(Map<String, Object> m, String k) {
    return Boolean.TRUE.equals(m.get(k));
  }

  static BigDecimal num(Object x, boolean zero) {
    try {
      BigDecimal n = new BigDecimal(String.valueOf(x));
      if (n.signum() < 0
          || (!zero && n.signum() == 0)
          || n.precision() > 30
          || Math.abs(n.scale()) > 20) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(zero ? "0 이상의 숫자를 입력하세요." : "0보다 큰 숫자를 입력하세요.");
    }
  }

  static long integer(Object x, boolean zero) {
    try {
      return num(x, zero).longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("범위 내 정수를 입력하세요.");
    }
  }

  static String month(Object x) {
    String s = String.valueOf(x);
    if (!s.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException("월 형식은 YYYY-MM입니다.");
    YearMonth.parse(s);
    return s;
  }

  static String day(Object x) {
    String s = String.valueOf(x);
    if (!s.matches("\\d{4}-\\d{2}-\\d{2}"))
      throw new IllegalArgumentException("날짜 형식은 YYYY-MM-DD입니다.");
    return LocalDate.parse(s).toString();
  }

  static String url(Object x) {
    try {
      URI u = URI.create(String.valueOf(x));
      if (!List.of("http", "https").contains(u.getScheme())
          || u.getHost() == null
          || u.getUserInfo() != null) throw new IllegalArgumentException();
      return u.toASCIIString();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("http 또는 https 출처 주소를 입력하세요.");
    }
  }

  static String unit(String c) {
    return c.equals("우유") ? "ml" : c.equals("계란") ? "개" : c.equals("라면") ? "봉" : "g";
  }

  static BigDecimal unitBase(String c) {
    return BigDecimal.valueOf(c.equals("계란") || c.equals("라면") ? 1 : 100);
  }

  static void product(Map<String, Object> p) {
    String c = text(p, "category");
    if (!CATEGORIES.contains(c) || !unit(c).equals(text(p, "unit")))
      throw new IllegalArgumentException("품목별 단위를 확인하세요: 우유 ml, 계란 개, 라면 봉, 쌀·두부 g");
    text(p, "id");
    text(p, "name");
    text(p, "brand");
    text(p, "spec");
    num(p.get("size"), false);
    integer(p.get("pack_count"), false);
  }

  static void conditions(Map<String, Object> r) {
    if (!List.of("온라인", "오프라인").contains(def(r, "channel", "온라인")))
      throw new IllegalArgumentException("판매 경로를 확인하세요.");
    if (def(r, "condition", "일반").isBlank()) throw new IllegalArgumentException("가격 조건을 입력하세요.");
  }

  static void offer(Map<String, Object> o, Map<String, Object> p) {
    product(p);
    if (!STORES.contains(s(o, "store"))) throw new IllegalArgumentException("지원 마트를 선택하세요.");
    conditions(o);
    num(o.get("price"), false);
    url(o.get("url"));
    String d = day(o.get("checked_date"));
    if (!s(o, "checked_at").isEmpty()
        && !OffsetDateTime.parse(s(o, "checked_at")).toLocalDate().toString().equals(d))
      throw new IllegalArgumentException("확인 날짜와 시간대 포함 시각이 일치해야 합니다.");
    for (String k : List.of("start", "end")) if (!s(o, k).isEmpty()) day(o.get(k));
    if (!s(o, "start").isEmpty()
        && !s(o, "end").isEmpty()
        && s(o, "start").compareTo(s(o, "end")) > 0)
      throw new IllegalArgumentException("행사 기간을 확인하세요.");
  }

  static BigDecimal unitPrice(Map<String, Object> o, Map<String, Object> p) {
    product(p);
    return num(o.get("price"), false)
        .multiply(unitBase(s(p, "category")))
        .divide(num(p.get("size"), false).multiply(num(p.get("pack_count"), false)), MC);
  }

  static String validity(Map<String, Object> o, OffsetDateTime now) {
    if (yes(o, "failed")) return "갱신 실패";
    if (s(o, "checked_at").isEmpty()) return "확인 시각 없음";
    Duration age = Duration.between(OffsetDateTime.parse(s(o, "checked_at")), now);
    if (age.isNegative()) return "미래 확인 시각";
    if (age.compareTo(Duration.ofHours(24)) >= 0) return "오래됨";
    String d = now.toLocalDate().toString();
    if (!s(o, "start").isEmpty() && d.compareTo(s(o, "start")) < 0) return "행사 시작 전";
    if (!s(o, "end").isEmpty() && d.compareTo(s(o, "end")) > 0) return "만료";
    return "현재 유효";
  }

  static boolean historical(Map<String, Object> o) {
    String d = s(o, "checked_date");
    return (s(o, "start").isEmpty() || s(o, "start").compareTo(d) <= 0)
        && (s(o, "end").isEmpty() || s(o, "end").compareTo(d) >= 0);
  }

  static String key(Map<String, Object> o) {
    return Json.stringify(
        List.of(
            s(o, "product_id"),
            s(o, "store"),
            s(o, "channel"),
            def(o, "branch", "전체"),
            def(o, "condition", "일반")));
  }

  static int observationCompare(Map<String, Object> a, Map<String, Object> b) {
    int date = s(a, "checked_date").compareTo(s(b, "checked_date"));
    if (date != 0) return date;
    String x = s(a, "checked_at"), y = s(b, "checked_at");
    if (x.isEmpty() || y.isEmpty()) return x.compareTo(y);
    return OffsetDateTime.parse(x).toInstant().compareTo(OffsetDateTime.parse(y).toInstant());
  }

  static List<Map<String, Object>> latest(AppState state) {
    Map<String, Map<String, Object>> last = new LinkedHashMap<>();
    for (var o : state.offers()) {
      String k = key(o);
      if (!last.containsKey(k) || observationCompare(o, last.get(k)) >= 0) last.put(k, o);
    }
    return new ArrayList<>(last.values());
  }

  static boolean comparable(Map<String, Object> a, Map<String, Object> b) {
    for (String k : List.of("category", "brand", "spec", "unit"))
      if (!s(a, k).equals(s(b, k))) return false;
    return num(a.get("size"), false).compareTo(num(b.get("size"), false)) == 0;
  }

  static Map<String, Object> monthly(AppState state, String month) {
    BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO, food = BigDecimal.ZERO;
    Map<String, Object> cats = m();
    for (var r : state.records())
      if (s(r, "date").startsWith(month)) {
        BigDecimal n = num(r.get("amount"), false);
        if (s(r, "kind").equals("수입")) income = income.add(n);
        else {
          expense = expense.add(n);
          String c = s(r, "category");
          cats.put(c, num(cats.getOrDefault(c, 0), true).add(n));
          if (c.equals("식료품")) food = food.add(n);
        }
      }
    Object budget = state.map("budgets").get(month);
    return m(
        "income",
        income,
        "expense",
        expense,
        "balance",
        income.subtract(expense),
        "food",
        food,
        "budget",
        budget,
        "remaining",
        budget == null ? null : num(budget, true).subtract(food),
        "categories",
        cats);
  }

  static String id() {
    return UUID.randomUUID().toString();
  }

  static Map<String, Object> copyMap(Map<String, Object> m) {
    return obj(Json.copy(m));
  }

  static Map<String, Object> ok(Object... args) {
    Map<String, Object> r = m("status", "OK");
    r.putAll(m(args));
    return r;
  }
}
