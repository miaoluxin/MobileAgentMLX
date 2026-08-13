plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mlx.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mlx.app"
        minSdk = 26
        // 34（非 36）：Android 15/16 对 targetSdk≥35 应用禁止 dlopen/加载 app-data 库；
        // execve 限制自 Android 10（targetSdk≥29）即存在，由 termux-exec 预加载方案绕过（见 EmbeddedEnv）
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-mvp"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 注：termux-root.tar 保持 zip 压缩（APK 156MB）；解压走流式 open()，
    // openFd 在压缩 asset 上不可用（容错已内置：进度无总长）

    // P6：每次打包把开发文档与产品介绍刷新进 assets/docs（关于页可查看）
    tasks.named("preBuild") {
        doLast {
            val docsDir = file("src/main/assets/docs")
            docsDir.mkdirs()
            copy {
                from(rootProject.file("docs/产品介绍.md"))
                into(docsDir)
                rename { "product.md" }
            }
            copy {
                from(rootProject.file("开发文档.md"))
                into(docsDir)
                rename { "dev.md" }
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
