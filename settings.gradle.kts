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
include(":core:model")
include(":core:database")
include(":core:data")

include(":system:timer")
include(":system:blocker")

include(":feature:timer")
include(":feature:planner")
include(":feature:stats")
include(":feature:subjects")
include(":feature:blocker")
include(":feature:settings")

// Added in later sprints (see docs/ARCHITECTURE.md):
// :core:network  :system:presence  :system:widgets  :feature:groups  :feature:onboarding
