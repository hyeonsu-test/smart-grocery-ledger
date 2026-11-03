package sgl;
import static sgl.Data.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline regression tests for official price parsing, collection and persistence. */
public final class CollectionTests {
  private CollectionTests() {}
  private static int passed;
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T06:00:00Z"), ZoneId.of("Asia/Seoul"));
  private static final OnlineCatalog.Source HP = OnlineCatalog.defaults().get(1);
  private static final OnlineCatalog.Source CO = OnlineCatalog.defaults().get(5);
  private static void check(boolean yes, String name) {
    if (!yes) throw new AssertionError(name);
    System.out.println("PASS " + (++passed) + " " + name);
  }
  private static void rejects(String html, String name) {
    try { OfficialPageParser.parse(HP, html, CLOCK); throw new AssertionError(name); }
    catch (IllegalArgumentException e) { check(true, name); }
  }
  private static String hp(Map<String,Object> sale, Map<String,Object> offer, String name, String sku) {
    return "<script type='application/ld+json'>" + Json.stringify(m("@graph", List.of(m(
        "@type", "Product", "name", name, "mpn", sku, "offers", offer))))
        + "</script><script id='/item/getItemDetail.json' type='application/json'>"
        + Json.stringify(m("data", m("item", m("basic", m("itemNo", HP.sku(), "itemNm", HP.product().name(),
            "storeType","HYPER","itemStatus","A","storeId",37), "sale",sale)))) + "</script>";
  }
  private static Map<String,Object> sale() {
    return m("salePrice",7890,"frontDcPriceInfo",m("priceType","BASIC","dcPrice",5990),
      "itemSoldOutYn","N","eventList",List.of(m("eventKind","BASIC","changeAmount",5990,
        "buyItemCount",1,"eventStartDt","2026-10-29","eventEndDt","2026-11-04")));
  }
  private static Map<String,Object> offer() { return m("price",5990,"priceCurrency","KRW"); }
  private static String html() { return hp(sale(),offer(),HP.product().name(),HP.sku()); }
  private static ProductCollector.Result good() {
    return new ProductCollector.Result(HP.product(), HP.store(),HP.uri().toString(),
        OfficialPageParser.parse(HP,html(),CLOCK),"");
  }
  private static ProductCollector.Result bad() {
    return new ProductCollector.Result(HP.product(),HP.store(),HP.uri().toString(),null,"HTTP 403");
  }
  private static Repository repo() throws IOException {
    return new Repository(Files.createTempDirectory("sgl-online-").resolve("ledger.json"));
  }
  private static OnlineCollectionService service(Repository r, ProductCollector c) {
    return new OnlineCollectionService(r,c,new TargetService(r,CLOCK),CLOCK);
  }
  /**
   * Runs deterministic tests without internet access.
   * @param args unused
   * @throws Exception if a regression occurs
   */
  public static void main(String[] args) throws Exception {
    var parsed=good().observation();
    check(parsed.price().intValueExact()==5990,"public BASIC discount price");
    check(parsed.start().toString().equals("2026-10-29") && parsed.end().toString().equals("2026-11-04"),"promotion interval from matching event");
    check(parsed.branch().contains("37") && parsed.condition().equals("일반"),"public branch and general condition");
    check(HP.product().unitPrice(parsed.price()).toString().startsWith("199.66"),"30 egg unit conversion");
    rejects(hp(sale(),offer(),"다른 계란 30개입",HP.sku()),"changed specification rejected");
    rejects(hp(sale(),offer(),HP.product().name(),"WRONG"),"wrong product ID rejected");
    var changed=offer();changed.put("priceCurrency","USD");
    rejects(hp(sale(),changed,HP.product().name(),HP.sku()),"wrong currency rejected");
    changed=offer();changed.put("price",0);
    rejects(hp(sale(),changed,HP.product().name(),HP.sku()),"zero price rejected");
    changed=sale();changed.put("itemSoldOutYn","Y");
    rejects(hp(changed,offer(),HP.product().name(),HP.sku()),"sold-out product rejected");
    changed=sale();changed.put("frontDcPriceInfo",m("priceType","CARD","dcPrice",5990));
    rejects(hp(changed,offer(),HP.product().name(),HP.sku()),"conditional card discount rejected");
    changed=offer();changed.put("price",1);
    rejects(hp(sale(),changed,HP.product().name(),HP.sku()),"metadata and actual price mismatch rejected");
    rejects("<script>var price=5990;</script>","unstructured page never becomes price");
    var coOffer=m("price",24990,"priceCurrency","KRW","availability","https://schema.org/InStock","priceValidUntil","2026-11-09");
    String co="<script type=\"application/ld+json\">"+Json.stringify(m("@type","product","name",CO.product().name(),"sku",CO.sku(),"offers",coOffer))+"</script>";
    var coPrice=OfficialPageParser.parse(CO,co,CLOCK);
    check(coPrice.condition().equals("코스트코 회원") && coPrice.branch().contains("배송비 포함"),"Costco membership and included shipping retained");
    check(coPrice.start()==null && coPrice.end()==null,"offer valid-until is not invented promotion");
    check(!coPrice.isPromotion(),"ordinary member price is not mislabeled as promotion");
    try { OfficialPageParser.parse(CO,co.replace("2026-11-09","2026-10-01"),CLOCK); throw new AssertionError(); }
    catch(IllegalArgumentException e){check(true,"expired public offer rejected");}
    var failure=new OnlineCatalog(CLOCK,u->{throw new IOException("timed out");},List.of(HP)).collect(x->{});
    check(failure.size()==1 && failure.get(0).observation()==null && failure.get(0).error().contains("timed out"),"network timeout becomes source failure");
    try {new HttpPageClient().get(java.net.URI.create("file:///tmp/example")); throw new AssertionError();}
    catch(IOException e){check(true,"non-HTTPS source rejected before network");}
    AtomicInteger attempts=new AtomicInteger();
    Repository r=repo();
    var s=service(r,p->{attempts.incrementAndGet();return List.of(good());});
    var result=s.collect(true,p->{});
    check(s(result,"status").equals("OK") && r.snapshot().products().size()==1,"first startup needs no JSON or manual entry");
    check(r.snapshot().array("favorites").isEmpty(),"collection does not invent user favorites");
    check(yes(s.collect(true,p->{}),"skipped") && attempts.get()==1,"daily automatic guard");
    s.collect(false,p->{});
    check(attempts.get()==2 && r.snapshot().offers().size()==1,"manual button retries and same-reading deduplication");
    new ProductService(r,new MartAdapter(),null,CLOCK).manageProducts(m("action","SET_FAVORITE","product_id",HP.product().id(),"isFavorite",true));
    var failed=service(r,p->List.of(bad())).collect(false,p->{});
    check(s(failed,"status").equals("REFRESH_FAILED") && r.snapshot().products().size()==1,"failure preserves catalog");
    check(r.snapshot().offers().size()==1 && r.snapshot().latest().get(0).failed(),"failure preserves history and excludes current web price");
    check(r.snapshot().array("favorites").size()==1,"failure preserves favorites");
    s.collect(false,p->{});
    check(!r.snapshot().latest().get(0).failed(),"successful retry recovers current price");
    var mixed=service(repo(),p->List.of(good(),new ProductCollector.Result(CO.product(),CO.store(),CO.uri().toString(),null,"HTTP 403")))
        .collect(false,p->{});
    check(s(mixed,"status").equals("PARTIAL_FAILURE") && integer(mixed.get("successful"),true)==1,"one failed source does not discard other sources");
    Repository concurrent=repo();
    var duringFetch=service(concurrent,p->{
      try {
        CompletableFuture.runAsync(()->{
          var state=concurrent.snapshot();
          state.map("budgets").put("2026-11",12345);
          if(!s(concurrent.saveAtomic(state),"status").equals("OK"))throw new AssertionError();
        }).get(3,TimeUnit.SECONDS);
      } catch(Exception e){throw new AssertionError("network must not hold ledger lock",e);}
      return List.of(good());
    });
    check(s(duringFetch.collect(false,p->{}),"status").equals("OK")
        && integer(concurrent.snapshot().map("budgets").get("2026-11"),true)==12345,"UI saves during fetch survive result merge");
    Repository demo=repo();demo.saveAtomic(DemoData.create(CLOCK));
    check(yes(service(demo,p->{throw new AssertionError("must not fetch demo");}).collect(false,p->{}),"skipped"),"demo never mixes real prices");
    Repository saveFailure=new Repository(Files.createTempDirectory("sgl-online-fail-").resolve("ledger.json")){
      @Override protected void replaceFile(Path a,Path b)throws IOException{throw new IOException("injected");}
    };
    check(s(service(saveFailure,p->List.of(good())).collect(false,p->{}),"status").equals("SAVE_FAILED")
        && saveFailure.snapshot().products().isEmpty(),"save failure commits neither prices nor daily guard");
    Repository coRepo=repo();
    service(coRepo,p->List.of(new ProductCollector.Result(CO.product(),CO.store(),CO.uri().toString(),coPrice,""))).collect(false,p->{});
    new ProductService(coRepo,new MartAdapter(),null,CLOCK).manageProducts(m("action","SET_FAVORITE","product_id",CO.product().id(),"isFavorite",true));
    var view=new TargetService(coRepo,CLOCK).managePurchaseTargets(m("action","VIEW","product_ids",List.of(CO.product().id())));
    check(s(obj(rows(view.get("items")).get(0).get("target")),"condition").equals("코스트코 회원"),"new target inherits source membership condition");
    check(new Repository(r.path()).snapshot().offers().size()==1,"collected prices survive restart");
    System.out.println("Collection tests: "+passed+" passed, 0 failed");
  }
}

