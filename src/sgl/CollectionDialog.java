package sgl;

import static sgl.Data.*;
import static sgl.SwingViews.*;
import java.awt.*;
import java.awt.event.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;

/** Search, explicit registration and persistent collection settings. */
final class CollectionDialog extends JDialog {
  private final LedgerFrame frame;
  private final ProductDiscovery discovery;
  private final CollectionListService settings;
  private final JTabbedPane tabs=new JTabbedPane();
  private final JComboBox<String> store=combo("홈플러스 익스프레스","코스트코");
  private final JTextField keyword=new JTextField(20),url=new JTextField(43);
  private final JTable found=table("온라인몰","상품명");
  private final JTable tracked=table("자동 수집","온라인몰","상품명","규격 / 묶음");
  private final JTextArea status=details(2),previewText=details(3);
  private final JComboBox<String> category=new JComboBox<>(CATEGORIES.toArray(String[]::new));
  private final JTextField quantity=new JTextField(10);
  private final JLabel quantityLabel=new JLabel("판매 묶음 전체 용량 / 개수");
  private final JButton search,inspect,inspectUrl,add;
  private final List<JButton> networkButtons=new ArrayList<>();
  private List<ProductDiscovery.Hit> hits=List.of();
  private List<CollectionSource> sources=List.of();
  private ProductDiscovery.Preview preview;
  private SwingWorker<?,?> worker;
  private boolean busy;
  private long selectionRevision;
  private boolean showingPreview;

