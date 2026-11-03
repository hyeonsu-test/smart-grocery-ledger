package sgl;

import static sgl.Data.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** First-use selection, registration and unsaved purchase-goal regression checks. */
public final class JourneyTests {
  private JourneyTests() {}
  private static int passed;
  private static LedgerFrame frame;
  private static CollectionDialog dialog;
  private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-03T11:00:00Z"),ZoneId.of("Asia/Seoul"));
  private static void check(boolean value,String name) {
    if(!value)throw new AssertionError(name);
    System.out.println("PASS "+(++passed)+" "+name);
  }
  private static Object field(Object o,String n) {
    try {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    catch(ReflectiveOperationException e){throw new AssertionError(e);}
  }
  private static JTextField text(Object o,String n){return (JTextField)field(o,n);}
  private static JTable table(Object o,String n){return (JTable)field(o,n);}
  private static JButton button(Container p,String n) {
    for(Component c:p.getComponents()){
      if(c instanceof JButton b && b.getText().equals(n))return b;
      if(c instanceof Container child){JButton b=button(child,n);if(b!=null)return b;}
    }
    return null;
  }
  @SuppressWarnings("unchecked")
  private static void select(Object tab,String name,String id) {
    SwingViews.select((JComboBox<SwingViews.Choice>)field(tab,name),id);
  }
  private static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
  private static void idle()throws Exception {
    for(int i=0;i<400;i++){
      boolean[] busy={true};edt(()->busy[0]=(boolean)field(dialog,"busy"));
      if(!busy[0])return;
      Thread.sleep(25);
    }
    throw new AssertionError("search did not finish");
  }
  private static String page(String id) {
    return "<script type='application/ld+json'>"+Json.stringify(m("@type","Product","sku",id,
        "name","검증 원두커피 "+id+" 1kg","brand",m("name","검증"),"offers",
        m("price",10000,"priceCurrency","KRW","availability","https://schema.org/InStock")))+"</script>";
  }
  private static void search(String query)throws Exception {
    edt(()->{text(dialog,"keyword").setText(query);button(dialog,"온라인 상품 검색").doClick();});idle();
  }
  private static String selectedSource() {
    int row=table(dialog,"tracked").getSelectedRow();
    return row<0?"":table(dialog,"tracked").getValueAt(row,2).toString();
  }
  private static String goalStatus(){JTable t=table(frame.targetTab,"targetTable");return t.getValueAt(t.getSelectedRow(),5).toString();}
  private static void capture(Path folder,String name,JComponent pane) {
    if(folder==null)return;
    try {
      pane.validate();
      BufferedImage image=new BufferedImage(pane.getWidth()*2,pane.getHeight()*2,BufferedImage.TYPE_INT_RGB);
      Graphics2D g=image.createGraphics();g.scale(2,2);pane.printAll(g);g.dispose();
      ImageIO.write(image,"png",folder.resolve(name).toFile());
    }catch(Exception e){throw new AssertionError(e);}
  }
  /** Runs first-use desktop checks using controlled official response fixtures.
   * @param args optional screenshot directory
   * @throws Exception on failure
   */
  public static void main(String[] args)throws Exception {
    Path evidence=args.length==0?null:Path.of(args[0]);
    if(evidence!=null)Files.createDirectories(evidence);
    Repository repo=new Repository(Files.createTempDirectory("sgl-journey-").resolve("ledger.json"));
    var empty=repo.snapshot();empty.collectionSources(List.of());repo.saveAtomic(empty);
    AtomicBoolean slow=new AtomicBoolean();
    CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    ProductDiscovery discovery=new ProductDiscovery(CLOCK,u->{
      if(u.getPath().equals("/search")) {
        if(u.getQuery().contains("empty"))return "검색 결과 없음";
        return "<a class='js-lister-name' href='/p/987654'>검증 커피 A 1kg</a>"
            +"<a class='js-lister-name' href='/p/987655'>검증 커피 B 1kg</a>";
      }
      if(slow.get()){
        started.countDown();
        try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
      }
      return page(u.getPath().endsWith("987655")?"987655":"987654");
    });
    SwingAppearance.install();
    try {
      edt(()->{
        frame=new LedgerFrame(repo,CLOCK,false);frame.setVisible(true);
        check(table(frame.productTab,"productTable").getRowCount()==0,"fresh ledger has no fabricated products");
        check(table(frame.priceTab,"compareTable").getRowCount()==0,"04 starts with no invented prices");
        check(!button(frame.targetTab,"목표 저장").isEnabled(),"05 cannot save before a favorite exists");
        check(((JTextArea)field(frame.targetTab,"targetDetails")).getText().contains("관심 상품"),"empty 05 explains collection and favorite setup");
        dialog=new CollectionDialog(frame,discovery,new CollectionListService(repo,CLOCK));
      });
      SwingUtilities.invokeLater(()->dialog.setVisible(true));edt(()->{});
      edt(()->((JComboBox<?>)field(dialog,"store")).setSelectedItem("코스트코"));
      search("empty");
      edt(()->check(table(dialog,"found").getRowCount()==0&&!button(dialog,"자동 수집에 추가").isEnabled(),"zero results cannot add a stale product"));
      search("coffee");
      edt(()->{
        check(table(dialog,"found").getRowCount()==2,"search displays two official candidates");
        check(repo.snapshot().products().isEmpty(),"search alone never registers a product");
        button(dialog,"선택 상품 확인").doClick();
      });idle();
      edt(()->{
        check(button(dialog,"자동 수집에 추가").isEnabled(),"confirmed A can be explicitly added");
        table(dialog,"found").setRowSelectionInterval(1,1);
        check(!button(dialog,"자동 수집에 추가").isEnabled(),"selecting B invalidates preview A");
        check(text(dialog,"url").getText().endsWith("987655"),"address follows the selected result");
        button(dialog,"자동 수집에 추가").doClick();
        check(repo.snapshot().products().isEmpty(),"invalidated add does not save A");
        button(dialog,"선택 상품 확인").doClick();
      });idle();
      edt(()->{
        check(((JTextArea)field(dialog,"previewText")).getText().contains("987655"),"B confirmation shows B identity");
        text(dialog,"url").setText("https://www.costco.co.kr/p/987654");
        check(!button(dialog,"자동 수집에 추가").isEnabled(),"editing URL invalidates B preview");
        slow.set(true);button(dialog,"주소 확인").doClick();
      });
      check(started.await(3,TimeUnit.SECONDS),"detail request can remain pending");
      edt(()->{table(dialog,"found").setRowSelectionInterval(0,0);table(dialog,"found").setRowSelectionInterval(1,1);});
      release.countDown();idle();slow.set(false);
      edt(()->{
        check(!button(dialog,"자동 수집에 추가").isEnabled()&&field(dialog,"preview")==null,"late A response cannot restore registration after B selection");
        check(repo.snapshot().offers().isEmpty(),"stale completion leaves disk prices empty");
        button(dialog,"선택 상품 확인").doClick();
      });idle();
      edt(()->{
        text(dialog,"keyword").setText("changed");
        check(table(dialog,"found").getRowCount()==0&&!button(dialog,"자동 수집에 추가").isEnabled(),"changed search words clear old results and preview");
      });
      search("coffee");
      edt(()->{button(dialog,"선택 상품 확인").doClick();});idle();
      edt(()->{
        button(dialog,"자동 수집에 추가").doClick();
        check(repo.snapshot().products().size()==1,"explicit confirmed A registers once");
        check(selectedSource().contains("987654"),"new source is selected in collection list");
        ((JTabbedPane)field(dialog,"tabs")).setSelectedIndex(0);
        table(dialog,"found").setRowSelectionInterval(1,1);button(dialog,"선택 상품 확인").doClick();
      });idle();
      edt(()->{
        ((JComboBox<?>)field(frame.productTab,"filterCategory")).setSelectedItem("우유");
        ((JCheckBox)field(frame.productTab,"promotionsOnly")).setSelected(true);
        button(dialog,"자동 수집에 추가").doClick();
        check(repo.snapshot().products().size()==2,"B is registered separately without replacing A");
        check(selectedSource().contains("987655"),"new B stays selected even with existing A");
        check(table(frame.productTab,"productTable").getValueAt(table(frame.productTab,"productTable").getSelectedRow(),1).toString().contains("987655"),"03 shows and selects B despite previous filters");
        button(dialog,"일시 중지").doClick();
        check(selectedSource().contains("987655"),"pause keeps the same collection row selected");
        check(repo.snapshot().collectionSources().stream().filter(x->x.sku().equals("987655")).noneMatch(CollectionSource::enabled),"pause affects selected B only");
        check(repo.snapshot().collectionSources().stream().filter(x->x.sku().equals("987654")).allMatch(CollectionSource::enabled),"A remains enabled after pausing B");
        button(dialog,"자동 수집 켜기").doClick();
        check(selectedSource().contains("987655"),"resume also keeps B selected");
        capture(evidence,"registration.png",(JComponent)dialog.getContentPane());
        dialog.dispose();
      });
      String pid=repo.snapshot().products().stream().filter(p->p.name().contains("987655")).findFirst().orElseThrow().id();
      edt(()->{
        frame.navigate(3);select(frame.priceTab,"priceProduct",pid);button(frame.priceTab,"단위 가격 비교").doClick();
        check(table(frame.priceTab,"compareTable").getRowCount()==1,"04 compares the newly added B");
        check(table(frame.priceTab,"compareTable").getValueAt(0,4).equals("1,000 / 100g"),"04 unit agrees with official package amount");
        check(table(frame.priceTab,"historyTable").getRowCount()==1,"04 has only the observed day");
        capture(evidence,"04-first-use.png",(JComponent)frame.getContentPane());
        frame.navigate(4);select(frame.targetTab,"favoriteProduct",pid);button(frame.targetTab,"관심 상품 추가").doClick();
        check(field(frame.targetTab,"editingTargetId").equals(pid),"05 editor selects B immediately");
        check(goalStatus().equals("기준 없음"),"first goal distinguishes missing basis from not reached");
        check(((JTextArea)field(frame.targetTab,"targetDetails")).getText().contains("목표 기준 없음"),"missing basis explains the next step");
        capture(evidence,"05-no-basis.png",(JComponent)frame.getContentPane());
        text(frame.targetTab,"targetPrice").setText("1000");
        ((JCheckBox)field(frame.targetTab,"targetEnabled")).doClick();button(frame.targetTab,"목표 저장").doClick();
        check(frame.status.getText().contains("저장했습니다"),"save gives explicit confirmation");
        check(goalStatus().equals("충족"),"personal unit target works on the first day");
        text(frame.targetTab,"targetPrice").setText("1");
        byte[] before;
        try{before=Files.readAllBytes(repo.path());}catch(Exception e){throw new AssertionError(e);}
        button(frame.targetTab,"현재 가격으로 알림 확인").doClick();
        check(repo.snapshot().map("alerts").isEmpty(),"unsaved target cannot consume a saved-target alert");
        check(text(frame.targetTab,"targetPrice").getText().equals("1")&&frame.targetTab.hasDraft(),"blocked evaluation preserves edited goal");
        check(frame.status.getText().contains("먼저 저장"),"unsaved evaluation explains saving first");
        try{check(java.util.Arrays.equals(before,Files.readAllBytes(repo.path())),"blocked evaluation changes no ledger bytes");}catch(Exception e){throw new AssertionError(e);}
        button(frame.targetTab,"목표 저장").doClick();check(goalStatus().equals("미충족"),"saved low target is genuinely not reached");
        button(frame.targetTab,"현재 가격으로 알림 확인").doClick();
        check(frame.status.getText().contains("새 알림이 없습니다"),"no alert still gives visible feedback");
        text(frame.targetTab,"targetPrice").setText("");button(frame.targetTab,"목표 저장").doClick();
        check(goalStatus().equals("기준 없음"),"clearing first-day personal goal returns to missing basis");
        text(frame.targetTab,"targetPrice").setText("1000");button(frame.targetTab,"목표 저장").doClick();
        javax.swing.Timer timer=new javax.swing.Timer(30,null);
        timer.addActionListener(e->{for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isVisible()){d.dispose();timer.stop();}});
        timer.start();button(frame.targetTab,"현재 가격으로 알림 확인").doClick();timer.stop();
        check(repo.snapshot().map("alerts").containsKey(pid),"saved reached target issues a durable alert");
        button(frame.targetTab,"현재 가격으로 알림 확인").doClick();
        check(frame.status.getText().contains("새 알림이 없습니다"),"repeated evaluation explains no duplicate alert");
        capture(evidence,"05-saved.png",(JComponent)frame.getContentPane());
        frame.dispose();frame=new LedgerFrame(new Repository(repo.path()),CLOCK,false);frame.setVisible(true);
        frame.navigate(4);select(frame.targetTab,"favoriteProduct",pid);button(frame.targetTab,"관심 상품 추가").doClick();
        check(text(frame.targetTab,"targetPrice").getText().equals("1000"),"goal restored after full window restart");
        check(goalStatus().equals("충족"),"restarted goal retains the correct state");
        check(frame.repo.snapshot().collectionSources().size()==2,"registered collection persists across restart");
        button(frame.targetTab,"현재 가격으로 알림 확인").doClick();
        check(frame.status.getText().contains("새 알림이 없습니다"),"restart does not repeat today's notification");
      });
    } finally {edt(()->{if(dialog!=null)dialog.dispose();if(frame!=null)frame.dispose();});}
    System.out.println("Journey tests: "+passed+" passed, 0 failed");
  }
}
