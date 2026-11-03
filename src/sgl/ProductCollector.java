package sgl;

import java.util.List;
import java.util.function.Consumer;

/** Reads current public retailer prices without modifying the user's ledger. */
public interface ProductCollector {
  /**
   * Collects supported products, reporting independent source failures.
   * @param progress short status messages suitable for the UI
   * @return one result for each attempted product
   */
  List<Result> collect(Consumer<String> progress);

  /**
   * Collects the user's enabled sources. Legacy injected collectors may ignore this selection.
   * @param sources enabled persisted collection list
   * @param progress progress callback
   * @return independent results for attempted sources
   */
  default List<Result> collect(List<CollectionSource> sources, Consumer<String> progress) {
    return collect(progress);
  }

  /**
   * A checked product specification and its current observation, or a source failure.
   * @param product verified catalog specification
   * @param store retailer name
   * @param url requested public source
   * @param observation current price, null on failure
   * @param error failure reason, empty on success
   */
  record Result(Product product, String store, String url,
                PriceObservation observation, String error) {
    /** Checks that a result is either successful or failed. */
    public Result {
      if (product == null || store == null || url == null || error == null
          || (observation == null) == error.isEmpty())
        throw new IllegalArgumentException("수집 결과 형식 오류");
    }
  }
}

