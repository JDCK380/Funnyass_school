plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.funnyass.test"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 独立副本使用单独的 applicationId，可与原调试版并存安装。
        applicationId = "com.funnyass.miuix"
        minSdk = 24
        targetSdk = 37
        versionCode = 1919
        versionName = "0.1919"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName.set(
                output.versionName.map { version -> "睿智校园-$version.apk" }
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
