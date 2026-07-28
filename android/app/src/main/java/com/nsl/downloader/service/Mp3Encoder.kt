package com.nsl.downloader.service

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.OutputStream

/**
 * Constant-bitrate MP3 encoder over jump3r (a pure-Java LAME port).
 *
 * jump3r's convenience `lowlevel.LameEncoder` wrapper is unusable here: its
 * static fields are `javax.sound.sampled` types that do not exist on Android,
 * so merely loading the class throws. This drives the `mp3.*` core directly,
 * replicating that wrapper's module wiring.
 *
 * PCM is passed as ints in LAME's own convention — a 16-bit sample shifted left
 * by 16 — which [Mp3Transcoder] produces.
 */
class Mp3Encoder(
    sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int,
    quality: Int = QUALITY_MIDDLE
) {

    companion object {
        const val QUALITY_MIDDLE = 5

        /** LAME's documented worst case for an output buffer. */
        fun mp3BufferSizeFor(samplesPerChannel: Int): Int =
            (samplesPerChannel * 1.25).toInt() + 7200
    }

    private val lame = Lame()
    private val bitStream = BitStream()
    private val gfp: LameGlobalFlags

    init {
        val gainAnalysis = GainAnalysis()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbrTag = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val mpgInterface = Interface()
        val common = Common()

        lame.setModules(
            gainAnalysis, bitStream, presets, quantizePvt, quantize,
            vbrTag, version, id3, mpg
        )
        bitStream.setModules(gainAnalysis, mpg, version, vbrTag)
        id3.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lame, bitStream, version)
        parse.setModules(version, id3, presets)
        mpg.setModules(mpgInterface, common)
        mpgInterface.setModules(vbrTag, common)

        gfp = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = sampleRate
        gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        gfp.brate = bitrateKbps
        gfp.quality = quality
        // We write no tags, and ReplayGain scanning is pure overhead here.
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false

        id3.id3tag_init(gfp)
        check(lame.lame_init_params(gfp) >= 0) { "LAME rejected the encoder settings" }
    }

    private var mp3Buffer = ByteArray(mp3BufferSizeFor(4096))

    /**
     * Encodes [samplesPerChannel] frames. [right] is ignored for mono input but
     * must still be non-null — LAME reads it unconditionally in stereo mode.
     */
    fun encode(left: IntArray, right: IntArray, samplesPerChannel: Int, out: OutputStream) {
        if (samplesPerChannel <= 0) return
        ensureCapacity(samplesPerChannel)
        val written = lame.lame_encode_buffer_int(
            gfp, left, if (channels == 1) left else right,
            samplesPerChannel, mp3Buffer, 0, mp3Buffer.size
        )
        if (written < 0) error("LAME encode failed with $written")
        if (written > 0) out.write(mp3Buffer, 0, written)
    }

    /** Emits the final partial frame; the encoder is unusable afterwards. */
    fun finish(out: OutputStream) {
        ensureCapacity(4096)
        val written = lame.lame_encode_flush(gfp, mp3Buffer, 0, mp3Buffer.size)
        if (written > 0) out.write(mp3Buffer, 0, written)
        lame.lame_close(gfp)
    }

    private fun ensureCapacity(samplesPerChannel: Int) {
        val needed = mp3BufferSizeFor(samplesPerChannel)
        if (mp3Buffer.size < needed) mp3Buffer = ByteArray(needed)
    }
}
