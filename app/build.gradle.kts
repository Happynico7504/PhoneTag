plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Version code = CI run number, so every CI build installs over the previous one in place.
// PHONETAG_VERSION_CODE overrides it; local builds without either get 1. Android needs it to grow
// on every update, so if the workflow is ever recreated (run numbers restart at 1), raise
// PHONETAG_VERSION_CODE_OFFSET above the last published code.
val ciVersionCode: Int = (System.getenv("PHONETAG_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))
    ?.toIntOrNull()
    ?.plus(System.getenv("PHONETAG_VERSION_CODE_OFFSET")?.toIntOrNull() ?: 0)
    ?: 1
val isCiBuild = System.getenv("PHONETAG_VERSION_CODE") != null || System.getenv("GITHUB_RUN_NUMBER") != null

// Release signing from the environment (CI secrets). In-place updates also require every build
// to be signed with the same key; without these, release builds fall back to the debug key.
val keystoreFile = System.getenv("PHONETAG_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.phonetag.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonetag.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = if (isCiBuild) "0.1.$ciVersionCode" else "0.1.0-local"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("PHONETAG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PHONETAG_KEY_ALIAS")
                keyPassword = System.getenv("PHONETAG_KEY_PASSWORD") ?: System.getenv("PHONETAG_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.play.services.location)
    implementation(libs.arcore)
    implementation(libs.mlkit.pose.detection)

    testImplementation(libs.junit)
}
