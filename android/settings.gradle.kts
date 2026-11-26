pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Appbase"
include(":core", ":ui")
if (providers.gradleProperty("withDemo").orNull == "true") {
    include(":appbaseTest")
    project(":appbaseTest").projectDir = file("../examples/appbaseTest/android")
}
