package sgl;

import static sgl.Data.*;

import java.time.*;
import java.util.*;

/** Fictional fixtures, kept in a separate file and prominently labeled in the UI. */
public final class DemoData {
  private DemoData() {}

  /**
   * Generates a deterministic fictional ledger relative to a supplied clock.
   *
   * @param clock time source for sample dates
   * @return demo state, never a claim about actual retail prices
   */
  public static AppState create(Clock clock) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    LocalDate today = now.toLocalDate();
    String month = today.toString().substring(0, 7);
    AppState st = new AppState();
    st.put("demo", true);
    Object[][] specs = {
      {"milk", "우유", "데일리 우유 1L", "1000", "ml", 1, 2800},
      {"eggs", "계란", "신선 계란 10구", "10", "개", 1, 4200},
      {"ramen", "라면", "얼큰 라면 5봉", "1", "봉", 5, 3800},
      {"rice", "쌀", "고운 쌀 4kg", "4000", "g", 1, 15800},
      {"tofu", "두부", "담백 두부 300g", "300", "g", 1, 1800}
    };
    for (Object[] a : specs) {
      String pid = (String) a[0];
      st.addProduct(
          new Product(
              pid,
              (String) a[1],
              (String) a[2],
              "시연식품",
              "기본형",
              new java.math.BigDecimal((String) a[3]),
              (String) a[4],
              (int) a[5]));
      for (int ago : new int[] {28, 24, 20, 16, 12, 8, 4, 1, 0})
        for (int i = 0; i < STORES.size(); i++) {
          OffsetDateTime stamp = now.minusDays(ago);
          long price = (int) a[6] + (ago == 0 ? -200 + i * 120 : i * 150 + (ago % 7) * 40);
          st.record(
              new PriceObservation(
                  pid + "-" + ago + "-" + i,
                  pid,
                  STORES.get(i),
                  "시연 지점",
                  "온라인",
                  "일반",
                  java.math.BigDecimal.valueOf(price),
                  "https://example.com/",
                  stamp.toLocalDate(),
                  stamp,
                  null,
                  null,
                  "시연 데이터",
                  false));
        }
    }
    Object[][] entries = {
      {1, "수입", "용돈", 600000, "이번 달 생활비"},
      {1, "지출", "식료품", 25800, "주간 장보기"},
      {1, "지출", "교통", 30000, "교통카드 충전"},
      {2, "지출", "식료품", 12300, "우유·계란·두부"},
      {2, "지출", "외식", 8500, "점심 식사"}
    };
    for (int i = 0; i < entries.length; i++) {
      var a = entries[i];
      st.array("records")
          .add(
              m(
                  "id",
                  "demo-" + i,
                  "date",
                  today.withDayOfMonth(Math.min((int) a[0], today.getDayOfMonth())).toString(),
                  "kind",
                  a[1],
                  "category",
                  a[2],
                  "amount",
                  a[3],
                  "memo",
                  a[4],
                  "store",
                  "",
                  "product_id",
                  "",
                  "quantity",
                  1));
    }
    st.map("budgets").put(month, 180000);
    st.map("plans")
        .put(
            month,
            new ArrayList<>(
                List.of(
                    m(
                        "itemId",
                        "milk",
                        "product_id",
                        "milk",
                        "quantity",
                        2,
                        "store",
                        "",
                        "channel",
                        "온라인",
                        "condition",
                        "일반"),
                    m(
                        "itemId",
                        "eggs",
                        "product_id",
                        "eggs",
                        "quantity",
                        1,
                        "store",
                        "",
                        "channel",
                        "온라인",
                        "condition",
                        "일반"),
                    m(
                        "itemId",
                        "rice",
                        "product_id",
                        "rice",
                        "quantity",
                        1,
                        "store",
                        "",
                        "channel",
                        "온라인",
                        "condition",
                        "일반"))));
    st.array("favorites").addAll(List.of("milk", "eggs", "tofu"));
    st.map("targets")
        .put("milk", m("price", "270", "enabled", true, "channel", "온라인", "condition", "일반"));
    for (String store : STORES)
      st.map("sources").put(store, m("last_success", now.toString(), "failed", false, "error", ""));
    return st;
  }
}
