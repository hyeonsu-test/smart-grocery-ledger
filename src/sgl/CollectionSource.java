package sgl;

import static sgl.Data.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A persisted product selected for recurring official-page collection.
 * @param product confirmed package specification
 * @param store official retailer
 * @param sku retailer product identifier
 * @param url public product page
 * @param enabled whether future collection should request this product
 */
public record CollectionSource(Product product, String store, String sku, String url, boolean enabled) {
  /** Validates the retailer identity and removes tracking parameters. */
  public CollectionSource {
    Objects.requireNonNull(product);
    url = canonicalUrl(url);
    if (!store.equals(storeOf(url)) || !sku.equals(skuOf(url)))
      throw new IllegalArgumentException("상품 출처와 ID가 일치하지 않습니다.");
  }

  /** Returns the retailer identity.
   * @return canonical key, including Homeplus sales channel
   */
  public String key() { return keyOf(url); }

  /** Changes the recurring collection setting.
   * @param active new collection setting
   * @return independent setting
   */
  public CollectionSource withEnabled(boolean active) {
    return new CollectionSource(product,store,sku,url,active);
  }

  OnlineCatalog.Source source() { return new OnlineCatalog.Source(product,store,sku,URI.create(url)); }
  Map<String,Object> toMap() {
    return m("product",product.toMap(),"store",store,"sku",sku,"url",url,"enabled",enabled);
  }
  static CollectionSource fromMap(Map<String,Object> row) {
    return new CollectionSource(Product.fromMap(obj(row.get("product"))),text(row,"store"),
        text(row,"sku"),text(row,"url"),bool(row.get("enabled")));
  }

  /**
   * Accepts only supported HTTPS product pages, never search pages or arbitrary hosts.
   * @param value user-pasted URL
   * @return normalized product URL
   */
  public static String canonicalUrl(String value) {
    try {
      URI u=URI.create(value.trim());
      String host=u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT);
      if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo()!=null
          || u.getPort()!=-1 && u.getPort()!=443 || value.length()>2000) throw new IllegalArgumentException();
      if (Set.of("mfront.homeplus.co.kr","www.homeplus.co.kr","wwwfront.homeplus.co.kr").contains(host)
          && "/item".equals(u.getPath())) {
        Map<String,String> query=query(u);
        String sku=query.getOrDefault("itemNo","");
        String type=query.getOrDefault("storeType","HYPER").toUpperCase(Locale.ROOT);
        if (!sku.matches("[0-9]{1,20}") || !Set.of("EXP","HYPER").contains(type))
          throw new IllegalArgumentException();
        return "https://mfront.homeplus.co.kr/item?itemNo="+sku+"&storeType="+type;
      }
      if (host.equals("www.costco.co.kr") && u.getPath().matches("/(?:[^/]+/)*p/[0-9]{1,20}"))
        return "https://www.costco.co.kr"+u.getRawPath();
      if (host.equals("emart.ssg.com") && "/item/itemView.ssg".equals(u.getPath())) {
        String sku=query(u).getOrDefault("itemId","");
        if (!sku.matches("[0-9]{1,20}"))throw new IllegalArgumentException();
        return "https://emart.ssg.com/item/itemView.ssg?itemId="+sku;
      }
    } catch (RuntimeException ignored) { /* Consistent actionable input message below. */ }
    throw new IllegalArgumentException("홈플러스·코스트코 공식 상품 상세 주소를 입력하세요. 검색 주소는 등록할 수 없습니다.");
  }

  static String keyOf(String url) {
    String canonical=canonicalUrl(url);
    URI u=URI.create(canonical);
    return storeOf(canonical)+":"+skuOf(canonical)
        +(u.getHost().equals("mfront.homeplus.co.kr")?":"+query(u).get("storeType"):"");
  }
  static String storeOf(String url) {
    return switch(URI.create(url).getHost()) {
      case "mfront.homeplus.co.kr" -> "홈플러스";
      case "www.costco.co.kr" -> "코스트코";
      default -> "이마트";
    };
  }
  static String skuOf(String url) {
    URI u=URI.create(url);
    if (u.getHost().equals("www.costco.co.kr"))return u.getPath().substring(u.getPath().lastIndexOf('/')+1);
    return query(u).get(u.getHost().equals("mfront.homeplus.co.kr")?"itemNo":"itemId");
  }
  private static Map<String,String> query(URI u) {
    Map<String,String> result=new HashMap<>();
    for(String part:Objects.toString(u.getRawQuery(),"").split("&")) {
      String[] pair=part.split("=",2);
      if(pair.length==2 && result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),
          URLDecoder.decode(pair[1],StandardCharsets.UTF_8))!=null)
        throw new IllegalArgumentException("중복 URL 매개변수");
    }
    return result;
  }
}

