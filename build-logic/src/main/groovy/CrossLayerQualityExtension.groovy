import org.gradle.api.provider.ListProperty

abstract class CrossLayerQualityExtension {
    abstract ListProperty<String> getParticipants()
}
