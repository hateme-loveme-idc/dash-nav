import java.util.Properties

plugins { id("com.android.application") }

// Release key lives outside git: ~/projects/dashnav/keys/release.properties (back it up: updates need the same key).
val releaseProps = Properties().apply {
    val f = rootProject.file("../keys/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "nav.dash"
    compileSdk = 36
    defaultConfig {
        applicationId = "nav.dash"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("release") {
            if (releaseProps.isNotEmpty()) {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }   // installs next to the release build on test phones
        release {
            // R8 off on purpose: Shizuku starts DashHelper by class name and it uses hidden-API reflection
            isMinifyEnabled = false
            signingConfig = if (releaseProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
