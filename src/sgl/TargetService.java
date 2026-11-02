package sgl;

import static sgl.Data.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * FN05 / UC05: four-week basis, personal purchase targets and durable daily alert deduplication.
 */
public final class TargetService extends Service {
  /**
   * Creates a target service.
   *
   * @param repository shared storage
   * @param clock local date and validity time source
   */
  public TargetService(Repository repository, Clock clock) {
    super(repository, clock);
  }

  /**
   * Calculates an equally weighted mean of observed daily means over the preceding 28 days. Today
   * is excluded; at least four distinct observed days are required for a 90% basis. Latest
   * observations are selected per day/product/store/branch/channel/condition. Missing days are
   * omitted, not filled with zero. Refresh failure today does not erase history.
   *
   * @param productId reference product
   * @param channel online or offline Korean label
   * @param condition discount condition
   * @return start, end, days, mean and basis (nullable)
   * @throws IllegalArgumentException for an unknown product
   */
  public Map<String, Object> basis(String productId, String channel, String condition) {
    synchronized (repo) {
      AppState st = repo.snapshot();
      var p = st.product(productId);
      LocalDate today = now().toLocalDate(), begin = today.minusDays(28);
      Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
      for (var o : st.offers()) {
        String d = s(o, "checked_date");
        if (!historical(o)
            || !comparable(p, st.product(s(o, "product_id")))
            || !s(o, "channel").equals(channel)
            || !def(o, "condition", "일반").equals(condition)
            || d.compareTo(begin.toString()) < 0
            || d.compareTo(today.toString()) >= 0) continue;
        String k = d + key(o);
        if (!latest.containsKey(k) || observationCompare(o, latest.get(k)) >= 0) latest.put(k, o);
      }
      Map<String, List<BigDecimal>> daily = new TreeMap<>();
      for (var o : latest.values())
        daily
            .computeIfAbsent(s(o, "checked_date"), k -> new ArrayList<>())
            .add(unitPrice(o, st.product(s(o, "product_id"))));
      BigDecimal sum = BigDecimal.ZERO;
      for (var values : daily.values())
        sum =
            sum.add(
                values.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), MC));
      BigDecimal mean = daily.isEmpty() ? null : sum.divide(BigDecimal.valueOf(daily.size()), MC);
      return m(
          "start",
          begin.toString(),
          "end",
          today.minusDays(1).toString(),
          "days",
          daily.size(),
          "mean",
          mean,
          "basis",
          daily.size() < 4 ? null : mean.multiply(new BigDecimal("0.9")));
    }
  }

  /**
   * Executes VIEW, SET_TARGET, CLEAR_TARGET or EVALUATE for favorites.
   *
   * <p>Supply product_id or product_ids. SET_TARGET requires enabled and optional positive
   * unit-price target; an empty target uses the four-week basis. channel/condition are retained. A
   * personal target takes precedence even with fewer than four historical days. EVALUATE saves the
   * per-product local-date guard before returning notifications.
   *
   * @param request action, IDs and optional price/enabled/channel/condition
   * @return status, items and notifications. Failed alert persistence returns no notifications.
   */
  public Map<String, Object> managePurchaseTargets(Map<String, Object> request) {
    return call(
        request,
        () -> {
          String action = s(request, "action");
          if (!List.of("VIEW", "SET_TARGET", "CLEAR_TARGET", "EVALUATE").contains(action))
            throw new IllegalArgumentException("목표 action을 확인하세요.");
          AppState st = repo.snapshot();
          List<Object> ids =
              request.containsKey("product_ids")
                  ? list(request.get("product_ids"))
                  : new ArrayList<>(List.of(text(request, "product_id")));
          String today = now().toLocalDate().toString();
          if (List.of("SET_TARGET", "CLEAR_TARGET").contains(action)) {
            if (ids.size() != 1) throw new IllegalArgumentException("하나의 상품을 선택하세요.");
            String pid = ids.get(0).toString();
            if (!st.array("favorites").contains(pid))
              throw new IllegalArgumentException("즐겨찾기에 먼저 등록하세요.");
            conditions(request);
            String price = action.equals("CLEAR_TARGET") ? "" : s(request, "price");
            st.map("targets")
                .put(
                    pid,
                    m(
                        "price",
                        price.isBlank() ? null : num(price, false).toPlainString(),
                        "enabled",
                        bool(request.get("enabled")),
                        "channel",
                        def(request, "channel", "온라인"),
                        "condition",
                        def(request, "condition", "일반")));
            var saved = repo.saveAtomic(st);
            if (!s(saved, "status").equals("OK")) return saved;
          }
          List<Object> items = new ArrayList<>(), notifications = new ArrayList<>();
          for (Object id : new LinkedHashSet<>(ids)) {
            String pid = id.toString();
            if (!st.array("favorites").contains(pid)) continue;
            var target =
                obj(
                    st.map("targets")
                        .getOrDefault(
                            pid,
                            m(
                                "price",
                                null,
                                "enabled",
                                false,
                                "channel",
                                "온라인",
                                "condition",
                                "일반")));
            var calc = basis(pid, def(target, "channel", "온라인"), def(target, "condition", "일반"));
            BigDecimal threshold =
                target.get("price") == null
                    ? (BigDecimal) calc.get("basis")
                    : num(target.get("price"), false);
            List<Object> matches = new ArrayList<>();
            for (var o : latest(st))
              if (s(o, "product_id").equals(pid)
                  && s(o, "channel").equals(def(target, "channel", "온라인"))
                  && def(o, "condition", "일반").equals(def(target, "condition", "일반"))
                  && validity(o, now()).equals("현재 유효")
                  && threshold != null
                  && unitPrice(o, st.product(pid)).compareTo(threshold) <= 0) {
                var row = copyMap(o);
                row.put("unit_price", unitPrice(o, st.product(pid)));
                matches.add(row);
              }
            String status = "NOT_EVALUATED";
            if (action.equals("EVALUATE")) {
              status =
                  !yes(target, "enabled")
                      ? "DISABLED"
                      : threshold == null
                          ? "NO_BASIS"
                          : today.equals(st.map("alerts").get(pid))
                              ? "ALREADY_SENT"
                              : matches.isEmpty() ? "NOT_REACHED" : "SENT";
              if (status.equals("SENT")) {
                st.map("alerts").put(pid, today);
                notifications.add(
                    m("product_id", pid, "name", s(st.product(pid), "name"), "matches", matches));
              }
            }
            calc.putAll(
                m(
                    "product_id",
                    pid,
                    "name",
                    s(st.product(pid), "name"),
                    "target",
                    target,
                    "applied",
                    threshold,
                    "matches",
                    matches,
                    "notificationStatus",
                    status,
                    "last_alert",
                    st.map("alerts").get(pid)));
            items.add(calc);
          }
          if (!notifications.isEmpty()) {
            var saved = repo.saveAtomic(st);
            if (!s(saved, "status").equals("OK")) {
              for (var item : rows(items))
                if (s(item, "notificationStatus").equals("SENT"))
                  item.put("notificationStatus", "SAVE_FAILED");
              saved.putAll(m("items", items, "notifications", new ArrayList<>()));
              return saved;
            }
          }
          return ok("items", items, "notifications", notifications);
        });
  }
}
