import java.util.Properties

plugins {
    id("com.android.application")
}

// Version comes from the git tag in CI (APP_VERSION_NAME=v1.2.3). Local builds default to 0.0.1.
val appVersionName: String = (System.getenv("APP_VERSION_NAME") ?: "0.0.1").removePrefix("v")
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

// Signing: env vars (CI) or keystore/keystore.properties (local). Both are git-ignored.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val ksFile = signingValue("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }

android {
    namespace = "xyz.samukis.hyperoscompat"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.samukis.hyperoscompat"
        minSdk = 35
        targetSdk = 36
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
        resourceConfigurations += listOf("en", "es")
    }

    signingConfigs {
        create("release") {
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile != null && ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("io.github.d4viddf:hyperisland_kit:0.4.5")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
