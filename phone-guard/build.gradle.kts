// 최상위(top-level) 빌드 파일. 하위 모듈에서 쓸 플러그인 버전만 선언한다.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
