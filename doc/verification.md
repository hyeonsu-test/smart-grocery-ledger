# 빌드와 검증

Java 17 이상의 JDK에서 프로젝트 폴더를 열고 빌드하세요. 소스·테스트를 컴파일하고 Javadoc과 실행 JAR를 생성한 뒤 서비스·수집·검색 검사와 JAR 검사를 실행합니다.

## 실행 명령

Windows PowerShell:

~~~powershell
.\build.ps1
java -cp "bin;test-bin" sgl.UiSmokeTests
java -cp "bin;test-bin" sgl.CollectionUiTests
java -cp "bin;test-bin" sgl.DiscoveryUiTests
java -cp "bin;test-bin" sgl.ProductFlowTests
~~~

Linux 또는 macOS:

~~~sh
sh build.sh
java -cp "bin:test-bin" sgl.UiSmokeTests
java -cp "bin:test-bin" sgl.CollectionUiTests
java -cp "bin:test-bin" sgl.DiscoveryUiTests
java -cp "bin:test-bin" sgl.ProductFlowTests
~~~

Swing 검사는 그래픽 화면이 필요합니다. JAR만 받은 경우 화면 없이 저장·재읽기·내장 Javadoc을 확인할 수 있습니다.

~~~sh
java -jar SmartGroceryLedger.jar --smoke-test
~~~

## 0.7.1 검증 결과

| 환경 | 확인한 범위 |
|---|---|
| Windows 11 / Temurin Java 21.0.9 | 서비스 61, 수집 32, 검색·목록 44, 기존 Swing 58, 수집 UI 18, 검색·목록 UI 17, 상품 연동 55 모두 통과 |
| Ubuntu 26.04 / WSL2 / Java 17.0.17 | 동일 항목 통과, WSLg 화면 검사와 같은 배포 JAR 실행 |
| Windows 11 / Oracle Java 26 | 같은 배포 JAR의 서비스·저장·재읽기·내장 Javadoc 검사 통과 |

macOS 실행은 미검증입니다. JAR SHA-256은 `f48dec5981ab08a823acf1c402ed920b3427f8254909ecb7ba34010f7802f51a`입니다. [배포 정보](../release-manifest.json)

[Windows 서비스](../test-log/service-tests.log) · [수집](../test-log/collection-tests.log) · [검색·목록](../test-log/discovery-tests.log) · [기존 UI](../test-log/swing-actions.log) · [수집 UI](../test-log/collection-swing.log) · [검색·목록 UI](../test-log/discovery-swing.log)

[Linux 서비스](../test-log/linux-service-tests.log) · [수집](../test-log/linux-collection-tests.log) · [검색·목록](../test-log/linux-discovery-tests.log) · [기존 UI](../test-log/linux-swing-actions.log) · [수집 UI](../test-log/linux-collection-swing.log) · [검색·목록 UI](../test-log/linux-discovery-swing.log) · [JAR](../test-log/linux-jar-smoke.log)

## 신규 상품의 04·05 연동 검증

ProductFlowTests는 실제 Swing 창에서 기존 상품이 있는 상태로 새 상품을 등록하고 비교·이력·관심 상품·목표 저장을 이어 실행합니다. 새 상품으로 바꿀 때 이전 지점이 남는 경우, 비교 행과 이력 상품이 다른 경우, 새 관심 상품 대신 기존 목표를 편집하는 경우를 재현해 수정했습니다. 긴 상품명은 목표 입력란 위에 표시하고 회원 조건 입력칸을 넓혔습니다.

55개 검사는 생수·커피·과자·개수 상품과 기존 다섯 품목의 단위, 목표 저장 대상, 미저장 변경 취소, 새 관심 행 선택, 알림 중복 방지, 재실행, 중지·삭제·24시간 경과·수집 실패·4일 평균 기준을 다룹니다. Windows와 Linux에서 모두 통과했습니다.

별도로 기존에 선택한 실제 상품 6개의 공식 페이지를 다시 확인해 빈 가계부에 새로 등록했습니다. 각 상품의 04 비교·이력, 05 목표·알림, 6개 재수집, 앱 재실행 후 설정·알림 이력 유지까지 양 플랫폼에서 각각 76개 검사를 통과했습니다. 실제 시각의 관측만 사용했으며 과거 이력을 만들어 넣지 않았습니다.

[연동 테스트 코드](../test/sgl/ProductFlowTests.java) · [Windows 연동](../test-log/product-flow.log) · [Linux 연동](../test-log/linux-product-flow.log) · [Windows 실제 상품](../test-log/live-flow-windows.log) · [Linux 실제 상품](../test-log/live-flow-linux.log)

## 실제 온라인 검색과 재수집

