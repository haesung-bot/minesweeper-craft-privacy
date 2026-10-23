# PhoneGuard 📱🛡️

내 스마트폰을 지키고, **내가 만든 앱을 내가 직접 점검**하며 모바일 보안을 공부하기 위한
Android(Kotlin) 학습 프로젝트.

> ⚠️ **윤리 규칙**: 이 앱과 아래 점검 방법은 **오직 본인 소유의 앱·기기**에만 사용합니다.
> 남의 앱/기기/계정을 대상으로 하는 행위는 범죄이며, 이 프로젝트의 목적이 아닙니다.

---

## 기능

| 기능 | 설명 | "두뇌"(테스트 가능한 순수 로직) |
|---|---|---|
| 🔗 피싱/스미싱 링크 검사 | 문자 속 링크의 위험 신호(단축URL, @트릭, IP, 브랜드 흉내 등)를 점수화 | `phishing/UrlRiskAnalyzer.kt` |
| 📋 기기 보안 상태 점검 | 화면잠금·USB 디버깅·출처불명 설치·루팅 흔적 점검 | `device/RootIndicators.kt` |
| 🔐 암호화 비밀 금고 | Android Keystore + EncryptedSharedPreferences로 비밀 보관 | `vault/SecureVault.kt` |
| 🧪 위험 권한 앱 검사 | 설치된 앱의 권한 조합을 위험도로 점수화 | `permissions/PermissionRiskScorer.kt` |
| 🎙️ 마이크 권한 점검 + 실시간 감시 | 마이크 권한 조합 점수화, 다른 앱의 마이크 사용을 실시간 감시 | `mic/MicRiskAnalyzer.kt`, `mic/MicWatchdog.kt` — 자세히: [docs/02-mic-guard.md](docs/02-mic-guard.md) |
| 🎯 MicTest Target (표적 앱) | 감시 기능을 직접 터뜨려서 확인하는 테스트용 앱. 마이크를 진짜로 열지만 저장·전송은 전혀 안 함 | 모듈 `mictest/` — 자세히: [docs/03-mic-selftest.md](docs/03-mic-selftest.md) |

설계 원칙: **판별 로직은 안드로이드 API에 의존하지 않는 순수 Kotlin으로 분리**했습니다.
덕분에 PC에서 단위 테스트로 빠르게 검증하고, 기기 전용 부분만 계측 테스트로 확인합니다.

---

## 빌드 & 실행 (내 PC)

사전 준비: **Android Studio** (또는 Android SDK + JDK 17).

```bash
cd phone-guard

# 1) 순수 로직 단위 테스트 (기기 불필요, 가장 빠름)
./gradlew test

# 2) 디버그 APK 빌드
./gradlew assembleDebug
#   결과물: app/build/outputs/apk/debug/app-debug.apk

# 3) 연결된 폰/에뮬레이터에 설치
./gradlew installDebug

# 4) 비밀 금고 계측 테스트 (폰/에뮬레이터 연결 필요)
./gradlew connectedDebugAndroidTest
```

> 처음이라면 단계별 가이드를 따라가세요: [docs/01-build-on-my-pc.md](docs/01-build-on-my-pc.md)
> (Android Studio 설치 → 빌드 → 폰/에뮬레이터 설치 → 첫 보안 점검 실습)

---

## "실제로 보안이 되는지" 점검하기 (내 앱 대상, OWASP MASTG 기준)

아래는 **내가 만든 이 앱이 공격에 얼마나 버티는지**를 스스로 점검하는 단계입니다.
모두 본인 기기/본인 APK에만 적용합니다.

1. **정적 분석 — 내 APK 뜯어보기**
   - `app-debug.apk`를 디컴파일해 문자열/하드코딩된 비밀이 없는지 확인
   - 확인 포인트: 비밀 금고의 암호화 키가 코드/리소스에 평문으로 없어야 함
2. **저장소 점검 — 평문이 남는지**
   - 설치 후 앱 데이터의 `shared_prefs/` 파일을 열어 비밀이 평문으로 안 보이는지 확인
   - 이 항목은 `SecureVaultInstrumentedTest.디스크에_평문이_남지_않는다`가 자동으로 검증
3. **동적 분석 — 실행 중 우회 시도**
   - 루팅 탐지 등 방어가 우회될 수 있는지 관찰하고, 약점을 찾으면 방어를 보강
4. **결과를 코드로 되먹이기**
   - 점검에서 발견한 약점마다 테스트를 추가하고 방어 로직을 강화 → 다시 점검

각 단계의 구체적 도구/명령은 진행하면서 함께 정리합니다.

---

## 프로젝트 구조

```
phone-guard/
├── app/                         # 🛡️ PhoneGuard (감시하는 쪽)
│   ├── src/main/java/com/phoneguard/app/
│   │   ├── MainActivity.kt
│   │   ├── phishing/UrlRiskAnalyzer.kt
│   │   ├── device/{DeviceSecurityChecker,RootIndicators}.kt
│   │   ├── permissions/{InstalledAppScanner,PermissionRiskScorer}.kt
│   │   ├── mic/{MicRiskAnalyzer,MicPermissionScanner,MicWatchdog}.kt
│   │   └── vault/SecureVault.kt
│   ├── src/test/...            # 순수 로직 단위 테스트 (JUnit)
│   └── src/androidTest/...     # 기기 계측 테스트
├── mictest/                     # 🎯 MicTest Target (감시당하는 표적 앱)
│   └── src/main/java/com/phoneguard/mictest/MainActivity.kt
└── (Gradle 설정)
```
