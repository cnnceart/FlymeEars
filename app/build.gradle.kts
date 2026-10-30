plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.cnnceart.flymeears"
    compileSdk = 37

    defaultConfig {
        // 可用 -PappId=... 覆盖，便于在 LSPosed 未授权新包名时做兼容测试。
        applicationId = (project.findProperty("appId") as String?) ?: "io.github.cnnceart.flymeears"
        minSdk = 31
        targetSdk = 36
        versionCode = 10000
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        // libxposed entry list and module.prop must survive resource merging.
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    buildTypes {
        getByName("release") {
            // LSPosed 模块没有单独的发布密钥，用 debug 签名保证可直接安装升级。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Provided by LSPosed at runtime.
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
