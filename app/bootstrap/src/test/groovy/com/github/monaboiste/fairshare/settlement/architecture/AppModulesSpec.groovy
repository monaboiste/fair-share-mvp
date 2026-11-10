package com.github.monaboiste.fairshare.settlement.architecture

import java.lang.module.ModuleFinder
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile
import spock.lang.Specification

class AppModulesSpec extends Specification {
    private static final Properties ARCHITECTURE = new Properties().tap {
        new File(System.getProperty("app.architecture")).withInputStream { stream -> load(stream) }
    }
    private static final String APP = "com.github.monaboiste.fairshare.app"
    private static final String BOOTSTRAP = "${APP}.bootstrap"
    private static final String SETTLEMENT = "com.github.monaboiste.fairshare.settlement"

    def "app groups four peers without source or executable ownership"() {
        expect:
        ARCHITECTURE.getProperty("app.parent.javaPlugin") == "false"
        ARCHITECTURE.getProperty("app.parent.bootPlugin") == "false"
        ARCHITECTURE.getProperty("app.parent.artifactTasks") == ""
        !new File(ARCHITECTURE.getProperty("app.parent.sourceDirectory")).exists()
        ARCHITECTURE.getProperty("app.children").split(",").toSet() == [
                ":app:domain", ":app:application", ":app:infrastructure", ":app:bootstrap"
        ] as Set
        !modules().find(APP).present
    }

    def "bootstrap resolves its three peer layer modules"() {
        given:
        def finder = modules()

        when:
        def configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), [BOOTSTRAP])

        then:
        configuration.modules()*.name().containsAll([
                "com.github.monaboiste.fairshare.app.domain",
                "com.github.monaboiste.fairshare.app.application",
                "com.github.monaboiste.fairshare.app.infrastructure"
        ])
    }

    def "typed wiring receives qualified access without public implementation packages"() {
        given:
        def domain = modules().find("${APP}.domain").orElseThrow().descriptor()
        def application = modules().find("${APP}.application").orElseThrow().descriptor()
        def infrastructure = modules().find("${APP}.infrastructure").orElseThrow().descriptor()

        when:
        def model = domain.exports().find { it.source() == "${SETTLEMENT}.domain.model" }
        def handlers = application.exports().findAll { it.source().endsWith(".handler") }

        then:
        model.qualified
        model.targets() == [BOOTSTRAP, "${APP}.application".toString(), "${APP}.infrastructure".toString()] as Set
        handlers.size() == 2
        handlers.every { it.qualified && it.targets() == [BOOTSTRAP] as Set }
        infrastructure.exports()*.source() == ["${SETTLEMENT}.infrastructure".toString()]
        infrastructure.exports().every { it.qualified && it.targets() == [BOOTSTRAP] as Set }
        infrastructure.opens()*.source().toSet() == ["${SETTLEMENT}.infrastructure".toString(), "${SETTLEMENT}.infrastructure.codecs".toString()] as Set
        infrastructure.opens().find { it.source().endsWith(".codecs") }.targets() == ["com.fasterxml.jackson.databind"] as Set
        infrastructure.opens().find { it.source().endsWith(".infrastructure") }.targets() == ["spring.core", "spring.beans"] as Set
        infrastructure.opens().every { it.qualified }
        domain.opens().empty
        application.opens().empty
        !domain.open && !application.open && !infrastructure.open
    }

    def "each production package has one module owner"() {
        given:
        def descriptors = modules().findAll()*.descriptor().findAll { it.name().startsWith(APP) }

        when:
        def packages = descriptors.collectMany { it.packages() }

        then:
        packages.size() == packages.toSet().size()
        descriptors.find { it.name() == BOOTSTRAP }.packages() == ["${SETTLEMENT}.application".toString(), "${SETTLEMENT}.config".toString()] as Set
    }

    def "the #layer #classpath classpath excludes adapter frameworks"() {
        when:
        def artifacts = ARCHITECTURE.getProperty("app.${layer}.${classpath}Artifacts").split(File.pathSeparator)*.toLowerCase()

        then:
        !artifacts.any { it =~ /spring|jackson|jdbc|postgresql/ }

        where:
        layer         | classpath
        "domain"      | "compile"
        "domain"      | "runtime"
        "application" | "compile"
        "application" | "runtime"
    }

    def "layer project dependencies point toward domain interfaces"() {
        when:
        def dependencies = ["domain", "application", "infrastructure"].collectEntries { layer ->
            [(layer): ARCHITECTURE.getProperty("app.${layer}.projectDependencies").split(",").toSet()]
        }

        then:
        dependencies.domain == [":core:common", ":core:quantity", ":core:pricing", ":core:graphs"] as Set
        dependencies.application == [":app:domain", ":core:common", ":core:quantity", ":core:pricing"] as Set
        dependencies.infrastructure == [":app:domain", ":app:application", ":core:common", ":core:quantity", ":core:pricing"] as Set
    }

    def "the Boot artifact contains peer layers and bootstrap resources"() {
        given:
        def artifact = new ZipFile(ARCHITECTURE.getProperty("app.bootJar"))

        when:
        def entries = artifact.entries().collect { it.name }
        def manifest = new Manifest(artifact.getInputStream(artifact.getEntry("META-INF/MANIFEST.MF")))

        then:
        manifest.mainAttributes.getValue("Main-Class") == "org.springframework.boot.loader.launch.JarLauncher"
        manifest.mainAttributes.getValue("Start-Class") == "${SETTLEMENT}.application.FairShareApplication".toString()
        entries.contains("BOOT-INF/classes/application.properties")
        entries.contains("BOOT-INF/classes/com/github/monaboiste/fairshare/settlement/application/FairShareApplication.class")
        ["domain", "application", "infrastructure"].every { layer ->
            entries.count { it ==~ /BOOT-INF\/lib\/${layer}-[^\/]+\.jar/ } == 1
        }
        !entries.any { it.startsWith("BOOT-INF/classes/com/github/monaboiste/fairshare/settlement/domain/") }
        !entries.any { it.startsWith("BOOT-INF/classes/com/github/monaboiste/fairshare/settlement/infrastructure/") }

        cleanup:
        artifact.close()
    }

    private static ModuleFinder modules() {
        def paths = ARCHITECTURE.getProperty("app.modulePath").split(File.pathSeparator).collect { Path.of(it) }
        ModuleFinder.of(paths as Path[])
    }
}
