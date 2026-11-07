# Egg Business Manager — release R8 rules.

# Strip verbose/debug/info logging from release builds (Constitution IV: no debug logs in release).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# kotlinx.serialization (type-safe navigation routes).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.eggbusinessmanager.**$$serializer { *; }
