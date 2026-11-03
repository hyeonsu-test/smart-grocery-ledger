package sgl;

import static sgl.Data.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Fetches outside the repository lock, then merges results with the latest saved ledger. */
public final class OnlineCollectionService extends Service {
  private final ProductCollector collector;
  private final TargetService targets;
  private final AtomicBoolean running = new AtomicBoolean();

  /**
   * Creates the collection coordinator.
   * @param repository shared ledger
   * @param collector read-only public price collector
   * @param targets alert service invoked after a successful commit
   * @param clock local scheduling clock
   */
  public OnlineCollectionService(Repository repository, ProductCollector collector,
                                  TargetService targets, Clock clock) {
    super(repository, clock);
    this.collector = Objects.requireNonNull(collector);
    this.targets = targets;
  }

  /**
   * Collects prices on request, or at most once per local day in automatic mode.
   * Network reads never hold the ledger lock. Current user edits and favorites are preserved.
   * Independent failures keep historical observations and mark affected web prices unavailable.
   * @param automatic whether to apply the daily guard and skip demo data
   * @param progress progress consumer; called on the invoking worker thread
   * @return status, successful/failed counts, source details and optional target result
   */
  public Map<String, Object> collect(boolean automatic, Consumer<String> progress) {
    if (!running.compareAndSet(false, true)) return m("status", "BUSY");
    try {
      String today = LocalDate.now(clock).toString();
      List<CollectionSource> requested;
      synchronized (repo) {
        if (!repo.readError().isEmpty()) return m("status", "READ_FAILED", "message", repo.readError());
        if (repo.snapshot().demo()) return ok("skipped", true, "message", "시연 모드에서는 실제 가격을 섞지 않습니다.");
        if (automatic && repo.snapshot().value("online_attempt").equals(today))
          return ok("skipped", true);
        requested=repo.snapshot().collectionSources();
      }
      if(requested.stream().noneMatch(CollectionSource::enabled))
        return ok("skipped",true,"message","자동 수집할 상품이 없습니다. 수집 목록에서 상품을 추가하거나 켜세요.");
      List<ProductCollector.Result> results = collector.collect(requested.stream().filter(CollectionSource::enabled).toList(),progress);
      if (results.isEmpty()) return m("status", "REFRESH_FAILED", "message", "수집 대상이 없습니다.");
      synchronized (repo) {
        AppState candidate = repo.snapshot();
        int count = 0;
        Set<String> updated = new LinkedHashSet<>();
        Map<String, Object> failures = m(), details = m();
        Map<String,CollectionSource> active=new HashMap<>();
        for(var source:candidate.collectionSources())if(source.enabled())active.put(source.key(),source);
        for (var result : results) {
          CollectionSource selected=active.get(CollectionSource.keyOf(result.url()));
          if(selected==null || !selected.product().equals(result.product()))continue;
          var detail = obj(details.computeIfAbsent(result.store(),
              k -> m("success", 0, "failed_count", 0, "errors", m())));
          if (result.observation() == null) {
            failures.put(result.product().name() + " / " + result.store(), result.error());
            obj(detail.get("errors")).put(result.product().name(), result.error());
            detail.put("failed_count", integer(detail.get("failed_count"), true) + 1);
            candidate.failOnline(result.url());
          } else {
            candidate.addProduct(result.product());
            candidate.record(result.observation());
            updated.add(result.product().id());
            count++;
            detail.put("success", integer(detail.get("success"), true) + 1);
          }
        }
        for (var entry : details.entrySet()) {
          var detail = obj(entry.getValue());
          var status = copyMap(obj(candidate.map("sources").getOrDefault(entry.getKey(), m())));
          boolean failed = integer(detail.get("failed_count"), true) > 0;
          status.putAll(m("failed", failed, "error", Json.stringify(detail.get("errors")),
              "success_count", detail.get("success"), "failed_count", detail.get("failed_count"),
              "last_attempt", now().toString(), "method", "웹 자동 수집"));
          if (integer(detail.get("success"), true) > 0) status.put("last_success", now().toString());
          candidate.map("sources").put(entry.getKey(), status);
        }
        if(requested.equals(candidate.collectionSources()))candidate.put("online_attempt", today);
        var saved = repo.saveAtomic(candidate);
        if (!s(saved, "status").equals("OK")) return saved;
        Map<String, Object> targetResult = null;
        if (!updated.isEmpty() && targets != null)
          targetResult = targets.managePurchaseTargets(m("action", "EVALUATE", "product_ids", new ArrayList<>(updated)));
        return m("status", failures.isEmpty() ? "OK" : count == 0 ? "REFRESH_FAILED" : "PARTIAL_FAILURE",
            "successful", count, "failed", failures.size(), "failures", failures,
            "sources", details, "target_result", targetResult);
      }
    } catch (RuntimeException e) {
      return m("status", "REFRESH_FAILED", "message",
          e.getMessage() == null ? "수집 처리 실패" : e.getMessage());
    } finally {
      running.set(false);
    }
  }
}

