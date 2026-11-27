# The modern module entry is instantiated by the framework.
-keep class org.matrix.chromext.MainHook { public <init>(); }
-dontwarn io.github.libxposed.annotation.**
-keepattributes SourceFile,LineNumberTable
