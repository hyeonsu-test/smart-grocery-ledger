/**
 * Smart Grocery Ledger desktop beta, Java 17+ / Swing, without third-party runtime libraries.
 *
 * <h2>Use cases and service API</h2>
 *
 * <ul>
 *   <li>FN01 / UC01: {@link sgl.LedgerService#manageLedger(java.util.Map)}
 *   <li>FN02 / UC02: {@link sgl.BudgetService#manageBudgetPlan(java.util.Map)}
 *   <li>FN03 / UC03: {@link sgl.ProductService#manageProducts(java.util.Map)}
 *   <li>FN04 / UC04: {@link sgl.PriceService#analyzePrices(java.util.Map)}
 *   <li>FN05 / UC05: {@link sgl.TargetService#managePurchaseTargets(java.util.Map)}
 * </ul>
 *
 * <h2>Common request and result types</h2>
 *
 * <p>Methods accept {@code Map<String,Object>} requests and return JSON-compatible result maps.
 * Every result contains a status. Dates use YYYY-MM-DD, months YYYY-MM, observation timestamps
 * include an explicit UTC offset. BigDecimal is used for price calculations; display rounding never
 * changes stored observations. Errors are returned as INVALID_INPUT, READ_FAILED or SAVE_FAILED.
 * Drafts survive failed writes. Expected cancellations return CANCELLED.
 *
 * <h2>Product and offer schema</h2>
 *
 * <p>A product contains id, category, name, brand, spec, size, unit and pack_count. Categories are
 * 우유, 계란, 라면, 쌀, 두부. Units are respectively ml, 개, 봉, g, g. An offer contains product_id, store,
 * branch, channel, price, condition, url, checked_date, checked_at, start and end. Optional
 * checked_at may be empty, which excludes the price from current comparisons. Store names are 이마트,
 * 홈플러스, 코스트코. The channel is 온라인 or 오프라인. Prices are product/package totals before unit conversion;
 * delivery charges are excluded. Manual entry and local JSON feeds are implemented; live retailer
 * web crawling is not.
 *
 * <h2>Persistence and alerts</h2>
 *
 * <p>Repository validates complete state, writes and flushes a same-directory temporary file, then
 * atomically replaces the destination. Unsupported atomic moves fail closed. All services share one
 * repository lock. A process-level file lock prevents two running apps from editing the same
 * ledger. Alerts are generated only while the app runs, once per product per local day, and only
 * after the deduplication guard is saved successfully.
 */
package sgl;
