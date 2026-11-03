package sgl;

import static sgl.Data.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;

/** End-to-end regression for discovered products across comparison, history and purchase targets. */
public final class ProductFlowTests {
  private ProductFlowTests() {}
  private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"),ZoneId.of("Asia/Seoul"));
  private static int passed;
  private static LedgerFrame frame;
  private static void check(boolean value,String name) {
    if(!value)throw new AssertionError(name);
    System.out.println("PASS "+(++passed)+" "+name);
  }
  private static Object field(Object o,String n) {
    try {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    catch(ReflectiveOperationException e){throw new AssertionError(e);}
  }
  private static JTable table(Object o,String n){return (JTable)field(o,n);}
  private static JTextField textField(Object o,String n){return (JTextField)field(o,n);}
  private static JButton button(Container p,String n) {
    for(Component c:p.getComponents()){
      if(c instanceof JButton b && b.getText().equals(n))return b;
      if(c instanceof Container child){JButton b=button(child,n);if(b!=null)return b;}
    }
    return null;
  }
  @SuppressWarnings("unchecked")
  private static void select(Object tab,String field,String id) {
    SwingViews.select((JComboBox<SwingViews.Choice>)field(tab,field),id);
  }
  private static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
  private static Repository empty()throws Exception{
    var r=new Repository(Files.createTempDirectory("sgl-product-flow-").resolve("ledger.json"));
    var s=r.snapshot();s.collectionSources(List.of());r.saveAtomic(s);return r;
  }
  private static String page(String sku,String name,int price) {
    return "<script type='application/ld+json'>"+Json.stringify(m("@type","Product","sku",sku,
        "name",name,"brand",m("name","검증 브랜드"),"offers",m("price",price,"priceCurrency","KRW",
        "availability","https://schema.org/InStock")))+"</script>";
  }
  private static ProductCollector.Result discovered(String sku,String name,int price)throws Exception {
    var d=new ProductDiscovery(CLOCK,u->page(sku,name,price));
    var p=d.inspect("https://www.costco.co.kr/p/"+sku);
    if(p.quantity()==null)throw new AssertionError("quantity fixture "+name);
    return d.confirm(p,p.quantity().category(),p.quantity().total());
  }
  private static Map<String,Object> view(Repository repo,Clock clock,String id){
    return rows(new TargetService(repo,clock).managePurchaseTargets(m("action","VIEW","product_id",id)).get("items")).get(0);
  }
  private static Map<String,Object> target(Repository repo,String id,String price,boolean enabled) {
    return new TargetService(repo,CLOCK).managePurchaseTargets(m("action","SET_TARGET","product_id",id,
        "price",price,"enabled",enabled,"channel","온라인","condition","코스트코 회원"));
  }
  private static Map<String,Object> evaluate(Repository repo,Clock clock,String id) {
    return new TargetService(repo,clock).managePurchaseTargets(m("action","EVALUATE","product_id",id));
  }
  private static PriceObservation observation(ProductCollector.Result source,Clock clock,String price,boolean failed,String condition) {
    var old=source.observation();var at=OffsetDateTime.now(clock);
    return new PriceObservation("flow-"+at,source.product().id(),source.store(),old.branch(),"온라인",condition,
        new BigDecimal(price),source.url(),at.toLocalDate(),at,null,null,"웹 자동 수집",failed);
  }
  private static void capture(Path folder,String name)throws Exception{
    var p=(JComponent)frame.getContentPane();p.validate();
    var img=new BufferedImage(p.getWidth()*2,p.getHeight()*2,BufferedImage.TYPE_INT_RGB);
    var g=img.createGraphics();g.scale(2,2);p.printAll(g);g.dispose();
    ImageIO.write(img,"png",folder.resolve(name).toFile());
  }
  private static void respond(int answer) {
    TimerUtil.answer(answer);
  }
  /** Runs deterministic service and real Swing interactions.
   * @param args optional evidence folder
   * @throws Exception on a failed regression
   */
  public static void main(String[] args)throws Exception {
    Path evidence=args.length==0?null:Path.of(args[0]);
    if(evidence!=null)Files.createDirectories(evidence);
    Repository repo=empty();var settings=new CollectionListService(repo,CLOCK);
    var base=OnlineCatalog.defaults().get(0);var now=OffsetDateTime.now(CLOCK);
    var initial=repo.snapshot();initial.addProduct(base.product());
    initial.record(new PriceObservation("milk",base.product().id(),base.store(),"익스프레스 · 공개 기준점포 245",
        "온라인","일반",new BigDecimal("3090"),base.uri().toString(),now.toLocalDate(),now,null,null,"웹 자동 수집",false));
    initial.array("favorites").add(base.product().id());repo.saveAtomic(initial);
    var coffee=discovered("987654","검증 브랜드 원두커피 1kg",10000);
    String cid=coffee.product().id();
    SwingAppearance.install();
    try {
      edt(()->{frame=new LedgerFrame(repo,CLOCK,false);frame.setVisible(true);});
      // Registration follows the actual preview dialog, with an existing item already selected.
      CollectionDialog[] holder={null};
      edt(()->holder[0]=new CollectionDialog(frame,new ProductDiscovery(CLOCK,u->page("987654",coffee.product().name(),10000)),settings));
      CollectionDialog dialog=holder[0];
      SwingUtilities.invokeLater(()->dialog.setVisible(true));edt(()->{});
      edt(()->{textField(dialog,"url").setText(coffee.url());button(dialog,"주소 확인").doClick();});
      long until=System.nanoTime()+10_000_000_000L;
      while(System.nanoTime()<until){
        boolean[] busy={true};edt(()->busy[0]=(boolean)field(dialog,"busy"));
        if(!busy[0])break;Thread.sleep(25);
      }
      edt(()->{
        check(button(dialog,"자동 수집에 추가").isEnabled(),"official preview enables explicit registration");
        button(dialog,"자동 수집에 추가").doClick();dialog.dispose();
        check(repo.snapshot().products().size()==2,"new product saved alongside existing ledger");
        check(((JComboBox<?>)field(frame.priceTab,"priceProduct")).getItemCount()==2,"04 product selector updates without restart");
        check(((JComboBox<?>)field(frame.targetTab,"favoriteProduct")).getItemCount()==2,"05 favorite selector updates without restart");
        select(frame.priceTab,"priceProduct",cid);frame.navigate(3);
        button(frame.priceTab,"단위 가격 비교").doClick();
        check(table(frame.priceTab,"compareTable").getRowCount()==1,"04 ranks registered coffee");
        check(table(frame.priceTab,"historyTable").getRowCount()==1,"04 replaces prior retailer and branch when switching to coffee");
        check(((JComboBox<?>)field(frame.priceTab,"historyStore")).getSelectedItem().equals("코스트코"),"04 history uses the newly selected retailer");
        check(textField(frame.priceTab,"priceCondition").getText().equals("코스트코 회원"),"04 keeps Costco membership condition");
        check(table(frame.priceTab,"compareTable").getValueAt(0,4).equals("1,000 / 100g"),"04 normalizes coffee package to 100g");
        select(frame.targetTab,"favoriteProduct",cid);frame.navigate(4);button(frame.targetTab,"관심 상품 추가").doClick();
        check(field(frame.targetTab,"editingTargetId").equals(cid),"05 adding new favorite selects its target editor");
        check(textField(frame.targetTab,"targetCondition").getText().equals("코스트코 회원"),"05 new target inherits collected membership condition");
        check(((JLabel)field(frame.targetTab,"targetPriceLabel")).getText().contains("100g"),"05 labels the same comparison unit");
        check(view(repo,CLOCK,cid).get("basis")==null,"new item has no invented four-day historical basis");
        textField(frame.targetTab,"targetPrice").setText("1000");
        ((JCheckBox)field(frame.targetTab,"targetEnabled")).doClick();button(frame.targetTab,"목표 저장").doClick();
        check(obj(repo.snapshot().map("targets").get(cid)).get("price").equals("1000"),"target is saved under the new product ID");
        check(!repo.snapshot().map("targets").containsKey(base.product().id()),"saving new target does not alter previous product");
        check(rows(view(repo,CLOCK,cid).get("matches")).size()==1,"personal target works immediately before four observed days");
      });
      check(list(evaluate(repo,CLOCK,cid).get("notifications")).size()==1,"registered item sends an enabled reached alert");
      check(list(evaluate(repo,CLOCK,cid).get("notifications")).isEmpty(),"same-day alert is not duplicated");
      var reopened=new Repository(repo.path());
      check(s(obj(reopened.snapshot().map("targets").get(cid)),"price").equals("1000")
          && reopened.snapshot().array("favorites").contains(cid),"favorite and target survive disk reload");
      check(list(evaluate(reopened,CLOCK,cid).get("notifications")).isEmpty(),"alert guard survives disk reload");

      // Another exact-spec catalog item has its own observations and history.
      var alternate=discovered("987655",coffee.product().name(),9000);settings.add(alternate);
      edt(()->{
        frame.refreshAfterPriceChange();frame.navigate(3);button(frame.priceTab,"단위 가격 비교").doClick();
        JTable compare=table(frame.priceTab,"compareTable");
        check(compare.getRowCount()==2,"04 compares two exact-spec new catalog items");
        compare.setRowSelectionInterval(1,1);compare.setRowSelectionInterval(0,0);
        check(table(frame.priceTab,"historyTable").getValueAt(0,1).equals("900"),"04 selected comparable row uses that product's history");
        textField(frame.priceTab,"historyBranch").setText("사용자 지정");
        frame.refreshAfterPriceChange();
        check(textField(frame.priceTab,"historyBranch").getText().equals("사용자 지정"),"background refresh retains custom history branch");
        select(frame.priceTab,"priceProduct",base.product().id());
        check(((JComboBox<?>)field(frame.priceTab,"historyStore")).getSelectedItem().equals("홈플러스")
            && textField(frame.priceTab,"historyBranch").getText().contains("245"),"switching back restores that product's retailer and branch");
      });

      String[][] fixtures={{"987660","검증 생수 2L","2000","100 / 100ml"},
          {"987661","검증 과자 200g","4000","2,000 / 100g"},
          {"987662","검증 커피컵 100개","5000","50 / 1개"},
          {"987663","검증 우유 1L","3000","300 / 100ml"},
          {"987664","검증 계란 10개입","4000","400 / 1개"},
          {"987665","검증 라면 5봉","5000","1,000 / 1봉"},
          {"987666","검증 쌀 4kg","20000","500 / 100g"},
          {"987667","검증 두부 300g","1500","500 / 100g"}};
      for(var f:fixtures){
        var product=discovered(f[0],f[1],Integer.parseInt(f[2]));settings.add(product);
        edt(()->{
          frame.refreshAfterPriceChange();select(frame.priceTab,"priceProduct",product.product().id());
          button(frame.priceTab,"단위 가격 비교").doClick();
          check(table(frame.priceTab,"compareTable").getRowCount()==1
              && table(frame.priceTab,"compareTable").getValueAt(0,4).equals(f[3]),"04 category unit integration: "+f[1]);
          select(frame.targetTab,"favoriteProduct",product.product().id());button(frame.targetTab,"관심 상품 추가").doClick();
          check(field(frame.targetTab,"editingTargetId").equals(product.product().id())
              && ((JLabel)field(frame.targetTab,"targetPriceLabel")).getText().contains(SwingViews.unitLabel(product.product())),
              "05 category target integration: "+f[1]);
        });
      }

      edt(()->{
        select(frame.targetTab,"favoriteProduct",cid);button(frame.targetTab,"관심 상품 추가").doClick();
        textField(frame.targetTab,"targetPrice").setText("777");
        select(frame.targetTab,"favoriteProduct",base.product().id());respond(JOptionPane.NO_OPTION);
        button(frame.targetTab,"관심 상품 추가").doClick();
        check(field(frame.targetTab,"editingTargetId").equals(cid) && textField(frame.targetTab,"targetPrice").getText().equals("777"),
            "declining favorite switch preserves unsaved target and identity");
        respond(JOptionPane.YES_OPTION);button(frame.targetTab,"관심 상품 추가").doClick();
        check(field(frame.targetTab,"editingTargetId").equals(base.product().id()) && !frame.targetTab.hasDraft(),
            "accepting favorite switch loads only the requested product");
      });

      // Existing target remains isolated from a collection list's pause/remove actions.
      settings.change(CollectionSource.keyOf(coffee.url()),"PAUSE");
      check(repo.snapshot().array("favorites").contains(cid) && rows(view(repo,CLOCK,cid).get("matches")).size()==1,
          "pausing collection preserves the valid current target price");
      var future=Clock.offset(CLOCK,Duration.ofHours(25));
      check(rows(view(repo,future,cid).get("matches")).isEmpty(),"paused price expires after 24 hours and cannot satisfy targets");
      settings.change(CollectionSource.keyOf(coffee.url()),"ENABLE");
      PriceObservation fresh=observation(coffee,future,"9500",false,"코스트코 회원");
      var online=new OnlineCollectionService(repo,p->List.of(new ProductCollector.Result(coffee.product(),coffee.store(),coffee.url(),fresh,"")),
          new TargetService(repo,future),future);
      var refreshed=online.collect(false,p->{});
      check(list(obj(refreshed.get("target_result")).get("notifications")).size()==1,"successful later-day collection evaluates saved target automatically");
      check(list(evaluate(new Repository(repo.path()),future,cid).get("notifications")).isEmpty(),"later-day automatic alert is also durable");
      var failure=new OnlineCollectionService(repo,p->List.of(new ProductCollector.Result(coffee.product(),coffee.store(),coffee.url(),null,"fixture HTTP failure")),
          new TargetService(repo,future),future).collect(false,p->{});
      check(s(failure,"status").equals("REFRESH_FAILED") && rows(view(repo,future,cid).get("matches")).isEmpty(),
          "failed collection excludes new product from current target matches");
      var prices=new PriceService(repo,future);
      var h=prices.analyzePrices(m("mode","HISTORY","product_id",cid,"store",coffee.store(),"branch",fresh.branch(),
          "channel","온라인","condition","코스트코 회원","start","2026-11-01","end","2026-11-05"));
      check(integer(h.get("count"),true)==2,"collection failure retains the two actually observed history days");
      settings.change(CollectionSource.keyOf(coffee.url()),"REMOVE");
      check(repo.snapshot().array("favorites").contains(cid) && repo.snapshot().map("targets").containsKey(cid),
          "removing source does not erase saved favorite and purchase goal");

      // Four-day basis for discovered products, with present-day data excluded.
      var state=repo.snapshot();
      for(int days=1;days<=4;days++){
        Clock past=Clock.offset(CLOCK,Duration.ofDays(-days));
        state.record(observation(coffee,past,"10000",false,"코스트코 회원"));
      }
      repo.saveAtomic(state);target(repo,cid,"",false);
      check(new BigDecimal(view(repo,CLOCK,cid).get("basis").toString()).compareTo(new BigDecimal("900"))==0,
          "four real fixture days enable the 90-percent unit-price basis");
      check(view(repo,CLOCK,cid).get("applied").equals(view(repo,CLOCK,cid).get("basis")),
          "clearing personal target returns to historical basis");
      check(list(evaluate(repo,CLOCK,cid).get("notifications")).isEmpty(),"disabled target emits no notification");

      var longItem=discovered("987699","검증 브랜드 유기농 프리미엄 스페셜티 싱글 오리진 콜롬비아 원두커피 1kg",20000);
      settings.add(longItem);
      edt(()->{
        frame.refreshAfterPriceChange();select(frame.targetTab,"favoriteProduct",longItem.product().id());
        button(frame.targetTab,"관심 상품 추가").doClick();frame.navigate(4);frame.setSize(1030,720);frame.validate();
        JButton save=button(frame.targetTab,"목표 저장");
        JTable targetTable=table(frame.targetTab,"targetTable");
        targetTable.scrollRectToVisible(targetTable.getCellRect(targetTable.getSelectedRow(),0,true));
        Rectangle bounds=SwingUtilities.convertRectangle(save.getParent(),save.getBounds(),frame.getContentPane());
        check(bounds.x>=0 && bounds.x+bounds.width<=frame.getContentPane().getWidth()
            && bounds.y+bounds.height<=frame.getContentPane().getHeight(),"long product name does not push target save button out of the minimum-size window");
      });
      edt(()->{frame.dispose();frame=new LedgerFrame(new Repository(repo.path()),future,false);frame.setVisible(true);});
      edt(()->{
        select(frame.priceTab,"priceProduct",longItem.product().id());button(frame.priceTab,"단위 가격 비교").doClick();
        check(table(frame.priceTab,"compareTable").getRowCount()==1,"registered products remain selectable after full frame restart");
        check(((JComboBox<?>)field(frame.targetTab,"favoriteProduct")).getItemCount()==12,"all newly registered products survive full restart");
        if(evidence!=null)try{frame.navigate(3);capture(evidence,"fixture-04.png");frame.navigate(4);capture(evidence,"fixture-05.png");}
        catch(Exception e){throw new AssertionError(e);}
      });
    } finally {edt(()->{for(Window w:Window.getWindows())if(w instanceof JDialog)w.dispose();if(frame!=null)frame.dispose();});}
    System.out.println("Product flow tests: "+passed+" passed, 0 failed");
  }
  private static final class TimerUtil {
    static void answer(int result) {
      AtomicInteger attempts=new AtomicInteger();
      javax.swing.Timer timer=new javax.swing.Timer(30,null);
      timer.addActionListener(e->{
        for(Window w:Window.getWindows())if(w instanceof JDialog d && d.isVisible()){
          JOptionPane pane=find(d);if(pane!=null){pane.setValue(result);timer.stop();return;}
        }
        if(attempts.incrementAndGet()>100)timer.stop();
      });timer.start();
    }
    static JOptionPane find(Container root){
      for(Component c:root.getComponents()){
        if(c instanceof JOptionPane p)return p;
        if(c instanceof Container p){var found=find(p);if(found!=null)return found;}
      }return null;
    }
  }
}
