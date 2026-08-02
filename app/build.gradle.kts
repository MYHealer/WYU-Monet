plugins {
    id("com.android.application")
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.wps.enhancer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wps.enhancer"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "4.0.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
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