0.7.0에서 2026년 11월 3일 Windows와 Linux의 공식 온라인몰 검색·수집을 확인했으며 아래 로그를 유지합니다. 0.7.1의 수집 코드는 같습니다. 기본 8개 중 홈플러스 4개·코스트코 3개를 수집했고 이마트 1개는 HTTP 403으로 실패했습니다.

별도로 빈 수집 목록에서 생수·커피·과자를 검색했습니다. 홈플러스 생수 20개·과자 20개, 코스트코 커피 40개 결과를 받았으며 그중 각 2개씩 총 6개를 등록했습니다. 저장 파일을 다시 읽은 뒤 6개 모두 가격을 다시 수집했습니다. 용량이 불명확한 결과는 자동 등록하지 않았습니다. 검색어·시각·재고·공개 응답에 따라 결과는 달라질 수 있습니다.

[Windows 기본 수집](../test-log/live-windows.log) · [Linux 기본 수집](../test-log/live-linux.log) · [Windows 검색·재수집](../test-log/discovery-live-windows.log) · [Linux 검색·재수집](../test-log/discovery-live-linux.log)

검색은 홈플러스 익스프레스·코스트코를 지원합니다. 홈플러스 대형마트는 상세 주소로 등록합니다. 검색 결과는 최대 40개, 저장 목록은 최대 100개입니다. 전체 온라인몰의 상품을 모두 가져오거나 앱 종료 중 수집하지는 않습니다. 가격 이력은 실제 관측일부터 쌓입니다.

## 검사 범위

기존 검사는 수입·외식·식료품의 구분, 상품 구매 수량, 예산·장보기, 가격 미확정, 단위 환산, 기간·조건·출처별 가격 유효성, 목표 알림 중복 방지, 저장 실패 및 기존 파일 호환을 다룹니다. 수집 검사는 상품 ID·이름·원화·재고·행사·회원 조건과 부분 실패·재시도·수집 중 동시 저장을 확인합니다.

새 검색·목록 검사는 공식 주소 정규화, 유사 도메인 거부, 실제 검색 응답 형식, 묶음 용량 환산, 불명확한 용량, 최소 주문 조건, 용기와 음료의 구분, 명시적 등록·중복 방지·재실행 복원·중지·재개·삭제를 확인합니다. 목록을 비운 뒤 기본 항목이 다시 생기지 않는지, 수집 중 삭제한 상품의 늦은 응답이 저장되지 않는지, 상품 규격 변경 시 새 ID로 등록하고 이력을 보존하는지도 포함합니다. UI 검사는 검색·미리보기만으로 저장되지 않는지와 비동기 확인 중 창을 닫아도 등록되지 않는지 확인합니다.

[서비스 검사](../test/sgl/ServiceTests.java) · [수집 검사](../test/sgl/CollectionTests.java) · [검색·목록 검사](../test/sgl/DiscoveryTests.java) · [검색·목록 UI 검사](../test/sgl/DiscoveryUiTests.java)

## 디렉터리와 API

| 경로 | 내용 |
|---|---|
| `src/sgl/`, `test/sgl/` | 앱과 테스트 Java 소스 |
| `bin/`, `test-bin/` | 컴파일 결과 |
| `lib/`, `resources/` | 라이브러리 안내, 글꼴·라이선스, 호환 예시와 내장 Javadoc |
| `doc/api/`, `doc/screenshots/`, `test-log/` | API HTML, 실행 화면, 실제 테스트 로그 |

Javadoc은 빌드 후 `doc/api/index.html` 또는 앱의 **Javadoc API 매뉴얼** 버튼으로 여세요. javac는 `--release 17 -Xlint:all,-serial -Werror`, javadoc은 `-Xdoclint:all -Werror`로 검사합니다.

rendered의 01·02는 가상 시연 자료, 03·04·05·08·09는 실제 수집·검색으로 실행한 Swing 컴포넌트 출력입니다. 03·04·05는 0.7.1의 신규 6개 연동 검증 화면입니다. 05의 목표가는 테스트용 사용자 설정 예시입니다. 나머지 화면은 해당 기능이 같은 이전 버전 자료를 유지합니다. native 폴더와 탭 배율 비교는 이전 버전의 보관 자료입니다. 새 보고서에는 실제 검색 화면과 목록을 포함합니다.

[상품 검색](screenshots/rendered/08-discovery.png) · [수집 목록](screenshots/rendered/09-collection-list.png) · [API 개요](screenshots/api/06-javadoc-overview.png)

IBM Plex Sans KR Regular·Bold 글꼴과 OFL 배포 조건은 JAR 안에 있습니다. 탭은 16pt이며 0.5.2에서 100%·125%·150%·200% 배율을 확인한 렌더러를 유지합니다. [라이선스](../resources/fonts/OFL.txt) · [원본과 해시](../resources/fonts/SOURCE.txt)
