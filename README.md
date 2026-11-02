# Smart Grocery Ledger

Java 17 이상과 Swing으로 실행하는 개인 식비 가계부입니다. 수입·지출, 월 식비 예산과 장보기 계획, 상품 가격, 단위 가격 비교·이력, 구매 목표 알림을 제공합니다. 현재 버전은 **0.4.2-java-beta**입니다. Python 없이 실행할 수 있습니다.

## 실행

호환되는 Java 17 이상 런타임이 설치되어 있어야 합니다.

```text
java -jar SmartGroceryLedger.jar
java -jar SmartGroceryLedger.jar --demo
```

Windows에서는 `run.bat` 또는 `demo.bat`, macOS/Linux에서는 `sh run.sh --demo`를 사용할 수 있습니다. 파일 연결이 설정돼 있으면 JAR를 더블 클릭해도 실행됩니다.

기본 저장 위치는 `<사용자 홈>/SmartGroceryLedger/data/`입니다. 일반 모드는 `ledger.json`, 시연 모드는 `demo.json`을 사용합니다. 시연 자료는 가상 가격이며 실제 판매가가 아닙니다. 새 시연 자료가 필요하면 별도 폴더로 실행하세요.

```text
java -jar SmartGroceryLedger.jar --demo --data-dir "./demo-data"
java -jar SmartGroceryLedger.jar --data-dir "./my-data"
java -jar SmartGroceryLedger.jar --smoke-test
```

`--no-auto`는 앱 실행 중 날짜별 피드 갱신 시도를 끕니다. 동시에 같은 데이터 파일을 사용하는 두 앱은 파일 잠금으로 차단합니다. Windows 외 환경의 GUI 사용에는 한글 글꼴과 그래픽 화면 환경이 필요합니다.

## 구현 범위

| 설계 | Java 구현 | 동작 |
|---|---|---|
| UC01 / FN01 | `LedgerService.manageLedger` | 실제 수입·지출 등록·수정·삭제·월/분류/키워드 검색, 월 합계 |
| UC02 / FN02 | `BudgetService.manageBudgetPlan` | 예산 저장, 장보기 초안·저장, 예상액·가격 미확정·초과액 |
| UC03 / FN03 | `ProductService.manageProducts` | 상품/마트/경로 필터, 즐겨찾기, 직접 가격 입력, JSON 피드 갱신 |
| UC04 / FN04 | `PriceService.analyzePrices` | 조건을 맞춘 단위 가격 비교, 날짜별 최종 관측 가격과 통계 |
| UC05 / FN05 | `TargetService.managePurchaseTargets` | 직전 28일 기준, 개인 목표 우선, 저장 후 상품별 하루 한 번 알림 |

**실제 마트 웹 자동 수집은 미구현입니다.** 가격은 직접 입력하거나 사용자가 준비한 JSON 스냅샷을 읽습니다. 앱의 피드 갱신은 원래 확인 날짜·시각을 유지하며 오래된 자료를 최신 가격으로 바꾸지 않습니다. 배송비는 비교 가격에서 제외합니다. 실제 결제 기록에는 배송비를 포함한 결제 총액을 직접 확인해 입력하세요.

현재 가격은 확인 후 24시간 미만이며 행사 기간 안에 있는 자료만 사용합니다. 확인 시각 없음·갱신 실패·미래 시각·만료 가격은 현재 비교에서 제외합니다. 과거 이력은 관측 당일에 유효했던 자료를 사용합니다. 평균은 관측한 날짜별 평균을 같은 가중치로 계산하며, 4일 미만이면 90% 구매 기준을 계산하지 않습니다. 개인 목표는 이 제한과 관계없이 우선 적용합니다. 앱 종료 중에는 알림이 발생하지 않습니다.

## 빌드와 테스트

빌드에는 JDK 17 이상이 필요합니다. 외부 라이브러리나 Maven/Gradle 설치는 필요하지 않습니다.

```powershell
# Windows PowerShell (JAVA_HOME을 설치한 JDK로 지정할 수 있습니다)
.\build.ps1
java -cp "bin;test-bin" sgl.UiSmokeTests
```

```sh
# macOS / Linux
sh build.sh
# GUI 화면이 있는 환경에서만 실행
java -cp bin:test-bin sgl.UiSmokeTests
```

빌드는 `javac --release 17`로 클래스 파일을 만들고, Javadoc을 생성하여 JAR 안에 포함한 후 서비스 테스트와 JAR 실행 검사를 수행합니다. 컴파일 및 Javadoc 경고는 실패로 처리합니다. Swing의 직렬화 경고만 제외합니다. Swing 액션 검사는 별도로 실행합니다.

