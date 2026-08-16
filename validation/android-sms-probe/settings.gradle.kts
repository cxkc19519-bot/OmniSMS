pluginManagement {
    repositories {
        // Google 官方 CDN 入口，解决当前网络对 dl.google.com 的间歇性无响应。
        maven("https://redirector.gvt1.com/edgedl/android/maven2") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://redirector.gvt1.com/edgedl/android/maven2") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "OmniSmsProbe"
include(":app")
