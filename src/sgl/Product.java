package sgl;

import static sgl.Data.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * An immutable product specification. Size describes one unit in the package.
 *
 * @param id stable product identifier
 * @param category food category
 * @param name display name
 * @param brand brand name
 * @param spec product variant
 * @param size quantity in one unit
 * @param unit measurement unit
 * @param packCount units per package
 */
public record Product(
    String id,
    String category,
    String name,
    String brand,
    String spec,
    BigDecimal size,
    String unit,
    long packCount) {
  /** Checks the specification and canonicalizes decimal sizes for equality. */
  public Product {
    for (String value : new String[] {id, category, name, brand, spec, unit}) {
      if (value == null || value.isBlank() || value.length() > 2000)
        throw new IllegalArgumentException("상품 식별 정보와 규격을 확인하세요.");
    }
    if (!CATEGORIES.contains(category) || !Data.unit(category).equals(unit))
      throw new IllegalArgumentException("품목과 단위가 일치하지 않습니다.");
    size = num(size, false).stripTrailingZeros();
    integer(packCount, false);
  }

  static Product fromMap(Map<String, Object> p) {
    return new Product(
        text(p, "id"),
        text(p, "category"),
        text(p, "name"),
        text(p, "brand"),
        text(p, "spec"),
        num(p.get("size"), false),
        text(p, "unit"),
        integer(p.get("pack_count"), false));
  }

  Map<String, Object> toMap() {
    return m(
        "id",
        id,
        "category",
        category,
        "name",
        name,
        "brand",
        brand,
        "spec",
        spec,
        "size",
        size.toPlainString(),
        "unit",
        unit,
        "pack_count",
        packCount);
  }

  /**
   * Tests whether the specifications can be compared after converting bundle quantities.
   *
   * @param other second product
   * @return true for the same category, brand, variant, size and unit
   */
  public boolean comparableTo(Product other) {
    return category.equals(other.category)
        && brand.equals(other.brand)
        && spec.equals(other.spec)
        && size.compareTo(other.size) == 0
        && unit.equals(other.unit);
  }

  /**
   * Converts a package price to the category's comparison unit.
   *
   * @param packagePrice positive observed package price
   * @return price per 100 ml, 100 g, egg or ramen unit
   */
  public BigDecimal unitPrice(BigDecimal packagePrice) {
    return num(packagePrice, false)
        .multiply(unitBase(category))
        .divide(size.multiply(BigDecimal.valueOf(packCount)), MC);
  }
}
