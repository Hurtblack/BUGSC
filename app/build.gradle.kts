plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.euedrc.bugsc"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.euedrc.bugsc"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release 签名: 凭证来自 ~/.gradle/gradle.properties (不进仓库)
    // 缺失任何一项时 release 走默认 debug 签名 (本地调试不阻塞)
    signingConfigs {
        val storePathProp = providers.gradleProperty("BUGSC_STORE_FILE").orNull
        if (storePathProp != null && file(storePathProp).exists()) {
            create("release") {
                storeFile = file(storePathProp)
                storePassword = providers.gradleProperty("BUGSC_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("BUGSC_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("BUGSC_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "bug公民-${buildType.name}-v${defaultConfig.versionName}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Compose (C 方案外壳 + 底部栏动效)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    // 单元测试使用真实的 org.json(Android 自带的是空桩,会抛 "not mocked")
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
