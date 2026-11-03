package sgl;
import static sgl.Data.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Swing integration checks for empty state, collection actions and favorite targets. */
public final class CollectionUiTests {
  private CollectionUiTests() {}
  private static int passed;
  private static LedgerFrame frame;
  private static void check(boolean value,String name) {
    if(!value)throw new AssertionError(name);
    System.out.println("PASS "+(++passed)+" "+name);
  }
  private static Object field(Object object,String name)throws Exception {
    Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
  }
  private static JButton button(Container root,String text) {
    for(Component c:root.getComponents()) {
      if(c instanceof JButton b && b.getText().equals(text))return b;
      if(c instanceof Container nested){JButton b=button(nested,text);if(b!=null)return b;}
    }
    return null;
  }
  private static void edt(Runnable run)throws Exception {SwingUtilities.invokeAndWait(run);}
  private static JTable table(Object tab,String field)throws Exception{return (JTable)field(tab,field);}
  private static void waitDone()throws Exception {
    long limit=System.currentTimeMillis()+10000;
    while(System.currentTimeMillis()<limit){
      boolean[] busy={true};edt(()->busy[0]=frame.productTab.refreshing());
      if(!busy[0]){edt(()->{});return;}
      Thread.sleep(40);
    }
    throw new AssertionError("collector did not complete");
  }
  /**
   * Runs desktop integration checks with an isolated ledger and deterministic collector.
   * @param args unused
   * @throws Exception if a check fails
   */
  public static void main(String[] args)throws Exception {
    Clock clock=Clock.systemDefaultZone();
    Repository repo=new Repository(Files.createTempDirectory("sgl-online-ui-").resolve("ledger.json"));
    var source=OnlineCatalog.defaults().get(0);
    var now=OffsetDateTime.now(clock);
    var offer=new PriceObservation("ui",source.product().id(),source.store(),"공개 기준점포 245",
        "온라인","일반",new java.math.BigDecimal("3090"),source.uri().toString(),
        now.toLocalDate(),now,null,null,"웹 자동 수집",false);
    CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    ProductCollector collector=progress->{
      started.countDown();
      try{if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("test timed out");}
      catch(InterruptedException e){throw new AssertionError(e);}
      return List.of(new ProductCollector.Result(source.product(),source.store(),source.uri().toString(),offer,""));
    };
    SwingAppearance.install();
    try {
      edt(()->{frame=new LedgerFrame(repo,clock,false,collector);frame.setVisible(true);});
      check(button(frame.productTab,"JSON 피드 연결")==null && button(frame.productTab,"연결 피드 갱신")==null,"no JSON file controls in customer UI");
      check(button(frame.productTab,"상품 정보 가져오기")!=null
          && button(frame.priceTab,"상품 정보 가져오기")!=null
          && button(frame.targetTab,"상품 정보 가져오기")!=null,"collection action in all three relevant tabs");
      check(table(frame.productTab,"productTable").getRowCount()==0,"fresh ledger has no invented prices");
      edt(()->button(frame.priceTab,"상품 정보 가져오기").doClick());
      check(started.await(2,TimeUnit.SECONDS),"04 button starts collector without files");
      edt(()->{
        check(!button(frame.productTab,"상품 수집 중…").isEnabled(),"duplicate collection disabled");
        var state=repo.snapshot();state.map("budgets").put(YearMonth.now(clock).toString(),10000);repo.saveAtomic(state);
        frame.navigate(0);
      });
      check(frame.isShowing(),"window remains responsive while collector waits");
      release.countDown();waitDone();
      check(table(frame.productTab,"productTable").getRowCount()==1,"03 populated after collection");
      check(table(frame.priceTab,"compareTable").getRowCount()==1,"04 comparison refreshed after collection");
      check(table(frame.priceTab,"historyTable").getRowCount()==1,"04 shows today's real observation");
      check(integer(repo.snapshot().map("budgets").get(YearMonth.now(clock).toString()),true)==10000,"concurrent ledger edit preserved");
      check(button(frame.priceTab,"상품 정보 가져오기").isEnabled(),"retry button restored");
      check(table(frame.targetTab,"targetTable").getRowCount()==0,"05 waits for explicit favorite choice");
      edt(()->button(frame.targetTab,"관심 상품 추가").doClick());
      check(table(frame.targetTab,"targetTable").getRowCount()==1,"05 favorite selector immediately adds target row");
      check(repo.snapshot().array("favorites").size()==1,"favorite is saved");
      check(((JButton)field(frame.targetTab,"saveButton")).isEnabled(),"new favorite target can be edited");
      check(((JTextArea)field(frame.targetTab,"targetDetails")).getText().contains("4일 미만"),"insufficient historical basis explained");
      edt(()->frame.dispose());
      CountDownLatch autoStarted=new CountDownLatch(1);
      Repository fresh=new Repository(Files.createTempDirectory("sgl-auto-ui-").resolve("ledger.json"));
      edt(()->{frame=new LedgerFrame(fresh,clock,true,p->{autoStarted.countDown();return List.of(new ProductCollector.Result(source.product(),source.store(),source.uri().toString(),offer,""));});});
      check(autoStarted.await(3,TimeUnit.SECONDS),"fresh app starts automatic collection");
      waitDone();
      check(fresh.snapshot().products().size()==1,"automatic result committed with no user file");
      System.out.println("Collection Swing tests: "+passed+" passed, 0 failed");
    } finally {if(frame!=null)edt(()->frame.dispose());}
  }
}

