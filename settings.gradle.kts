pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
rootProject.name = "vrcode"
// Use the local xmux embedding API. Override for other checkout locations.
includeBuild(providers.gradleProperty("xmuxDir").getOrElse("../../leonsilicon/xmux"))
include(":app")
include(":settings-ui")
include(":connection")

