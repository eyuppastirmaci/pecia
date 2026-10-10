pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Provisions JDK 21 only when no local installation satisfies the toolchain. The version catalog is not
    // available in this block, so the version is written here.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pecia"

include("pecia-core", "pecia-cli")
