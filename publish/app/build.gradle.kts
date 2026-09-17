import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.bilimonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bilimonitor"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0622.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    signingConfigs {
        create("release") {
            // 签名材料不入库：通过 keystore.properties（已被 .gitignore 忽略）
            // 或环境变量注入。缺失时 release 保持未签名，assembleDebug 不受影响。
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties().apply {
                if (propsFile.exists()) propsFile.inputStream().use { load(it) }
            }
            val path = props.getProperty("storeFile") ?: System.getenv("BILIMONITOR_STORE_FILE")
            val pwd = props.getProperty("storePassword") ?: System.getenv("BILIMONITOR_STORE_PASSWORD")
            val aliasValue = props.getProperty("keyAlias") ?: System.getenv("BILIMONITOR_KEY_ALIAS")
            val keyPwd = props.getProperty("keyPassword") ?: System.getenv("BILIMONITOR_KEY_PASSWORD")
            if (path != null && pwd != null && aliasValue != null && keyPwd != null) {
                storeFile = file(path)
                storePassword = pwd
                keyAlias = aliasValue
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            // 混淆 + 资源压缩：debug 包 64.7MB 对国内分发渠道偏大。
            // proguard-rules.pro 已为 Retrofit / serialization / Room / Hilt 配置规则。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 未配置签名材料时保持未签名（不影响 assembleDebug）
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // AGP 8 起 BuildConfig 默认不生成；HTTP 调试日志需要在 debug 构建里判断 BuildConfig.DEBUG
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // 纯 JVM 单测会加载少量来自 android.jar 的类型（Room 注解、枚举等），
            // 没有真实实现时返回默认值即可，避免 "not mocked" 直接失败。
            isReturnDefaultValues = true
        }
    }
    lint {
        // release 构建触发 lintVital（致命问题检查）。
        // 台账 H19-2.10：这里原先把 release 检查也关了，等于把"缺少权限声明、
        // PendingIntent 可变标志、NewApi 调用"这类致命问题永久放行。
        // 现在恢复 release 检查（有致命问题就让构建失败），但保留 abortOnError=false
        // 以免历史遗留的大量 warning 阻断日常 assembleDebug。
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    implementation(libs.zxing.core)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // instrumented 测试：需要真实设备/模拟器（DAO、事务、唯一索引等门槛无法在 JVM 上验证）
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
