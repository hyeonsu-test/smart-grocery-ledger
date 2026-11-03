# 빌드와 검증

Java 17 이상의 JDK에서 프로젝트 최상위 폴더를 열고 다음 명령을 실행하세요. 빌드 스크립트가 Java 소스와 테스트를 컴파일하고, Javadoc을 생성하고, 실행 JAR를 만든 뒤 서비스 검사와 JAR 실행 검사를 수행합니다.

**Windows PowerShell**

```powershell
.\build.ps1
java -cp "bin;test-bin" sgl.UiSmokeTests
```

**Linux 또는 macOS 셸**

```sh
sh build.sh
java -cp "bin:test-bin" sgl.UiSmokeTests
```

Swing 검사를 실행하려면 그래픽 화면이 필요합니다. JAR만 받은 경우에는 다음 명령으로 화면을 열지 않고 서비스 다섯 개, 임시 파일 저장과 재읽기, 내장 Javadoc 자원을 확인할 수 있습니다.

```sh
java -jar SmartGroceryLedger.jar --smoke-test
```

## 0.6.0 실행 결과

| 환경 | 확인한 동작 | 실행 로그 |
|---|---|---|
| Windows 11 · Java 21.0.9 | 서비스 61개와 Swing 58항목 통과. JAR의 다섯 탭 실행 확인 | [서비스](../test-log/service-tests.log) · [Swing](../test-log/swing-actions.log) · [JAR](../test-log/jar-smoke.log) |
| Windows 11 · Java 26 | 배포 JAR의 서비스·저장·재읽기 검사 통과 | [JAR](../test-log/windows-java26-jar.log) |
| Ubuntu 26.04 · WSL2 · Java 17.0.17 | 서비스 61개·수집 32개, WSLg 화면에서 Swing 58항목·수집 UI 18항목과 동일 배포 JAR 검사 통과 | [서비스](../test-log/linux-service-tests.log) · [Swing](../test-log/linux-swing-actions.log) · [JAR](../test-log/linux-jar-smoke.log) |

macOS 실행은 미검증입니다. 배포 JAR의 SHA-256은 `acbb1e04ee12fd4798d79d3b9a17ebcd95ae95cdb83cb0832eb74dc84ee1366f`입니다. [배포 정보](../release-manifest.json)

이전 버전에서 생성한 가계부를 열어 상품 규격, 가격, 시간대, 거래, 예산을 저장 후 다시 읽는 검사도 포함합니다. 반복소수 단가를 화면에 표시하고 정렬하는 경로, 상품 탭에서 가계부 탭으로 이동해 저장할 때 지출이 한 번만 생기는 경로를 확인합니다. [호환성 검사 자료](../test/fixtures/legacy-ledger-v1.json)

서비스 검사는 독립된 임시 폴더에서 실제 저장, 저장 실패 시 복구, 월 이동, 가격 유효 기간, 단위 환산, 목표 알림의 재실행 중복 방지를 확인합니다. Swing 검사는 저장 버튼, 장보기 초안, 미저장 입력과 피드 갱신 후 상품 목록 반영을 확인합니다. 실패가 있으면 검사 프로세스가 종료 코드 1을 반환합니다. [서비스 검사 코드](../test/sgl/ServiceTests.java) · [화면 검사 코드](../test/sgl/UiSmokeTests.java)

0.6.0 검사는 수입·지출 분류, 외식의 거래처, 상품 구매에만 적용되는 수량, 식료품 예산과 외식의 구분을 확인합니다. 가격 없는 상품의 조회·계획·결제 입력, 현재 행사 필터, 직접 입력과 피드의 독립 보존, 비교 상품·지점 변경 시 이전 이력 초기화, 초안 이동 취소, 빈 목표 화면, 숫자 정렬도 포함합니다.

## 디렉터리와 API 문서

