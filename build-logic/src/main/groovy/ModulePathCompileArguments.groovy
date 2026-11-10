import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

abstract class ModulePathCompileArguments implements CommandLineArgumentProvider {
    @Classpath
    abstract ConfigurableFileCollection getModulePath()

    @Input
    abstract Property<String> getModuleName()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getPatchSources()

    @Override
    Iterable<String> asArguments() {
        ["--module-path", modulePath.asPath, "--patch-module", "${moduleName.get()}=${patchSources.asPath}".toString()]
    }
}
