import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class ConventionPluginsSpec extends Specification {
    @TempDir
    File projectDirectory

    def "Java libraries receive language settings without runtime or test dependencies"() {
        given:
        conventionProject()
        buildFile("fairshare.java.library-conventions", """
            assert plugins.hasPlugin("java-library")
            assert project.group == "com.github.monaboiste.fairshare"
            assert project.version == "0.1.0"
            assert java.toolchain.languageVersion.get().asInt() == 25
            assert configurations.compileOnly.dependencies.collect { it.name } == ["jspecify"]
            assert configurations.implementation.dependencies.empty
            assert configurations.runtimeOnly.dependencies.empty
            assert configurations.testImplementation.dependencies.empty
            assert tasks.compileJava.options.compilerArgs.containsAll([
                "-parameters", "-Xlint:unchecked", "-Xlint:deprecation"
            ])
        """)

        when:
        def result = runner("verifyConvention").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
    }

    def "libraries add testing and logging only through explicit composition"() {
        given:
        conventionProject()
        buildFile([
                "fairshare.java.library-conventions",
                "fairshare.testing.conventions",
                "fairshare.observability.logging-conventions"
        ], """
            assert plugins.hasPlugin("groovy")
            assert !plugins.hasPlugin("org.springframework.boot")
            assert configurations.implementation.dependencies.collect { it.name } == ["slf4j-api"]
            assert configurations.runtimeOnly.dependencies.collect { it.name }.sort() == [
                "logback-classic", "logstash-logback-encoder"
            ]
            assert configurations.testImplementation.dependencies.collect { it.name }.sort() == [
                "groovy", "junit-bom", "spock-bom", "spock-core"
            ]
            assert configurations.testRuntimeOnly.dependencies.collect { it.name } == ["junit-platform-launcher"]
            assert tasks.test.options instanceof org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
            assert tasks.test.testLogging.showStandardStreams
            assert tasks.compileTestGroovy.groovyOptions.javaAnnotationProcessing
        """)

        when:
        def result = runner("verifyConvention").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
    }

    def "libraries exclude inherited logging backends only from their production runtime"() {
        given:
        conventionProject()
        buildFile("fairshare.observability.backend-free-conventions", """
            assert configurations.runtimeClasspath.excludeRules.collect { [it.group, it.module] }.toSet() == [
                ["net.logstash.logback", "logstash-logback-encoder"], ["ch.qos.logback", null]
            ] as Set
            assert configurations.compileClasspath.excludeRules.empty
            assert configurations.testRuntimeClasspath.excludeRules.empty
            assert configurations.runtimeOnly.dependencies.empty
        """)

        when:
        def result = runner("verifyConvention").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
    }

    def "Spring adapters receive technical dependencies without an executable artifact"() {
        given:
        conventionProject()
        buildFile("fairshare.spring.adapter-conventions", """
            assert plugins.hasPlugin("java-library")
            assert !plugins.hasPlugin("org.springframework.boot")
            assert configurations.implementation.dependencies.collect { it.name }.sort() == [
                "jackson-annotations", "jackson-core", "jackson-databind", "slf4j-api"
            ]
            assert configurations.api.dependencies.collect { it.name }.sort() == [
                "spring-context", "spring-jdbc", "spring-tx"
            ]
            assert configurations.runtimeOnly.dependencies.empty
            assert tasks.findByName("bootJar") == null
        """)

        when:
        def result = runner("verifyConvention").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
    }

    def "Spring applications keep library dependencies and executable artifact settings"() {
        given:
        conventionProject()
        buildFile("fairshare.spring.application-conventions", """
            assert plugins.hasPlugin("java-library")
            assert !plugins.hasPlugin("application")
            assert !plugins.hasPlugin("groovy")
            assert springBoot.mainClass.get() ==
                "com.github.monaboiste.fairshare.settlement.application.FairShareApplication"
            assert configurations.implementation.dependencies.collect { it.name }.sort() == [
                "spring-beans", "spring-boot", "spring-boot-autoconfigure", "spring-context",
                "spring-core", "spring-jdbc", "spring-tx"
            ]
            assert configurations.runtimeOnly.dependencies.collect { it.name } == ["postgresql"]
            assert configurations.api != null
            assert sourceSets.test.resources.srcDirs.contains(file("scripts/postgres"))
            assert tasks.test.taskDependencies.getDependencies(tasks.test)*.name.contains("bootJar")
            assert tasks.bootJar.enabled
            assert tasks.bootJar.archiveClassifier.get() == ""
            assert tasks.jar.enabled
            assert tasks.jar.archiveClassifier.get() == "plain"
        """)

        when:
        def result = runner("verifyConvention").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
    }

    def "coverage joins check without mutation testing or scans"() {
        given:
        conventionProject()
        buildFile([
                "fairshare.java.library-conventions",
                "fairshare.quality.coverage-conventions"
        ], """
            assert plugins.hasPlugin("jacoco")
            assert !plugins.hasPlugin("info.solidsoft.pitest")
            assert !plugins.hasPlugin("org.jetbrains.qodana")
            assert jacoco.toolVersion == "0.8.15"
            assert tasks.jacocoTestReport.reports.xml.required.get()
            assert tasks.jacocoTestReport.reports.html.required.get()
            assert !tasks.jacocoTestReport.reports.csv.required.get()
            assert tasks.jacocoTestReport.taskDependencies.getDependencies(tasks.jacocoTestReport)*.name.contains("test")
            assert tasks.coverageCheck.taskDependencies.getDependencies(tasks.coverageCheck)*.name == ["jacocoTestReport"]
            assert tasks.check.taskDependencies.getDependencies(tasks.check)*.name.contains("coverageCheck")
        """)

        when:
        def result = runner("verifyConvention").build()
        def check = runner("check", "--dry-run").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
        check.output.contains(":coverageCheck SKIPPED")
        !check.output.contains(":pitest")
        !check.output.contains(":qodanaScan")
    }

    def "mutation testing stays separate from coverage and check"() {
        given:
        conventionProject()
        buildFile([
                "fairshare.java.library-conventions",
                "fairshare.quality.mutation-testing-conventions"
        ], """
            assert plugins.hasPlugin("info.solidsoft.pitest")
            assert !plugins.hasPlugin("jacoco")
            assert pitest.pitestVersion.get() == "1.30.0"
            assert pitest.junit5PluginVersion.get() == "1.0.0"
            assert pitest.threads.get() == 4
            assert pitest.outputFormats.get() == ["HTML", "XML"] as Set
            assert !pitest.timestampedReports.get()
            assert tasks.mutationCheck.taskDependencies.getDependencies(tasks.mutationCheck)*.name == ["pitest"]
        """)

        when:
        def result = runner("verifyConvention").build()
        def check = runner("check", "--dry-run").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
        !check.output.contains(":pitest")
        !check.output.contains(":mutationCheck")
        !check.output.contains(":jacocoTestReport")
    }

    def "module quality composes format checks static analysis coverage mutation and dependency analysis"() {
        given:
        conventionProject()
        buildFile([
                "fairshare.java.library-conventions",
                "fairshare.testing.conventions",
                "fairshare.quality.conventions"
        ], """
            assert plugins.hasPlugin("com.diffplug.spotless")
            assert plugins.hasPlugin("net.ltgt.errorprone")
            assert plugins.hasPlugin("jacoco")
            assert plugins.hasPlugin("info.solidsoft.pitest")
            assert plugins.hasPlugin("com.autonomousapps.dependency-analysis")
            assert !plugins.hasPlugin("org.jetbrains.qodana")
            assert configurations.errorprone.dependencies.collect { it.name } == ["error_prone_core"]
            assert tasks.compileJava.options.errorprone.disableWarningsInGeneratedCode.get()
            assert tasks.format.taskDependencies.getDependencies(tasks.format)*.name == ["spotlessApply"]
            assert tasks.formatCheck.taskDependencies.getDependencies(tasks.formatCheck)*.name == ["spotlessCheck"]
            assert tasks.check.taskDependencies.getDependencies(tasks.check)*.name.sort() == [
                "coverageCheck", "spotlessCheck", "test"
            ]
            assert tasks.dependencyAnalysis.taskDependencies.getDependencies(tasks.dependencyAnalysis)*.name == ["buildHealth"]
        """)

        when:
        def result = runner("verifyConvention").build()
        def check = runner("check", "--dry-run").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
        check.output.contains(":spotlessJavaCheck SKIPPED")
        check.output.contains(":spotlessGroovyCheck SKIPPED")
        check.output.contains(":coverageCheck SKIPPED")
        !check.output.contains(":mutationCheck")
        !check.output.contains(":pitest")
        !check.output.contains(":qodanaScan")
    }

    def "layer reports own their classes and use all scenario tests"() {
        given:
        conventionProject()
        new File(projectDirectory, "settings.gradle").append("\ninclude(\"domain\", \"application\")\n")
        ["domain", "application"].each { name ->
            def directory = new File(projectDirectory, name)
            directory.mkdirs()
            new File(directory, "build.gradle").text = """
                plugins {
                    id("fairshare.java.library-conventions")
                    id("fairshare.testing.conventions")
                    id("fairshare.quality.conventions")
                }
            """
        }
        buildFile([
                "fairshare.java.library-conventions",
                "fairshare.testing.conventions",
                "fairshare.quality.conventions",
                "fairshare.quality.cross-layer-conventions"
        ], """
            allprojects.each { member ->
                assert member.pitest.testSourceSets.get()*.name == ["test", "test", "test"]
                assert member.pitest.mainSourceSets.get() == [member.sourceSets.main] as Set
                assert member.tasks.pitest.enabled
                assert member.pitest.jvmArgs.get().contains("-Dscenario.fixture=known")
                assert member.tasks.jacocoTestReport.classDirectories.files == member.sourceSets.main.output.files
                assert member.tasks.jacocoTestReport.executionData.files == allprojects.collect {
                    it.layout.buildDirectory.file("jacoco/test.exec").get().asFile
                } as Set
                assert member.tasks.jacocoTestReport.taskDependencies.getDependencies(member.tasks.jacocoTestReport)
                    .findAll { it.name == "test" }*.path.toSet() == [":test", ":domain:test", ":application:test"] as Set
            }
        """)
        new File(projectDirectory, "build.gradle").append("""
            tasks.named("test") {
                systemProperty("scenario.fixture", "known")
            }
            crossLayerQuality {
                participants = [":", ":domain", ":application"]
            }
        """)

        when:
        def result = runner("verifyConvention").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
    }

    def "module path smoke checks patch a named module without public test exports"() {
        given:
        conventionProject()
        buildFile("fairshare.testing.module-path-conventions", """
            assert tasks.check.taskDependencies.getDependencies(tasks.check)*.name.contains("modulePathSmoke")
            assert sourceSets*.name == ["main", "test"]
        """)
        new File(projectDirectory, "build.gradle").append("""
            modulePathSmoke {
                moduleName = "smoke.consumer"
                mainClass = "smoke.Main"
                fixture = layout.projectDirectory.file("fixture.txt")
            }
        """)
        def main = new File(projectDirectory, "src/main/java")
        main.mkdirs()
        new File(main, "module-info.java").text = "module smoke.consumer {}"
        def smoke = new File(projectDirectory, "src/modulePathSmoke/java/smoke")
        smoke.mkdirs()
        new File(smoke, "Main.java").text = """
            package smoke;
            public class Main {
                public static void main(String[] arguments) throws Exception {
                    System.out.println(java.nio.file.Files.readString(java.nio.file.Path.of(arguments[0])));
                }
            }
        """
        new File(projectDirectory, "fixture.txt").text = "Module path fixture"

        when:
        def convention = runner("verifyConvention").build()
        def result = runner("modulePathSmoke", "--configuration-cache").build()
        def repeated = runner("modulePathSmoke", "--configuration-cache").build()

        then:
        convention.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
        result.task(":modulePathSmoke").outcome == TaskOutcome.SUCCESS
        result.output.contains("Module path fixture")
        repeated.output.contains("Configuration cache entry reused")
    }

    def "source-free parents aggregate checks without Java or executable artifacts"() {
        given:
        conventionProject()
        new File(projectDirectory, "settings.gradle").append("\ninclude(\"domain\", \"bootstrap\")\n")
        ["domain", "bootstrap"].each { name ->
            def directory = new File(projectDirectory, name)
            directory.mkdirs()
            new File(directory, "build.gradle").text = """
                plugins { id("base") }
                ["test", "format", "formatCheck", "coverageCheck", "mutationCheck", "dependencyAnalysis"].each { name ->
                    tasks.register(name)
                }
            """
        }
        buildFile("fairshare.lifecycle.aggregation-conventions", """
            assert !plugins.hasPlugin("java")
            assert !plugins.hasPlugin("org.springframework.boot")
            assert tasks.findByName("jar") == null
            assert tasks.findByName("bootJar") == null
            assert tasks.findByName("bootRun") == null
            ["assemble", "check", "clean", "test", "format", "formatCheck", "coverageCheck", "mutationCheck", "dependencyAnalysis"].each { name ->
                def task = tasks.named(name).get()
                assert task.dependsOn.flatten().findAll { it instanceof CharSequence }*.toString().toSet() == [":domain:" + name, ":bootstrap:" + name] as Set
            }
        """)

        when:
        def result = runner("verifyConvention").build()
        def repeated = runner("check", "--configuration-cache").build()

        then:
        result.task(":verifyConvention").outcome == TaskOutcome.SUCCESS
        repeated.task(":domain:check").outcome == TaskOutcome.UP_TO_DATE
        repeated.task(":bootstrap:check").outcome == TaskOutcome.UP_TO_DATE
    }

    def "root quality plugin #pluginId keeps its tasks and rejects module placement"() {
        given:
        conventionProject()
        buildFile(pluginId, rootConditions)

        when:
        def rootResult = runner("verifyConvention").build()

        then:
        rootResult.task(":verifyConvention").outcome == TaskOutcome.SUCCESS

        when:
        new File(projectDirectory, "settings.gradle").append("\ninclude(\"module\")\n")
        def moduleDirectory = new File(projectDirectory, "module")
        moduleDirectory.mkdirs()
        new File(moduleDirectory, "build.gradle").text = "plugins { id(\"${pluginId}\") }"
        def moduleResult = runner("help").buildAndFail()

        then:
        moduleResult.output.contains("'${pluginId}' must only be applied to the root project")

        where:
        pluginId | rootConditions
        "fairshare.quality.scan-conventions" | """
            assert tasks.scan.taskDependencies.getDependencies(tasks.scan)*.name == ["qodanaScan"]
            assert qodana.projectPath.get() == projectDir.absolutePath
            assert qodana.resultsPath.get() == layout.buildDirectory.dir("qodana/results").get().asFile.absolutePath
            assert qodana.cachePath.get().endsWith("caches/qodana/convention-consumer")
            assert tasks.qodanaScan.arguments.get() == ["--linter", "qodana-jvm-community", "--within-docker", "false"]
            assert tasks.findByName("check") == null
        """
        "fairshare.quality.markdown-conventions" | """
            assert tasks.format.taskDependencies.getDependencies(tasks.format)*.name.contains("formatMarkdown")
            assert tasks.formatCheck.taskDependencies.getDependencies(tasks.formatCheck)*.name.contains("formatMarkdownCheck")
            assert tasks.formatMarkdown.commandLine == [
                "rumdl", "fmt", "--config", file("build-logic/config/rumdl.toml").absolutePath, "."
            ]
            assert tasks.formatMarkdownCheck.commandLine == [
                "rumdl", "check", "--config", file("build-logic/config/rumdl.toml").absolutePath, "."
            ]
            assert tasks.formatMarkdown.workingDir == projectDir
            assert tasks.formatMarkdownCheck.workingDir == projectDir
        """
    }

    private void conventionProject() {
        new File(projectDirectory, "settings.gradle").text = """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs {
                    libs { from(files("catalog.toml")) }
                }
            }
            rootProject.name = "convention-consumer"
        """
        new File(projectDirectory, "catalog.toml").text = new File(System.getProperty("catalogPath")).text
        new File(projectDirectory, "gradle.properties").text = """
            project.group=com.github.monaboiste.fairshare
            project.version=0.1.0
        """
    }

    private void buildFile(String pluginId, String conditions) {
        buildFile([pluginId], conditions)
    }

    private void buildFile(List<String> pluginIds, String conditions) {
        new File(projectDirectory, "build.gradle").text = """
            plugins {
                ${pluginIds.collect { "id(\"${it}\")" }.join("\n") }
            }
            tasks.register("verifyConvention") {
                doLast {
                    ${conditions}
                }
            }
        """
    }

    private GradleRunner runner(String... tasks) {
        GradleRunner.create()
                .withProjectDir(projectDirectory)
                .withPluginClasspath()
                .withArguments(tasks.toList() + ["--stacktrace"])
    }
}
