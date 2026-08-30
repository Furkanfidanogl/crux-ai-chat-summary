plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
    alias(libs.plugins.androidx.navigation.safeargs)
}

android {
    namespace = "com.furkanfidanoglu.cruxaisummarize"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.furkanfidanoglu.cruxaisummarize"
        minSdk = 26
        targetSdk = 36
        versionCode = 117
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.fragment)
    implementation(libs.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth") // Firebase
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-config")

    // Firebase App Check (Play Integrity) - Güvenlik Kalkanı
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    implementation("com.github.bumptech.glide:glide:4.16.0") //Resim yüklemek için

    implementation("org.jsoup:jsoup:1.17.2") //HTML okumak için

    implementation("org.apache.poi:poi-ooxml:5.2.3") // Excel okumak için

    // Credential Manager + Sign in with Google (legacy GoogleSignIn SDK replacement)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation("com.google.android.play:app-update:2.1.0")

    implementation("com.android.billingclient:billing:8.0.0")

    implementation("androidx.core:core-splashscreen:1.0.1")
}
