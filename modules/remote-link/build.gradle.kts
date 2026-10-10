plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.opensecurity.remotelink"
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
    // msgpack 二进制序列化（协议帧负载; 无反射、Android 兼容）
    implementation("org.msgpack:msgpack-core:0.9.9")
    // Noise NNpsk2 原语（X25519/ChaCha20-Poly1305——Android 平台 JCA 版本不足）
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    // 本地 HTTP 网关（Legado HttpTTS 引擎对接; ~100KB 无传递依赖）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("junit:junit:4.13.2")
}

dependencies {
    testImplementation("org.json:json:20240303")
}
