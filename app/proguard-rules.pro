# 老乡斗地主 ProGuard 规则

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.laoxiang.ddz.**$$serializer { *; }
-keepclassmembers class com.laoxiang.ddz.** {
    *** Companion;
}
-keepclasseswithmembers class com.laoxiang.ddz.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 反射资源名（getIdentifier）不受影响，无需额外规则

# 崩溃堆栈可读
-keepattributes SourceFile,LineNumberTable
