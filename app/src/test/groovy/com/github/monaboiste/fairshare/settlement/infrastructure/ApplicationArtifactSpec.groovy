package com.github.monaboiste.fairshare.settlement.infrastructure

import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.util.Converter
import com.github.monaboiste.fairshare.common.events.EventId
import com.github.monaboiste.fairshare.common.events.PendingEvent
import com.github.monaboiste.fairshare.settlement.application.FairShareApplication
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.jar.Manifest
import java.util.zip.ZipFile
import javax.money.Monetary
import org.springframework.boot.autoconfigure.SpringBootApplication
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class ApplicationArtifactSpec extends Specification {
    def "the executable application contains no Flyway or schema SQL"() {
        given:
        def jar = new ZipFile(applicationJar())

        when:
        def entries = jar.entries().collect { it.name }

        then:
        entries.any { it.startsWith("BOOT-INF/lib/spring-boot-") }
        !entries.any { it.toLowerCase().contains("flyway") }
        !entries.any { it.endsWith(".sql") || it.contains("db/migration") }

        cleanup:
        jar?.close()
    }

    def "the executable declares the annotated application package bootstrap"() {
        given:
        def jar = new ZipFile(applicationJar())

        when:
        def entries = jar.entries().collect { it.name }
        def startClass = new Manifest(jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF"))).mainAttributes.getValue("Start-Class")

        then:
        entries.contains("BOOT-INF/classes/com/github/monaboiste/fairshare/settlement/application/FairShareApplication.class")
        !entries.contains("BOOT-INF/classes/com/github/monaboiste/fairshare/settlement/infrastructure/runtime/FairShareApplication.class")
        startClass == FairShareApplication.name
        FairShareApplication.getAnnotation(SpringBootApplication) != null

        cleanup:
        jar?.close()
    }

    def "the executable keeps every codec implementation in a top-level class"() {
        given:
        def jar = new ZipFile(applicationJar())
        def prefix = "BOOT-INF/classes/"

        when:
        def implementations = jar.entries().findAll {
            it.name.startsWith("${prefix}com/github/monaboiste/fairshare/settlement/infrastructure/codecs/") && it.name.endsWith(".class")
        }.collect {
            def name = it.name.substring(prefix.length(), it.name.length() - ".class".length()).replace("/", ".")
            Class.forName(name, false, getClass().classLoader)
        }.findAll {
            JsonSerializer.isAssignableFrom(it) || JsonDeserializer.isAssignableFrom(it) || Converter.isAssignableFrom(it)
        }

        then:
        !implementations.empty
        implementations.every { it.enclosingClass == null && !it.anonymousClass }

        cleanup:
        jar?.close()
    }

    def "the #profile application process #behavior"() {
        given:
        def database = new PostgresFixture()
        def id = SettlementId.random()
        database.store().append(id, 0, [new PendingEvent(EventId.random(), new SettlementOpened("Process recovery", Monetary.getCurrency("EUR")), Instant.EPOCH)])
        def output = Files.createTempFile("application-process", ".log").toFile()
        def process = new ProcessBuilder(
            new File(System.getProperty("java.home"), "bin/java").path, "-jar", applicationJar().path,
            "--spring.profiles.active=${profile}".toString(), "--spring.main.banner-mode=off",
            "--fairshare.database.url=${database.dataSource.url}".toString(),
            "--fairshare.database.username=${database.database.username}".toString(),
            "--fairshare.database.password=${database.database.password}".toString())
            .redirectErrorStream(true).redirectOutput(output).start()

        when:
        if (profile.contains("rebuild")) {
            assert process.waitFor(30, TimeUnit.SECONDS)
            assert process.exitValue() == 0: output.text
        } else {
            new PollingConditions(timeout: 30).eventually {
                assert output.text.contains("Started FairShareApplication")
            }
            assert !process.waitFor(500, TimeUnit.MILLISECONDS): output.text
            process.destroy()
            assert process.waitFor(15, TimeUnit.SECONDS)
        }
        def views = new PostgresSettlementProjection(database.jdbc, database.transactions, new SettlementEventCodec())

        then:
        views.checkpoint() == 1
        views.findById(id).get().name() == "Process recovery"
        views.rebuildOffline(database.store(), 1) == null

        cleanup:
        process?.destroyForcibly()
        output?.delete()
        database?.close()

        where:
        profile         | behavior
        "normal"        | "stays active until shutdown"
        "extra,rebuild" | "exits after its runner completes"
    }

    def "the annotated application leaves an unprepared database for explicit setup"() {
        given:
        def database = new PostgresFixture(false)
        def output = Files.createTempFile("unprepared-application", ".log").toFile()
        def process = new ProcessBuilder(
            new File(System.getProperty("java.home"), "bin/java").path, "-jar", applicationJar().path,
            "--spring.main.banner-mode=off",
            "--fairshare.database.url=${database.dataSource.url}".toString(),
            "--fairshare.database.username=${database.database.username}".toString(),
            "--fairshare.database.password=${database.database.password}".toString())
            .redirectErrorStream(true).redirectOutput(output).start()

        when:
        def finished = process.waitFor(30, TimeUnit.SECONDS)

        then:
        finished
        process.exitValue() != 0
        output.text.contains("event_cursor")

        when:
        def setup = database.setup()

        then:
        setup.exitCode == 0
        database.store().highWaterMark() == 6

        cleanup:
        process?.destroyForcibly()
        output?.delete()
        database?.close()
    }

    private static File applicationJar() {
        new File("build/libs").listFiles().find { it.name.endsWith(".jar") && !it.name.endsWith("-plain.jar") }
    }
}
