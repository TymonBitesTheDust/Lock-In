pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "LockIn"

include(":app")

include(":core:timer")
include(":core:designsystem")

include(":system:timer")

include(":feature:timer")

// Added in later sprints (see docs/ARCHITECTURE.md):
// :core:model  :core:database  :core:datastore  :core:network  :core:data
// :system:blocker  :system:presence  :system:widgets
// :feature:stats  :feature:groups  :feature:subjects  :feature:blocker  :feature:onboarding
