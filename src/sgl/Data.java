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

  static void conditions(Map<String, Object> r) {
    if (!List.of("온라인", "오프라인").contains(def(r, "channel", "온라인")))
      throw new IllegalArgumentException("판매 경로를 확인하세요.");
    if (def(r, "condition", "일반").isBlank()) throw new IllegalArgumentException("가격 조건을 입력하세요.");
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
