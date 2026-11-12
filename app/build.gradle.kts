plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.audiostats"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.audiostats"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "1.32"
        testInstrumentationRunner = "com.audiostats.VerificationInstrumentation"
    }

    signingConfigs {
        // 固定签名文件：签名变了就只能卸载重装，会丢失本机数据
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 个人使用：直接用 debug 签名，省去配置 keystore
            signingConfig = signingConfigs.getByName("debug")
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
    }
    testOptions {
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
            System.getProperty("robolectric.dependency.repo.url")?.let { url ->
                it.systemProperty("robolectric.dependency.repo.url", url)
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// 每次打包后另存一份带版本号的 APK 到项目 apk/ 目录，方便找到和发到手机
val copyApk by tasks.registering(Copy::class) {
    val version = android.defaultConfig.versionName
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "音频播放统计_v$version.apk" }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(copyApk) }
