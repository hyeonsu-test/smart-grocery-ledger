# 화면과 가격 모델

상품 화면에서 우유를 골라 **실제 구매 기록**을 누르면 선택한 상품과 가격이 가계부 탭으로 전달됩니다. 가계부 탭은 금액과 메모를 먼저 입력란에 표시합니다. **내역 저장**을 눌렀을 때만 가계부 서비스가 거래를 저장합니다.

## 화면별 책임

| 화면 | 담당 클래스 | 맡은 일 |
|---|---|---|
| 창 | [LedgerFrame](../src/sgl/LedgerFrame.java) | 다섯 탭 구성, 이동·종료 확인, 공통 월과 새 가격 반영 |
| 가계부 | [LedgerTab](../src/sgl/LedgerTab.java) | 수입·지출 입력, 선택한 거래 수정, 월 합계 |
| 예산 · 장보기 | [BudgetTab](../src/sgl/BudgetTab.java) | 예산 입력, 항목 편집, 장보기 초안·저장 |
| 상품 · 할인 | [ProductTab](../src/sgl/ProductTab.java) | 상품 선택, 가격 직접 입력, 비동기 피드 갱신 |
| 가격 비교 · 이력 | [PriceTab](../src/sgl/PriceTab.java) | 단위 가격 순위, 기간·지점 선택, 이력 그래프 |
| 구매 목표 · 알림 | [TargetTab](../src/sgl/TargetTab.java) | 개인 목표 편집, 충족 가격과 알림 확인 |

각 탭은 자기 입력란과 선택 상태를 보유합니다. 공통 상위 클래스 `TabView`의 `hasDraft()`와 `discardDraft()`를 통해 창이 이동 전에 미저장 편집을 확인합니다. [화면 클래스 다이어그램](class-storage-ui.png)

## 상품과 가격

`Product`는 상품 ID, 품목, 규격, 낱개 용량, 단위와 묶음 수를 보유하는 불변 Java 레코드입니다. `unitPrice`는 묶음 가격을 기준 단위로 환산합니다. 우유 1,000ml 한 팩이 2,600원이면 100ml당 가격은 260원입니다. `comparableTo`는 포장 개수가 다른 경우에도 품목, 브랜드, 규격, 낱개 용량과 단위가 일치하는지 확인합니다. [Product 소스](../src/sgl/Product.java)

`PriceObservation`은 어느 상품의 가격을 언제, 어느 마트·지점에서, 어떤 조건으로 확인했는지 기록합니다. 금액은 `BigDecimal`, 확인 시각은 `OffsetDateTime`입니다. 행사 기간과 확인 후 24시간 경계를 검사해 현재 유효 여부를 판정합니다. 상품·비교 탭은 이 객체와 상품이 들어 있는 `OfferRow`를 표시합니다. [가격 관측 소스](../src/sgl/PriceObservation.java) · [상품과 가격 클래스 다이어그램](class-domain.png)

## 저장과 서비스 API

`AppState`는 상품을 ID로 찾는 `Map<String, Product>`와 가격 관측 목록 `List<PriceObservation>`을 보유합니다. `Repository`가 읽기·쓰기를 담당하며 JSON 파일의 기존 필드 이름과 버전 1 구조를 사용합니다. 상태 복사본은 독립된 컬렉션을 갖고 불변 상품·관측값을 공유합니다. [상태](../src/sgl/AppState.java) · [저장소](../src/sgl/Repository.java)

다섯 서비스는 같은 저장소와 시계를 사용하는 `Service`를 상속합니다. `ProductService`는 연결된 피드를 `MartAdapter`로 읽고 새 가격을 저장한 뒤 `TargetService`에 목표 판정을 요청합니다. 공개 서비스 요청·결과와 JSON 입출력은 기존 맵 계약을 사용합니다. 입력 키, 결과 상태와 오류는 실행 창의 Javadoc API 매뉴얼에서 확인할 수 있습니다. [서비스 클래스 다이어그램](class-services.png) · [빌드와 검증](verification.md)
