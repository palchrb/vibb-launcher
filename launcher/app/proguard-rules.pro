# Add project specific ProGuard rules here.
-dontobfuscate
-dontoptimize
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Readable crash traces (class/method names are already kept by -dontobfuscate).
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# see app/build/outputs/mapping/release/missing_rules.txt
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedAnnotationTypes
-dontwarn javax.annotation.processing.SupportedSourceVersion

# dnsjava (KidVpnService's DNS message parsing) references lombok's compile-time-only @Generated
# annotation and an optional slf4j logging binding this app doesn't include - neither has any
# runtime footprint we need, dnsjava just no-ops logging without a real slf4j binding present.
-dontwarn lombok.Generated
-dontwarn org.slf4j.**
# dnsjava's optional adapter for the desktop-JDK-only sun.net.spi.nameservice SPI (plugging into
# InetAddress's internal resolution) - doesn't exist on Android and this app never uses it.
-dontwarn sun.net.spi.nameservice.**

# R8 only shrinks here (-dontobfuscate -dontoptimize above). These keeps are belt and braces for
# code the shrinker can't see being used:
# - tsnet.aar's gomobile bindings are called from native code over JNI. gomobile ships the same
#   rules as consumer rules inside the aar; keeping them here too costs nothing.
# - pcap4j instantiates its packet factories per packet type at runtime (ServiceLoader +
#   reflection), so KidVpnService's DNS filter would break if one were removed.
-keep class go.** { *; }
-keep class tsembed.** { *; }
-keep class org.pcap4j.packet.** { *; }

# dnsjava registers a java.net.spi.InetAddressResolverProvider (Java 18+ SPI). Android has no
# such service and nothing loads it; R8 warned about the reference on every release build.
-dontwarn java.net.spi.InetAddressResolverProvider
-dontwarn org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider
