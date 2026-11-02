package sgl;

import static sgl.Data.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Adapter for explicitly configured local JSON snapshots, not a retailer web crawler. */
public class MartAdapter {
  /** Constructs the local-file adapter. */
  public MartAdapter() {}

  /**
   * Reads a bounded UTF-8 feed containing products and offers arrays.
   *
   * @param store supported retailer name
   * @param path locally configured feed path
   * @return parsed feed; observation timestamps are preserved
   * @throws IOException if the file cannot be read or exceeds 5 MB
   * @throws IllegalArgumentException if the feed structure is invalid
   */
  public Map<String, Object> fetchOffers(String store, Path path) throws IOException {
    if (!STORES.contains(store)) throw new IllegalArgumentException("지원 마트를 선택하세요.");
    if (Files.size(path) > 5_000_000) throw new IOException("피드 제한은 5MB입니다.");
    var feed = obj(Json.parse(Files.readString(path, StandardCharsets.UTF_8)));
    rows(feed.get("products"));
    rows(feed.get("offers"));
    return feed;
  }
}
