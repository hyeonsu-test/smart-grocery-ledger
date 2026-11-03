package sgl;

import static sgl.Data.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.util.*;
import java.util.regex.*;

/** Searches live official catalogs and previews a selected product before the user registers it. */
public final class ProductDiscovery {
  private final Clock clock;
  private final OnlineCatalog.PageReader reader;

  /** Creates a live official-storefront discovery client.
   * @param clock timestamp source for successful previews
   */
  public ProductDiscovery(Clock clock) { this(clock,new HttpPageClient()::get); }
  ProductDiscovery(Clock clock,OnlineCatalog.PageReader reader) {
    this.clock=Objects.requireNonNull(clock);this.reader=Objects.requireNonNull(reader);
  }

  /**
   * A search result is a link, not an imported price.
   * @param store retailer
   * @param name official listing title
   * @param url official detail page
   */
  public record Hit(String store,String name,String url) {}

  /**
   * Parsed package quantity, normalized to grams, milliliters or count.
   * @param total entire sale package quantity
   * @param category category defining the comparison unit
   * @param evidence quantity confirmation note
   */
  public record Quantity(BigDecimal total,String category,String evidence) {}

  /**
   * A fetched page awaiting explicit user registration.
   * @param url normalized official detail page
   * @param name verified official name
   * @param brand official brand or conservative fallback
   * @param quantity suggested quantity; null means user confirmation is required
   * @param html fetched detail response, never persisted in the ledger
   */
  public record Preview(String url,String name,String brand,Quantity quantity,String html) {}

  /**
   * Searches one live storefront. Results do not alter the user's collection list.
   * @param store Homeplus Express or Costco search selection
   * @param keyword product words, 1 to 80 characters
   * @return up to 40 distinct official result links from the search page
   * @throws IOException if the storefront cannot be reached
   */
  public List<Hit> search(String store,String keyword) throws IOException {
    keyword=keyword.strip();
    if(keyword.isEmpty() || keyword.length()>80)throw new IllegalArgumentException("검색어를 1~80자로 입력하세요.");
    String encoded=URLEncoder.encode(keyword,StandardCharsets.UTF_8).replace("+","%20");
    if(store.equals("홈플러스 익스프레스"))
      return parseSearch(store,reader.read(URI.create("https://mfront.homeplus.co.kr/express/search?keyword="+encoded)));
    if(store.equals("코스트코"))
      return parseSearch(store,reader.read(URI.create("https://www.costco.co.kr/search?text="+encoded)));
    throw new IllegalArgumentException("검색할 온라인몰을 선택하세요.");
  }

  static List<Hit> parseSearch(String store,String html) {
    LinkedHashMap<String,Hit> hits=new LinkedHashMap<>();
    if(store.equals("홈플러스 익스프레스")) {
      Matcher scripts=Pattern.compile("<script\\b([^>]*)>(.*?)</script\\s*>",Pattern.DOTALL|Pattern.CASE_INSENSITIVE).matcher(html);
      boolean found=false;
      while(scripts.find()) if(scripts.group(1).contains("/express/search/item.json")) {
        found=true;
        var data=obj(obj(Json.parse(scripts.group(2))).get("data"));
        for(var row:rows(data.get("dataList"))) {
          String sku=s(row,"itemNo");
          if(!sku.matches("[0-9]{1,20}") || !s(row,"storeType").equals("EXP")
              || s(row,"itemNm").isBlank())continue;
          String url="https://mfront.homeplus.co.kr/item?itemNo="+sku+"&storeType=EXP";
          hits.putIfAbsent(CollectionSource.keyOf(url),new Hit("홈플러스",s(row,"itemNm"),url));
          if(hits.size()==40)break;
        }
      }
      if(!found)throw new IllegalArgumentException("검색 응답을 읽을 수 없습니다. 상품 상세 주소로 추가하거나 다시 검색하세요.");
    } else {
      Matcher anchors=Pattern.compile("<a\\b([^>]*)>(.*?)</a\\s*>",Pattern.DOTALL|Pattern.CASE_INSENSITIVE).matcher(html);
      while(anchors.find()) {
        String attributes=anchors.group(1);
        if(!attribute(attributes,"class").contains("js-lister-name"))continue;
        String path=attribute(attributes,"href");
        String name=plain(anchors.group(2));
        if(name.isBlank())continue;
        try {
          String url=CollectionSource.canonicalUrl(URI.create("https://www.costco.co.kr").resolve(path).toString());
          if(!CollectionSource.storeOf(url).equals("코스트코"))continue;
          hits.putIfAbsent(CollectionSource.keyOf(url),new Hit("코스트코",name,url));
        } catch(IllegalArgumentException ignored) { }
        if(hits.size()==40)break;
      }
      if(hits.isEmpty() && !html.contains("검색 결과") && !html.contains("검색결과"))
        throw new IllegalArgumentException("검색 응답을 읽을 수 없습니다.");
    }
    return List.copyOf(hits.values());
  }

