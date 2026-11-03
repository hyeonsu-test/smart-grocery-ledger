# 객체 구조와 서비스 API

화면에서 내역 하나를 저장할 때 `LedgerFrame`은 날짜와 금액을 읽어 `LedgerService`에 전달합니다. 서비스는 저장된 상태의 복사본에 변경을 적용합니다. `Repository`는 복사본을 검증하고 임시 파일을 쓴 뒤 기존 파일을 원자적으로 교체합니다. 저장에 실패하면 사용자가 입력한 내역과 기존 파일을 보존합니다.

| 책임 | 담당 객체 | 코드를 읽는 출발점 |
|---|---|---|
| 화면과 사용자 입력 | `LedgerFrame` | 다섯 탭을 구성하고 버튼 이벤트를 서비스 호출로 연결합니다. [소스](../src/sgl/LedgerFrame.java) |
| 내역과 월 식비 | `LedgerService`, `BudgetService` | 실제 결제, 월별 집계, 예산, 장보기 초안과 저장을 담당합니다. [가계부](../src/sgl/LedgerService.java) · [예산](../src/sgl/BudgetService.java) |
| 가격 수집과 비교 | `ProductService`, `MartAdapter`, `PriceService` | 파일 읽기, 가격 등록, 단위 환산과 이력 조회를 나누어 처리합니다. [상품](../src/sgl/ProductService.java) · [피드](../src/sgl/MartAdapter.java) · [가격](../src/sgl/PriceService.java) |
| 구매 목표와 알림 | `TargetService` | 직전 28일 기준과 개인 목표를 적용하고 같은 날짜의 중복 알림을 방지합니다. [소스](../src/sgl/TargetService.java) |
| 상태와 저장 | `AppState`, `Repository` | 상태 구조와 검증은 `AppState`가 맡고, 현재 상태와 디스크 파일은 `Repository`가 관리합니다. [상태](../src/sgl/AppState.java) · [저장](../src/sgl/Repository.java) |

## 객체가 협력하는 방식

다섯 서비스는 추상 클래스 `Service`를 상속합니다. 공통 진입점 `call`이 같은 저장소의 작업을 순차적으로 처리하고 읽기 오류와 잘못된 입력을 공통 결과로 변환합니다. 화면이 서비스를 만들 때 같은 저장소와 시계(`Clock`)를 전달하므로, 테스트에서는 고정된 시각으로 가격 유효 기간과 목표 알림을 재현할 수 있습니다. [공통 서비스](../src/sgl/Service.java)

예를 들어 JSON 피드를 갱신하면 `ProductService`가 `MartAdapter`에 파일 읽기를 맡깁니다. 한 마트의 자료를 모두 검증한 뒤 저장하고, 유효 가격을 저장한 뒤에만 연결된 `TargetService`에 목표 판정을 요청합니다. 알림 이력이 저장되면 화면에 표시할 알림이 반환됩니다. [서비스 클래스 다이어그램](class-services.png)

저장소가 보유한 상태는 비공개입니다. `snapshot()`은 독립된 상태를 반환하고 `AppState.toMap()`도 내부 컬렉션의 복사본을 반환합니다. 테스트의 `FailingRepository`는 파일 교체 메서드를 재정의해 저장 실패를 일으킵니다. 같은 가계부 서비스로 파일과 메모리가 보존되는지 검사할 수 있습니다. [화면·저장 클래스 다이어그램](class-storage-ui.png) · [저장 실패와 복사본 격리 테스트](../test/sgl/ServiceTests.java)

## API와 변경 범위

서비스의 공개 작업 메서드는 `Map<String, Object>`로 요청을 받고 결과의 `status`를 반환합니다. 예를 들어 `LedgerService.manageLedger`는 `action=SEARCH`와 `month=2026-11`을 받아 해당 월의 내역과 집계를 반환합니다. 문자열 키와 값의 타입은 실행 시 검증하므로 호출 코드를 바꿀 때는 Javadoc의 메서드 계약을 함께 확인하세요. [API 화면 예시](screenshots/api/07-javadoc-method.png)

상품과 가격 관측도 맵으로 표현하고 단위 환산·유효성 판정에는 `Data`의 정적 함수를 사용합니다. 다섯 탭의 입력, 선택, 미저장 상태는 한 `LedgerFrame`에서 조정합니다. 탭 이동이나 공통 가격 규칙을 변경할 때는 영향을 받는 다른 화면의 동작도 확인해야 합니다. [가격 규칙](../src/sgl/Data.java) · [Swing 화면 테스트](../test/sgl/UiSmokeTests.java)
