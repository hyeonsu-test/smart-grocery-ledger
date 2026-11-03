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
 * <p>Ledger entries use store for the optional counterparty. Income uses a payer, dining uses
 * a restaurant and other expenses use a merchant. Only an entry with product_id has a purchase
 * quantity and must be classified as 지출/식료품. Monthly grocery budgets use 식료품 expenses.
 *
 * <h2>Product and offer schema</h2>
 *
 * <p>Application state holds immutable {@link sgl.Product} and {@link sgl.PriceObservation}
 * records. The five tabs own their controls and editing state. Version 1 JSON files, local feeds
 * and map-based service requests use the following wire fields. A product contains id, category,
 * name, brand, spec, size, unit and pack_count. Categories include 우유/ml, 계란/개, 라면/봉, 쌀/g, 두부/g, 음료/ml,
 * 기타 식품/g and 개수 상품/개. An offer contains product_id, store, branch, channel, price,
 * condition, url, checked_date, checked_at, start and end. Optional checked_at may be empty, which
 * excludes the price from current comparisons. Store names are 이마트, 홈플러스, 코스트코. The channel is 온라인
 * or 오프라인. Prices are published package totals before unit conversion. Costco prices require
 * membership and include delivery; these conditions are retained, not silently normalized away.
 * {@link sgl.OnlineCollectionService} fetches enabled {@link sgl.CollectionSource} settings via {@link sgl.OnlineCatalog}
 * on startup once per day, or on the collection button. Homeplus and Costco public product pages
 * are supported; inaccessible sources are reported independently.
 * {@link sgl.ProductDiscovery} searches live catalogs and previews the selected detail page.
 * {@link sgl.CollectionListService} persists only explicitly confirmed registrations.
 * Legacy ledgers start with eight editable sources; an explicitly empty list stays empty.
 * Pausing or removing a source retains historical observations and ledger entries.
 * The list supports at most 100 sources and each search returns at most 40 results.
 * Unknown package quantities require confirmation before registration; prices are never invented. No user-supplied feed file is
 * needed. {@link sgl.MartAdapter} remains a legacy local-snapshot API, without a customer UI.
 *
 * <p>Product queries include catalog-only products with null price and unit_price when source
 * filters are empty. promotions_only selects current eligible promotions. Manual and feed
 * observations keep separate provenance even at the same time; a failed feed preserves
 * independently confirmed manual observations.
 *
 * <h2>Persistence and alerts</h2>
 *
 * <p>Repository validates complete state, writes and flushes a same-directory temporary file, then
 * atomically replaces the destination. Unsupported atomic moves fail closed. All services share one
 * repository lock for commits. Network collection runs outside the lock and merges into a fresh
 * snapshot, preserving edits made while downloading. A process-level file lock prevents two running apps from editing the same
 * ledger. Alerts are generated only while the app runs, once per product per local day, and only
 * after the deduplication guard is saved successfully.
 */
package sgl;