  /**
   * Fetches identity and package information; no settings or prices are saved.
   * @param url official detail page
   * @return preview requiring a separate add operation
   * @throws IOException on HTTP failure
   */
  public Preview inspect(String url) throws IOException {
    url=CollectionSource.canonicalUrl(url);
    if(CollectionSource.storeOf(url).equals("이마트"))
      throw new IllegalArgumentException("이마트는 현재 접근 제한으로 신규 등록을 지원하지 않습니다.");
    String html=reader.read(URI.create(url));
    var meta=OfficialPageParser.metadata(CollectionSource.skuOf(url),html);
    String name=text(meta,"name");
    Object rawBrand=meta.get("brand");
    String brand=rawBrand instanceof Map<?,?>?s(obj(rawBrand),"name")
        :rawBrand instanceof String?(String)rawBrand:"";
    if(brand.isBlank())brand=name.split("\\s+")[0];
    Map<String,Object> detail=CollectionSource.storeOf(url).equals("홈플러스")
        ?OfficialPageParser.homeplusDetail(html):m();
    Quantity quantity=quantity(name,detail);
    for(var original:OnlineCatalog.defaults()) if(original.product().name().equals(name)
        && CollectionSource.keyOf(original.uri().toString()).equals(CollectionSource.keyOf(url))) {
      var p=original.product();
      quantity=new Quantity(p.size().multiply(BigDecimal.valueOf(p.packCount())),p.category(),"기존 등록 규격");
    }
    return new Preview(url,name,brand,quantity,html);
  }

  /**
   * Checks a preview and the user-confirmed package quantity.
   * @param preview fetched preview
   * @param category comparison category
   * @param total entire package quantity, not the package price
   * @return verified source and actual current observation
   */
  public ProductCollector.Result confirm(Preview preview,String category,BigDecimal total) {
    num(total,false);
    if(preview.quantity()!=null && (!unit(category).equals(unit(preview.quantity().category()))
        || total.compareTo(preview.quantity().total())!=0))
      throw new IllegalArgumentException("공식 페이지에서 확인된 총 용량·개수를 변경할 수 없습니다.");
    String url=CollectionSource.canonicalUrl(preview.url());
    String sku=CollectionSource.skuOf(url),store=CollectionSource.storeOf(url);
    String id="tracked-"+(store.equals("홈플러스")?"hp-":"co-")+sku
        +(store.equals("홈플러스")?(url.contains("storeType=EXP")?"-exp":"-hyper"):"");
    id += "-"+UUID.nameUUIDFromBytes((preview.name()+"\n"+preview.brand()+"\n"+category+"\n"
        +total.stripTrailingZeros().toPlainString()).getBytes(StandardCharsets.UTF_8)).toString().substring(0,8);
    Product p=new Product(id,category,preview.name(),preview.brand(),preview.name(),total,unit(category),1);
    for(var original:OnlineCatalog.defaults()) if(original.product().name().equals(preview.name())
        && CollectionSource.keyOf(original.uri().toString()).equals(CollectionSource.keyOf(url))) {
      p=original.product();break;
    }
    var source=new OnlineCatalog.Source(p,store,sku,URI.create(url));
    PriceObservation price=OfficialPageParser.parse(source,preview.html(),clock);
    return new ProductCollector.Result(p,store,url,price,"");
  }

