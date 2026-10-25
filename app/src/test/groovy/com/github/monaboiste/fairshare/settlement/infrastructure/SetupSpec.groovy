package com.github.monaboiste.fairshare.settlement.infrastructure

import java.nio.file.Files
import spock.lang.Specification

class SetupSpec extends Specification {
    def "explicit setup uses #command and sends one atomic schema and sample script"() {
        given:
        def directory = Files.createTempDirectory("setup-spec").toFile()
        def log = new File(directory, "arguments")
        def input = new File(directory, "input.sql")
        def docker = new File(directory, "docker")
        docker.text = "#!/bin/sh\nif [ \"\$1\" = compose ] && [ \"\$2\" = version ]; then exit ${plugin ? 0 : 1}; fi\nprintf '%s\\n' \"\$@\" > '${log}'\ncat > '${input}'\nexit ${status}\n"
        docker.setExecutable(true)
        def standalone = new File(directory, "docker-compose")
        standalone.text = "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${log}'\ncat > '${input}'\nexit ${status}\n"
        standalone.setExecutable(true)
        def process = new ProcessBuilder("bash", new File("../scripts/setup.sh").canonicalPath)
        process.environment().put("PATH", "${directory}:/usr/bin:/bin".toString())

        when:
        def running = process.redirectErrorStream(true).start()
        def output = running.inputStream.text
        def result = running.waitFor()

        then:
        result == status
        log.text.contains("exec\n-T\npostgres\n")
        log.text.contains("--single-transaction")
        log.text.contains("ON_ERROR_STOP=1")
        input.text.contains("CREATE TABLE settlement_event")
        input.text.contains("INSERT INTO settlement_event")
        !input.text.contains("TRUNCATE")
        !input.text.contains("DROP TABLE")
        output != null

        cleanup:
        directory?.deleteDir()

        where:
        command              | plugin | status
        "Compose plugin"     | true   | 0
        "standalone Compose" | false  | 0
        "failed psql"        | true   | 9
    }
}
