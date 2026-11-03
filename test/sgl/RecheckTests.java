package sgl;

import static sgl.Data.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Regression checks for package totals, target draft sorting and observed sales channels. */
public final class RecheckTests {
  private RecheckTests() {}
  private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"),ZoneId.of("Asia/Seoul"));
  private static int passed;
  private static void check(boolean condition,String label) {
    if(!condition)throw new AssertionError(label);
    System.out.println("PASS "+(++passed)+" "+label);
  }
  private static Object field(Object o,String n) {
    try {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    catch(ReflectiveOperationException e){throw new AssertionError(e);}
  }
  private static JButton button(Container root,String name) {
    for(Component c:root.getComponents()) {
      if(c instanceof JButton b && b.getText().equals(name))return b;
      if(c instanceof Container nested){var b=button(nested,name);if(b!=null)return b;}
    }
    return null;
  }
  @SuppressWarnings("unchecked")
  private static void choose(Object tab,String name,String id) {
    SwingViews.select((JComboBox<SwingViews.Choice>)field(tab,name),id);
  }
  private static String page(String sku,String name) {
    return "<script type='application/ld+json'>"+Json.stringify(m("@type","Product","sku",sku,"name",name,
        "brand",m("name","검증"),"offers",m("price",10000,"priceCurrency","KRW",
        "availability","https://schema.org/InStock")))+"</script>";
  }
  private static Map<String,Object> view(Repository repo,String id) {
    return rows(new TargetService(repo,CLOCK).managePurchaseTargets(m("action","VIEW","product_id",id)).get("items")).get(0);
  }
  /** Executes deterministic service and actual Swing regressions.
   * @param args unused
   * @throws Exception when a regression fails
   */
  public static void main(String[] args)throws Exception {
    String[][] packs={
      {"계란 10구 x 2팩","20","계란"},
      {"계란 2 x 10구","20","계란"},
      {"라면 5봉 x 2팩","10","라면"},
      {"라면 2 x 5봉 x 3박스","30","라면"},
      {"종이컵 100개 x 2팩","200","개수 상품"},
      {"커피컵 354ml x 80개 x 2박스","160","개수 상품"},
      {"종이컵 2 x 100개","200","개수 상품"},
      {"생수 2 x 500ml","1000","음료"},
      {"생수 2 x 3 x 500ml x 4팩","12000","음료"},
      {"생수 1,500ml","1500","음료"},
      {"원두커피 1,000g x 2","2000","기타 식품"},
      {"대용량 식품 1,500,000g","1500000","기타 식품"},
      {"우유 １，５００ｍｌ × ２","3000","우유"},
      {"필터 50매 x 2팩","100","개수 상품"},
      {"원두커피 1kg /최소구매 2","1000","기타 식품"},
      {"계란 10구 x 2팩 / 최소 구매 3","20","계란"},
      {"커피컵 354ml x 80개","80","개수 상품"},
      {"카누 라떼 커피 13.5g x 50스틱 x 2박스","1350","기타 식품"}
    };
    for(var p:packs) {
      var q=ProductDiscovery.quantity(p[0],m());
      check(q!=null && q.total().compareTo(new BigDecimal(p[1]))==0 && q.category().equals(p[2]),
          "entire sale package: "+p[0]);
    }
    for(String name:List.of("계란 10구 + 10구","라면 5봉 2팩","커피컵 80개 + 사은품",
        "종이컵 100개 / 200개 선택","생수 1,50ml","식품 1,23,456g","생수 500ml 6입",
        "생수 500ml x 2.5","계란 10구 x 0","계란 10구 2팩","종이컵 100개 증정",
        "생수 2팩 x 500ml","과자 200g + 100g")) {
      check(ProductDiscovery.quantity(name,m())==null,"ambiguous quantity requires confirmation: "+name);
    }
    var repo=new Repository(Files.createTempDirectory("sgl-recheck-").resolve("ledger.json"));
    var state=repo.snapshot();state.collectionSources(List.of());repo.saveAtomic(state);
    var sources=new CollectionListService(repo,CLOCK);
    List<ProductCollector.Result> verified=new ArrayList<>();
    for(int i=0;i<5;i++) {
      String sku="99880"+i;String name=packs[i][0];
      var d=new ProductDiscovery(CLOCK,u->page(sku,name));
      var p=d.inspect("https://www.costco.co.kr/p/"+sku);
      var result=d.confirm(p,p.quantity().category(),p.quantity().total());
      check(s(sources.add(result),"status").equals("OK"),"bundle registration: "+name);
      verified.add(result);
    }
    var d=new ProductDiscovery(CLOCK,u->page("998899","라면 5봉 2팩"));
    var unknown=d.inspect("https://www.costco.co.kr/p/998899");
    check(unknown.quantity()==null,"ambiguous preview leaves quantity editable");
    var confirmed=d.confirm(unknown,"라면",new BigDecimal("10"));
    check(confirmed.product().unitPrice(confirmed.observation().price()).compareTo(new BigDecimal("1000"))==0,
        "explicit confirmed total drives correct unit price");
    var oldRepo=new Repository(Files.createTempDirectory("sgl-old-quantity-").resolve("ledger.json"));
    var correct=verified.get(0);var product=correct.product();var offer=correct.observation();
    var wrong=new Product("legacy-wrong-count",product.category(),product.name(),product.brand(),product.spec(),
        new BigDecimal("10"),product.unit(),1);
    var old=oldRepo.snapshot();old.addProduct(wrong);
    old.record(new PriceObservation("legacy",wrong.id(),offer.store(),offer.branch(),offer.channel(),offer.condition(),
        offer.price(),offer.url(),offer.checkedDate(),offer.checkedAt(),null,null,offer.method(),false));
    old.array("favorites").add(wrong.id());
    old.collectionSources(List.of(new CollectionSource(wrong,offer.store(),CollectionSource.skuOf(offer.url()),offer.url(),true)));
    oldRepo.saveAtomic(old);
    var failed=new OnlineCollectionService(oldRepo,new OnlineCatalog(CLOCK,u->page("998800",product.name()),List.of()),
        new TargetService(oldRepo,CLOCK),CLOCK).collect(false,p->{});
    check(s(failed,"status").equals("REFRESH_FAILED") && oldRepo.snapshot().latest().get(0).failed(),
        "old incorrectly counted package is rejected during recollection");
    check(oldRepo.snapshot().offers().size()==1 && oldRepo.snapshot().array("favorites").contains(wrong.id()),
        "rejecting wrong old quantity preserves observed history and favorite");
    var oldSettings=new CollectionListService(oldRepo,CLOCK);
    oldSettings.change(CollectionSource.keyOf(offer.url()),"REMOVE");
    check(s(oldSettings.add(correct),"status").equals("OK") && oldRepo.snapshot().products().size()==2,
        "correct package can be re-registered without rewriting old product history");
    SwingAppearance.install();
    SwingUtilities.invokeAndWait(()->{
      var frame=new LedgerFrame(repo,CLOCK,false);
      try {
        frame.setVisible(true);
        for(var item:verified) {
          String id=item.product().id();
          choose(frame.priceTab,"priceProduct",id);button(frame.priceTab,"단위 가격 비교").doClick();
          var compare=(JTable)field(frame.priceTab,"compareTable");
          String expected=SwingViews.money(item.product().unitPrice(new BigDecimal("10000")))+" / "+SwingViews.unitLabel(item.product());
          check(compare.getValueAt(0,4).equals(expected),"04 bundle unit price: "+item.product().name());
          choose(frame.targetTab,"favoriteProduct",id);button(frame.targetTab,"관심 상품 추가").doClick();
          ((JTextField)field(frame.targetTab,"targetPrice")).setText(item.product().unitPrice(new BigDecimal("10000")).setScale(2,java.math.RoundingMode.CEILING).toPlainString());
          button(frame.targetTab,"목표 저장").doClick();
          check(rows(view(repo,id).get("matches")).size()==1,"05 bundle target matches actual unit price: "+item.product().name());
        }
        var tab=frame.targetTab;var price=(JTextField)field(tab,"targetPrice");
        var table=(JTable)field(tab,"targetTable");
        String editing=(String)field(tab,"editingTargetId");
        price.setText("777");
        ((JTextField)field(tab,"targetCondition")).setText("미저장 조건");
        ((JCheckBox)field(tab,"targetEnabled")).doClick();
        table.getRowSorter().toggleSortOrder(0);
        table.getRowSorter().toggleSortOrder(0);
        check(price.getText().equals("777") && tab.hasDraft() && field(tab,"editingTargetId").equals(editing),
            "ascending/descending name sort preserves unsaved goal and product");
        table.getRowSorter().toggleSortOrder(3);
        check(((JTextField)field(tab,"targetCondition")).getText().equals("미저장 조건")
            && ((JCheckBox)field(tab,"targetEnabled")).isSelected(),"numeric sort preserves all target draft fields");
        frame.refreshAfterPriceChange();
        check(price.getText().equals("777") && tab.hasDraft(),"collection refresh after sorting preserves draft");
        button(tab,"목표 저장").doClick();
        var saved=obj(repo.snapshot().map("targets").get(editing));
        check(s(saved,"price").equals("777") && s(saved,"condition").equals("미저장 조건") && yes(saved,"enabled"),
            "sorted target save commits the original selected product");
        var reload=new Repository(repo.path());
        check(s(obj(reload.snapshot().map("targets").get(editing)),"price").equals("777"),"sorted edit survives reload");
      } finally {frame.dispose();}
    });

    state=repo.snapshot();
    Product offline=new Product("offline","음료","Z 오프라인 생수","검증","생수",new BigDecimal("500"),"ml",1);
    state.addProduct(offline);state.array("favorites").add(offline.id());
    var at=OffsetDateTime.now(CLOCK);
    state.record(new PriceObservation("old",offline.id(),"홈플러스","온라인점","온라인","일반",new BigDecimal("2000"),
        "https://www.homeplus.co.kr",at.toLocalDate(),at.minusHours(1),null,null,"직접 입력",false));
    state.record(new PriceObservation("new",offline.id(),"홈플러스","오프라인점","오프라인","행사",new BigDecimal("1000"),
        "https://www.homeplus.co.kr",at.toLocalDate(),at,null,null,"직접 입력",false));
    repo.saveAtomic(state);
    check(s(obj(view(repo,offline.id()).get("target")),"channel").equals("오프라인")
        && s(obj(view(repo,offline.id()).get("target")),"condition").equals("행사"),
        "new target inherits most recent observation channel and condition");
    SwingUtilities.invokeAndWait(()->{
      var frame=new LedgerFrame(repo,CLOCK,false);
      try {
        choose(frame.priceTab,"priceProduct",offline.id());button(frame.priceTab,"단위 가격 비교").doClick();
        check(((JComboBox<?>)field(frame.priceTab,"priceChannel")).getSelectedItem().equals("오프라인")
            && ((JTable)field(frame.priceTab,"compareTable")).getValueAt(0,4).equals("200 / 100ml"),
            "04 defaults to most recent observed channel");
        choose(frame.targetTab,"favoriteProduct",offline.id());button(frame.targetTab,"관심 상품 추가").doClick();
        check(((JComboBox<?>)field(frame.targetTab,"targetChannel")).getSelectedItem().equals("오프라인"),
            "05 shows inherited offline channel");
        ((JTextField)field(frame.targetTab,"targetPrice")).setText("200");
        button(frame.targetTab,"목표 저장").doClick();
        check(rows(view(repo,offline.id()).get("matches")).size()==1,"offline personal target matches without changing channel manually");
      } finally {frame.dispose();}
    });
    new TargetService(repo,CLOCK).managePurchaseTargets(m("action","SET_TARGET","product_id",offline.id(),"price","500",
        "channel","온라인","condition","일반","enabled",false));
    check(s(obj(view(repo,offline.id()).get("target")),"channel").equals("온라인"),
        "explicitly saved channel is never replaced by an observation default");
    System.out.println("Recheck tests: "+passed+" passed, 0 failed");
  }
}
