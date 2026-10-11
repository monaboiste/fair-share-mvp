import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

abstract class AutomaticModule implements TransformAction<TransformParameters.None> {
    @InputArtifact
    abstract Provider<FileSystemLocation> getInputArtifact()

    @Override
    void transform(TransformOutputs outputs) {
        File input = inputArtifact.get().asFile
        if (!input.name.startsWith("flyway-core-")) {
            outputs.file(input)
            return
        }
        File output = outputs.file(input.name)
        new JarFile(input).withCloseable { jar ->
            Manifest manifest = new Manifest(jar.manifest)
            manifest.mainAttributes.putValue("Automatic-Module-Name", "org.flywaydb.core")
            new JarOutputStream(new FileOutputStream(output), manifest).withCloseable { stream ->
                jar.entries().each { entry ->
                    if (entry.name != "META-INF/MANIFEST.MF") {
                        stream.putNextEntry(entry)
                        jar.getInputStream(entry).withCloseable { source -> source.transferTo(stream) }
                        stream.closeEntry()
                    }
                }
            }
        }
    }
}
