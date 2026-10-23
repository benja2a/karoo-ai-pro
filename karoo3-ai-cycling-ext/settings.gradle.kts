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
        // Official Hammerhead karoo-ext repository on GitHub Packages
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("USERNAME") ?: "").get()
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("TOKEN") ?: "").get()
            }
        }
    }
}

rootProject.name = "karoo3-ai-cycling-ext"
include(":app")
