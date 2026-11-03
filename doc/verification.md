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

## 0.4.2 실행 결과

| 환경 | 확인한 동작 | 실행 로그 |
|---|---|---|
| Windows 11 · Java 21.0.9 | 서비스 54개와 Swing 25항목 통과. JAR의 다섯 탭 실행 확인 | [서비스](../test-log/service-tests.log) · [Swing](../test-log/swing-actions.log) · [JAR](../test-log/jar-smoke.log) |
| Windows 11 · Java 26 | 배포 JAR의 서비스·저장·재읽기 검사 통과 | [JAR](../test-log/windows-java26-jar.log) |
| Ubuntu 26.04 · WSL2 · Java 17.0.17 | 화면 없이 서비스 54개와 동일 배포 JAR 검사 통과 | [서비스](../test-log/linux-service-tests.log) · [JAR](../test-log/linux-jar-smoke.log) |

Linux GUI와 macOS 실행은 미검증입니다. 배포 JAR의 SHA-256은 `6481866cc2307e29e686dca3a6a31d02612ae05cb3010be4b37ae616eca49faf`입니다. [배포 정보](../release-manifest.json)

서비스 검사는 독립된 임시 폴더에서 실제 저장, 저장 실패 시 복구, 월 이동, 가격 유효 기간, 단위 환산, 목표 알림의 재실행 중복 방지를 확인합니다. Swing 검사는 저장 버튼, 장보기 초안, 미저장 입력과 피드 갱신 후 상품 목록 반영을 확인합니다. 실패가 있으면 검사 프로세스가 종료 코드 1을 반환합니다. [서비스 검사 코드](../test/sgl/ServiceTests.java) · [화면 검사 코드](../test/sgl/UiSmokeTests.java)

## 디렉터리와 API 문서

| 경로 | 내용 |
|---|---|
| `src/sgl/`, `test/sgl/` | 앱과 테스트의 Java 소스 |
| `bin/`, `test-bin/` | 빌드가 생성하는 앱과 테스트 클래스 |
| `lib/`, `resources/` | 라이브러리 안내, 예시 피드, 빌드가 생성하는 Javadoc 압축 파일 |
| `doc/api/`, `doc/screenshots/`, `test-log/` | 생성된 API 문서, 시연 화면, 테스트 로그 |

Javadoc은 빌드 후 `doc/api/index.html`에서 열거나, 실행 중인 앱 상단의 **Javadoc API 매뉴얼** 버튼으로 열 수 있습니다. 빌드는 Java 17을 대상으로 하며 컴파일과 Javadoc 경고를 오류로 처리합니다. [빌드 스크립트](../build.ps1) · [API 개요 화면](screenshots/api/06-javadoc-overview.png)

`doc/screenshots/native`에는 Windows에서 실행한 창의 원본 캡처가, `doc/screenshots/rendered`에는 Swing 컴포넌트를 고해상도로 출력한 이미지가 있습니다. 두 폴더 모두 가상 시연 데이터를 사용합니다. [Windows 실행 창](screenshots/native/01-ledger.png) · [장보기 화면](screenshots/rendered/02-budget.png)
