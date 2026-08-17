import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun buildNumber(): String =
    project.findProperty("buildNumber")?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmm"))

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.power.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.power.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0build" + buildNumber()
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.xposed.api)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}

tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName)
    }
}
