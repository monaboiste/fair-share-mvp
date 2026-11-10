import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

abstract class ModulePathSmokeExtension {
    abstract Property<String> getModuleName()
    abstract Property<String> getMainClass()
    abstract RegularFileProperty getFixture()
}
