plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 깃허브 빌드 번호를 버전 코드로 사용 → 새 APK를 받으면 항상 덮어 설치된다.
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.soundcatch.recorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soundcatch.recorder"
        minSdk = 23
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // 공개 저장소에 있는 키라서 '보안용'이 아니라 '업데이트 덮어 설치'용이다.
            // 플레이스토어에 올릴 때는 새 키를 만들어 비공개로 보관할 것.
            storeFile = rootProject.file("signing/soundcatch.p12")
            storeType = "pkcs12"
            storePassword = "soundcatch"
            keyAlias = "soundcatch"
            keyPassword = "soundcatch"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // 순수 JVM 단위 테스트 (소리 감지 로직, 드라이브 업로드 프로토콜)
    testImplementation("junit:junit:4.13.2")
}
