package sgl;

import static sgl.Data.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** FN03 / UC03: product discovery, manual observations, local feeds and favorites. */
public final class ProductService extends Service {
  private final MartAdapter adapter;
  private final TargetService targets;

  /**
   * Creates a product service.
   *
   * @param repository shared storage
   * @param adapter local source reader
   * @param targets purchase-target service called only after price data commits; may be null
   * @param clock observation and validity time source
   */
  public ProductService(
      Repository repository, MartAdapter adapter, TargetService targets, Clock clock) {
    super(repository, clock);
    this.adapter = adapter;
    this.targets = targets;
  }

  private String importOffer(
      AppState st, Map<String, Object> product, Map<String, Object> offer, String method) {
    var p = copyMap(product);
    p.putIfAbsent("id", id());
    p.put("size", num(p.get("size"), false).stripTrailingZeros().toPlainString());
    p.put("pack_count", integer(p.get("pack_count"), false));
    product(p);
    String pid = s(p, "id");
    if (st.map("products").containsKey(pid)) {
      var old = st.product(pid);
      for (String k : List.of("name", "brand", "category", "spec", "unit"))
        if (!s(p, k).equals(s(old, k)))
          throw new IllegalArgumentException("기존 상품 정보는 새 ID로 등록하세요.");
      if (num(p.get("size"), false).compareTo(num(old.get("size"), false)) != 0
          || integer(p.get("pack_count"), false) != integer(old.get("pack_count"), false))
        throw new IllegalArgumentException("기존 규격은 새 ID로 등록하세요.");
    }
    var o = copyMap(offer);
    o.putAll(
        m(
            "id",
            id(),
            "product_id",
            pid,
            "method",
            method,
            "failed",
            false,
            "price",
            num(o.get("price"), false).toPlainString()));
    for (var e :
        m("condition", "일반", "branch", "전체", "checked_at", "", "start", "", "end", "").entrySet())
      o.putIfAbsent(e.getKey(), e.getValue());
    offer(o, p);
    if (LocalDate.parse(s(o, "checked_date")).isAfter(now().toLocalDate())
        || (!s(o, "checked_at").isEmpty()
            && OffsetDateTime.parse(s(o, "checked_at")).isAfter(now())))
      throw new IllegalArgumentException("미래 확인 날짜/시각은 입력할 수 없습니다.");
    st.array("offers")
        .removeIf(
            x -> {
              var a = obj(x);
              return key(a).equals(key(o))
                  && s(a, "checked_date").equals(s(o, "checked_date"))
                  && s(a, "checked_at").equals(s(o, "checked_at"));
            });
    st.map("products").put(pid, p);
    st.array("offers").add(o);
    return validity(o, now()).equals("현재 유효") ? pid : null;
  }

