import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}



configure<ApplicationExtension> {
    namespace = "com.mrboombastic.buwudzik"
    compileSdk {
        version = release(36)
    }

    defaultConfig.apply {
        applicationId = "com.mrboombastic.buwudzik"
        minSdk = 34
        targetSdk = 36
        versionCode = 12
        versionName = "1.4.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }

}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_24
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.slf4j.simple)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.zxing.android.embedded)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}