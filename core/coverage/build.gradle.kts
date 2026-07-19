plugins { alias(libs.plugins.kotlin.jvm) }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    api(project(":core:geo"))
    api(libs.jts.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