  /**
   * Executes QUERY, UPSERT_MANUAL, SET_FAVORITE, CONFIGURE_FEED, REFRESH or AUTO_REFRESH.
   *
   * <p>QUERY accepts category/store/channel filters. UPSERT_MANUAL requires product and offer
   * objects documented in the package overview. SET_FAVORITE requires product_id and boolean
   * isFavorite. CONFIGURE_FEED requires store and local path (empty removes). REFRESH optionally
   * selects one store. AUTO_REFRESH attempts configured stores at most once per local day,
   * including failed attempts. Each store is imported transactionally.
   *
   * @param request action and action-specific fields
   * @return status plus offers for QUERY; failures, updated IDs and target_result for updates.
   *     Possible statuses include PARTIAL_FAILURE, REFRESH_FAILED, SAVE_FAILED and INVALID_INPUT.
   */
  public Map<String, Object> manageProducts(Map<String, Object> request) {
    return call(
        request,
        () -> {
          AppState st = repo.snapshot();
          String action = s(request, "action");
          Set<String> changed = new LinkedHashSet<>();
          Map<String, Object> failures = m();
          List<String> successes = new ArrayList<>();
          if (action.equals("QUERY")) {
            List<Object> result = new ArrayList<>();
            for (var o : latest(st)) {
              var p = st.product(s(o, "product_id"));
              if (!s(request, "category").isEmpty()
                  && !s(request, "category").equals(s(p, "category"))) continue;
              if (!s(request, "store").isEmpty() && !s(request, "store").equals(s(o, "store")))
                continue;
              if (!s(request, "channel").isEmpty()
                  && !s(request, "channel").equals(s(o, "channel"))) continue;
              var row = copyMap(o);
              row.putAll(
                  m(
                      "product",
                      p,
                      "unit_price",
                      unitPrice(o, p),
                      "validity",
                      validity(o, now()),
                      "favorite",
                      st.array("favorites").contains(s(p, "id"))));
              result.add(row);
            }
            return ok(
                "offers", result, "products", st.map("products"), "sources", st.map("sources"));
          }
          if (action.equals("SET_FAVORITE")) {
            String pid = text(request, "product_id");
            st.product(pid);
            boolean enabled = bool(request.get("isFavorite"));
            st.array("favorites").remove(pid);
            if (enabled) st.array("favorites").add(pid);
          } else if (action.equals("CONFIGURE_FEED")) {
            String store = text(request, "store");
            if (!STORES.contains(store)) throw new IllegalArgumentException("지원 마트를 선택하세요.");
            String path = s(request, "path");
            if (path.isBlank()) st.map("feed_paths").remove(store);
            else
              st.map("feed_paths")
                  .put(store, Path.of(path).toAbsolutePath().normalize().toString());
          } else if (action.equals("UPSERT_MANUAL")) {
            String pid =
                importOffer(st, obj(request.get("product")), obj(request.get("offer")), "직접 입력");
            if (pid != null) changed.add(pid);
          } else if (List.of("REFRESH", "AUTO_REFRESH").contains(action)) {
            boolean auto = action.equals("AUTO_REFRESH");
            String today = now().toLocalDate().toString();
            if (auto && st.value("auto_attempt").equals(today)) return ok("skipped", true);
            if (auto) st.put("auto_attempt", today);
            List<String> stores = new ArrayList<>();
            if (!s(request, "store").isEmpty()) stores.add(text(request, "store"));
            else if (auto) stores.addAll(st.map("feed_paths").keySet());
            else stores.addAll(STORES);
            for (String store : stores) {
              if (!STORES.contains(store)) throw new IllegalArgumentException("지원 마트를 선택하세요.");
              try {
                String path = s(st.map("feed_paths"), store);
                if (path.isEmpty())
                  throw new IOException("피드가 설정되지 않았습니다. 직접 입력 또는 JSON 피드를 사용하세요.");
                var feed = adapter.fetchOffers(store, Path.of(path));
                Map<String, Object> products = m();
                for (var p : rows(feed.get("products"))) {
                  String pid = text(p, "id");
                  if (products.put(pid, p) != null) throw new IllegalArgumentException("중복 상품 ID");
                }
                AppState candidate = st.copy();
                Set<String> ids = new LinkedHashSet<>();
                int count = 0;
                for (var o : rows(feed.get("offers")))
                  if (s(o, "store").equals(store)) {
                    count++;
                    String pid =
                        importOffer(candidate, obj(products.get(s(o, "product_id"))), o, "JSON 피드");
                    if (pid != null) ids.add(pid);
                  }
                if (count == 0) throw new IllegalArgumentException("선택 마트의 가격 정보가 없습니다.");
                st = candidate;
                changed.addAll(ids);
                successes.add(store);
                st.map("sources")
                    .put(store, m("last_success", now().toString(), "failed", false, "error", ""));
              } catch (IOException | IllegalArgumentException | DateTimeException e) {
                failures.put(store, e.getMessage());
                var status =
                    st.map("sources").containsKey(store) ? obj(st.map("sources").get(store)) : m();
                status.putAll(m("failed", true, "error", e.getMessage()));
                st.map("sources").put(store, status);
                for (var o : st.offers()) if (s(o, "store").equals(store)) o.put("failed", true);
              }
            }
          } else throw new IllegalArgumentException("지원하지 않는 상품 action입니다.");
          var saved = repo.saveAtomic(st);
          if (!s(saved, "status").equals("OK")) {
            saved.put("draft", request);
            return saved;
          }
          Map<String, Object> targetResult = null;
          if (!changed.isEmpty() && targets != null)
            targetResult =
                targets.managePurchaseTargets(
                    m("action", "EVALUATE", "product_ids", new ArrayList<>(changed)));
          return m(
              "status",
              failures.isEmpty()
                  ? "OK"
                  : successes.isEmpty() ? "REFRESH_FAILED" : "PARTIAL_FAILURE",
              "failures",
              failures,
              "updated",
              new ArrayList<>(changed),
              "target_result",
              targetResult);
        });
  }
}
