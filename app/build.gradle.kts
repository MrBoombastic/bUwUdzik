import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 21
        versionName = "1.8.0"
        buildConfigField(
            "String",
            "WIDGET_UPDATE_ACTION",
            "\"com.mrboombastic.buwudzik.ACTION_UPDATE_WIDGET\""
        )
        manifestPlaceholders["widgetUpdateAction"] =
            "com.mrboombastic.buwudzik.ACTION_UPDATE_WIDGET"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("stable") {
            dimension = "distribution"
            isDefault = true
        }
        create("beta") {
            dimension = "distribution"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            buildConfigField(
                "String",
                "WIDGET_UPDATE_ACTION",
                "\"com.mrboombastic.buwudzik.beta.ACTION_UPDATE_WIDGET\""
            )
            manifestPlaceholders["widgetUpdateAction"] =
                "com.mrboombastic.buwudzik.beta.ACTION_UPDATE_WIDGET"
        }
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.zxing.android.embedded)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.guava.android)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.ui.tooling)
}