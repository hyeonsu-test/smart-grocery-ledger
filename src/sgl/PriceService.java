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
    return Product.fromMap(product).unitPrice(num(offer.get("price"), false));
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
            for (var o : st.latest()) {
              var other = st.product(o.productId());
              if (!other.comparableTo(p)
                  || !o.channel().equals(channel)
                  || !o.condition().equals(condition)) continue;
              var row = o.toMap();
              row.putAll(
                  m(
                      "name",
                      other.name(),
                      "unit_price",
                      other.unitPrice(o.price()),
                      "validity",
                      o.validity(now())));
              (s(row, "validity").equals("현재 유효") ? ranked : excluded).add(row);
            }
            ranked.sort(Comparator.comparing(o -> (BigDecimal) obj(o).get("unit_price")));
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
          Map<String, PriceObservation> daily = new TreeMap<>();
          for (var o : st.offers()) {
            String d = o.checkedDate().toString();
            if (!o.inPeriod(o.checkedDate())
                || !o.productId().equals(p.id())
                || !o.store().equals(s(request, "store"))
                || !o.channel().equals(channel)
                || !o.condition().equals(condition)
                || !o.branch().equals(def(request, "branch", "전체"))
                || d.compareTo(start) < 0
                || d.compareTo(end) > 0) continue;
            if (!daily.containsKey(d) || o.compareTime(daily.get(d)) >= 0) daily.put(d, o);
          }
          List<Object> values = new ArrayList<>();
          BigDecimal min = null, max = null, sum = BigDecimal.ZERO;
          for (var e : daily.entrySet()) {
            BigDecimal n = p.unitPrice(e.getValue().price());
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
