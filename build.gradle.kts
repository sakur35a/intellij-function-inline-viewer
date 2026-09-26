import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
}

tasks {
    runIde {
        // 샌드박스 IDE가 뜨면 sample 프로젝트를 연다. -PopenProject=<경로> 로 다른 프로젝트(성능 측정용)를 열 수 있다.
        val openProject = providers.gradleProperty("openProject")
            .orElse(layout.projectDirectory.dir("sample").asFile.absolutePath)
        argumentProviders += CommandLineArgumentProvider { listOf(openProject.get()) }

        // -Pperf: 성능 측정 모드. perf 디버그 로그를 켜고 JFR(CPU 프로파일)을 build/perf/ 에 남긴다.
        if (providers.gradleProperty("perf").isPresent) {
            val perfDir = layout.buildDirectory.dir("perf").get().asFile
            perfDir.mkdirs()
            jvmArgs(
                "-Didea.log.debug.categories=#com.github.ljk0071.inlinecall.perf",
                "-XX:StartFlightRecording=filename=${perfDir.absolutePath}/runIde.jfr,settings=profile,dumponexit=true",
            )
        }
    }
}
