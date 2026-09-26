import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("com.github.jmongard.git-semver-plugin") version "0.19.5"
}

// 버전은 git 태그(vX.Y.Z)와 그 뒤의 Conventional Commits 로 계산한다(fix: 는 patch, feat: 는 minor, ! / BREAKING CHANGE 는 major).
// 릴리스는 ./gradlew releaseVersion 으로 "release: vX.Y.Z" 커밋과 vX.Y.Z 태그를 만든다. 그 사이의 빌드는 -SNAPSHOT 이 붙는다.
semver {
    releaseTagNameFormat = "v%s"
}

group = providers.gradleProperty("pluginGroup").get()
version = semver.version

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pluginVerifier()
    }
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

/**
 * Marketplace 의 change-notes. 직전 릴리스 태그 이후의 Conventional Commits 중 feat/fix/perf 를 모은다.
 * HEAD^ 기준으로 태그를 찾으므로, 릴리스 커밋(태그가 달린 HEAD)에서 빌드해도 그 릴리스의 변경이 나온다.
 */
val gitChangeNotes: Provider<String> = run {
    val releaseVersion = version.toString()
    val previousTag = providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*", "HEAD^")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
    val subjects = previousTag.flatMap { tag ->
        providers.exec {
            commandLine(listOf("git", "log", "--format=%s") + if (tag.isEmpty()) listOf("HEAD") else listOf("$tag..HEAD"))
        }.standardOutput.asText
    }
    subjects.map { log ->
        val sections = linkedMapOf("feat" to "New", "fix" to "Fixed", "perf" to "Performance")
        val pattern = Regex("""^(\w+)(?:\([^)]*\))?!?:\s*(.+)$""")
        val byType = log.lines().mapNotNull { pattern.find(it.trim()) }
            .groupBy({ it.groupValues[1] }, { it.groupValues[2].replaceFirstChar(Char::uppercaseChar) })
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = sections.mapNotNull { (type, title) ->
            byType[type]?.let { items -> "<b>$title</b><ul>${items.joinToString("") { "<li>${escape(it)}</li>" }}</ul>" }
        }.joinToString("")
        "<b>$releaseVersion</b><br/>" + body.ifEmpty { "<ul><li>Maintenance release.</li></ul>" }
    }
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = gitChangeNotes
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // 상한은 두지 않는다. 새 IDE 버전과의 호환은 verifyPlugin 으로 확인한다.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        // 내부 API 사용은 DeclarativeHint.kt 에 모아 둔 의도된 것이라(README 참고) 실패로 치지 않고 리포트로만 본다.
        // 호환성 문제, 누락된 의존성 등은 계속 실패로 잡는다.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NOT_DYNAMIC,
        )
        ides {
            // 지원하는 최소 버전(sinceBuild)과 같은 IntelliJ IDEA Ultimate 로 검증한다.
            create("IU", providers.gradleProperty("platformVersion"))
        }
    }

    publishing {
        // 업로드용 토큰은 환경 변수 PUBLISH_TOKEN 으로만 받는다(코드/저장소에 두지 않는다).
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    // 테스트 컴파일이 main 의 API 변경(예: 기본값 파라미터가 붙은 생성자 변경)을 입력 변화로 감지하지 못해
    // UP-TO-DATE 로 건너뛰거나 증분 컴파일에서 빠뜨려, 옛 시그니처로 컴파일된 테스트가 NoSuchMethodError 를 냈다.
    // 테스트 코드는 작으니 항상 전체 컴파일한다.
    compileTestKotlin {
        incremental = false
        outputs.upToDateWhen { false }
    }

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
                "-Didea.log.debug.categories=#com.github.sakur35a.functioninlineviewer.perf",
                "-XX:StartFlightRecording=filename=${perfDir.absolutePath}/runIde.jfr,settings=profile,dumponexit=true",
                // 정상 종료가 아니면 runIde.jfr 이 비어 있다. 기록 조각(chunk)을 여기 남겨 `jfr assemble` 로 복구할 수 있게 한다.
                "-XX:FlightRecorderOptions=repository=${perfDir.absolutePath}/jfr-repo",
            )
        }
    }
}
