package sgl;

import static sgl.Data.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic discovery, selection persistence and concurrent removal regression tests. */
public final class DiscoveryTests {
  private DiscoveryTests() {}
  private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"),ZoneId.of("Asia/Seoul"));
  private static final String URL="https://www.costco.co.kr/Foods/Test/p/987654";
  private static int passed;
  private static void check(boolean yes,String name) {
    if(!yes)throw new AssertionError(name);
    System.out.println("PASS "+(++passed)+" "+name);
  }
  private static void rejects(Runnable run,String name) {
    try {run.run();throw new AssertionError(name);}catch(IllegalArgumentException e){check(true,name);}
  }
  static String page(String name) {
    return "<script type='application/ld+json'>"+Json.stringify(m("@type","Product","sku","987654",
        "name",name,"brand",m("name","테스트"),"offers",m("price",10000,"priceCurrency","KRW",
        "availability","https://schema.org/InStock")))+"</script>";
  }
  private static Repository empty()throws IOException {
    var repo=new Repository(Files.createTempDirectory("sgl-discovery-").resolve("ledger.json"));
    var state=repo.snapshot();state.collectionSources(List.of());repo.saveAtomic(state);return repo;
  }
  private static ProductCollector.Result result()throws IOException {
    var discovery=new ProductDiscovery(CLOCK,u->page("테스트 원두커피 1kg"));
    var preview=discovery.inspect(URL);
    return discovery.confirm(preview,preview.quantity().category(),preview.quantity().total());
  }
  private static OnlineCollectionService collection(Repository repo,ProductCollector reader) {
    return new OnlineCollectionService(repo,reader,new TargetService(repo,CLOCK),CLOCK);
  }

