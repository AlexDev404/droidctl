package dev.alexdev404.droidctl.debug

/** One packet of a recorded stream, shaped the way the scrcpy server sends them. */
data class SamplePacket(val data: ByteArray, val isConfig: Boolean, val isKeyFrame: Boolean) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SamplePacket &&
            isConfig == other.isConfig && isKeyFrame == other.isKeyFrame &&
            data.contentEquals(other.data))

    override fun hashCode(): Int =
        (data.contentHashCode() * 31 + isConfig.hashCode()) * 31 + isKeyFrame.hashCode()
}

/**
 * Turns a recorded Annex-B H.264 elementary stream into scrcpy-shaped packets.
 *
 * A Target's `MediaCodec` emits one buffer per access unit, with the parameter
 * sets arriving separately flagged as codec config. A file on disk has neither
 * of those boundaries marked, so they have to be recovered: parameter sets that
 * precede the first frame become the config packet, and slices belonging to one
 * frame are recombined into one packet.
 *
 * Getting the second part wrong is not cosmetic. An encoder that emits several
 * slices per frame would otherwise produce four packets per frame, each with its
 * own timestamp, and the decoder would render garbage at four times the rate --
 * which would look exactly like a bug in the video path this harness exists to
 * exercise.
 */
object AnnexBPacketizer {

    private const val NAL_NON_IDR = 1
    private const val NAL_IDR = 5

    fun packetize(bytes: ByteArray): List<SamplePacket> {
        val nals = splitAnnexB(bytes)
        require(nals.isNotEmpty()) { "not an Annex-B H.264 stream (no start codes)" }

        val units = groupIntoAccessUnits(nals)
        require(units.isNotEmpty()) { "the stream contains no coded frames" }

        val packets = mutableListOf<SamplePacket>()

        val first = units.first()
        val leadingConfig = first.takeWhile { !isVcl(it) }
        if (leadingConfig.isNotEmpty()) {
            packets += SamplePacket(concat(leadingConfig), isConfig = true, isKeyFrame = false)
        }
        val firstFrame = first.drop(leadingConfig.size)
        if (firstFrame.isNotEmpty()) {
            packets += SamplePacket(concat(firstFrame), isConfig = false, isKeyFrame = isKeyFrame(firstFrame))
        }
        for (unit in units.drop(1)) {
            packets += SamplePacket(concat(unit), isConfig = false, isKeyFrame = isKeyFrame(unit))
        }
        return packets
    }

    /**
     * Groups NAL units into access units, so that one packet is one frame.
     *
     * A slice begins a new frame when its `first_mb_in_slice` is 0. That field
     * is the first `ue(v)` of the slice header and `ue(0)` encodes as a single
     * `1` bit, so it is the top bit of the byte following the NAL header.
     */
    internal fun groupIntoAccessUnits(nals: List<ByteArray>): List<List<ByteArray>> {
        val units = mutableListOf<MutableList<ByteArray>>()
        var current = mutableListOf<ByteArray>()
        var currentHasVcl = false

        for (nal in nals) {
            val vcl = isVcl(nal)
            // A non-VCL NAL after a frame's slices (a new SPS, an SEI) also
            // opens the next access unit.
            val boundary = if (vcl) startsFrame(nal) && currentHasVcl else currentHasVcl
            if (boundary) {
                units += current
                current = mutableListOf()
                currentHasVcl = false
            }
            current += nal
            if (vcl) currentHasVcl = true
        }
        if (currentHasVcl) units += current
        return units
    }

    /** Splits an Annex-B stream into NAL units, each keeping its start code. */
    internal fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val offsets = mutableListOf<Int>()
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
                // A four-byte start code is a three-byte one preceded by a zero.
                offsets += if (i > 0 && bytes[i - 1].toInt() == 0) i - 1 else i
                i += 3
            } else {
                i++
            }
        }
        return offsets.mapIndexed { index, offset ->
            bytes.copyOfRange(offset, offsets.getOrNull(index + 1) ?: bytes.size)
        }
    }

    private fun isVcl(nal: ByteArray): Boolean =
        nalType(nal) == NAL_NON_IDR || nalType(nal) == NAL_IDR

    private fun isKeyFrame(unit: List<ByteArray>): Boolean = unit.any { nalType(it) == NAL_IDR }

    private fun startsFrame(nal: ByteArray): Boolean {
        val header = startCodeLength(nal)
        return nal.size > header + 1 && (nal[header + 1].toInt() and 0x80) != 0
    }

    private fun nalType(nal: ByteArray): Int = nal[startCodeLength(nal)].toInt() and 0x1F

    /** 3 for `00 00 01`, 4 for `00 00 00 01`. */
    private fun startCodeLength(nal: ByteArray): Int = if (nal[2].toInt() == 1) 3 else 4

    private fun concat(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size })
        var offset = 0
        for (nal in nals) {
            nal.copyInto(out, offset)
            offset += nal.size
        }
        return out
    }
}
