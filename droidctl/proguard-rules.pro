# DroidCtl does not enable minification for release builds (see build.gradle.kts).
# Keep libsu's root service entry points if minification is ever turned on.
-keep class com.topjohnwu.superuser.** { *; }
