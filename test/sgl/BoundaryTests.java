package sgl;

import static sgl.Data.*;
import java.awt.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Regression checks for minimum-order estimates and collection lifecycle boundaries. */
public final class BoundaryTests {
  private BoundaryTests() {}
  private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"),ZoneId.of("Asia/Seoul"));
  private static int passed;
  private static void check(boolean ok,String text) {
    if(!ok)throw new AssertionError(text);
    System.out.println("PASS "+(++passed)+" "+text);
  }
  private static Object field(Object o,String name) {
    try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    catch(ReflectiveOperationException e){throw new AssertionError(e);}
  }
  private static void edt(Runnable action)throws Exception {SwingUtilities.invokeAndWait(action);}
  private static JButton button(Container root,String name) {
    for(Component c:root.getComponents()) {
      if(c instanceof JButton b && b.getText().equals(name))return b;
      if(c instanceof Container nested){var b=button(nested,name);if(b!=null)return b;}
    }
    return null;
  }
  private static Repository fresh()throws IOException {
    return new Repository(Files.createTempDirectory("sgl-boundary-").resolve("ledger.json"));
  }
  private static PriceObservation offer(String condition) {
    var source=OnlineCatalog.defaults().get(0);var now=OffsetDateTime.now(CLOCK);
    return new PriceObservation("min",source.product().id(),source.store(),"test","온라인",condition,
        new BigDecimal("3000"),source.uri().toString(),now.toLocalDate(),now,null,null,"웹 자동 수집",false);
  }
  private static Map<String,Object> plan(Repository repo,int quantity,String condition,Clock clock) {
    return new BudgetService(repo,clock).manageBudgetPlan(m("action","UPSERT_ITEM","month","2026-11",
        "product_id",OnlineCatalog.defaults().get(0).product().id(),"quantity",quantity,"condition",condition,"draftPlan",List.of()));
  }
  private static void awaitIgnoringInterrupt(CountDownLatch latch) {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
    while(latch.getCount()!=0 && System.nanoTime()<deadline) {
      try{latch.await(40,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){ }
    }
    if(latch.getCount()!=0)throw new AssertionError("test release timed out");
  }

  /** Executes service and actual Swing boundary checks using isolated ledgers.
   * @param args unused
   * @throws Exception if a check fails
   */
  public static void main(String[] args)throws Exception {
    var source=OnlineCatalog.defaults().get(0);var minimum=offer("일반 · 최소 2개 구매");
    var repo=fresh();var state=repo.snapshot();state.addProduct(source.product());state.record(minimum);
    state.map("budgets").put("2026-11",10000);repo.saveAtomic(state);
    byte[] original=Files.readAllBytes(repo.path());
    check(minimum.minimumQuantity()==2,"minimum is package count");
    check(offer("일반").minimumQuantity()==1,"ordinary offer defaults to one");
    check(offer("코스트코 회원 · 최소 3개 구매").minimumQuantity()==3,"membership minimum recognized");
    check(source.product().unitPrice(minimum.price()).compareTo(new BigDecimal("300"))==0,"minimum does not multiply package contents or unit price");
    var one=plan(repo,1,minimum.condition(),CLOCK);
    check(s(one,"status").equals("OK") && one.get("totalEstimate")==null,"below minimum leaves plan total unknown");
    check(one.get("excess")==null && one.get("projectedBalance")==null,"below minimum cannot assert affordability");
    check(integer(one.get("unknownCount"),true)==1 && num(one.get("validSubtotal"),true).signum()==0,"ineligible price excluded from valid subtotal");
    check(s(rows(one.get("items")).get(0),"estimateReason").equals("최소 2개 필요"),"minimum requirement explained");
    check(integer(rows(one.get("draftPlan")).get(0).get("quantity"),false)==1,"user quantity is never silently increased");
    check(Arrays.equals(original,Files.readAllBytes(repo.path())),"estimating does not save");
    var two=plan(repo,2,minimum.condition(),CLOCK);
    check(num(two.get("totalEstimate"),false).compareTo(new BigDecimal("6000"))==0,"exact minimum uses two package prices");
    check(num(two.get("projectedBalance"),true).compareTo(new BigDecimal("4000"))==0,"eligible plan computes remaining budget");
    check(s(rows(two.get("items")).get(0),"estimateReason").isEmpty(),"eligible quantity clears reason");
    check(num(plan(repo,3,minimum.condition(),CLOCK).get("totalEstimate"),false).compareTo(new BigDecimal("9000"))==0,"above minimum uses actual quantity");
    check(plan(repo,1,"일반",CLOCK).get("totalEstimate")==null,"ordinary plan cannot borrow conditional price");
    new BudgetService(repo,CLOCK).manageBudgetPlan(m("action","SAVE_PLAN","month","2026-11","draftPlan",one.get("draftPlan")));
    var reopened=new Repository(repo.path());
    check(new BudgetService(reopened,CLOCK).manageBudgetPlan(m("action","GET","month","2026-11")).get("totalEstimate")==null,"restart preserves insufficient quantity and unknown estimate");
    check(reopened.snapshot().records().isEmpty(),"plan save never creates actual expense");
    var receipt=new LedgerService(repo,CLOCK).manageLedger(m("action","CREATE","date","2026-11-03","kind","지출",
        "category","식료품","amount",2900,"quantity",1,"product_id",source.product().id()));
    check(s(receipt,"status").equals("OK") && integer(repo.snapshot().records().get(0).get("amount"),false)==2900,"actual receipt remains independent of online minimum and price");
    Clock before=Clock.offset(CLOCK,Duration.ofHours(24).minusMillis(1)),after=Clock.offset(CLOCK,Duration.ofHours(24));
    check(plan(repo,2,minimum.condition(),before).get("totalEstimate")!=null,"price remains usable just before 24 hours");
    check(plan(repo,2,minimum.condition(),after).get("totalEstimate")==null,"price expires at exactly 24 hours");
    Repository failing=new Repository(repo.path()) {
      @Override protected void replaceFile(Path a,Path b)throws IOException {throw new IOException("injected boundary failure");}
    };
    byte[] beforeFailure=Files.readAllBytes(repo.path());
    var failed=new BudgetService(failing,CLOCK).manageBudgetPlan(m("action","SAVE_PLAN","month","2026-11","draftPlan",two.get("draftPlan")));
    check(s(failed,"status").equals("SAVE_FAILED") && rows(failed.get("draftPlan")).get(0).get("quantity").toString().equals("2"),"failed plan save returns edited quantity");
    check(Arrays.equals(beforeFailure,Files.readAllBytes(repo.path()))
        && integer(rows(failing.snapshot().map("plans").get("2026-11")).get(0).get("quantity"),false)==1,"failed save preserves disk and memory");

    SwingAppearance.install();
    edt(()->{
      var frame=new LedgerFrame(repo,CLOCK,false);
      try {
        frame.setVisible(true);frame.budgetTab.addProduct(source.product(),minimum);
        JTable table=(JTable)field(frame.budgetTab,"planTable");
        check(table.getValueAt(table.getRowCount()-1,1).equals("1"),"03 to 02 keeps chosen package quantity");
        check(table.getValueAt(table.getRowCount()-1,6).equals("최소 2개 필요"),"02 shows unmet minimum in estimate cell");
        table.setRowSelectionInterval(table.getRowCount()-1,table.getRowCount()-1);
        ((JTextField)field(frame.budgetTab,"planQty")).setText("2");button(frame.budgetTab,"항목 적용").doClick();
        check(table.getValueAt(table.getRowCount()-1,6).equals("6,000"),"02 recalculates after quantity correction");
      } finally{frame.dispose();}
    });

    var closedRepo=fresh();var saved=closedRepo.snapshot();saved.map("budgets").put("2026-11",10000);
    saved.addProduct(source.product());saved.array("favorites").add(source.product().id());closedRepo.saveAtomic(saved);
    new TargetService(closedRepo,CLOCK).managePurchaseTargets(m("action","SET_TARGET","product_id",source.product().id(),
        "enabled",true,"price",350,"channel","온라인","condition",minimum.condition()));
    byte[] originalClosed=Files.readAllBytes(closedRepo.path());
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    var result=new ProductCollector.Result(source.product(),source.store(),source.uri().toString(),minimum,"");
    var coordinator=new OnlineCollectionService(closedRepo,p->{entered.countDown();awaitIgnoringInterrupt(release);return List.of(result);},
        new TargetService(closedRepo,CLOCK),CLOCK);
    ExecutorService pool=Executors.newSingleThreadExecutor();
    try {
      Future<Map<String,Object>> pending=pool.submit(()->coordinator.collect(false,p->{}));
      check(entered.await(3,TimeUnit.SECONDS),"delayed collection started");
      coordinator.close();coordinator.close();release.countDown();
      check(s(pending.get(4,TimeUnit.SECONDS),"status").equals("CANCELLED"),"close discards response even if collector ignores cancellation");
      check(Arrays.equals(originalClosed,Files.readAllBytes(closedRepo.path())),"cancelled collection leaves ledger bytes unchanged");
      check(closedRepo.snapshot().offers().isEmpty() && closedRepo.snapshot().value("online_attempt").isEmpty(),"cancelled collection does not consume daily attempt");
      check(closedRepo.snapshot().map("alerts").isEmpty(),"cancelled collection does not consume daily target notification");
      check(s(coordinator.collect(false,p->{}),"status").equals("CANCELLED"),"closed coordinator cannot restart");
    } finally{release.countDown();pool.shutdownNow();coordinator.close();}
    var next=new OnlineCollectionService(closedRepo,p->List.of(result),new TargetService(closedRepo,CLOCK),CLOCK);
    check(s(next.collect(true,p->{}),"status").equals("OK") && closedRepo.snapshot().offers().size()==1,"next application session retries cancelled day");
    check(closedRepo.snapshot().map("alerts").containsKey(source.product().id()),"next session can still notify a matching target");
    next.close();

    var uiRepo=fresh();CountDownLatch uiStarted=new CountDownLatch(1),uiRelease=new CountDownLatch(1);
    LedgerFrame[] ui={null};String[] finalStatus={""};
    edt(()->{ui[0]=new LedgerFrame(uiRepo,CLOCK,false,p->{
      uiStarted.countDown();awaitIgnoringInterrupt(uiRelease);p.accept("late progress");return List.of(result);
    });ui[0].setVisible(true);ui[0].productTab.collectOnline(false);});
    try {
      check(uiStarted.await(3,TimeUnit.SECONDS),"Swing collector starts in background");
      edt(()->{ui[0].dispose();finalStatus[0]=ui[0].status.getText();});
      check(!ui[0].isDisplayable(),"window disposes without waiting for network response");
      uiRelease.countDown();
      AtomicBoolean running=(AtomicBoolean)field(field(ui[0].productTab,"online"),"running");
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
      while(running.get() && System.nanoTime()<deadline)Thread.sleep(20);
      check(!running.get(),"interrupted collector actually finishes");
      Thread.sleep(100);edt(()->{});
      check(uiRepo.snapshot().offers().isEmpty() && !Files.exists(uiRepo.path()),"closed Swing window does not create ledger from late result");
      check(ui[0].status.getText().equals(finalStatus[0]),"late progress and completion do not update disposed UI");
      edt(()->check(Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing),"late completion opens no window or notification"));
      AtomicInteger starts=new AtomicInteger();
      edt(()->{var immediate=new LedgerFrame(freshUnchecked(),CLOCK,true,p->{starts.incrementAndGet();return List.of(result);});immediate.dispose();});
      Thread.sleep(100);edt(()->{});
      check(starts.get()==0,"queued startup auto collection is cancelled when window closes immediately");
    } finally{uiRelease.countDown();edt(()->ui[0].dispose());}
    System.out.println("Boundary tests: "+passed+" passed, 0 failed");
  }

  private static Repository freshUnchecked() {
    try{return fresh();}catch(IOException e){throw new AssertionError(e);}
  }
}
