package dev.alexdev404.droidctl.model

/**
 * How the Host reaches the Target.
 *
 * The two modes are not a fast path and a fallback: they need root on
 * *different* devices, so which one is usable is decided by what the user
 * happens to have.
 *
 * |        | [Adb]                           | [Ssh]                        |
 * |--------|---------------------------------|------------------------------|
 * | Host   | rooted, with the adb-ndk module | nothing -- no root needed    |
 * | Target | stock, nothing installed        | rooted, running an sshd      |
 *
 * Lives in `model` rather than next to the transports themselves because it is
 * a user choice that gets persisted, so `data` and the UI both need it without
 * pulling in jsch.
 */
enum class TransportKind(val label: String) {
    /** Wireless debugging, driven by adb on a rooted Host. */
    Adb("ADB"),

    /** SSH to a rooted Target running an sshd, from a Host that needs no root. */
    Ssh("SSH");

    companion object {
        /** Tolerant of an unknown stored value: an old install predates the choice. */
        fun decode(raw: String?): TransportKind =
            entries.firstOrNull { it.name == raw } ?: Adb
    }
}
