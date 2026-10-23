# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# The Xposed entry class is referenced only by META-INF/xposed/java_init.list,
# which R8 cannot see, so keep it (and its no-arg constructor) by name.
-keep class io.github.tophtab.lspstore.LspStoreModule {
    <init>();
}
