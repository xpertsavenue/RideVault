pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RideVault"
include(":app", ":core:model", ":core:device-api", ":core:ble", ":export", ":fit", ":data", ":driver:bikevee", ":driver:igpsport")
