pluginManagement {
    includeBuild("gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CloudstreamPlugins"

include(":NetCine")
include(":TopAnimes")
include(":AnimeFire")
include(":Pobreflix")
include(":RedeCanais")
include(":CineVision")