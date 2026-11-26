plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.fanhuatv"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.fanhuatv"
        minSdk = 24
        // 对齐官方 FongMi/TVBox 壳（targetSdk=28）：Android 10(API 29) 起禁止执行应用
        // 私有目录内的二进制（W^X 限制），而 jar 的 Go 引擎 pvideo→5266 正是下载到
        // filesDir/Pizazz 后直接 exec。targetSdk≥29 时 exec 被系统拒绝 → 配置中心永远
        // 「Go 离线」→ 网盘/弹幕/直播全废。降到 28 与参考壳行为一致，启动链恢复。
        targetSdk = 28
        versionCode = 69
        versionName = "1.1.69"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("boolean", "SIGNATURE_CHECK_ENABLED", "false")
        buildConfigField("String", "EXPECTED_SIGNATURE_SHA256", "\"\"")
        buildConfigField("String", "VERSION_NAME", "\"1.1.69\"")
        buildConfigField("int", "VERSION_CODE", "69")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // 签名：与电视版共用同一把钥匙（仓库内 .github/workflows/TVBoxOSC.jks，密码 TVBoxOSC），
    // CI 打包前把它拷到本工程根目录即可出可安装的 release 包；本地没这个文件时保持原样不签
    signingConfigs.maybeCreate("release").apply {
        storeFile = rootProject.file("TVBoxOSC.jks")
        storePassword = "TVBoxOSC"
        keyAlias = "TVBoxOSC"
        keyPassword = "TVBoxOSC"
    }

    buildTypes {
        release {
            // R8 先关掉：这版开了之后 jar/py 全部站点分类都不出了（混淆裁/改了反射加载的类），
            // 功能优先，恢复可用；规则留档，后续逐项验证后再开
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.getByName("release").storeFile?.exists() == true) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // targetSdk=28 是刻意的（私有目录 exec Go 引擎需要），Google Play 的
    // ExpiredTargetSdkVersion 提示不适用本项目，release 打包不再跑 vital lint
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)

    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
}
