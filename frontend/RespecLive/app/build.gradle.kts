import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.respeclive"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.respeclive"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Gemini 키는 서버에만 둔다 (앱은 서버가 발급한 1회용 Live 토큰으로 연결)
        fun local(name: String) = "\"${localProps.getProperty(name, "")}\""
        buildConfigField("String", "API_BASE_URL", local("API_BASE_URL"))
        buildConfigField("String", "SUPABASE_URL", local("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", local("SUPABASE_ANON_KEY"))
        buildConfigField("String", "SUPABASE_DEV_EMAIL", local("SUPABASE_DEV_EMAIL"))
        buildConfigField("String", "SUPABASE_DEV_PASSWORD", local("SUPABASE_DEV_PASSWORD"))
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}