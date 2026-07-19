pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AgroGuideRTK"
include(":app", ":core:geo", ":core:nmea", ":core:ntrip", ":core:coverage", ":data", ":device", ":service")
