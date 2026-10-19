plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.opensecurity.customtts"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles += file("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    // JSON 编解码边界（服务端音色目录解析/参数序列化——模块其余部分为纯 Kotlin）
    implementation(libs.gson)
    testImplementation("junit:junit:4.13.2")
}