| 경로 | 내용 |
|---|---|
| `src/sgl/`, `test/sgl/` | 앱과 테스트의 Java 소스 |
| `bin/`, `test-bin/` | 빌드가 생성하는 앱과 테스트 클래스 |
| `lib/`, `resources/` | 라이브러리 안내, 예시 피드, 빌드가 생성하는 Javadoc 압축 파일 |
| `doc/api/`, `doc/screenshots/`, `test-log/` | 생성된 API 문서, 시연 화면, 테스트 로그 |

Javadoc은 빌드 후 `doc/api/index.html`에서 열거나, 실행 중인 앱 상단의 **Javadoc API 매뉴얼** 버튼으로 열 수 있습니다. 빌드는 Java 17을 대상으로 하며 컴파일과 Javadoc 경고를 오류로 처리합니다. [빌드 스크립트](../build.ps1) · [API 개요 화면](screenshots/api/06-javadoc-overview.png)

`doc/screenshots/native`에는 0.5.0 시연 창의 원본 캡처가, `doc/screenshots/rendered`에는 0.6.0 Swing 컴포넌트를 고해상도로 출력한 이미지가 있습니다. 01·02와 native 폴더는 가상 시연 자료이며, rendered의 03·04·05는 2026-11-03 실제 수집 가격입니다. 05의 개인 목표 330원/100ml는 화면 설명을 위한 설정 예시입니다. [0.5.0 실행 창](screenshots/native/01-ledger.png) · [장보기 화면](screenshots/rendered/02-budget.png)

0.5.2 글꼴 수정 때 Windows에서 0.5.1과 0.5.2 JAR를 별도 빈 폴더로 실행하고 실제 탭 막대를 100%, 125%, 150%, 200% 배율에서 비교했습니다. 각 배율에서 다섯 탭을 차례로 선택해 제목이 한 줄 안에 들어가는지 확인했습니다. 0.6.0에도 같은 탭 렌더러와 글꼴을 사용합니다. 탭은 JAR 안의 IBM Plex Sans KR Regular를 16pt로 사용합니다. [150% 실제 탭 비교](screenshots/tab-comparison.png)

IBM Plex Sans KR의 일반·굵은 글꼴 파일과 배포 조건은 실행 JAR의 fonts 폴더에 들어 있습니다. [배포 라이선스](../resources/fonts/OFL.txt) · [원본 버전과 해시](../resources/fonts/SOURCE.txt)


## 공식 상품 수집 검증

Windows Java 21과 Ubuntu Java 17에서 같은 JAR로 인터넷에 직접 요청했다. 홈플러스 4개·코스트코 3개의 양수 가격과 상품 규격을 받아 단위 가격 비교까지 통과했다. 이마트 1개는 HTTP 403으로 실패했으며 결과에 그대로 표시한다. 수집 범위는 내장 목록 8개이며 마트 전체 카탈로그 검색은 지원하지 않는다.

[Windows 실제 수집 로그](../test-log/live-windows.log) · [Linux 실제 수집 로그](../test-log/live-linux.log)

오프라인 수집 검사 32개는 상품 ID·이름 불일치, 원화·가격 불일치, 품절, 조건부 카드 할인, 행사 기간, 회원 가격, 시간 초과, 부분 실패, 이력 보존, 재시도, 당일 자동 시도 제한, 수집 중 별도 저장, 저장 실패와 재실행을 검사한다. Swing 검사 18개는 파일 입력 없이 자동 수집, 03·04·05 버튼, 중복 요청 방지, 04 이력 반영과 05 관심 상품 추가를 확인한다.

[수집 검사 코드](../test/sgl/CollectionTests.java) · [수집 화면 검사](../test/sgl/CollectionUiTests.java) · [수집 검사 로그](../test-log/collection-tests.log) · [수집 화면 로그](../test-log/collection-swing.log)

독립 실행: java -cp "bin;test-bin" sgl.CollectionTests 및 java -cp "bin;test-bin" sgl.CollectionUiTests. Linux에서는 클래스 경로 구분자를 콜론으로 바꾼다. 테스트는 임시 가계부를 사용하며 개인 가계부를 수정하지 않는다.
