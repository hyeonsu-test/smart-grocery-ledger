package sgl;

import static sgl.Data.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** FN04 / UC04: comparable unit prices and latest valid observation per historical day. */
public final class PriceService extends Service {
  /**
   * Creates the price analysis service.
   *
   * @param repository shared storage
   * @param clock validity time source
   */
  public PriceService(Repository repository, Clock clock) {
    super(repository, clock);
  }

  /**
   * Converts an offer to price per 100 ml, 100 g, egg or ramen bag.
   *
   * @param offer offer containing price
   * @param product product containing category, size, unit and pack_count
   * @return decimal unit price with DECIMAL128 division; display rounding is separate
   * @throws IllegalArgumentException for invalid price or product
   */
  public static BigDecimal normalizeUnitPrice(
      Map<String, Object> offer, Map<String, Object> product) {
    return unitPrice(offer, product);
  }

  /**
   * Executes COMPARE or HISTORY, keeping channel and discount conditions separate.
   *
   * <p>Both require product_id; channel/condition default to online/general Korean labels. HISTORY
   * also requires store, branch, start and end (inclusive ISO dates). COMPARE matches category,
   * brand, spec, unit and per-package size; bundle counts may vary.
   *
   * @param request mode and filters
   * @return ranked/excluded current observations, or rows/count/min/max/mean history. NO_DATA has
   *     empty rows and null statistics, never a fictional zero price.
   */
  public Map<String, Object> analyzePrices(Map<String, Object> request) {
    return call(
        request,
        () -> {
          AppState st = repo.snapshot();
          var p = st.product(text(request, "product_id"));
          conditions(request);
          String mode = s(request, "mode"),
              channel = def(request, "channel", "온라인"),
              condition = def(request, "condition", "일반");
          if (mode.equals("COMPARE")) {
            List<Object> ranked = new ArrayList<>(), excluded = new ArrayList<>();
            for (var o : latest(st)) {
              var other = st.product(s(o, "product_id"));
              if (!comparable(other, p)
                  || !s(o, "channel").equals(channel)
                  || !def(o, "condition", "일반").equals(condition)) continue;
              var row = copyMap(o);
              row.putAll(
                  m(
                      "name",
                      s(other, "name"),
                      "unit_price",
                      unitPrice(o, other),
                      "validity",
                      validity(o, now())));
              (s(row, "validity").equals("현재 유효") ? ranked : excluded).add(row);
            }
            ranked.sort(Comparator.comparing(o -> num(obj(o).get("unit_price"), false)));
            return m(
                "status",
                ranked.isEmpty() ? "NO_DATA" : "OK",
                "ranked",
                ranked,
                "excluded",
                excluded);
          }
          if (!mode.equals("HISTORY")) throw new IllegalArgumentException("조회 모드를 확인하세요.");
          String start = day(request.get("start")), end = day(request.get("end"));
          if (start.compareTo(end) > 0) throw new IllegalArgumentException("조회 기간을 확인하세요.");
          Map<String, Map<String, Object>> daily = new TreeMap<>();
          for (var o : st.offers()) {
            String d = s(o, "checked_date");
            if (!historical(o)
                || !s(o, "product_id").equals(s(p, "id"))
                || !s(o, "store").equals(s(request, "store"))
                || !s(o, "channel").equals(channel)
                || !def(o, "condition", "일반").equals(condition)
                || !def(o, "branch", "전체").equals(def(request, "branch", "전체"))
                || d.compareTo(start) < 0
                || d.compareTo(end) > 0) continue;
            if (!daily.containsKey(d) || observationCompare(o, daily.get(d)) >= 0) daily.put(d, o);
          }
          List<Object> values = new ArrayList<>();
          BigDecimal min = null, max = null, sum = BigDecimal.ZERO;
          for (var e : daily.entrySet()) {
            BigDecimal n = unitPrice(e.getValue(), p);
            values.add(m("date", e.getKey(), "unit_price", n));
            sum = sum.add(n);
            min = min == null ? n : min.min(n);
            max = max == null ? n : max.max(n);
          }
          return m(
              "status",
              values.isEmpty() ? "NO_DATA" : "OK",
              "rows",
              values,
              "count",
              values.size(),
              "min",
              min,
              "max",
              max,
              "mean",
              values.isEmpty() ? null : sum.divide(BigDecimal.valueOf(values.size()), MC));
        });
  }
}
