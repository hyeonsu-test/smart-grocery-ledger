package sgl;

import static sgl.Data.*;

import java.time.Clock;
import java.util.*;

/** FN01 / UC01: actual income and expense entry, editing, deletion and monthly search. */
public final class LedgerService extends Service {
  /**
   * Creates a ledger service.
   *
   * @param repository shared storage
   * @param clock current local time source
   */
  public LedgerService(Repository repository, Clock clock) {
    super(repository, clock);
  }

  /**
   * Executes CREATE, UPDATE, DELETE or SEARCH.
   *
   * <p>CREATE/UPDATE require date, kind, category, positive integer amount; UPDATE/DELETE require
   * recordId; CREATE rejects a nonempty recordId. DELETE requires confirmed=true. SEARCH accepts
   * month (YYYY-MM), category and keyword. Optional purchase fields: product_id, store, quantity,
   * memo. store is an optional counterparty for any entry. A linked purchase requires a food
   * expense and a positive package quantity; unlinked entries have no purchase quantity.
   *
   * @param request action and fields described above
   * @return status, records and unfiltered monthly summary for SEARCH; affected months after a
   *     save. INVALID_INPUT retains draft; CANCELLED, NOT_FOUND, READ_FAILED and SAVE_FAILED never
   *     commit.
   */
  public Map<String, Object> manageLedger(Map<String, Object> request) {
    return call(
        request,
        () -> {
          AppState st = repo.snapshot();
          String action = s(request, "action");
          if (yes(request, "cancelled")) return m("status", "CANCELLED");
          if (action.equals("CREATE") && !s(request, "recordId").isEmpty())
            throw new IllegalArgumentException("기존 내역 ID로 새 내역을 등록할 수 없습니다. UPDATE를 사용하세요.");
          String mon = month(def(request, "month", now().toLocalDate().toString().substring(0, 7)));
          if (action.equals("SEARCH")) {
            List<Object> found = new ArrayList<>();
            for (var r : st.records())
              if (s(r, "date").startsWith(mon)
                  && (s(request, "category").isEmpty()
                      || s(r, "category").equals(s(request, "category")))
                  && Json.stringify(r)
                      .toLowerCase(Locale.ROOT)
                      .contains(s(request, "keyword").toLowerCase(Locale.ROOT))) found.add(r);
            found.sort((a, b) -> s(obj(b), "date").compareTo(s(obj(a), "date")));
            return ok("records", found, "summary", monthly(st, mon));
          }
          Map<String, Object> old =
              st.records().stream()
                  .filter(r -> s(r, "id").equals(s(request, "recordId")))
                  .findFirst()
                  .orElse(null);
          Set<String> affected = new LinkedHashSet<>();
          if (List.of("UPDATE", "DELETE").contains(action) && old == null)
            return m("status", "NOT_FOUND");
          if (action.equals("DELETE")) {
            if (!yes(request, "confirmed")) return m("status", "CANCELLED");
            st.array("records").remove(old);
            affected.add(s(old, "date").substring(0, 7));
          } else if (List.of("CREATE", "UPDATE").contains(action)) {
            String d = day(request.get("date"));
            String kind = text(request, "kind"), category = text(request, "category");
            if (!List.of("수입", "지출").contains(kind))
              throw new IllegalArgumentException("수입/지출을 선택하세요.");
            String pid = s(request, "product_id");
            if (!pid.isEmpty()) {
              st.product(pid);
              if (!kind.equals("지출") || !category.equals("식료품"))
                throw new IllegalArgumentException("상품 연결 내역은 식료품 지출입니다. 다른 분류는 상품 연결을 해제하세요.");
            }
            if (kind.equals("수입") && EXPENSE_CATEGORIES.contains(category) && !category.equals("기타")
                || kind.equals("지출") && INCOME_CATEGORIES.contains(category) && !category.equals("기타"))
              throw new IllegalArgumentException("수입·지출 구분에 맞는 분류를 선택하세요.");
            Map<String, Object> row =
                m(
                    "id",
                    old == null ? id() : s(old, "id"),
                    "date",
                    d,
                    "kind",
                    kind,
                    "category",
                    category,
                    "amount",
                    integer(request.get("amount"), false),
                    "memo",
                    s(request, "memo"),
                    "store",
                    s(request, "store"),
                    "product_id",
                    pid,
                    "quantity",
                    pid.isEmpty() ? 1 : integer(request.getOrDefault("quantity", 1), false));
            if (old != null) {
              st.array("records").remove(old);
              affected.add(s(old, "date").substring(0, 7));
            }
            st.array("records").add(row);
            affected.add(d.substring(0, 7));
          } else throw new IllegalArgumentException("지원하지 않는 내역 action입니다.");
          var saved = repo.saveAtomic(st);
          if (!s(saved, "status").equals("OK")) {
            saved.put("draft", request);
            return saved;
          }
          Map<String, Object> months = m();
          for (String m : affected) months.put(m, monthly(st, m));
          return ok("months", months);
        });
  }
}