  /** Runs offline assertions.
   * @param args unused
   * @throws Exception on a failed regression
   */
  public static void main(String[] args)throws Exception {
    check(CollectionSource.canonicalUrl("https://www.homeplus.co.kr/item?storeType=exp&itemNo=123&utm=a")
        .equals("https://mfront.homeplus.co.kr/item?itemNo=123&storeType=EXP"),"canonical Homeplus URL removes tracking");
    check(CollectionSource.keyOf(URL+"?utm=a").equals(CollectionSource.keyOf("https://www.costco.co.kr/p/987654")),"same SKU deduplicates alternate Costco paths");
    rejects(()->CollectionSource.canonicalUrl("https://www.costco.co.kr.evil.example/p/123"),"lookalike host rejected");
    rejects(()->CollectionSource.canonicalUrl("http://www.costco.co.kr/p/123"),"plain HTTP rejected");
    rejects(()->CollectionSource.canonicalUrl("https://user@www.costco.co.kr/p/123"),"URL credentials rejected");
    rejects(()->CollectionSource.canonicalUrl("https://www.costco.co.kr/search?text=coffee"),"search URL cannot become a tracked product");
    rejects(()->CollectionSource.canonicalUrl("https://mfront.homeplus.co.kr/item?itemNo=1&itemNo=2"),"ambiguous duplicate SKU rejected");

    String hp="<script id='/express/search/item.json' type='application/json'>"
        +Json.stringify(m("data",m("dataList",List.of(m("itemNo","42","storeType","EXP","itemNm","커피 500ML"),
        m("itemNo","42","storeType","EXP","itemNm","커피 500ML")))))+"</script>";
    check(ProductDiscovery.parseSearch("홈플러스 익스프레스",hp).size()==1,"live Homeplus structure yields deduplicated search links");
    String co="<a href='/Foods/Test/p/987654' class='lister-name js-lister-name'><span>커피 &amp; 차</span></a>"
        +"<a class='js-lister-name' href='https://evil.example/p/123'>외부</a>"
        +"<a class='js-lister-name' href='/p/987654'>duplicate</a>";
    var hits=ProductDiscovery.parseSearch("코스트코",co);
    check(hits.size()==1 && hits.get(0).name().equals("커피 & 차"),"Costco links retain Korean text and reject off-site links");
    rejects(()->ProductDiscovery.parseSearch("홈플러스 익스프레스","<html>server error</html>"),"changed search structure surfaces error");
    check(ProductDiscovery.quantity("카누 라떼 커피 13.5g x 50스틱 x 2박스",m()).total().compareTo(new BigDecimal("1350"))==0,"multi-level package multiplication");
    check(ProductDiscovery.quantity("멸균우유 200ml x 24 x 2",m()).total().intValueExact()==9600,"milk package total");
    check(ProductDiscovery.quantity("계란 30개입",m()).category().equals("계란"),"egg category keeps per-egg comparison");
    check(ProductDiscovery.quantity("스낵 200g + 100g",m())==null,"mixed package needs explicit confirmation");
    check(ProductDiscovery.quantity("커피 1kg / 2kg 선택",m())==null,"alternative package sizes are not guessed");
    var detail=m("etc",m("unitMeasure","ML","totalUnitQty",2500));
    check(ProductDiscovery.quantity("생수",detail).total().intValueExact()==2500,"official total quantity works without title quantity");

    var discovery=new ProductDiscovery(CLOCK,u->page("테스트 원두커피 1kg"));
    var preview=discovery.inspect(URL);
    rejects(()->discovery.confirm(preview,"기타 식품",new BigDecimal("1")),"detected package total cannot be changed to a false unit price");
    var verified=result();
    check(verified.product().unitPrice(verified.observation().price()).intValueExact()==1000,"new food category uses actual price per 100g");
    Repository repo=empty();var settings=new CollectionListService(repo,CLOCK);
    check(s(settings.add(verified),"status").equals("OK") && settings.list().size()==1,"explicit add stores a selected source");
    check(repo.snapshot().offers().size()==1 && repo.snapshot().array("favorites").isEmpty(),"registration saves observed price without inventing favorites");
    check(yes(settings.add(verified),"duplicate") && settings.list().size()==1,"duplicate registration is idempotent");
    Repository restarted=new Repository(repo.path());
    check(restarted.readError().isEmpty() && restarted.snapshot().collectionSources().size()==1,"selection survives restart");
    AtomicInteger reads=new AtomicInteger();
    OnlineCatalog reader=new OnlineCatalog(CLOCK,u->{reads.incrementAndGet();return page("테스트 원두커피 1kg");},List.of());
    var refresh=collection(restarted,reader).collect(false,p->{});
    check(integer(refresh.get("successful"),true)==1 && reads.get()==1,"automatic collector reads saved user source instead of defaults");
    String key=settings.list().get(0).key();
    check(s(settings.change(key,"PAUSE"),"status").equals("OK"),"source can be paused");
    check(yes(collection(repo,reader).collect(false,p->{}),"skipped") && reads.get()==1,"paused source performs no HTTP");
    check(s(settings.change(key,"ENABLE"),"status").equals("OK"),"source can be resumed");
    check(integer(collection(repo,reader).collect(true,p->{}).get("successful"),true)==1,"changed selection resets same-day automatic guard");
    check(s(settings.change(key,"REMOVE"),"status").equals("OK") && repo.snapshot().offers().size()==1,"removal preserves historical prices");
    check(new Repository(repo.path()).snapshot().collectionSources().isEmpty(),"intentionally empty collection does not restore starter defaults");
    check(yes(collection(repo,reader).collect(true,p->{}),"skipped"),"empty collection performs no HTTP");

    settings.add(verified);
    ProductCollector removing=progress->{
      settings.change(key,"REMOVE");
      return List.of(verified);
    };
    collection(repo,removing).collect(false,p->{});
    check(settings.list().isEmpty() && repo.snapshot().offers().size()==1,"late response cannot re-enable removed source");
    check(repo.snapshot().value("online_attempt").isEmpty(),"concurrent setting change keeps next collection eligible");
    settings.add(verified);
    var changed=new OnlineCatalog(CLOCK,u->page("테스트 원두커피 2kg"),List.of());
    var failed=collection(repo,changed).collect(false,p->{});
    check(s(failed,"status").equals("REFRESH_FAILED") && repo.snapshot().latest().get(0).failed(),"changed identity keeps history but invalidates current price");

    Repository fail=new Repository(Files.createTempDirectory("sgl-discovery-fail-").resolve("ledger.json")) {
      @Override protected void replaceFile(Path from,Path to)throws IOException {throw new IOException("injected");}
    };
    check(s(new CollectionListService(fail,CLOCK).add(verified),"status").equals("SAVE_FAILED")
        && fail.snapshot().products().isEmpty(),"failed registration commits neither selection nor price");
    var original=new Repository(Files.createTempDirectory("sgl-legacy-").resolve("ledger.json"));
    check(original.snapshot().collectionSources().size()==8,"old ledgers get starter defaults without migration");
    var corrupted=repo.snapshot().toMap();
    corrupted.put("collection_sources",List.of(m("product",verified.product().toMap(),"store","코스트코",
        "sku","987654","url","https://evil.example/p/987654","enabled",true)));
    rejects(()->new AppState(corrupted).validate(),"persisted invalid source is rejected");
    Product milk=new Product("extra","음료","생수","테스트","생수",new BigDecimal("2000"),"ml",6);
    check(milk.unitPrice(new BigDecimal("12000")).intValueExact()==100,"new drink quantities calculate per 100ml");
    Product count=new Product("count","개수 상품","개수 상품","테스트","개수 상품",new BigDecimal("10"),"개",2);
    check(count.unitPrice(new BigDecimal("2000")).intValueExact()==100,"new count category uses per-item comparison");
    var unknown=new ProductDiscovery(CLOCK,u->page("테스트 원두커피")).inspect(URL);
    check(unknown.quantity()==null,"missing quantity is explicit instead of guessed");
    rejects(()->discovery.confirm(unknown,"기타 식품",BigDecimal.ZERO),"manual quantity confirmation rejects zero");
    var cups=ProductDiscovery.quantity("삼풍 이중단열 커피컵 354ml x 80개",m());
    check(cups.category().equals("개수 상품") && cups.total().intValueExact()==80,"container capacity never becomes beverage content");
    check(ProductDiscovery.quantity("원두커피 1kg /최소구매 2",m()).total().intValueExact()==1000,"minimum order is not package size multiplier");
    settings.change(key,"REMOVE");
    var newSpecDiscovery=new ProductDiscovery(CLOCK,u->page("테스트 원두커피 2kg"));
    var newPreview=newSpecDiscovery.inspect(URL);
    var newSpec=newSpecDiscovery.confirm(newPreview,newPreview.quantity().category(),newPreview.quantity().total());
    check(!newSpec.product().id().equals(verified.product().id()) && s(settings.add(newSpec),"status").equals("OK"),"changed package can be re-registered with independent historical identity");
    check(repo.snapshot().products().size()==2 && repo.snapshot().offers().size()==2,"old specification history remains after re-registration");
    System.out.println("Discovery tests: "+passed+" passed, 0 failed");
  }
}

