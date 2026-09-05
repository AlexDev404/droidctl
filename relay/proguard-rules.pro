# The relay is pushed over the same link the user is about to mirror across, so
# it is shrunk hard: everything not reachable from main() goes, including the
# Kotlin runtime AGP adds by default even to a module with no Kotlin in it.
-keep class dev.alexdev404.droidctl.relay.Relay {
    public static void main(java.lang.String[]);
}
-dontwarn **
