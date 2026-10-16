plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.agroguide.rtk.device"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The 4 h pipeline soak test keeps 216k NMEA events in memory; the default
    // worker heap is not enough when it runs alongside a full build.
    testOptions { unitTests.all { it.maxHeapSize = "1g" } }
}
dependencies {
    api(project(":core:nmea"))
    api(project(":core:geo"))
    api(project(":core:ntrip"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
