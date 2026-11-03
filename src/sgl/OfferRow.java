package sgl;

import static sgl.Data.*;
import static sgl.SwingViews.*;

import java.math.BigDecimal;
import java.util.Map;

/** A typed price row shared by the product and comparison screens. */
record OfferRow(
    Product product,
    PriceObservation offer,
    BigDecimal unitPrice,
    String validity,
    boolean favorite) {
  static OfferRow read(Map<String, Object> result, Product p) {
    return new OfferRow(
        p,
        result.get("price") == null ? null : PriceObservation.fromMap(result),
        (BigDecimal) result.get("unit_price"),
        s(result, "validity"),
        yes(result, "favorite"));
  }

  String details() {
    String specification = product.name()
        + " · "
        + product.brand()
        + " / "
        + product.spec()
        + " / "
        + product.size().toPlainString()
        + product.unit()
        + " × "
        + product.packCount()
        + "묶음\n";
    if (offer == null) return specification + "가격 미확인 · 가격과 출처를 직접 입력하거나 피드를 갱신하세요.";
    return specification + offer.store()
        + " / "
        + offer.branch()
        + " · "
        + offer.channel()
        + " / "
        + offer.condition()
        + " · "
        + money(offer.price())
        + "원 ("
        + money(unitPrice)
        + "원 / "
        + unitLabel(product)
        + ")\n행사 "
        + (offer.start() == null ? "시작일 없음" : offer.start())
        + " ~ "
        + (offer.end() == null ? "종료일 없음" : offer.end())
        + " · 확인 "
        + (offer.checkedAt() == null ? offer.checkedDate() + " (시각 없음)" : offer.checkedAt())
        + " · "
        + offer.method()
        + "\n출처 "
        + offer.url();
  }
}
