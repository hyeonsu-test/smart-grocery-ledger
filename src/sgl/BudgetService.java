package sgl;

import static sgl.Data.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;

/** FN02 / UC02: food budget, unsaved shopping plan and conservative estimates. */
public final class BudgetService extends Service {
  /**
   * Creates a budget service.
   *
   * @param repository shared storage
   * @param clock price-validity time source
   */
  public BudgetService(Repository repository, Clock clock) {
    super(repository, clock);
  }

  /**
   * Executes GET, SET_BUDGET, UPSERT_ITEM, REMOVE_ITEM or SAVE_PLAN.
   *
   * <p>All requests require month. SET_BUDGET requires a nonnegative integer budget. UPSERT_ITEM
   * requires product_id and positive integer quantity, optionally itemId, store, channel,
   * condition, branch. draftPlan is an optional list of plan items. Edits only return a draft;
   * SAVE_PLAN commits it and never creates an expense.
   *
   * @param request action, month and action-specific fields
   * @return status, draftPlan, items, validSubtotal, unknownCount, totalEstimate, projectedBalance
   *     and excess. Unknown prices make totalEstimate/excess null.
   */
  public Map<String, Object> manageBudgetPlan(Map<String, Object> request) {
    return call(
        request,
        () -> {
          AppState st = repo.snapshot();
          String mon = month(request.get("month")), action = s(request, "action");
          List<Object> draft =
              list(
                  Json.copy(
                      request.getOrDefault(
                          "draftPlan", st.map("plans").getOrDefault(mon, new ArrayList<>()))));
          if (action.equals("UPSERT_ITEM")) {
            String pid = text(request, "product_id");
            st.product(pid);
            String iid = def(request, "itemId", id());
            var item =
                m(
                    "itemId",
                    iid,
                    "product_id",
                    pid,
                    "quantity",
                    integer(request.get("quantity"), false),
                    "store",
                    s(request, "store"),
                    "channel",
                    def(request, "channel", "온라인"),
                    "condition",
                    def(request, "condition", "일반"),
                    "branch",
                    s(request, "branch"));
            draft.removeIf(x -> s(obj(x), "itemId").equals(iid));
            draft.add(item);
          } else if (action.equals("REMOVE_ITEM"))
            draft.removeIf(x -> s(obj(x), "itemId").equals(s(request, "itemId")));
          else if (action.equals("SET_BUDGET"))
            st.map("budgets").put(mon, integer(request.get("budget"), true));
          else if (!List.of("GET", "SAVE_PLAN").contains(action))
            throw new IllegalArgumentException("지원하지 않는 계획 action입니다.");
          Set<String> ids = new HashSet<>();
          for (var item : rows(draft)) {
            st.product(s(item, "product_id"));
            integer(item.get("quantity"), false);
            conditions(item);
            if (!s(item, "store").isEmpty() && !STORES.contains(s(item, "store")))
              throw new IllegalArgumentException("지원 마트를 선택하세요.");
            if (!ids.add(text(item, "itemId"))) throw new IllegalArgumentException("중복 계획 ID입니다.");
          }
          if (action.equals("SAVE_PLAN")) st.map("plans").put(mon, draft);
          if (List.of("SAVE_PLAN", "SET_BUDGET").contains(action)) {
            var saved = repo.saveAtomic(st);
            if (!s(saved, "status").equals("OK")) {
              saved.put("draftPlan", draft);
              return saved;
            }
          }
          BigDecimal subtotal = BigDecimal.ZERO;
          int unknown = 0;
          List<Object> items = new ArrayList<>();
          for (var item : rows(draft)) {
            BigDecimal best = null;
            for (var o : st.latest())
              if (o.productId().equals(s(item, "product_id"))
                  && o.validity(now()).equals("현재 유효")
                  && (s(item, "store").isEmpty() || o.store().equals(s(item, "store")))
                  && o.channel().equals(def(item, "channel", "온라인"))
                  && o.condition().equals(def(item, "condition", "일반"))
                  && (s(item, "branch").isEmpty() || o.branch().equals(s(item, "branch")))) {
                BigDecimal price = o.price();
                best = best == null ? price : best.min(price);
              }
            BigDecimal cost = best == null ? null : best.multiply(num(item.get("quantity"), false));
            if (cost == null) unknown++;
            else subtotal = subtotal.add(cost);
            var row = copyMap(item);
            row.putAll(
                m(
                    "name",
                    st.product(s(item, "product_id")).name(),
                    "price",
                    best,
                    "estimate",
                    cost));
            items.add(row);
          }
          var summary = monthly(st, mon);
          BigDecimal total = unknown > 0 ? null : subtotal,
              balance =
                  total == null || summary.get("remaining") == null
                      ? null
                      : new BigDecimal(summary.get("remaining").toString()).subtract(total);
          return ok(
              "summary",
              summary,
              "draftPlan",
              draft,
              "items",
              items,
              "validSubtotal",
              subtotal,
              "unknownCount",
              unknown,
              "totalEstimate",
              total,
              "projectedBalance",
              balance,
              "excess",
              balance == null ? null : balance.negate().max(BigDecimal.ZERO));
        });
  }
}
