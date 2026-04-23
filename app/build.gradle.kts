import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}


configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
    beforeVariants { variant ->
        if (variant.name == "canaryRelease") {
            variant.enable = false
        }
    }
}

configure<ApplicationExtension> {
    val canaryBuild = providers.gradleProperty("CANARY_BUILD").orNull
    namespace = "com.mrboombastic.buwudzik"
    compileSdk {
        version = release(36)
    }

    defaultConfig.apply {
        applicationId = "com.mrboombastic.buwudzik"
        minSdk = 34
        targetSdk = 36
        versionCode = 32
        versionName = "1.9.0"
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
            buildConfigField("Boolean", "ALLOW_CUSTOM_UPDATES", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("Boolean", "ALLOW_CUSTOM_UPDATES", "false")
        }
        create("canary") {
            dimension = "distribution"
            applicationIdSuffix = ".canary"
            versionNameSuffix =
                if (canaryBuild.isNullOrBlank()) "-canary" else "-canary.$canaryBuild"
            buildConfigField("Boolean", "ALLOW_CUSTOM_UPDATES", "true")
            buildConfigField(
                "String",
                "WIDGET_UPDATE_ACTION",
                "\"com.mrboombastic.buwudzik.canary.ACTION_UPDATE_WIDGET\""
            )
            manifestPlaceholders["widgetUpdateAction"] =
                "com.mrboombastic.buwudzik.canary.ACTION_UPDATE_WIDGET"
        }
    }

    sourceSets {
        getByName("stable") {
            kotlin.directories.add("src/stable/java")
            kotlin.directories.add("src/full/java")
        }
        getByName("canary") {
            kotlin.directories.add("src/canary/java")
            kotlin.directories.add("src/full/java")
        }
        getByName("play") {
            kotlin.directories.add("src/play/java")
        }
    }

    buildTypes {
        debug {
            // Pretend the app is older, so update + changelog dialogs always qualify against GitHub latest.
            // Set to "" to use the real versionName from the manifest during debug.
            buildConfigField(
                "String",
                "UPDATE_CHECK_DEBUG_FAKE_VERSION",
                "\"0.0.1\""
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField(
                "String",
                "UPDATE_CHECK_DEBUG_FAKE_VERSION",
                "\"\""
            )
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
    implementation(libs.mikepenz.markdown.m3)
    debugImplementation(libs.androidx.ui.tooling)
}