  static Quantity quantity(String original,Map<String,Object> detail) {
    String name=Normalizer.normalize(original,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    name=name.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))","");
    if(name.matches(".*\\d,\\d.*"))return null;
    name=name.replaceAll("\\s*/\\s*최소\\s*구매\\s*[0-9]+\\s*$","");
    if(name.matches(".*(커피컵|종이컵|머그|그라인더|메이커|머신|텀블러|물병|보온병|커피필터).*")) {
      Matcher count=Pattern.compile("([0-9]+)\\s*(?:개입|개|매)(?![가-힣])").matcher(name);
      if(count.find()) {
        BigDecimal total=packageTotal(name,count.start(),count.end(),new BigDecimal(count.group(1)),true);
        if(total!=null)return new Quantity(total,"개수 상품","용기의 용량 대신 판매 개수 × 묶음 수로 비교");
      }
      return null;
    }
    boolean eggs=name.matches(".*(계란|달걀|대란|왕란|특란)(?:\\s|[0-9]|$).*");
    boolean ramen=name.matches(".*(라면|신라면|짜파게티|너구리|불닭볶음면)(?:\\s|[0-9]|$).*");
    if(eggs || ramen) {
      Matcher count=Pattern.compile("([0-9]+)\\s*(?:개입|개|구|입|봉)(?![가-힣])").matcher(name);
      if(count.find()) {
        BigDecimal total=packageTotal(name,count.start(),count.end(),new BigDecimal(count.group(1)),false);
        return total==null?null:new Quantity(total,eggs?"계란":"라면","상품명에 표시된 개수 × 묶음 수");
      }
    }
    if(!detail.isEmpty()) {
      var etc=detail.get("etc")==null?m():obj(detail.get("etc"));
      String measure=s(etc,"unitMeasure").toUpperCase(Locale.ROOT);
      if(etc.get("totalUnitQty")!=null && Set.of("G","ML","EA","개").contains(measure)) {
        BigDecimal total=num(etc.get("totalUnitQty"),true);
        if(total.signum()>0)return new Quantity(total,measure.equals("G")?"기타 식품"
            :measure.equals("ML")?(name.contains("우유")?"우유":"음료"):"개수 상품","공식 페이지의 판매 묶음 총 용량");
      }
    }
    // Reject mixed packs, gifts and alternative sizes instead of silently selecting one number.
    if(name.contains("+") || name.contains("/") || name.contains("선택") || name.contains("증정"))return null;
    Matcher q=Pattern.compile("(?<![0-9.,])([0-9]+(?:\\.[0-9]+)?)\\s*(kg|ml|g|l)(?![a-z])").matcher(name);
    if(q.find()) {
      BigDecimal total=new BigDecimal(q.group(1));
      String measure=q.group(2);
      int begin=q.start(),end=q.end();
      if(q.find())return null;
      if(measure.equals("kg") || measure.equals("l"))total=total.multiply(BigDecimal.valueOf(1000));
      total=packageTotal(name,begin,end,total,false);
      if(total==null)return null;
      String category=measure.equals("ml") || measure.equals("l")?(name.contains("우유")?"우유":"음료"):"기타 식품";
      return new Quantity(total,category,"상품명에 표시된 용량 × 묶음 수");
    }
    Matcher count=Pattern.compile("([0-9]+)\\s*(?:개입|개|매|입)(?![가-힣])").matcher(name);
    if(count.find()) {
      BigDecimal total=packageTotal(name,count.start(),count.end(),new BigDecimal(count.group(1)),false);
      if(total!=null)return new Quantity(total,"개수 상품","상품명에 표시된 개수 × 묶음 수");
    }
    return null;
  }

  /** Reads explicit multipliers on either side; ambiguous packs require user confirmation. */
  private static BigDecimal packageTotal(String name,int begin,int end,BigDecimal amount,boolean container) {
    if(name.contains("+") || name.contains("/") || name.contains("선택") || name.contains("증정"))return null;
    String before=name.substring(0,begin),after=name.substring(end).strip();
    if(container)before=before.replaceAll("[0-9]+(?:\\.[0-9]+)?\\s*(?:ml|l)\\s*[*x×]?\\s*","");
    Matcher leading=Pattern.compile("(?<![0-9.,])((?:[0-9]+\\s*[*x×]\\s*)+)$").matcher(before);
    if(leading.find()) {
      Matcher factors=Pattern.compile("[0-9]+").matcher(leading.group(1));
      while(factors.find())amount=amount.multiply(new BigDecimal(factors.group()));
      before=before.substring(0,leading.start());
    }
    if(before.matches(".*(?:[0-9]\\s*[x×*]|[×*]).*") || before.strip().endsWith("x"))return null;
    Pattern multiplier=Pattern.compile("^\\s*[*x×]\\s*([0-9]+)\\s*(?:개입|개|구|입|봉|팩|병|캔|스틱|박스|포|매|ct|p)?");
    Matcher trailing=multiplier.matcher(after);
    while(trailing.find()) {
      amount=amount.multiply(new BigDecimal(trailing.group(1)));
      after=after.substring(trailing.end()).strip();
      trailing=multiplier.matcher(after);
    }
    if(after.matches(".*[0-9*x×].*") || amount.signum()<=0)return null;
    return amount;
  }

  private static String attribute(String attrs,String name) {
    Matcher m=Pattern.compile("\\b"+name+"\\s*=\\s*(['\"])(.*?)\\1",Pattern.DOTALL|Pattern.CASE_INSENSITIVE).matcher(attrs);
    return m.find()?plain(m.group(2)):"";
  }
  private static String plain(String s) {
    return s.replaceAll("<[^>]*>","").replace("&amp;","&").replace("&quot;","\"")
        .replace("&#39;","'").replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">").strip();
  }
}

