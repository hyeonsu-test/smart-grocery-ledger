package sgl;

import static sgl.Data.*;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Desktop regression of search, explicit add, persistent settings and close-before-fetch completion. */
public final class DiscoveryUiTests {
  private DiscoveryUiTests() {}
  private static int passed;
  private static void check(boolean yes,String name) {
    if(!yes)throw new AssertionError(name);
    System.out.println("PASS "+(++passed)+" "+name);
  }
  private static Object get(Object object,String name) {
    try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    catch(Exception e){throw new AssertionError(e);}
  }
  private static void edt(Runnable run)throws Exception {SwingUtilities.invokeAndWait(run);}
  private static JButton button(Container parent,String name) {
    for(Component c:parent.getComponents()) {
      if(c instanceof JButton b && b.getText().equals(name))return b;
      if(c instanceof Container p){JButton b=button(p,name);if(b!=null)return b;}
    }
    return null;
  }
  private static void waitDone(CollectionDialog dialog)throws Exception {
    long deadline=System.nanoTime()+10_000_000_000L;
    while(System.nanoTime()<deadline) {
      AtomicBoolean busy=new AtomicBoolean();
      edt(()->busy.set((boolean)get(dialog,"busy")));
      if(!busy.get()){edt(()->{});return;}
      Thread.sleep(30);
    }
    throw new AssertionError("dialog did not finish");
  }
  private static int rows(CollectionDialog dialog,String table) {
    return ((JTable)get(dialog,table)).getRowCount();
  }
  /** Runs desktop tests with controlled storefront responses.
   * @param args unused
   * @throws Exception if any interaction fails
   */
  public static void main(String[] args)throws Exception {
    Clock clock=Clock.systemDefaultZone();
    Repository repo=new Repository(Files.createTempDirectory("sgl-discovery-ui-").resolve("ledger.json"));
    var empty=repo.snapshot();empty.collectionSources(List.of());repo.saveAtomic(empty);
    AtomicInteger reads=new AtomicInteger();
    ProductDiscovery discovery=new ProductDiscovery(clock,uri->{
      reads.incrementAndGet();
      return uri.getPath().equals("/search")
          ?"<a class='js-lister-name' href='/Foods/Test/p/987654'>테스트 원두커피 1kg</a>"
          :DiscoveryTests.page("테스트 원두커피 1kg");
    });
    SwingAppearance.install();
    LedgerFrame[] frame={null};CollectionDialog[] box={null};
    try {
      edt(()->{
        frame[0]=new LedgerFrame(repo,clock,false,progress->List.of());
        frame[0].setVisible(true);
        box[0]=new CollectionDialog(frame[0],discovery,new CollectionListService(repo,clock));
      });
      CollectionDialog dialog=box[0];
      SwingUtilities.invokeLater(()->dialog.setVisible(true));edt(()->{});
      check(button(frame[0].productTab,"상품 검색 · 수집 목록")!=null,"product tab exposes search and collection settings");
      check(rows(dialog,"tracked")==0,"explicit empty list remains empty in UI");
      edt(()->{
        ((JComboBox<?>)get(dialog,"store")).setSelectedItem("코스트코");
        ((JTextField)get(dialog,"keyword")).setText("커피");
        button(dialog,"온라인 상품 검색").doClick();
      });
      waitDone(dialog);
      check(rows(dialog,"found")==1 && repo.snapshot().products().isEmpty(),"search results do not silently register products");
      check(!button(dialog,"자동 수집에 추가").isEnabled(),"add stays disabled before detail confirmation");
      edt(()->button(dialog,"선택 상품 확인").doClick());waitDone(dialog);
      check(((JTextField)get(dialog,"quantity")).getText().equals("1000"),"preview displays normalized full-package quantity");
      check(!((JTextField)get(dialog,"quantity")).isEditable(),"known official quantity cannot be accidentally edited");
      check(repo.snapshot().collectionSources().isEmpty(),"preview alone does not change saved collection");
      edt(()->button(dialog,"자동 수집에 추가").doClick());
      check(repo.snapshot().collectionSources().size()==1 && repo.snapshot().offers().size()==1,"explicit add saves source and fetched price");
      check(rows(dialog,"tracked")==1,"registered source appears in collection manager");
      check(((JTable)get(frame[0].productTab,"productTable")).getRowCount()==1,"registered price reaches product screen");
      edt(()->button(dialog,"일시 중지").doClick());
      check(!repo.snapshot().collectionSources().get(0).enabled(),"pause button persists disabled setting");
      int before=reads.get();
      var result=new OnlineCollectionService(repo,new OnlineCatalog(clock,u->{reads.incrementAndGet();return "";},List.of()),null,clock)
          .collect(false,p->{});
      check(yes(result,"skipped") && reads.get()==before,"paused UI source is not fetched");
      edt(()->button(dialog,"자동 수집 켜기").doClick());
      check(new Repository(repo.path()).snapshot().collectionSources().get(0).enabled(),"enable setting survives restart");
      edt(()->button(dialog,"수집 목록에서 삭제").doClick());
      check(repo.snapshot().collectionSources().isEmpty() && repo.snapshot().offers().size()==1,"UI removal preserves observations");
      edt(dialog::dispose);
      CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
      ProductDiscovery slow=new ProductDiscovery(clock,u->{
        started.countDown();
        try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        return DiscoveryTests.page("테스트 원두커피 1kg");
      });
      edt(()->box[0]=new CollectionDialog(frame[0],slow,new CollectionListService(repo,clock)));
      CollectionDialog closing=box[0];SwingUtilities.invokeLater(()->closing.setVisible(true));edt(()->{});
      edt(()->{
        ((JTextField)get(closing,"url")).setText("https://www.costco.co.kr/p/987654");
        button(closing,"주소 확인").doClick();
      });
      check(started.await(3,TimeUnit.SECONDS),"detail fetch runs asynchronously");
      edt(closing::dispose);release.countDown();Thread.sleep(100);edt(()->{});
      check(repo.snapshot().collectionSources().isEmpty(),"closing pending preview cannot add a source");
      check(frame[0].isDisplayable(),"closing manager leaves main application running");
    } finally {
      edt(()->{if(box[0]!=null)box[0].dispose();if(frame[0]!=null)frame[0].dispose();});
    }
    System.out.println("Discovery Swing tests: "+passed+" passed, 0 failed");
  }
}