  CollectionDialog(LedgerFrame frame,ProductDiscovery discovery,CollectionListService settings) {
    super(frame,"상품 검색과 자동 수집 목록",true);
    this.frame=frame;this.discovery=discovery;this.settings=settings;
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setSize(1020,800);setMinimumSize(new Dimension(960,740));setLocationRelativeTo(frame);
    JPanel content=new JPanel(new BorderLayout(8,8));
    content.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));
    setContentPane(content);
    JTextArea intro=details(2);
    intro.setFocusable(false);
    intro.setText("원하는 상품을 검색하고 확인한 뒤 자동 수집에 추가하세요. 다음 실행과 갱신에도 계속 가져옵니다.\n수집 목록은 즐겨찾기와 별개입니다. 목록에서 중지하거나 삭제해도 기존 가격 이력과 가계부는 남습니다.");
    content.add(intro,BorderLayout.NORTH);
    content.add(tabs,BorderLayout.CENTER);
    JPanel bottom=new JPanel(new BorderLayout(8,0));
    bottom.add(new JScrollPane(status),BorderLayout.CENTER);
    JPanel close=flow();close.add(frame.button("닫기",this::dispose));
    bottom.add(close,BorderLayout.EAST);
    content.add(bottom,BorderLayout.SOUTH);
    JPanel searchPage=new JPanel(new BorderLayout(8,8));
    JPanel query=flow();
    query.add(store);query.add(keyword);
    search=frame.button("온라인 상품 검색",this::search);
    query.add(search);keyword.addActionListener(e->search());
    searchPage.add(query,BorderLayout.NORTH);
    found.getColumnModel().getColumn(0).setPreferredWidth(120);
    found.getColumnModel().getColumn(1).setPreferredWidth(700);
    searchPage.add(new JScrollPane(found),BorderLayout.CENTER);
    JPanel editor=new JPanel();
    editor.setLayout(new BoxLayout(editor,BoxLayout.Y_AXIS));
    JPanel actions=flow();
    inspect=frame.button("선택 상품 확인",this::inspectSelected);
    actions.add(inspect);
    actions.add(new JLabel("검색은 최대 40개 · 구체적인 상품명으로 좁혀 보세요."));
    editor.add(actions);
    JPanel address=flow();
    address.add(new JLabel("상품 상세 주소"));address.add(url);
    inspectUrl=frame.button("주소 확인",()->inspect(url.getText()));
    address.add(inspectUrl);editor.add(address);
    editor.add(new JScrollPane(previewText));
    JPanel measurement=flow();
    measurement.add(new JLabel("품목"));measurement.add(category);
    measurement.add(quantityLabel);measurement.add(quantity);
    add=frame.button("자동 수집에 추가",this::add);measurement.add(add);
    category.addActionListener(e->quantityLabel.setText("판매 묶음 전체 "+unit(selected(category))));
    editor.add(measurement);
    searchPage.add(editor,BorderLayout.SOUTH);
    tabs.addTab("상품 찾기 · 추가",searchPage);
    JPanel settingsPage=new JPanel(new BorderLayout(8,8));
    settingsPage.add(new JScrollPane(tracked),BorderLayout.CENTER);
    tracked.getColumnModel().getColumn(2).setPreferredWidth(430);
    JPanel manage=flow();
    manage.add(frame.button("자동 수집 켜기",()->change("ENABLE")));
    manage.add(frame.button("일시 중지",()->change("PAUSE")));
    manage.add(frame.button("수집 목록에서 삭제",()->change("REMOVE")));
    settingsPage.add(manage,BorderLayout.SOUTH);
    tabs.addTab("수집 목록 관리",settingsPage);
    networkButtons.addAll(List.of(search,inspect,inspectUrl));
    resetPreview();reloadList();
    status.setText("기본 목록 밖의 상품도 검색하거나 공식 상품 상세 주소로 추가할 수 있습니다.\n홈플러스 익스프레스·코스트코 검색 지원. 홈플러스 대형마트는 상품 주소로 추가할 수 있습니다.");
    found.getSelectionModel().addListSelectionListener(e -> {
      if(e.getValueIsAdjusting())return;
      invalidatePreview();
      int row=found.getSelectedRow();
      showingPreview=true;
      try {url.setText(row<0?"":hits.get(found.convertRowIndexToModel(row)).url());}
      finally {showingPreview=false;}
    });
    url.getDocument().addDocumentListener(SwingViews.change(this::invalidatePreview));
    keyword.getDocument().addDocumentListener(SwingViews.change(this::queryChanged));
    store.addActionListener(e -> queryChanged());
  }

  private void invalidatePreview() {
    if(showingPreview)return;
    selectionRevision++;
    resetPreview();
    status.setText("선택 또는 주소가 바뀌었습니다. 원하는 상품을 다시 확인한 뒤 추가하세요.");
  }
  private void queryChanged() {
    invalidatePreview();
    clear(found);hits=List.of();
    status.setText("검색어와 온라인몰을 확인하고 온라인 상품 검색을 누르세요.");
  }

  private void search() {
    if(busy)return;
    String query=keyword.getText(),site=selected(store);
    resetPreview();
    long requested=selectionRevision;
    runNetwork("온라인몰에서 검색하는 중…",()->discovery.search(site,query),result->{
      if(requested!=selectionRevision){status.setText("검색 조건이 바뀌었습니다. 다시 검색하세요.");return;}
      hits=result;clear(found);
      for(var hit:hits)addRow(found,hit.store(),hit.name());
      if(!hits.isEmpty())found.setRowSelectionInterval(0,0);
      status.setText("'"+query+"' 검색 결과 "+hits.size()+"개입니다. 상품을 선택하고 '선택 상품 확인'을 누르세요.\n검색 결과가 적으면 다른 검색어 또는 온라인몰을 선택하세요.");
    });
  }
  private void inspectSelected() {
    int row=found.getSelectedRow();
    if(row<0){status.setText("검색 결과에서 상품을 선택하세요.");return;}
    inspect(hits.get(found.convertRowIndexToModel(row)).url());
  }
  private void inspect(String address) {
    if(busy)return;
    resetPreview();
    long requested=selectionRevision;
    runNetwork("상품 이름·가격·용량을 확인하는 중…",()->discovery.inspect(address),result->{
      if(requested!=selectionRevision){status.setText("선택 또는 주소가 바뀌었습니다. 원하는 상품을 다시 확인하세요.");return;}
      preview=result;
      showingPreview=true;
      try {url.setText(result.url());} finally {showingPreview=false;}
      var q=result.quantity();
      if(q!=null){category.setSelectedItem(q.category());quantity.setText(q.total().stripTrailingZeros().toPlainString());}
      else {category.setSelectedItem("기타 식품");quantity.setText("");}
      category.setEnabled(q==null);quantity.setEditable(q==null);
      previewText.setText(result.name()+"\n"+result.url()+"\n"+(q==null
          ?"용량을 자동 확정하지 못했습니다. 원문에서 판매 묶음 전체의 용량·개수를 확인해 입력하세요."
          :q.evidence()+" · 총 "+q.total().stripTrailingZeros().toPlainString()+" "+unit(q.category())));
      previewText.setCaretPosition(0);
      add.setEnabled(true);
      status.setText("상품을 확인했습니다. '자동 수집에 추가'를 눌러야 가격과 수집 설정이 저장됩니다.\n가격은 공식 응답에서 가져오며 사용자가 입력하지 않습니다.");
    });
  }
  private void add() {
    if(preview==null || busy)return;
    try {
      var result=discovery.confirm(preview,selected(category),num(quantity.getText(),false));
      var saved=settings.add(result);
      if(!s(saved,"status").equals("OK")){status.setText(def(saved,"message","저장하지 못했습니다."));return;}
      if(yes(saved,"duplicate")){reloadList();selectTracked(CollectionSource.keyOf(result.url()));status.setText(s(saved,"message"));tabs.setSelectedIndex(1);return;}
      frame.refreshAfterPriceChange();frame.productTab.selectRegisteredProduct(result.product().id());
      reloadList();selectTracked(CollectionSource.keyOf(result.url()));
      status.setText(result.product().name()+" · "+money(result.observation().price())+"원\n자동 수집 목록에 저장했습니다. 다음 실행·갱신에 포함됩니다. 05에서 관심 상품으로도 추가할 수 있습니다.");
      resetPreview();tabs.setSelectedIndex(1);
    } catch(RuntimeException e){status.setText(e.getMessage());}
  }
  private void change(String action) {
    int selected=tracked.getSelectedRow();
    if(selected<0){status.setText("수집 목록에서 상품을 선택하세요.");return;}
    CollectionSource source=sources.get(tracked.convertRowIndexToModel(selected));
    var saved=settings.change(source.key(),action);
    if(!s(saved,"status").equals("OK")){status.setText(def(saved,"message","설정을 저장하지 못했습니다."));return;}
    reloadList();
    status.setText(source.product().name()+" · "+switch(action) {
      case "REMOVE" -> "앞으로 수집하지 않습니다. 기존 가격 이력과 가계부는 유지했습니다.";
      case "PAUSE" -> "자동 수집을 일시 중지했습니다.";
      default -> "다음 자동 수집과 상품 정보 가져오기에 포함됩니다.";
    });
  }
  private void reloadList() {
    int previous=tracked.getSelectedRow();
    String key=previous<0?"":sources.get(tracked.convertRowIndexToModel(previous)).key();
    sources=settings.list();clear(tracked);
    for(var source:sources) {
      var p=source.product();
      addRow(tracked,source.enabled()?"켜짐":"일시 중지",source.store(),p.name(),
          p.size().toPlainString()+p.unit()+" × "+p.packCount());
    }
    tabs.setTitleAt(1,"수집 목록 관리 ("+sources.size()+"개)");
    if(!sources.isEmpty())tracked.setRowSelectionInterval(0,0);
    selectTracked(key);
  }
  private void selectTracked(String key) {
    for(int i=0;i<sources.size();i++)if(sources.get(i).key().equals(key)) {
      int row=tracked.convertRowIndexToView(i);tracked.setRowSelectionInterval(row,row);
      tracked.scrollRectToVisible(tracked.getCellRect(row,0,true));return;
    }
  }
  private void resetPreview() {
    preview=null;previewText.setText("상품을 확인한 뒤 여기에 이름과 판매 묶음 규격이 표시됩니다.");
    quantity.setText("");category.setEnabled(false);quantity.setEditable(false);add.setEnabled(false);
  }
  private <T> void runNetwork(String message,Callable<T> task,Consumer<T> done) {
    busy=true;for(var button:networkButtons)button.setEnabled(false);add.setEnabled(false);
    status.setText(message);
    worker=new SwingWorker<T,Void>() {
      @Override protected T doInBackground() throws Exception{return task.call();}
      @Override protected void done() {
        busy=false;for(var button:networkButtons)button.setEnabled(true);
        if(!isDisplayable())return;
        try {done.accept(get());}
        catch(Exception e) {
          Throwable cause=e.getCause()==null?e:e.getCause();
          status.setText("가져오지 못했습니다: "+cause.getMessage()+"\n기존 수집 목록과 이력은 그대로입니다. 다시 시도하거나 다른 상품을 선택하세요.");
        }
      }
    };
    worker.execute();
  }
  @Override public void dispose() {
    if(worker!=null)worker.cancel(true);
    super.dispose();
  }
}

