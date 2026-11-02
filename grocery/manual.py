"""Human-readable API manual shared by the application and HTML documentation."""
API_TEXT='''Smart Grocery Ledger — Python service API
FN01–FN05 / version 0.4.0-beta / 2026-11-02

사용 방식
각 서비스는 Repository를 공유하며, 공개 메서드는 request dict를 받아 result dict를 반환한다.
금액 계산은 Decimal로 수행한다. JSON에는 단위 가격을 문자열로 저장한다.
HTTP 서버 API가 아닌 데스크톱 앱의 내부 Python API이다.

FN01  LedgerService.manageLedger(req) → dict
입력  action: CREATE | UPDATE | DELETE | SEARCH
등록: date(YYYY-MM-DD), kind(수입/지출), category, amount(양의 정수), memo
수정/삭제: recordId / 삭제: confirmed=True / 검색: month, category?, keyword?
구매 내역: product_id, store, quantity(양의 정수), amount(실제 결제 총액)
출력  SEARCH: records, summary / 변경: months(영향받은 월별 합계)
기능  저장 성공 뒤 합계를 확정한다. 날짜 이동 시 이전 월과 새 월을 모두 재계산한다.
상태  OK, INVALID_INPUT, CANCELLED, NOT_FOUND, SAVE_FAILED, READ_FAILED

FN02  BudgetService.manageBudgetPlan(req) → dict
입력  action: GET | SET_BUDGET | UPSERT_ITEM | REMOVE_ITEM | SAVE_PLAN, month
예산: budget(0 이상 정수) / 계획: draftPlan[], itemId?, product_id, quantity, store?, channel, condition
출력  summary, draftPlan, items, validSubtotal, unknownCount, totalEstimate, projectedBalance, excess
기능  추가/수정/제거는 초안만 반환한다. SAVE_PLAN만 파일에 확정한다.
        유효 가격이 없는 항목은 0원으로 대체하지 않으며 전체 예상액·초과액은 None이다.
상태  OK, INVALID_INPUT, SAVE_FAILED, READ_FAILED

FN03  ProductService.manageProducts(req) → dict
입력  action: QUERY | REFRESH | AUTO_REFRESH | UPSERT_MANUAL | SET_FAVORITE | OPEN_LINK
조회: category?, store?, channel? / 직접 입력: product dict, offer dict
즐겨찾기: product_id, isFavorite(bool) / 링크: url(http/https)
출력  offers, sources, products 또는 updated[], failures{}, target_result
기능  AUTO_REFRESH는 날짜별 1회 시도. REFRESH는 즉시 재시도한다.
        출처별 실패는 마지막 성공 값·시각을 보존하되 현재 가격 계산에서 제외한다.
        유효 가격을 저장한 뒤 FN05 EVALUATE를 호출한다. 피드의 확인 시각은 바꾸지 않는다.
상태  OK, PARTIAL_FAILURE, REFRESH_FAILED, INVALID_INPUT, SAVE_FAILED, LINK_FAILED, READ_FAILED

FN04  PriceService.analyzePrices(req) → dict
입력  mode: COMPARE | HISTORY, product_id, channel(기본 온라인), condition(기본 일반)
이력: store, branch(기본 전체), start, end(YYYY-MM-DD)
출력  COMPARE: ranked[], excluded[] / HISTORY: rows[{date, unit_price}], count, min, max, mean
기능  동일 브랜드·규격·단위·용량·경로·할인 조건만 비교한다. 묶음 수를 반영한다.
        확인 시각 없음/24시간 경과/갱신 실패/행사 기간 밖은 현재 비교에서 제외한다.
        과거 통계는 날짜별 마지막 관측을 사용하며 기록 없는 날짜를 채우지 않는다.
상태  OK, NO_DATA, INVALID_INPUT, READ_FAILED

FN05  TargetService.managePurchaseTargets(req) → dict
입력  action: VIEW | SET_TARGET | CLEAR_TARGET | EVALUATE, product_id 또는 product_ids[]
목표: price(양수 또는 None), enabled(bool), channel, condition
출력  items(기간·days·mean·basis·target·applied·matches·notificationStatus), notifications[]
기능  오늘 이전 28일의 날짜별 평균을 계산한다. 관측일 4일 이상이면 평균의 90%가 기준이다.
        개인 목표가 우선하며, 유효 가격이 목표 이하일 때만 알림을 생성한다.
        당일 상품별 알림 기록을 저장한 후 표시하여 재실행 후 중복 알림을 방지한다.
알림  SENT, DISABLED, ALREADY_SENT, NO_BASIS, SAVE_FAILED, NOT_EVALUATED, NOT_REACHED
상태  OK, INVALID_INPUT, SAVE_FAILED, READ_FAILED

공통 저장 API
Repository.loadState() → {status, state? 또는 message, path}
Repository.saveAtomic(candidate: AppState) → {status, message?}
임시 파일 flush/fsync 후 os.replace로 확정한다. 실패 시 파일·메모리는 이전 상태를 유지한다.
읽기 실패 파일은 덮어쓰지 않는다. READ_FAILED 후 정상 데이터 파일을 선택한다.

상품 / 가격 스키마
product: id, name, category(우유/계란/라면/쌀/두부), brand, spec, size, unit, pack_count
offer: product_id, store(이마트/홈플러스/코스트코), channel, branch, condition, price, url,
        checked_date, checked_at(시간대 포함 ISO 8601 또는 빈 문자열), start?, end?
기준 단위: 우유 100ml, 계란 1개, 라면 1봉, 쌀·두부 100g.
size는 묶음 안 1개 포장의 용량/개수, pack_count는 포장 수이다.
예: 1L 우유 2개 묶음은 size="1000", unit="ml", pack_count=2.
spec에 종류·등급·규격을 적어 비교 조건을 구분한다.

JSON 피드 / MartAdapter.fetchOffers(store)
설정된 UTF-8 JSON 파일(5MB 이하)의 {"products": [...], "offers": [...]}를 읽는다.
해당 마트의 모든 레코드를 검증한 뒤 원자적으로 반영한다.
베타에는 마트 웹 크롤러가 없다. 제공자가 확인한 가격과 시각을 입력해야 한다.
시연 파일의 가격은 설명용 가상 값이다. 실사용 데이터 파일과 분리한다.

예제
repo = Repository("userdata/ledger.json")
result = LedgerService(repo).manageLedger({
    "action": "CREATE", "date": "2026-11-02", "kind": "지출",
    "category": "식료품", "amount": 2500, "memo": "우유 실제 결제"})
assert result["status"] == "OK"

오류 처리
INVALID_INPUT이면 message와 draft(해당 서비스)를 표시하고 입력값을 유지한다.
SAVE_FAILED이면 재시도 전 파일 권한·디스크 상태를 확인한다.
NO_DATA 또는 None 통계는 자료 부족이며 0원이 아니다.
앱이 종료된 동안에는 자동 갱신과 알림을 실행하지 않는다.
'''
