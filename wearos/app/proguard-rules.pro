# kotlinx.serialization generates serializers that are only referenced reflectively
# through the companion object of each @Serializable class.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class de.akaflieg_freiburg.enroute.wear.data.dto.** {
    *** Companion;
}
-keepclasseswithmembers class de.akaflieg_freiburg.enroute.wear.data.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
