package sgl;

import static sgl.Data.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Dependency-free regression runner. Exits nonzero on any failure; each test has an isolated file.
 */
public final class ServiceTests {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneId.of("Asia/Seoul"));
  private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
  private static int passed = 0, failed = 0;

  private static final class FailingRepository extends Repository {
    boolean fail;

    FailingRepository(Path p) {
      super(p);
    }

    @Override
    protected void replaceFile(Path a, Path b) throws IOException {
      if (fail) throw new IOException("injected write failure");
      super.replaceFile(a, b);
    }
  }

  // Fixture edits describe on-disk v1 input, independently of the production model.
  private record FixtureData(Map<String, Object> json) {
    Map<String, Object> map(String key) {
      return obj(json.get(key));
    }

    List<Object> array(String key) {
      return list(json.get(key));
    }

    List<Map<String, Object>> offers() {
      return rows(json.get("offers"));
    }

    void put(String key, Object value) {
      json.put(key, value);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Path directory, file;
    final FailingRepository repo;
    final LedgerService ledger;
    final BudgetService budget;
    final PriceService price;
    final TargetService target;
    final ProductService products;

    Fixture() throws IOException {
      directory = Files.createTempDirectory("sgl-한글 space-");
      file = directory.resolve("ledger.json");
      repo = new FailingRepository(file);
      status(repo.saveAtomic(DemoData.create(CLOCK)), "OK");
      ledger = new LedgerService(repo, CLOCK);
      budget = new BudgetService(repo, CLOCK);
      price = new PriceService(repo, CLOCK);
      target = new TargetService(repo, CLOCK);
      products = new ProductService(repo, new MartAdapter(), target, CLOCK);
    }

    Map<String, Object> current(String pid) {
      return repo.snapshot().offers().stream()
          .map(PriceObservation::toMap)
          .filter(
              o ->
                  s(o, "product_id").equals(pid)
                      && s(o, "store").equals("이마트")
                      && s(o, "checked_date").equals("2026-11-02"))
          .findFirst()
          .orElseThrow();
    }

    void modify(java.util.function.Consumer<FixtureData> mutation) {
      var fixture = new FixtureData(repo.snapshot().toMap());
      mutation.accept(fixture);
      status(repo.saveAtomic(new AppState(fixture.json)), "OK");
    }

    Map<String, Object> evaluate() {
      return target.managePurchaseTargets(m("action", "EVALUATE", "product_id", "milk"));
    }

    Map<String, Object> manual(Map<String, Object> offer) {
      return products.manageProducts(
          m(
              "action",
              "UPSERT_MANUAL",
              "product",
              repo.snapshot().product("milk").toMap(),
              "offer",
              offer));
    }

    @Override
    public void close() throws IOException {
      try (var paths = Files.walk(directory)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
    }
  }

  @FunctionalInterface
  private interface Test {
    void run(Fixture f) throws Exception;
  }

  private static void test(String name, Test test) {
    try (Fixture f = new Fixture()) {
      test.run(f);
      passed++;
      System.out.println("PASS " + name);
    } catch (Throwable e) {
      failed++;
      System.out.println("FAIL " + name + " : " + e);
      e.printStackTrace(System.out);
    }
  }

  private static void check(boolean condition) {
    if (!condition) throw new AssertionError("condition false");
  }

  private static void eq(Object got, Object want) {
    if (!Objects.equals(String.valueOf(got), String.valueOf(want)))
      throw new AssertionError("expected=" + want + " actual=" + got);
  }

  private static void decimal(Object got, String want) {
    if (got == null
        || new BigDecimal(got.toString())
                .subtract(new BigDecimal(want))
                .abs()
                .compareTo(new BigDecimal("0.000000000000000001"))
            > 0) throw new AssertionError("expected=" + want + " actual=" + got);
  }

  private static void status(Map<String, Object> result, String want) {
    eq(result.get("status"), want);
  }

  private static Map<String, Object> payment() {
    return m(
        "action", "CREATE", "date", "2026-11-02", "kind", "지출", "category", "식료품", "amount", 2500);
  }

  private static Map<String, Object> history() {
    return m(
        "mode",
        "HISTORY",
        "product_id",
        "milk",
        "store",
        "이마트",
        "branch",
        "시연 지점",
        "start",
        "2026-11-02",
        "end",
        "2026-11-02");
  }

  public static void main(String[] args) {
    System.out.println("Smart Grocery Ledger Java regression tests | " + OffsetDateTime.now());
    System.out.println(
        "OS="
            + System.getProperty("os.name")
            + " "
            + System.getProperty("os.arch")
            + " Java="
            + System.getProperty("java.version")
            + " | business clock="
            + NOW);
    test(
        "JSON unicode escape and decimal round trip",
        f -> {
          var value =
              m(
                  "text",
                  "한글\n\"\\\t",
                  "amount",
                  new BigDecimal("0.1"),
                  "list",
                  Arrays.asList(true, null, "🙂"));
          eq(Json.stringify(Json.parse(Json.stringify(value))), Json.stringify(value));
        });
    test(
        "JSON malformed duplicate keys trailing tokens rejected",
        f -> {
          for (String value :
              List.of(
                  "{bad",
                  "{\"a\":1,\"a\":2}",
                  "[1,]",
                  "01",
                  "1 true",
                  "\"bad\ntext\"",
                  "1e99999999")) {
            boolean rejected = false;
            try {
              Json.parse(value);
            } catch (IllegalArgumentException e) {
              rejected = true;
            }
            check(rejected);
          }
        });
    test(
        "UC01 actual payment commits and survives reload",
        f -> {
          var r = f.ledger.manageLedger(payment());
          status(r, "OK");
          decimal(obj(obj(r.get("months")).get("2026-11")).get("food"), "40600");
          eq(new Repository(f.file).snapshot().records().size(), 6);
        });
    test(
        "UC01 moved date recomputes both months",
        f -> {
          var req = payment();
          req.putAll(m("action", "UPDATE", "recordId", "demo-1", "date", "2026-10-31"));
          var r = f.ledger.manageLedger(req);
          status(r, "OK");
          check(obj(r.get("months")).keySet().equals(Set.of("2026-10", "2026-11")));
          decimal(obj(obj(r.get("months")).get("2026-10")).get("food"), "2500");
        });
    test(
        "UC01 cancelled and missing deletion never writes",
        f -> {
          byte[] before = Files.readAllBytes(f.file);
          status(
              f.ledger.manageLedger(
                  m("action", "DELETE", "recordId", "demo-1", "confirmed", false)),
              "CANCELLED");
          status(
              f.ledger.manageLedger(
                  m("action", "DELETE", "recordId", "missing", "confirmed", true)),
              "NOT_FOUND");
          check(Arrays.equals(before, Files.readAllBytes(f.file)));
        });
    test(
        "UC01 confirmed deletion persists",
        f -> {
          status(
              f.ledger.manageLedger(m("action", "DELETE", "recordId", "demo-1", "confirmed", true)),
              "OK");
          eq(new Repository(f.file).snapshot().records().size(), 4);
        });
    test(
        "UC01 invalid money and overflow rejected",
        f -> {
          byte[] before = Files.readAllBytes(f.file);
          for (Object n :
              List.of("NaN", "Infinity", -1, 0, "1.5", "abc", "99999999999999999999999999")) {
            var r = payment();
            r.put("amount", n);
            status(f.ledger.manageLedger(r), "INVALID_INPUT");
          }
          check(Arrays.equals(before, Files.readAllBytes(f.file)));
        });
    test(
        "UC01 invalid date and malformed month rejected",
        f -> {
          var r = payment();
          r.put("date", "2026-02-30");
          status(f.ledger.manageLedger(r), "INVALID_INPUT");
          status(f.ledger.manageLedger(m("action", "SEARCH", "month", "2026-2")), "INVALID_INPUT");
        });
    test(
        "UC01 actual purchase forces food expense",
        f -> {
          var r = payment();
          r.putAll(m("product_id", "milk", "kind", "수입", "category", "기타", "quantity", 2));
          status(f.ledger.manageLedger(r), "OK");
          var row = f.repo.snapshot().records().get(5);
          eq(s(row, "kind"), "지출");
          eq(s(row, "category"), "식료품");
          decimal(row.get("amount"), "2500");
        });
    test(
        "Repository atomic failure preserves disk memory and draft",
        f -> {
          byte[] before = Files.readAllBytes(f.file);
          String memory = Json.stringify(f.repo.snapshot().toMap());
          f.repo.fail = true;
          var r = f.ledger.manageLedger(payment());
          status(r, "SAVE_FAILED");
          eq(obj(r.get("draft")).get("amount"), 2500);
          eq(Json.stringify(f.repo.snapshot().toMap()), memory);
          check(Arrays.equals(before, Files.readAllBytes(f.file)));
        });
    test(
        "Repository corrupt file is read-only and never overwritten",
        f -> {
          Files.writeString(f.file, "{broken");
          Repository bad = new Repository(f.file);
          status(new LedgerService(bad, CLOCK).manageLedger(payment()), "READ_FAILED");
          status(bad.saveAtomic(new AppState()), "SAVE_FAILED");
          eq(Files.readString(f.file), "{broken");
        });
    test(
        "Repository nested corruption and version fail closed",
        f -> {
          for (String key : List.of("products", "version")) {
            var data = f.repo.snapshot().toMap();
            if (key.equals("products")) obj(data.get("products")).put("milk", null);
            else data.put(key, 99);
            Files.writeString(f.file, Json.stringify(data));
            check(!new Repository(f.file).readError().isEmpty());
          }
        });
    test(
        "Repository returned snapshots cannot mutate stored memory",
        f -> {
          f.repo.snapshot().array("records").clear();
          eq(f.repo.snapshot().records().size(), 5);
        });
    test(
        "UC01 search filters keep whole-month summary",
        f -> {
          var r =
              f.ledger.manageLedger(
                  m("action", "SEARCH", "month", "2026-11", "category", "식료품", "keyword", "우유"));
          eq(list(r.get("records")).size(), 1);
          decimal(obj(r.get("summary")).get("income"), "600000");
        });
    test(
        "UC02 draft does not save or create expense",
        f -> {
          byte[] before = Files.readAllBytes(f.file);
          var r =
              f.budget.manageBudgetPlan(
                  m(
                      "action",
                      "UPSERT_ITEM",
                      "month",
                      "2026-11",
                      "draftPlan",
                      new ArrayList<>(),
                      "product_id",
                      "milk",
                      "quantity",
                      2));
          decimal(r.get("totalEstimate"), "5200");
          check(Arrays.equals(before, Files.readAllBytes(f.file)));
          status(
              f.budget.manageBudgetPlan(
                  m("action", "SAVE_PLAN", "month", "2026-11", "draftPlan", r.get("draftPlan"))),
              "OK");
          eq(f.repo.snapshot().records().size(), 5);
          eq(list(f.repo.snapshot().map("plans").get("2026-11")).size(), 1);
        });
    test(
        "UC02 unknown price is not zero",
        f -> {
          f.modify(
              st -> {
                for (var o : st.offers())
                  if (s(o, "product_id").equals("milk")) o.put("failed", true);
              });
          var r = f.budget.manageBudgetPlan(m("action", "GET", "month", "2026-11"));
          eq(r.get("unknownCount"), 1);
          check(r.get("totalEstimate") == null && r.get("excess") == null);
        });
    test(
        "UC02 unset zero and exceeded budgets differ",
        f -> {
          f.modify(st -> st.map("budgets").clear());
          check(
              f.budget
                      .manageBudgetPlan(m("action", "GET", "month", "2026-11"))
                      .get("projectedBalance")
                  == null);
          var r =
              f.budget.manageBudgetPlan(m("action", "SET_BUDGET", "month", "2026-11", "budget", 0));
          status(r, "OK");
          check(new BigDecimal(r.get("excess").toString()).signum() > 0);
        });
    test(
        "UC02 quantity validation and duplicate item rejected",
        f -> {
          for (Object n : List.of(0, -1, "1.2"))
            status(
                f.budget.manageBudgetPlan(
                    m(
                        "action",
                        "UPSERT_ITEM",
                        "month",
                        "2026-11",
                        "product_id",
                        "milk",
                        "quantity",
                        n)),
                "INVALID_INPUT");
          var item = obj(list(f.repo.snapshot().map("plans").get("2026-11")).get(0));
          status(
              f.budget.manageBudgetPlan(
                  m("action", "SAVE_PLAN", "month", "2026-11", "draftPlan", List.of(item, item))),
              "INVALID_INPUT");
        });
    test(
        "UC02 draft preserved on failed save",
        f -> {
          f.repo.fail = true;
          var plan = f.repo.snapshot().map("plans").get("2026-11");
          var r =
              f.budget.manageBudgetPlan(
                  m("action", "SAVE_PLAN", "month", "2026-11", "draftPlan", plan));
          status(r, "SAVE_FAILED");
          check(r.containsKey("draftPlan"));
        });
    test(
        "UC04 all five unit conversions",
        f -> {
          Map<String, String> expected =
              Map.of(
                  "milk",
                  "260",
                  "eggs",
                  "400",
                  "ramen",
                  "720",
                  "rice",
                  "390",
                  "tofu",
                  "533.3333333333333333333333333333333");
          for (var e : expected.entrySet())
            decimal(
                PriceService.normalizeUnitPrice(
                    f.current(e.getKey()), f.repo.snapshot().product(e.getKey()).toMap()),
                e.getValue());
        });
    test(
        "Price freshness boundary and absent timestamp",
        f -> {
          var o = f.current("milk");
          o.put("checked_date", NOW.minusDays(1).toLocalDate().toString());
          o.put("checked_at", NOW.minusHours(24).toString());
          eq(PriceObservation.fromMap(o).validity(NOW), "오래됨");
          o.put("checked_at", "");
          eq(PriceObservation.fromMap(o).validity(NOW), "확인 시각 없음");
        });
    test(
        "Price future time and event bounds",
        f -> {
          var o = f.current("milk");
          o.put("checked_at", NOW.plusSeconds(1).toString());
          eq(PriceObservation.fromMap(o).validity(NOW), "미래 확인 시각");
          o.put("checked_at", NOW.toString());
          o.put("start", "2026-11-03");
          eq(PriceObservation.fromMap(o).validity(NOW), "행사 시작 전");
          o.put("start", "");
          o.put("end", "2026-11-01");
          eq(PriceObservation.fromMap(o).validity(NOW), "만료");
        });
    test(
        "UC04 failed and expired sources excluded",
        f -> {
          f.modify(
              st -> {
                for (var o : st.offers())
                  if (s(o, "store").equals("이마트")) o.put("failed", true);
                  else if (s(o, "store").equals("홈플러스")) o.put("end", "2026-11-01");
              });
          var r = f.price.analyzePrices(m("mode", "COMPARE", "product_id", "milk"));
          eq(list(r.get("ranked")).size(), 1);
          eq(list(r.get("excluded")).size(), 2);
        });
    test(
        "UC04 channel and membership are never mixed",
        f -> {
          f.modify(
              st -> {
                for (var o : st.offers()) o.put("condition", "회원");
              });
          status(
              f.price.analyzePrices(m("mode", "COMPARE", "product_id", "milk", "condition", "일반")),
              "NO_DATA");
          status(
              f.price.analyzePrices(
                  m("mode", "COMPARE", "product_id", "milk", "condition", "회원", "channel", "오프라인")),
              "NO_DATA");
        });
    test(
        "UC04 numeric-equivalent sizes and bundle counts compare",
        f -> {
          var p = f.repo.snapshot().product("milk").toMap();
          var b = copyMap(p);
          b.put("size", "1000.0");
          b.put("pack_count", 2);
          check(Product.fromMap(p).comparableTo(Product.fromMap(b)));
          b.put("brand", "다른브랜드");
          check(!Product.fromMap(p).comparableTo(Product.fromMap(b)));
        });
    test(
        "UC04 empty and reversed history",
        f -> {
          var r = history();
          r.putAll(m("start", "2027-01-01", "end", "2027-01-02"));
          var out = f.price.analyzePrices(r);
          status(out, "NO_DATA");
          check(out.get("mean") == null);
          r.put("start", "2027-02-01");
          status(f.price.analyzePrices(r), "INVALID_INPUT");
        });
    test(
        "UC04 one latest observation per day",
        f -> {
          f.modify(
              st -> {
                var o = f.current("milk");
                o.put("price", "3000");
                st.array("offers").add(o);
              });
          var r = f.price.analyzePrices(history());
          eq(r.get("count"), 1);
          decimal(r.get("mean"), "300");
        });
    test(
        "UC04 timestamps compare as instants across offsets",
        f -> {
          var earlier = f.current("milk");
          earlier.put("checked_at", "2026-11-02T11:00+09:00");
          var later = copyMap(earlier);
          later.put("checked_at", "2026-11-02T03:00Z");
          check(PriceObservation.fromMap(later).compareTime(PriceObservation.fromMap(earlier)) > 0);
        });
    test(
        "UC05 preceding 28 days excludes today",
        f -> {
          var before = f.target.basis("milk", "온라인", "일반");
          eq(before.get("days"), 8);
          f.modify(
              st -> {
                for (var o : st.offers())
                  if (s(o, "checked_date").equals("2026-11-02")) o.put("price", "999999");
              });
          eq(Json.stringify(f.target.basis("milk", "온라인", "일반")), Json.stringify(before));
        });
    test(
        "UC05 minimum four distinct observed days",
        f -> {
          f.modify(
              st ->
                  st.array("offers")
                      .removeIf(x -> s(obj(x), "checked_date").compareTo("2026-10-25") < 0));
          var r = f.target.basis("milk", "온라인", "일반");
          eq(r.get("days"), 3);
          check(r.get("basis") == null);
        });
    test(
        "UC05 daily means use equal day weights",
        f -> {
          f.modify(
              st -> {
                st.array("offers").clear();
                int[][] amounts = {{1000}, {2000, 4000, 6000}, {1000}, {1000}};
                for (int day = 0; day < amounts.length; day++)
                  for (int j = 0; j < amounts[day].length; j++) {
                    var o = f.current("milk");
                    OffsetDateTime stamp = NOW.minusDays(day + 1);
                    o.putAll(
                        m(
                            "store",
                            STORES.get(j),
                            "price",
                            String.valueOf(amounts[day][j]),
                            "checked_date",
                            stamp.toLocalDate().toString(),
                            "checked_at",
                            stamp.toString()));
                    st.array("offers").add(o);
                  }
              });
          decimal(f.target.basis("milk", "온라인", "일반").get("basis"), "157.5");
        });
    test(
        "UC05 personal target works without historical basis",
        f -> {
          f.modify(
              st ->
                  st.array("offers")
                      .removeIf(x -> !s(obj(x), "checked_date").equals("2026-11-02")));
          var r = f.evaluate();
          decimal(rows(r.get("items")).get(0).get("applied"), "270");
          eq(s(rows(r.get("items")).get(0), "notificationStatus"), "SENT");
        });
    test(
        "UC05 once per local day survives restart",
        f -> {
          eq(s(rows(f.evaluate().get("items")).get(0), "notificationStatus"), "SENT");
          var r =
              new TargetService(new Repository(f.file), CLOCK)
                  .managePurchaseTargets(m("action", "EVALUATE", "product_id", "milk"));
          eq(s(rows(r.get("items")).get(0), "notificationStatus"), "ALREADY_SENT");
          eq(list(r.get("notifications")).size(), 0);
        });
    test(
        "UC05 no notification when guard save fails",
        f -> {
          f.repo.fail = true;
          var r = f.evaluate();
          status(r, "SAVE_FAILED");
          eq(list(r.get("notifications")).size(), 0);
          eq(s(rows(r.get("items")).get(0), "notificationStatus"), "SAVE_FAILED");
          check(!f.repo.snapshot().map("alerts").containsKey("milk"));
        });
    test(
        "UC05 view empty favorites and disabled do not notify",
        f -> {
          eq(
              list(f.target
                      .managePurchaseTargets(m("action", "VIEW", "product_ids", new ArrayList<>()))
                      .get("items"))
                  .size(),
              0);
          var r = f.target.managePurchaseTargets(m("action", "EVALUATE", "product_id", "eggs"));
          eq(s(rows(r.get("items")).get(0), "notificationStatus"), "DISABLED");
        });
    test(
        "UC05 clear target retains settings and nonfavorite rejected",
        f -> {
          status(
              f.target.managePurchaseTargets(
                  m("action", "CLEAR_TARGET", "product_id", "milk", "enabled", true)),
              "OK");
          check(obj(f.repo.snapshot().map("targets").get("milk")).get("price") == null);
          status(
              f.target.managePurchaseTargets(
                  m("action", "SET_TARGET", "product_id", "rice", "enabled", true, "price", 1)),
              "INVALID_INPUT");
        });
    test(
        "UC03 future timestamp and unsafe URL rejected",
        f -> {
          var o = f.current("milk");
          o.put("checked_at", NOW.plusMinutes(1).toString());
          status(f.manual(o), "INVALID_INPUT");
          o.put("checked_at", NOW.toString());
          o.put("url", "file:///private");
          status(f.manual(o), "INVALID_INPUT");
        });
    test(
        "UC03 date-only observation retained but not made fresh",
        f -> {
          var o = f.current("milk");
          o.put("checked_at", "");
          o.put("price", "1234");
          var r = f.manual(o);
          status(r, "OK");
          eq(list(r.get("updated")).size(), 0);
          check(
              f.repo.snapshot().offers().stream()
                  .map(PriceObservation::toMap)
                  .anyMatch(a -> s(a, "price").equals("1234") && s(a, "checked_at").isEmpty()));
        });
    test(
        "UC03 immutable product specification",
        f -> {
          var p = f.repo.snapshot().product("milk").toMap();
          p.put("size", "500");
          status(
              f.products.manageProducts(
                  m("action", "UPSERT_MANUAL", "product", p, "offer", f.current("milk"))),
              "INVALID_INPUT");
        });
    test(
        "UC03 favorite enable and disable persists",
        f -> {
          status(
              f.products.manageProducts(
                  m("action", "SET_FAVORITE", "product_id", "rice", "isFavorite", true)),
              "OK");
          check(new Repository(f.file).snapshot().array("favorites").contains("rice"));
          status(
              f.products.manageProducts(
                  m("action", "SET_FAVORITE", "product_id", "rice", "isFavorite", false)),
              "OK");
          check(!f.repo.snapshot().array("favorites").contains("rice"));
        });
    test(
        "UC03 partial refresh preserves prior success time",
        f -> {
          String old = s(obj(f.repo.snapshot().map("sources").get("홈플러스")), "last_success");
          Path feed = f.directory.resolve("feed.json");
          Files.writeString(
              feed,
              Json.stringify(
                  m(
                      "products",
                      List.of(f.repo.snapshot().product("milk").toMap()),
                      "offers",
                      List.of(f.current("milk")))));
          status(
              f.products.manageProducts(
                  m("action", "CONFIGURE_FEED", "store", "이마트", "path", feed.toString())),
              "OK");
          status(
              f.products.manageProducts(
                  m(
                      "action",
                      "CONFIGURE_FEED",
                      "store",
                      "홈플러스",
                      "path",
                      f.directory.resolve("unavailable.json").toString())),
              "OK");
          var r = f.products.manageProducts(m("action", "REFRESH"));
          status(r, "PARTIAL_FAILURE");
          eq(s(obj(f.repo.snapshot().map("sources").get("홈플러스")), "last_success"), old);
          check(
              f.repo.snapshot().offers().stream()
                  .map(PriceObservation::toMap)
                  .filter(o -> s(o, "store").equals("홈플러스"))
                  .allMatch(o -> yes(o, "failed")));
        });
    test(
        "UC03 invalid second feed record rolls back entire store",
        f -> {
          var a = f.current("milk");
          a.put("price", "1111");
          var b = copyMap(a);
          b.put("price", "bad");
          Path feed = f.directory.resolve("invalid.json");
          Files.writeString(
              feed,
              Json.stringify(
                  m(
                      "products",
                      List.of(f.repo.snapshot().product("milk").toMap()),
                      "offers",
                      List.of(a, b))));
          f.products.manageProducts(
              m("action", "CONFIGURE_FEED", "store", "이마트", "path", feed.toString()));
          status(
              f.products.manageProducts(m("action", "REFRESH", "store", "이마트")), "REFRESH_FAILED");
          check(
              f.repo.snapshot().offers().stream()
                  .map(PriceObservation::toMap)
                  .noneMatch(o -> s(o, "price").equals("1111")));
        });
    test(
        "UC03 automatic attempt guard survives restart",
        f -> {
          status(f.products.manageProducts(m("action", "AUTO_REFRESH")), "OK");
          var r =
              new ProductService(new Repository(f.file), new MartAdapter(), null, CLOCK)
                  .manageProducts(m("action", "AUTO_REFRESH"));
          check(yes(r, "skipped"));
        });
    test(
        "UC03 price event only after commit",
        f -> {
          f.repo.fail = true;
          status(f.manual(f.current("milk")), "SAVE_FAILED");
          check(f.repo.snapshot().map("alerts").isEmpty());
          f.repo.fail = false;
          var r = f.manual(f.current("milk"));
          status(r, "OK");
          eq(list(obj(r.get("target_result")).get("notifications")).size(), 1);
        });
    test(
        "UC03 stale feed never receives fabricated freshness",
        f -> {
          var o = f.current("milk");
          o.putAll(m("checked_date", "2026-10-31", "checked_at", NOW.minusDays(2).toString()));
          Path feed = f.directory.resolve("stale.json");
          Files.writeString(
              feed,
              Json.stringify(
                  m(
                      "products",
                      List.of(f.repo.snapshot().product("milk").toMap()),
                      "offers",
                      List.of(o))));
          f.products.manageProducts(
              m("action", "CONFIGURE_FEED", "store", "이마트", "path", feed.toString()));
          var r = f.products.manageProducts(m("action", "REFRESH", "store", "이마트"));
          status(r, "OK");
          eq(list(r.get("updated")).size(), 0);
        });
    test(
        "Local paths with Korean and spaces persist correctly",
        f -> {
          check(f.directory.toString().contains("한글 space"));
          Repository reloaded = new Repository(f.file);
          check(reloaded.readError().isEmpty());
          eq(reloaded.snapshot().product("milk").name(), "데일리 우유 1L");
        });
    test(
        "UC03 manual refresh only attempts configured sources",
        f -> {
          Path feed = f.directory.resolve("connected.json");
          Files.writeString(
              feed,
              Json.stringify(
                  m(
                      "products",
                      List.of(f.repo.snapshot().product("milk").toMap()),
                      "offers",
                      List.of(f.current("milk")))));
          f.products.manageProducts(
              m("action", "CONFIGURE_FEED", "store", "이마트", "path", feed.toString()));
          status(f.products.manageProducts(m("action", "REFRESH")), "OK");
          check(
              f.repo.snapshot().offers().stream()
                  .map(PriceObservation::toMap)
                  .filter(o -> !s(o, "store").equals("이마트"))
                  .noneMatch(o -> yes(o, "failed")));
        });
    test(
        "UC03 failed feed does not invalidate independently confirmed manual prices",
        f -> {
          status(f.manual(f.current("milk")), "OK");
          f.products.manageProducts(
              m(
                  "action",
                  "CONFIGURE_FEED",
                  "store",
                  "이마트",
                  "path",
                  f.directory.resolve("missing.json").toString()));
          status(
              f.products.manageProducts(m("action", "REFRESH", "store", "이마트")), "REFRESH_FAILED");
          var manual =
              f.repo.snapshot().latest().stream()
                  .map(PriceObservation::toMap)
                  .filter(o -> s(o, "method").equals("직접 입력"))
                  .findFirst()
                  .orElseThrow();
          eq(PriceObservation.fromMap(manual).validity(NOW), "현재 유효");
        });
    test(
        "UC03 omitted optional channel is normalized before persistence",
        f -> {
          var offer = f.current("milk");
          offer.remove("channel");
          offer.put("price", "2501");
          status(f.manual(offer), "OK");
          var saved =
              f.repo.snapshot().offers().stream()
                  .map(PriceObservation::toMap)
                  .filter(o -> s(o, "price").equals("2501"))
                  .findFirst()
                  .orElseThrow();
          eq(s(saved, "channel"), "온라인");
          check(
              rows(f.price.analyzePrices(m("mode", "COMPARE", "product_id", "milk")).get("ranked"))
                  .stream()
                  .anyMatch(o -> s(o, "price").equals("2501")));
        });
    test(
        "UC04 newest instant wins across differing local calendar dates",
        f -> {
          var a = f.current("milk");
          a.putAll(
              m(
                  "checked_date",
                  "2026-11-02",
                  "checked_at",
                  "2026-11-02T00:00:00+14:00",
                  "price",
                  "3000"));
          var b = copyMap(a);
          b.putAll(
              m(
                  "checked_date",
                  "2026-11-01",
                  "checked_at",
                  "2026-11-01T23:00:00-01:00",
                  "price",
                  "2400"));
          status(f.manual(a), "OK");
          status(f.manual(b), "OK");
          f.modify(
              st ->
                  st.array("offers")
                      .removeIf(
                          o ->
                              s(obj(o), "store").equals("이마트")
                                  && s(obj(o), "product_id").equals("milk")
                                  && !List.of("3000", "2400").contains(s(obj(o), "price"))));
          var latest =
              f.repo.snapshot().latest().stream()
                  .map(PriceObservation::toMap)
                  .filter(o -> s(o, "store").equals("이마트") && s(o, "product_id").equals("milk"))
                  .findFirst()
                  .orElseThrow();
          decimal(latest.get("price"), "2400");
        });
    test(
        "CR02 malformed source state fails read without overwriting",
        f -> {
          var broken = f.repo.snapshot().toMap();
          obj(broken.get("sources")).put("이마트", "invalid source object");
          String bytes = Json.stringify(broken);
          Files.writeString(f.file, bytes);
          Repository reopened = new Repository(f.file);
          check(!reopened.readError().isEmpty());
          status(reopened.saveAtomic(new AppState()), "SAVE_FAILED");
          eq(Files.readString(f.file), bytes);
        });
    test(
        "CR02 invalid persisted plan store is rejected on load",
        f -> {
          var broken = f.repo.snapshot().toMap();
          obj(list(obj(broken.get("plans")).get("2026-11")).get(0))
              .put("store", "not-a-supported-store");
          Files.writeString(f.file, Json.stringify(broken));
          check(!new Repository(f.file).readError().isEmpty());
        });
    test(
        "UC01 CREATE cannot silently replace an existing record ID",
        f -> {
          var req = payment();
          req.put("recordId", "demo-0");
          String before = Files.readString(f.file);
          status(f.ledger.manageLedger(req), "INVALID_INPUT");
          eq(Files.readString(f.file), before);
        });
    test(
        "UC03 past instant in a later local calendar day is accepted",
        f -> {
          Clock late = Clock.fixed(Instant.parse("2026-11-01T14:00:00Z"), ZoneId.of("Asia/Seoul"));
          ProductService service = new ProductService(f.repo, new MartAdapter(), null, late);
          var offer = f.current("milk");
          offer.putAll(m("checked_date", "2026-11-02", "checked_at", "2026-11-02T03:59:00+14:00"));
          status(
              service.manageProducts(
                  m(
                      "action",
                      "UPSERT_MANUAL",
                      "product",
                      f.repo.snapshot().product("milk").toMap(),
                      "offer",
                      offer)),
              "OK");
        });
    test(
        "Repeating decimal unit prices retain precision when ranked and displayed",
        f -> {
          var result = f.price.analyzePrices(m("mode", "COMPARE", "product_id", "tofu"));
          status(result, "OK");
          var ranked = rows(result.get("ranked"));
          eq(ranked.size(), 3);
          var row = OfferRow.read(ranked.get(0), f.repo.snapshot().product("tofu"));
          decimal(row.unitPrice(), "533.3333333333333333333333333333333");
        });
    test(
        "Legacy v1 ledger loads into typed objects and round trips after a save",
        f -> {
          byte[] fixture;
          try (var input = ServiceTests.class.getResourceAsStream("/legacy-ledger-v1.json")) {
            if (input == null) throw new AssertionError("legacy fixture missing");
            fixture = input.readAllBytes();
          }
          Path path = f.directory.resolve("legacy.json");
          Files.write(path, fixture);
          Repository repo = new Repository(path);
          eq(repo.readError(), "");
          AppState state = repo.snapshot();
          Product milk = state.product("milk");
          PriceObservation first = state.offers().get(0);
          decimal(milk.size(), "1000");
          decimal(milk.unitPrice(first.price()), "260");
          eq(first.checkedAt().getOffset().toString(), "+09:00");
          eq(state.records().size(), 1);
          status(repo.saveAtomic(state), "OK");
          AppState reloaded = new Repository(path).snapshot();
          eq(reloaded.product("milk"), milk);
          eq(reloaded.offers(), state.offers());
          eq(reloaded.records(), state.records());
          eq(reloaded.map("budgets"), state.map("budgets"));
        });
    System.out.println(
        "RESULT passed=" + passed + " failed=" + failed + " total=" + (passed + failed));
    if (failed > 0) System.exit(1);
  }
}
