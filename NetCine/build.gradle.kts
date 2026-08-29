// Minimal per-plugin build file - parent build.gradle.kts applies all config
// Keep empty to inherit subprojects { } block from root.
// Runner para teste live do parser de vídeo em Kotlin (search/load/loadLinks)
tasks.register<JavaExec>("runTestNetCine") {
    group = "verification"
    description = "Roda TestNetCine.kt (search/load/loadLinks) com callbacks de ExtractorLink"
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("TestNetCineKt")
}
