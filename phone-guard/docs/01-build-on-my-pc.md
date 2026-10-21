# 01. 내 PC에서 빌드하고 내 폰에 설치하기

목표: PhoneGuard를 직접 빌드해서 폰(또는 에뮬레이터)에 깔고, **첫 보안 점검 실습**까지 해 본다.

> 이 코드는 클라우드 환경에서 `./gradlew test assembleDebug lintDebug`로
> 단위 테스트 14개 통과, APK 생성, Lint 에러 0개를 확인한 상태다.

---

## 1단계. Android Studio 설치 (약 20분)

1. https://developer.android.com/studio 에서 Android Studio 다운로드 → 설치
2. 처음 실행하면 나오는 **Setup Wizard**에서 `Standard` 선택 → SDK가 자동으로 깔린다
   - JDK도 Android Studio 안에 포함돼 있어서 따로 설치할 필요 없다

## 2단계. 코드 받기

터미널(Windows는 PowerShell)에서:

```bash
git clone https://github.com/haesung-bot/minesweeper-craft-privacy.git
cd minesweeper-craft-privacy
git checkout ccr-01095dca-2zjm50
```

> git이 없으면: GitHub 저장소 페이지 → 브랜치를 `ccr-01095dca-2zjm50`으로 바꾼 뒤
> `Code` → `Download ZIP` 으로 받아도 된다.

## 3단계. Android Studio로 열기

1. Android Studio → `File` → `Open` → **`phone-guard` 폴더**를 선택
   (저장소 최상위 폴더가 아니라 그 안의 `phone-guard`!)
2. 오른쪽 아래에 `Gradle sync` 진행 표시가 뜬다. 처음엔 몇 분 걸린다
3. 끝나고 빨간 에러가 없으면 성공

## 4단계. 자동화 테스트 돌려보기 (기기 없이)

Android Studio 아래쪽 `Terminal` 탭에서:

| OS | 명령 |
|---|---|
| Mac / Linux | `./gradlew test` |
| Windows | `.\gradlew.bat test` |

마지막에 `BUILD SUCCESSFUL`이 나오면 14개 테스트가 모두 통과한 것이다.
결과 보고서: `app/build/reports/tests/testDebugUnitTest/index.html` (브라우저로 열기)

## 5단계. 설치할 기기 준비 — 둘 중 하나

### (A) 에뮬레이터 (추천: 내 폰을 건드리지 않아 안전)

1. Android Studio 오른쪽 `Device Manager` → `+` → `Create Virtual Device`
2. `Pixel 8` 같은 기종 → 시스템 이미지는 **API 35** 선택 → `Finish`
3. ▶ 버튼으로 에뮬레이터 켜기

### (B) 내 실제 폰 (갤럭시 기준)

1. `설정` → `휴대전화 정보` → `소프트웨어 정보` → **`빌드번호`를 7번 연속 탭**
   → "개발자 모드를 켰습니다"
2. `설정` → `개발자 옵션` → **`USB 디버깅` 켜기**
3. USB 케이블로 PC 연결 → 폰에 뜨는 "USB 디버깅을 허용하시겠습니까?" → `허용`
4. Windows에서 폰이 안 잡히면 삼성 USB 드라이버 설치:
   https://developer.samsung.com/android-usb-driver

## 6단계. 앱 설치하고 실행

Android Studio 상단에서 기기를 고르고 초록색 ▶ (`Run 'app'`) 클릭.
또는 터미널에서 `./gradlew installDebug` (Windows: `.\gradlew.bat installDebug`).

## 7단계. 비밀 금고 계측 테스트 (기기 연결된 상태)

```bash
./gradlew connectedDebugAndroidTest
```

`SecureVaultInstrumentedTest` 3개가 통과하면 **"폰 저장소에 비밀번호가 평문으로 안 남는다"**
는 것이 실제 기기에서 확인된 것이다.

---

## 🧪 첫 보안 실습: "내 앱이 내 폰의 약점을 잡아내는가?"

(B) 실제 폰으로 했다면, 지금 폰은 **USB 디버깅이 켜진 상태**다. 이걸 이용한다.

1. PhoneGuard에서 `기기 보안 점검 실행` → `⚠️ USB 디버깅: 켜져 있어요` 가 나와야 정상
2. 폰 설정에서 USB 디버깅을 **끄고** 다시 점검 → `✅` 로 바뀌어야 정상
3. 화면 잠금도 같은 식으로 확인 (잠금 없음 → ⚠️, 설정 → ✅)

| 기대 결과 | 의미 |
|---|---|
| 상태를 바꿀 때마다 결과도 바뀐다 | 탐지가 실제로 작동한다 ✅ |
| 바꿨는데 결과가 그대로다 | 탐지 로직에 버그가 있다 → 고칠 거리 발견! |

(A) 에뮬레이터라면 `루팅 흔적`에 **`test-keys 빌드`** 가 뜰 수 있다.
에뮬레이터 이미지 중 일부가 test-keys로 서명돼 있기 때문인데, 이게 바로
**오탐(false positive)** 이다. 보안 도구가 왜 "확실하다"가 아니라 "의심 신호"로 말해야 하는지
보여주는 좋은 예다.

### 링크 검사도 해보기

아래를 하나씩 붙여넣고 판정을 비교해 본다.

| 입력 | 예상 판정 |
|---|---|
| `https://www.naver.com/` | SAFE (0점) |
| `http://bit.ly/3xYz` | SUSPICIOUS (45점) |
| `http://naver.com@evil.com/login` | DANGEROUS (60점) |
| `http://naver.com.security-login.ru/` | DANGEROUS (60점) |

> ⚠️ 위 주소들은 **붙여넣어 판정만** 하는 용도다. 브라우저로 열지 말 것.

---

## 실습이 끝나면 꼭

- 실제 폰을 썼다면: **USB 디버깅 끄기** (평소엔 꺼두는 게 안전)
- APK 파일로 설치했다면: 파일 관리자 앱의 **"출처를 알 수 없는 앱 설치" 권한 끄기**

## 자주 막히는 곳

| 증상 | 해결 |
|---|---|
| `SDK location not found` | Android Studio로 한 번 열면 `local.properties`가 자동 생성된다 |
| `JAVA_HOME is not set` | Android Studio 안의 Terminal 탭에서 실행한다 (내장 JDK 사용) |
| 폰이 기기 목록에 안 보임 | 케이블이 충전 전용인지 확인, USB 디버깅 허용 팝업 다시 확인 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 폰에서 기존 PhoneGuard를 지우고 다시 설치 |
