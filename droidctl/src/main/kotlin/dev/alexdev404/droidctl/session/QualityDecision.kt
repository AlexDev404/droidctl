package dev.alexdev404.droidctl.session

import dev.alexdev404.droidctl.model.ConnectionQuality

/**
 * A rung and the reason it was picked, so the log can say what happened rather
 * than leaving the user to infer it from the picture.
 */
data class QualityDecision(val quality: ConnectionQuality, val reason: String) {

    companion object {

        /**
         * What Automatic mode picks, given whatever measurements exist.
         *
         * There are three sources, in order of preference:
         *
         *  1. a **fresh** measurement, from a server push that happened this
         *     session;
         *  2. a **remembered** one, from the last push to this Target -- needed
         *     because the push is skipped once the Target already has the jar,
         *     and that push is the only thing DroidCtl transfers before the
         *     video stream exists;
         *  3. nothing at all, on a first connection where the jar somehow
         *     arrived without being timed.
         *
         * A push too brief to time is its own case: the link is certainly fast,
         * the number just is not trustworthy.
         */
        fun automatic(
            freshBitsPerSecond: Long?,
            rememberedBitsPerSecond: Long?,
            pushWasTooBriefToTime: Boolean,
        ): QualityDecision {
            if (pushWasTooBriefToTime) {
                return QualityDecision(
                    ConnectionQuality.entries.last(),
                    "the server push finished too quickly to time, so the link is fast",
                )
            }
            if (freshBitsPerSecond != null) {
                return QualityDecision(
                    ConnectionQuality.forMeasuredBandwidth(freshBitsPerSecond),
                    "measured ${freshBitsPerSecond / 1000} kbps pushing the server",
                )
            }
            if (rememberedBitsPerSecond != null) {
                return QualityDecision(
                    ConnectionQuality.forMeasuredBandwidth(rememberedBitsPerSecond),
                    "${rememberedBitsPerSecond / 1000} kbps measured on an earlier session " +
                        "(the Target already had the server, so nothing was pushed to time)",
                )
            }
            return QualityDecision(
                ConnectionQuality.UNMEASURED_DEFAULT,
                "nothing measured yet",
            )
        }
    }
}