테스트는 JUnit 없이 실행되는 assertion 기반 Java 테스트 러너이며, 실패가 있으면 종료 코드 1을 반환합니다. 각 테스트는 독립된 임시 폴더를 사용합니다. `test-log/`에 실제 실행 로그를 보관했습니다. 세부 범위는 [검증 기록](doc/verification.md)을 확인하세요.

## Javadoc과 설계

- `doc/api/index.html`: 실제 Java 문서 주석에서 생성한 API 매뉴얼. 빌드 후 브라우저에서 엽니다.
- 앱 상단 **Javadoc API 매뉴얼** 버튼: JAR에 포함된 문서를 데이터 폴더의 캐시에 풀고 브라우저로 엽니다.
- [서비스 클래스 다이어그램](doc/class-services.png), [화면·저장 클래스 다이어그램](doc/class-storage-ui.png).
- `.puml`과 `.svg` 원본도 `doc/`에 있습니다. 유스케이스의 dependency/include/extend 제외 결정을 유지합니다.
- [피드 예시](resources/feed-example.json)는 고정 날짜의 **가상** 자료입니다. 실제 관측 없이 날짜만 최신으로 바꾸지 마세요.

API는 HTTP 서버가 아닌 데스크톱 내부 메서드 API입니다. 요청·결과는 `Map<String,Object>`로 전달하며, 메서드 주석에 action별 입력·결과·오류를 명시했습니다. 가격 계산은 `BigDecimal`, 시각은 명시적 시간대가 있는 ISO 형식을 사용합니다.

## 디렉터리 구조

```text
SmartGroceryLedger/
  src/sgl/                 Java 소스
  bin/sgl/                 컴파일 결과 (빌드 생성, 제출 ZIP 포함)
  lib/                     외부 라이브러리 없음 안내
  resources/               피드 예시, 내장 Javadoc 압축본
  test/sgl/                서비스·Swing 액션·화면 출력 검증 코드
  test-bin/sgl/            테스트 컴파일 결과 (빌드 생성)
  test-log/                실제 테스트 및 플랫폼 실행 로그
  doc/api/                 생성된 Javadoc (빌드 생성, 제출 ZIP 포함)
  doc/screenshots/         시연 창 원본 캡처와 Swing 고해상도 출력
  doc/                     클래스 다이어그램, 검증·배포 설명
  SmartGroceryLedger.jar   실행 파일
  build.ps1 / build.sh     재현 가능한 빌드·문서·테스트
  run.bat / demo.bat       Windows 실행
  run.sh                   macOS/Linux 실행
```

소스 저장소에는 재생성할 수 있는 `bin`, `test-bin`, `doc/api`, `resources/api.zip`을 제외합니다. 제출용 `src.zip`에는 이 결과물도 포함해 폴더 트리를 유지합니다. 개인 가계부, 인증 정보, 학번이 포함된 과제 보고서는 공개 저장소에 넣지 않습니다.

이전 Python 베타는 Git 이력과 별도 보관본에 남기고, 현재 기본 실행 경로는 Java로 전환했습니다. 실행 명령과 소스·Javadoc·클래스 도면을 같은 Java 구현에 맞췄습니다.

## 0.4.2 전체 검토 반영

- 연결한 피드만 갱신하며, 피드 실패가 별도 직접 확인 가격을 무효화하지 않습니다.
- 새로운 상품을 가져오면 장보기와 비교 목록을 즉시 갱신합니다. 상품 상세에서 규격·행사 기간·원문·최근 피드 성공 시각을 확인하고, 선택 상품을 장보기 초안에 넣을 수 있습니다.
- 미확정 가격이 있어도 확인된 소계는 표시하며 전체 총액은 미확정으로 유지합니다. 예산·항목·목표의 미저장 입력을 보호합니다.
- 목표 알림에 상품·마트·단위 가격·링크를 함께 표시합니다. 가격 이력 그래프의 가로축은 실제 날짜 간격을 사용합니다.
- 날짜가 다른 시간대의 최신 가격 판정, 저장 데이터 검증, CREATE의 기존 내역 덮어쓰기를 수정했습니다.

서비스 테스트는 54개, Swing 액션 검사는 25항목입니다. [전체 검토 기록](doc/review-2026-11-02.md)에 설계·강의자료와의 대응을 기록했습니다.

기존 GUI 스케치의 달력 선택과 입력란 아래 오류 안내는 현재 ISO 날짜 텍스트 입력과 오류 대화상자로 구현했습니다. 미저장 이동은 버리기/돌아가기 확인이며 즉시 저장 선택은 없으므로 저장 버튼을 먼저 사용합니다. 손상 파일 복구는 `--data-dir`로 정상 폴더를 지정합니다. 전용 Request/Result 클래스 대신 문서화한 Map 계약을 사용하며, 클래스 도면은 실제 Java 구조를 표시합니다. 실제 웹 자동 수집과 Linux GUI/macOS 검증은 여전히 미완료입니다.
