import com.android.build.api.dsl.ApplicationExtension
plugins {
    alias(libs.plugins.android.application)
}

configure<ApplicationExtension> {
    namespace = "com.safelogj.lim"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.safelogj.lim"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.documentfile)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.glide)
    implementation(libs.glidecompiler)
    implementation(libs.workruntime)
    coreLibraryDesugaring(libs.desugar)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}