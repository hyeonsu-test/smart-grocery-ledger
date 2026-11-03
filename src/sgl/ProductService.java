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

  private Product readProduct(Map<String, Object> product) {
    var input = copyMap(product);
    input.putIfAbsent("id", id());
    return Product.fromMap(input);
  }

  private String importOffer(AppState st, Product p, Map<String, Object> offer, String method) {
    var row = copyMap(offer);
    row.putAll(m("id", id(), "product_id", p.id(), "method", method, "failed", false));
    PriceObservation observation = PriceObservation.fromMap(row);
    if (observation.checkedAt() == null
        ? observation.checkedDate().isAfter(now().toLocalDate())
        : observation.checkedAt().isAfter(now()))
      throw new IllegalArgumentException("미래 확인 날짜/시각은 입력할 수 없습니다.");
    st.addProduct(p);
    st.record(observation);
    return observation.validity(now()).equals("현재 유효") ? p.id() : null;
  }

  /**
   * Executes QUERY, UPSERT_MANUAL, SET_FAVORITE, CONFIGURE_FEED, REFRESH or AUTO_REFRESH.
   *
   * <p>QUERY accepts category/store/channel filters and optional promotions_only.
   * Without store/channel/promotion filters, products without observations are returned with
   * null price/unit_price and validity "가격 미확인". UPSERT_MANUAL requires product and offer
   * objects documented in the package overview. SET_FAVORITE requires product_id and boolean
   * isFavorite. CONFIGURE_FEED requires store and local path (empty removes). REFRESH optionally
   * selects one store; otherwise only configured stores are attempted. AUTO_REFRESH attempts
   * configured stores at most once per local day, including failed attempts. Each store is imported
   * transactionally. A failed feed invalidates feed observations, while independently confirmed
   * manual observations retain their own timestamps and validity.
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
            boolean promotionsOnly = yes(request, "promotions_only");
            for (var o : st.latest()) {
              var p = st.product(o.productId());
              if (!s(request, "category").isEmpty() && !s(request, "category").equals(p.category()))
                continue;
              if (!s(request, "store").isEmpty() && !s(request, "store").equals(o.store()))
                continue;
              if (!s(request, "channel").isEmpty() && !s(request, "channel").equals(o.channel()))
                continue;
              if (promotionsOnly && (!o.isPromotion() || !o.validity(now()).equals("현재 유효")))
                continue;
              var row = o.toMap();
              row.putAll(
                  m(
                      "product",
                      p.toMap(),
                      "unit_price",
                      p.unitPrice(o.price()),
                      "validity",
                      o.validity(now()),
                      "favorite",
                      st.array("favorites").contains(p.id())));
              result.add(row);
            }
            if (!promotionsOnly && s(request, "store").isEmpty() && s(request, "channel").isEmpty()) {
              Set<String> priced = new HashSet<>();
              for (var o : st.offers()) priced.add(o.productId());
              for (var p : st.products()) {
                if (priced.contains(p.id())
                    || !s(request, "category").isEmpty() && !s(request, "category").equals(p.category()))
                  continue;
                result.add(m("product_id", p.id(), "product", p.toMap(), "price", null,
                    "unit_price", null, "validity", "가격 미확인",
                    "favorite", st.array("favorites").contains(p.id())));
              }
            }
            return ok("offers", result, "products", st.productJson(), "sources", st.map("sources"));
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
                importOffer(
                    st,
                    readProduct(obj(request.get("product"))),
                    obj(request.get("offer")),
                    "직접 입력");
            if (pid != null) changed.add(pid);
          } else if (List.of("REFRESH", "AUTO_REFRESH").contains(action)) {
            boolean auto = action.equals("AUTO_REFRESH");
            String today = now().toLocalDate().toString();
            if (auto && st.value("auto_attempt").equals(today)) return ok("skipped", true);
            if (auto) st.put("auto_attempt", today);
            List<String> stores = new ArrayList<>();
            if (!s(request, "store").isEmpty()) stores.add(text(request, "store"));
            else stores.addAll(st.map("feed_paths").keySet());
            for (String store : stores) {
              if (!STORES.contains(store)) throw new IllegalArgumentException("지원 마트를 선택하세요.");
              try {
                String path = s(st.map("feed_paths"), store);
                if (path.isEmpty())
                  throw new IOException("피드가 설정되지 않았습니다. 직접 입력 또는 JSON 피드를 사용하세요.");
                var feed = adapter.fetchOffers(store, Path.of(path));
                Map<String, Map<String, Object>> feedProducts = new LinkedHashMap<>();
                for (var p : rows(feed.get("products"))) {
                  String pid = text(p, "id");
                  if (feedProducts.put(pid, p) != null)
                    throw new IllegalArgumentException("중복 상품 ID");
                }
                AppState candidate = st.copy();
                for (var p : feedProducts.values()) candidate.addProduct(Product.fromMap(p));
                Set<String> ids = new LinkedHashSet<>();
                int count = 0;
                for (var raw : rows(feed.get("offers")))
                  if (s(raw, "store").equals(store)) {
                    count++;
                    String pid =
                        importOffer(
                            candidate,
                            Product.fromMap(obj(feedProducts.get(s(raw, "product_id")))),
                            raw,
                            "JSON 피드");
                    if (pid != null) ids.add(pid);
                  }
                if (count == 0 && (feedProducts.isEmpty() || !rows(feed.get("offers")).isEmpty()))
                  throw new IllegalArgumentException("선택 마트의 가격 정보가 없습니다.");
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
                st.failFeed(store);
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
