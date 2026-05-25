-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep public class com.nemesis.irischat.** { public protected *; }

-keep class org.signal.** { *; }
-keep class org.whispersystems.** { *; }
-dontwarn org.signal.**
-dontwarn org.whispersystems.**


-keep class org.pircbotx.** { *; }
-dontwarn org.pircbotx.**
-dontwarn com.google.common.**
-dontwarn org.slf4j.**


-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**


-dontwarn androidx.**
-dontwarn com.google.android.material.**


-keep class org.json.** { *; }


-dontwarn java.awt.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe