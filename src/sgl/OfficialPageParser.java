package sgl;

import static sgl.Data.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Extracts only identity-matched structured product prices, never arbitrary numbers in HTML. */
final class OfficialPageParser {
  private static final Pattern SCRIPT =
      Pattern.compile("<script\\b([^>]*)>(.*?)</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern TYPE = Pattern.compile(
      "\\btype\\s*=\\s*['\"]application/ld\\+json['\"]", Pattern.CASE_INSENSITIVE);

  static PriceObservation parse(OnlineCatalog.Source source, String html, Clock clock) {
    List<Map<String, Object>> products = new ArrayList<>();
    Map<String, Object> detail = null;
    Matcher scripts = SCRIPT.matcher(html);
    while (scripts.find()) {
      String attributes = scripts.group(1);
      if (TYPE.matcher(attributes).find()) {
        try { findProducts(Json.parse(scripts.group(2)), products); }
        catch (IllegalArgumentException ignored) { /* Another script may hold the matching product. */ }
      }
      if (attributes.contains("/item/getItemDetail.json")) {
        detail = obj(obj(obj(Json.parse(scripts.group(2))).get("data")).get("item"));
      }
    }
    Map<String, Object> match = null;
    for (var product : products) {
      String sku = def(product, "sku", s(product, "mpn"));
      if (sku.equals(source.sku()) && normalized(s(product, "name")).equals(normalized(source.product().name()))) {
        if (match != null) throw new IllegalArgumentException("중복 상품 응답");
        match = product;
      }
    }
    if (match == null) throw new IllegalArgumentException("상품 ID·이름·규격을 확인할 수 없습니다.");
    List<Map<String, Object>> offers = new ArrayList<>();
    Object rawOffers = match.get("offers");
    if (rawOffers instanceof Map<?, ?>) offers.add(obj(rawOffers));
    else offers.addAll(rows(rawOffers));
    if (offers.size() != 1) throw new IllegalArgumentException("단일 판매 가격을 확인할 수 없습니다.");
    var offer = offers.get(0);
    if (!s(offer, "priceCurrency").equals("KRW")) throw new IllegalArgumentException("원화 가격이 아닙니다.");
    BigDecimal price = num(offer.get("price"), false);
    String availability = s(offer, "availability");
    if (!availability.isEmpty() && !availability.endsWith("/InStock"))
      throw new IllegalArgumentException("현재 품절 또는 판매 중지");
    String condition = source.store().equals("코스트코") ? "코스트코 회원" : "일반";
    String branch = source.store().equals("코스트코") ? "온라인몰 · 배송비 포함" : "온라인몰 · 지점 미지정";
    LocalDate start = null, end = null;
    if (source.store().equals("홈플러스")) {
      if (detail == null) throw new IllegalArgumentException("상품 판매 상태 응답이 없습니다.");
      var basic = obj(detail.get("basic"));
      var sale = obj(detail.get("sale"));
      String expectedType = source.uri().getQuery().contains("storeType=EXP") ? "EXP" : "HYPER";
      if (!source.sku().equals(s(basic, "itemNo")) || !expectedType.equals(s(basic, "storeType"))
          || !normalized(s(basic, "itemNm")).equals(normalized(source.product().name())))
        throw new IllegalArgumentException("상품 또는 판매 채널 응답 불일치");
      if (!s(basic, "itemStatus").equals("A") || s(sale, "itemSoldOutYn").equals("Y")
          || s(sale, "itemCloseYn").equals("Y") || s(sale, "stopDealYn").equals("Y"))
        throw new IllegalArgumentException("현재 품절 또는 판매 중지");
      var discount = sale.get("frontDcPriceInfo") == null ? m() : obj(sale.get("frontDcPriceInfo"));
      BigDecimal actual = num(sale.get("salePrice"), false);
      if (!discount.isEmpty()) {
        if (!s(discount, "priceType").equals("BASIC"))
          throw new IllegalArgumentException("추가 구매 조건이 있는 가격");
        actual = num(discount.get("dcPrice"), false);
      }
      if (price.compareTo(actual) != 0) throw new IllegalArgumentException("표시 가격과 판매 가격 불일치");
      branch = (expectedType.equals("EXP") ? "익스프레스" : "대형마트")
          + " · 공개 기준점포 " + integer(basic.get("storeId"), false);
      for (var event : rows(sale.get("eventList"))) {
        if (s(event, "eventKind").equals("BASIC")
            && num(event.get("changeAmount"), true).compareTo(price) == 0
            && integer(event.get("buyItemCount"), false) == 1) {
          start = LocalDate.parse(day(event.get("eventStartDt")));
          end = LocalDate.parse(day(event.get("eventEndDt")));
          break;
        }
      }
    } else if (source.store().equals("코스트코")) {
      if (availability.isEmpty()) throw new IllegalArgumentException("재고 상태를 확인할 수 없습니다.");
      if (!s(offer, "priceValidUntil").isEmpty()
          && LocalDate.parse(day(offer.get("priceValidUntil"))).isBefore(LocalDate.now(clock)))
        throw new IllegalArgumentException("판매 가격 유효 기간이 지났습니다.");
      // priceValidUntil is the offer validity date, not evidence of a promotion.
    } else {
      throw new IllegalArgumentException("이 출처의 가격·구매 조건 해석은 아직 지원하지 않습니다.");
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    PriceObservation observation = new PriceObservation(id(), source.product().id(), source.store(), branch,
        "온라인", condition, price, source.uri().toString(), now.toLocalDate(), now,
        start, end, "웹 자동 수집", false);
    if (!observation.validity(now).equals("현재 유효"))
      throw new IllegalArgumentException("현재 적용되지 않는 행사 가격");
    return observation;
  }

  private static void findProducts(Object value, List<Map<String, Object>> result) {
    if (value instanceof List<?>) {
      for (Object item : (List<?>) value) findProducts(item, result);
    } else if (value instanceof Map<?, ?>) {
      var map = obj(value);
      Object type = map.get("@type");
      if (type instanceof String && ((String) type).equalsIgnoreCase("Product")
          || type instanceof List<?> && ((List<?>) type).stream().anyMatch(x -> "Product".equalsIgnoreCase(String.valueOf(x))))
        result.add(map);
      if (map.containsKey("@graph")) findProducts(map.get("@graph"), result);
    }
  }

  private static String normalized(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "")
        .toLowerCase(Locale.ROOT);
  }
}

