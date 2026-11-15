import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { stream -> props.load(stream) }
}

android {
    namespace = "com.example.gilkok"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.gilkok"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TOUR_API_KEY", "\"${localProps.getProperty("TOUR_API_KEY", "")}\"")
        buildConfigField("String", "SEOUL_TRAFFIC_KEY", "\"${localProps.getProperty("SEOUL_TRAFFIC_KEY", "")}\"")
    }

    // 제출용 서명: local.properties 에 RELEASE_STORE_FILE 등이 있을 때만 사용 (키 파일·비밀번호는 저장소에 올리지 않음)
    signingConfigs {
        create("release") {
            localProps.getProperty("RELEASE_STORE_FILE")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (localProps.getProperty("RELEASE_STORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(files(androidComponents.sdkComponents.sdkDirectory.map {
        it.file("platforms/android-37.0/optional/android.car.jar")
    }))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
