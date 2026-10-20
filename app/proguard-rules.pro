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

# 反射资源名（getIdentifier）不受影响，无需额外规则；
# 但 SoundManager 会反射枚举 R.raw 全部字段以整包预加载 ddz_/mjw_ 音效，
# 这些字段没有静态引用，R8 收缩会把它们删掉导致预加载枚举为空——必须显式保留
-keepclassmembers class **.R$raw {
    public static final *;
}

# 崩溃堆栈可读
-keepattributes SourceFile,LineNumberTable
