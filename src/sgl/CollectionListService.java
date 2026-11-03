package sgl;

import static sgl.Data.*;
import java.time.Clock;
import java.util.*;

/** Saves collection settings independently from price history and purchase favorites. */
public final class CollectionListService extends Service {
  /**
   * Creates a settings service.
   * @param repo shared ledger
   * @param clock local clock
   */
  public CollectionListService(Repository repo,Clock clock) { super(repo,clock); }

  /** Reads the current collection settings.
   * @return persisted list, or starter products for ledgers without collection settings
   */
  public List<CollectionSource> list() { synchronized(repo) { return repo.snapshot().collectionSources(); } }

  /**
   * Saves a verified new product and enables future automatic collection.
   * @param verified preview result with a current official observation
   * @return save status; duplicate indicates a source already in the list
   */
  public Map<String,Object> add(ProductCollector.Result verified) {
    return call(m(),()->{
      if(repo.snapshot().demo())return m("status","INVALID_INPUT","message","일반 모드에서 자동 수집 목록을 관리하세요.");
      if(verified.observation()==null)throw new IllegalArgumentException("현재 가격을 확인한 상품만 등록할 수 있습니다.");
      var observation=verified.observation();
      if(!observation.productId().equals(verified.product().id()) || !observation.store().equals(verified.store())
          || !CollectionSource.keyOf(observation.url()).equals(CollectionSource.keyOf(verified.url()))
          || !observation.validity(now()).equals("현재 유효") || !observation.method().equals("웹 자동 수집"))
        throw new IllegalArgumentException("상품과 수집 가격의 출처·시각을 다시 확인하세요.");
      var source=new CollectionSource(verified.product(),verified.store(),CollectionSource.skuOf(verified.url()),verified.url(),true);
      AppState state=repo.snapshot();
      List<CollectionSource> sources=new ArrayList<>(state.collectionSources());
      if(sources.stream().anyMatch(s->s.key().equals(source.key())))
        return ok("duplicate",true,"message","이미 수집 목록에 있는 상품입니다. 일시 중지 상태라면 다시 켜세요.");
      if(sources.size()>=100)throw new IllegalArgumentException("수집 목록은 최대 100개입니다. 사용하지 않는 항목을 삭제하세요.");
      state.addProduct(verified.product());state.record(verified.observation());
      sources.add(source);state.collectionSources(sources);
      return repo.saveAtomic(state);
    });
  }

  /**
   * Enables, pauses or removes one source. Existing prices, favorites and ledger entries remain.
   * @param key canonical source key
   * @param action ENABLE, PAUSE or REMOVE
   * @return save result
   */
  public Map<String,Object> change(String key,String action) {
    return call(m("key",key,"action",action),()->{
      if(repo.snapshot().demo())return m("status","INVALID_INPUT","message","일반 모드에서 수집 목록을 관리하세요.");
      if(!Set.of("ENABLE","PAUSE","REMOVE").contains(action))throw new IllegalArgumentException("수집 설정 동작 오류");
      AppState state=repo.snapshot();
      List<CollectionSource> sources=new ArrayList<>(state.collectionSources());
      int index=-1;
      for(int i=0;i<sources.size();i++)if(sources.get(i).key().equals(key))index=i;
      if(index<0)throw new IllegalArgumentException("수집 목록에서 상품을 찾을 수 없습니다.");
      if(action.equals("REMOVE"))sources.remove(index);
      else sources.set(index,sources.get(index).withEnabled(action.equals("ENABLE")));
      state.collectionSources(sources);
      return repo.saveAtomic(state);
    });
  }
}

