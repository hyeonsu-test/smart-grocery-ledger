package sgl;

import static sgl.Data.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;

/** Collects the built-in five-category catalog directly from official retailer pages. */
public final class OnlineCatalog implements ProductCollector {
  @FunctionalInterface
  interface PageReader { String read(URI uri) throws IOException; }
  record Source(Product product, String store, String sku, URI uri) {}
  private final Clock clock;
  private final PageReader reader;
  private final List<Source> sources;

  /** 
   * Creates the default official-page collector.
   * @param clock clock used only after each successful HTTP response
   */
  public OnlineCatalog(Clock clock) {
    this(clock, new HttpPageClient()::get, defaults());
  }

  OnlineCatalog(Clock clock, PageReader reader, List<Source> sources) {
    this.clock = Objects.requireNonNull(clock);
    this.reader = Objects.requireNonNull(reader);
    this.sources = List.copyOf(sources);
  }

  @Override
  public List<Result> collect(Consumer<String> progress) {
    List<Result> result = new ArrayList<>();
    for (int i = 0; i < sources.size(); i++) {
      Source source = sources.get(i);
      progress.accept((i + 1) + "/" + sources.size() + " · " + source.store()
          + " · " + source.product().name());
      try {
        String html = reader.read(source.uri());
        PriceObservation observation = OfficialPageParser.parse(source, html, clock);
        result.add(new Result(source.product(), source.store(), source.uri().toString(), observation, ""));
      } catch (IOException | IllegalArgumentException | java.time.DateTimeException e) {
        result.add(new Result(source.product(), source.store(), source.uri().toString(), null,
            e.getMessage() == null ? "수집 실패" : e.getMessage()));
      }
    }
    return List.copyOf(result);
  }

  static List<Source> defaults() {
    return List.of(
      hp("milk", "우유", "서울우유 1L", "서울우유", "흰우유 1L", "1000", 1, "000018872", "EXP"),
      hp("eggs", "계란", "행복대란 30개입", "해밀영농조합법인", "대란", "1", 30, "127502198", "HYPER"),
      hp("ramen", "라면", "농심 신라면 120G*5입", "농심", "신라면 120g", "1", 5, "120074651", "EXP"),
      hp("tofu", "두부", "simplus 고소하고 담백한 두부 300G", "simplus", "300g", "300", 1, "144624108", "HYPER"),
      co("milk", "우유", "연세우유 멸균우유 200ml x 24 x 2", "연세우유", "멸균 흰우유 200ml", "200", 48,
          "672900", "Foods/Beverages/Soy-MilkMilk/Yonsei-Sterilized-Milk-200ml-x-24-x-2"),
      co("ramen", "라면", "농심 신라면 120g x 30개", "농심", "신라면 120g", "1", 30,
          "524497", "Foods/Processed-Food/PastaNoodles/Nongshim-Shin-Ramyun-120g-x-30"),
      co("rice", "쌀", "쌀명당 고시히카리 완전미 5kg", "쌀명당", "고시히카리 완전미", "5000", 1,
          "695031", "Foods/RiceGrains/Rice/Ssal-Myeongdang-Goshihikari-Head-Rice-5kg"),
      new Source(new Product("web-em-milk", "우유", "[서울우유] 1000ml 나100%", "서울우유",
          "흰우유 1L", new BigDecimal("1000"), "ml", 1), "이마트", "0000006615474",
          URI.create("https://emart.ssg.com/item/itemView.ssg?itemId=0000006615474")));
  }

  private static Source hp(String id, String category, String name, String brand, String spec,
                            String size, int pack, String sku, String storeType) {
    return new Source(new Product("web-hp-" + id, category, name, brand, spec,
        new BigDecimal(size), unit(category), pack), "홈플러스", sku,
        URI.create("https://mfront.homeplus.co.kr/item?itemNo=" + sku + "&storeType=" + storeType));
  }

  private static Source co(String id, String category, String name, String brand, String spec,
                            String size, int pack, String sku, String path) {
    return new Source(new Product("web-co-" + id, category, name, brand, spec,
        new BigDecimal(size), unit(category), pack), "코스트코", sku,
        URI.create("https://www.costco.co.kr/" + path + "/p/" + sku));
  }
}

