import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 제출용 release 서명. 키스토어와 비밀번호는 저장소 밖 ~/.milebloom/signing.properties에 둔다.
// 파일이 없는 곳(소스만 받은 심사자 등)에서는 서명 없이 빌드된다.
val signingFile = File(System.getProperty("user.home"), ".milebloom/signing.properties")
val signingProps = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.milebloom.app"
    compileSdk {
        version = release(37)
    }

    // AAOS car framework (android.car) — needed for CarPropertyManager.GEAR_SELECTION
    // and CarUxRestrictionsManager. Provided by the platform at runtime.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.milebloom.app"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingFile.isFile) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8을 쓰지 않는다. Pleos Vehicle SDK가 kotlin-reflect로 권한 어노테이션을 읽는데, R8이 kotlin.**를
            // 줄이면 첫 호출이 InvocationTargetException으로 죽어 주행거리를 못 받는다 (5-D 실측).
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // 기록 파일 왕복 시험용. 안드로이드의 org.json은 단위 테스트에서 빈 껍데기라 실제 구현을 넣는다 (앱에는 들어가지 않는다)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    // CarInfo.getCapabilityInfo()의 반환 타입이 여기 있다. Vehicle AAR만 넣으면 코드에서 직접 못 읽는다.
    implementation("ai.pleos.playground:vehicle-data:1.1.11")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
}