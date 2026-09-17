import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 签名配置
// 在项目根目录放一个 keystore.properties 就会自动启用正式签名，
// 文件不存在时使用 debug 签名，不影响编译。
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        // ★ Properties 默认用 ISO-8859-1 读取，中文文件名的签名文件会乱码。
        //   必须显式按 UTF-8 读。
        FileInputStream(keystorePropertiesFile).use { fis ->
            load(InputStreamReader(fis, Charsets.UTF_8))
        }
    }
}

android {
    namespace = "com.wuyi.guard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wuyi.guard"   // ★ 包名
        minSdk = 28          // Android 9.0
        targetSdk = 35
        versionCode = 8      // ★ 内部版本号（整数）
        versionName = "4.1.2"  // ★ 显示版本号

        buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 输出文件名：无翼守护_v1.0-debug.apk / 无翼守护_v1.0-release.apk
// （AGP 官方方式：设置 archivesName，输出目录见 app/build/outputs/apk/<变体>/）
base {
    archivesName.set("无翼守护_v${android.defaultConfig.versionName}")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // 协程（帧处理调度）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OCR：ML Kit 中文识别（依赖 Google Play services）
    // 设备无 GMS 时会自动降级为「无障碍节点取词」，见 OcrEngine.kt 说明
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}
