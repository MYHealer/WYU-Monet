plugins {
    id("com.android.application")
    alias(libs.plugins.composeCompiler)
}

// 读取签名配置（key.properties 已 git 忽略，勿提交密钥）
import java.util.Properties
val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.wps.enhancer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wps.enhancer"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "5.0.6"
    }

    signingConfigs {
        if (keyProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keyProps["storeFile"] as String)
                storePassword = keyProps["storePassword"] as String
                keyAlias = keyProps["keyAlias"] as String
                keyPassword = keyProps["keyPassword"] as String
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keyProps.isNotEmpty()) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // libxposed stubs - compileOnly, 不打包进APK
    compileOnly(project(":libxposed-stubs"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    // DexMaker: 运行时动态生成子类（用于 OkHttp WebSocketListener）
    implementation(files("libs/dexmaker-2.28.6.jar"))

    // Miuix (本地模块)
    implementation(project(":miuix-ui"))
    implementation(project(":miuix-preference"))
}
