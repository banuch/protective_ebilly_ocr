import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.protective.ebillyocr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.protective.ebillyocr"
        minSdk = 24
        targetSdk = 35
        versionCode = getVersionCode()
        versionName = getVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding= true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation (libs.tensorflow.lite)
    implementation (libs.tensorflow.lite.support)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}


fun getVersionCode(): Int {
    val versionFile = File("version.properties")

    // Create the file if it doesn't exist and initialize with default versions
    if (!versionFile.exists()) {
        versionFile.createNewFile()
        versionFile.writeText("MAJOR_VERSION=1\nMINOR_VERSION=0")
    }

    // Load properties from the file
    val properties = Properties()
    versionFile.inputStream().use { properties.load(it) }

    // Read the current major and minor version
    var majorVersion = properties.getProperty("MAJOR_VERSION").toInt()
    var minorVersion = properties.getProperty("MINOR_VERSION").toInt()

    // Increment minor version and check if it needs to roll over
    if (minorVersion < 10) {
        minorVersion += 1
    } else {
        minorVersion = 0
        majorVersion += 1
    }

    // Combine major and minor versions to create a unique versionCode
    val versionCode = majorVersion * 100 + minorVersion

    // Update properties in the file
    properties.setProperty("MAJOR_VERSION", majorVersion.toString())
    properties.setProperty("MINOR_VERSION", minorVersion.toString())
    versionFile.outputStream().use { properties.store(it, null) }

    return versionCode
}


fun getVersionName(): String {
    val versionFile = File("version.properties")
    val properties = Properties()
    versionFile.inputStream().use { properties.load(it) }

    // Retrieve the major and minor versions to construct version name
    val majorVersion = properties.getProperty("MAJOR_VERSION")
    val minorVersion = properties.getProperty("MINOR_VERSION")

    return "$majorVersion.$minorVersion"
}
