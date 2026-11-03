package sgl;

import static sgl.Data.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;

/**
 * A package price observed at a particular store, branch and time.
 *
 * @param id observation identifier
 * @param productId product specification identifier
 * @param store retailer
 * @param branch store branch
 * @param channel sales channel
 * @param condition discount condition
 * @param price observed package price
 * @param url source address
 * @param checkedDate local observation date
 * @param checkedAt observation time with UTC offset, if known
 * @param start first promotional day, if known
 * @param end last promotional day, if known
 * @param method observation source
 * @param failed whether the feed failed to refresh
 */
public record PriceObservation(
    String id,
    String productId,
    String store,
    String branch,
    String channel,
    String condition,
    BigDecimal price,
    String url,
    LocalDate checkedDate,
    OffsetDateTime checkedAt,
    LocalDate start,
    LocalDate end,
    String method,
    boolean failed) {
  /** Checks the price, source, time and promotion bounds. */
  public PriceObservation {
    if (productId == null || productId.isBlank() || store == null || !STORES.contains(store))
      throw new IllegalArgumentException("상품과 마트 정보를 확인하세요.");
    if (!("온라인".equals(channel) || "오프라인".equals(channel))
        || condition == null
        || condition.isBlank()) throw new IllegalArgumentException("판매 경로와 가격 조건을 확인하세요.");
    if (id == null || method == null || branch == null)
      throw new IllegalArgumentException("가격 식별 정보가 필요합니다.");
    num(price, false);
    Data.url(url);
    if (checkedDate == null || checkedAt != null && !checkedAt.toLocalDate().equals(checkedDate))
      throw new IllegalArgumentException("확인 날짜와 시각이 일치해야 합니다.");
    if (start != null && end != null && start.isAfter(end))
      throw new IllegalArgumentException("행사 기간을 확인하세요.");
  }

  static PriceObservation fromMap(Map<String, Object> o) {
    String at = s(o, "checked_at");
    return new PriceObservation(
        s(o, "id"),
        text(o, "product_id"),
        s(o, "store"),
        def(o, "branch", "전체"),
        def(o, "channel", "온라인"),
        def(o, "condition", "일반"),
        num(o.get("price"), false),
        s(o, "url"),
        LocalDate.parse(day(o.get("checked_date"))),
        at.isEmpty() ? null : OffsetDateTime.parse(at),
        optionalDate(s(o, "start")),
        optionalDate(s(o, "end")),
        s(o, "method"),
        yes(o, "failed"));
  }

  private static LocalDate optionalDate(String s) {
    return s.isEmpty() ? null : LocalDate.parse(day(s));
  }

  Map<String, Object> toMap() {
    return m(
        "id",
        id,
        "product_id",
        productId,
        "store",
        store,
        "branch",
        branch,
        "channel",
        channel,
        "condition",
        condition,
        "price",
        price.toPlainString(),
        "url",
        url,
        "checked_date",
        checkedDate.toString(),
        "checked_at",
        checkedAt == null ? "" : checkedAt.toString(),
        "start",
        start == null ? "" : start.toString(),
        "end",
        end == null ? "" : end.toString(),
        "method",
        method,
        "failed",
        failed);
  }

  record Key(String productId, String store, String branch, String channel, String condition) {}

  Key key() {
    return new Key(productId, store, branch, channel, condition);
  }

  int compareTime(PriceObservation other) {
    if (checkedAt != null && other.checkedAt != null)
      return checkedAt.toInstant().compareTo(other.checkedAt.toInstant());
    int order = checkedDate.compareTo(other.checkedDate);
    return order == 0 ? Boolean.compare(checkedAt != null, other.checkedAt != null) : order;
  }

  boolean sameReading(PriceObservation other) {
    return key().equals(other.key())
        && method.equals(other.method())
        && checkedDate.equals(other.checkedDate())
        && java.util.Objects.equals(checkedAt, other.checkedAt);
  }

  boolean isPromotion() {
    return start != null || end != null
        || !condition.matches("(?:일반|코스트코 회원)(?: · 최소 [0-9]+개 구매)?");
  }

  /**
   * Returns the minimum number of sale packages required by the observed price condition.
   * @return minimum package count, or one when no minimum is recorded
   */
  public long minimumQuantity() {
    var match = java.util.regex.Pattern.compile("(?:^| · )최소 ([0-9]+)개 구매(?:$| · )")
        .matcher(condition);
    return match.find() ? integer(match.group(1), false) : 1;
  }

  boolean inPeriod(LocalDate date) {
    return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end));
  }

  /**
   * Reports freshness, feed failure and promotional eligibility at a given time.
   *
   * @param now evaluation time with offset
   * @return Korean status shown on the price screens
   */
  public String validity(OffsetDateTime now) {
    if (failed) return "갱신 실패";
    if (checkedAt == null) return "확인 시각 없음";
    Duration age = Duration.between(checkedAt, now);
    if (age.isNegative()) return "미래 확인 시각";
    if (age.compareTo(Duration.ofHours(24)) >= 0) return "오래됨";
    if (start != null && now.toLocalDate().isBefore(start)) return "행사 시작 전";
    return inPeriod(now.toLocalDate()) ? "현재 유효" : "만료";
  }

  PriceObservation withFailedFeed() {
    return new PriceObservation(
        id,
        productId,
        store,
        branch,
        channel,
        condition,
        price,
        url,
        checkedDate,
        checkedAt,
        start,
        end,
        method,
        true);
  }
}
