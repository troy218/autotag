package com.troy.autotag.fp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of the FFmpeg `av_resample` polyphase resampler that Chromaprint bundles
 * (src/avresample/resample2.c). Chromaprint always resamples to 11025 Hz mono
 * before computing the fingerprint, so using the very same resampler keeps our
 * output as close as possible to `fpcalc`.
 *
 * Filter bank coefficients are computed with the same kaiser windowed sinc
 * design (16 taps, 8-bit phase, cutoff 0.8, beta = 9) and stored as Q15 values,
 * exactly like the C code with `FILTER_SHIFT = 15`.
 */
internal class Resampler(
    outRate: Int,
    inRate: Int,
    filterSize: Int = 16,
    phaseShift: Int = 8,
    cutoff: Double = 0.8,
) {
    private val phaseShift = phaseShift
    private val phaseMask = (1 shl phaseShift) - 1
    private val filterLength: Int
    private val filterBank: ShortArray
    private val srcIncr: Int = outRate
    private val idealDstIncr: Int = inRate shl phaseShift

    var index: Int = 0
        private set
    var frac: Int = 0
        private set
    private var dstIncr: Int = inRate shl phaseShift

    init {
        val factor = min(outRate * cutoff / inRate, 1.0)
        val phaseCount = 1 shl phaseShift
        filterLength = max(ceil(filterSize / factor).toInt(), 1)
        val bank = ShortArray(filterLength * (phaseCount + 1))
        buildFilter(bank, factor, filterLength, phaseCount, 1 shl 15, 9)
        // Duplicate the beginning of the filter bank so that the "ideal" phase
        // can read one extra tap without bounds checks, same as the C code.
        System.arraycopy(
            bank,
            0,
            bank,
            filterLength * phaseCount + 1,
            filterLength - 1,
        )
        bank[filterLength * phaseCount] = bank[filterLength - 1]
        filterBank = bank
        index = -phaseCount * ((filterLength - 1) / 2)
    }

    /**
     * Resamples [srcSize] samples from [src] (starting at [srcOffset]).
     *
     * @return number of produced samples written into [dst]
     */
    fun process(
        dst: ShortArray,
        dstOffset: Int,
        dstSize: Int,
        src: ShortArray,
        srcOffset: Int,
        srcSize: Int,
        consumed: IntArray,
    ): Int {
        var idx = index
        var fr = frac
        val dstIncrFrac = dstIncr % srcIncr
        val dstIncrWhole = dstIncr / srcIncr

        var dstIndex = 0
        while (dstIndex < dstSize) {
            val filterBase = filterLength * (idx and phaseMask)
            val sampleIndex = idx shr phaseShift
            var value = 0L

            if (sampleIndex < 0) {
                for (i in 0 until filterLength) {
                    val si = (abs(sampleIndex + i)) % srcSize
                    value += src[srcOffset + si].toLong() * filterBank[filterBase + i]
                }
            } else if (sampleIndex + filterLength > srcSize) {
                break
            } else {
                for (i in 0 until filterLength) {
                    value += src[srcOffset + sampleIndex + i].toLong() * filterBank[filterBase + i]
                }
            }

            value = (value + (1L shl (15 - 1))) shr 15
            dst[dstOffset + dstIndex] = when {
                value > Short.MAX_VALUE -> Short.MAX_VALUE
                value < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> value.toInt().toShort()
            }

            fr += dstIncrFrac
            idx += dstIncrWhole
            if (fr >= srcIncr) {
                fr -= srcIncr
                idx++
            }
            dstIndex++
        }

        consumed[0] = max(idx, 0) shr phaseShift
        if (idx >= 0) idx = idx and phaseMask
        frac = fr
        index = idx
        dstIncr = dstIncrFrac + srcIncr * dstIncrWhole
        return dstIndex
    }

    private fun buildFilter(
        filter: ShortArray,
        factorIn: Double,
        tapCount: Int,
        phaseCount: Int,
        scale: Int,
        type: Int,
    ) {
        var factor = factorIn
        val center = (tapCount - 1) / 2
        val tab = DoubleArray(tapCount)
        if (factor > 1.0) factor = 1.0

        for (ph in 0 until phaseCount) {
            var norm = 0.0
            for (i in 0 until tapCount) {
                var x = PI * ((i - center).toDouble() - ph.toDouble() / phaseCount) * factor
                var y = if (x == 0.0) 1.0 else sin(x) / x
                // type >= 2: kaiser windowed sinc, beta = type
                val w = 2.0 * x / (factor * tapCount * PI)
                y *= bessel(type * sqrt(max(1.0 - w * w, 0.0)))
                tab[i] = y
                norm += y
            }
            for (i in 0 until tapCount) {
                val v = Math.rint(tab[i] * scale / norm)
                filter[ph * tapCount + i] = when {
                    v > Short.MAX_VALUE.toDouble() -> Short.MAX_VALUE
                    v < Short.MIN_VALUE.toDouble() -> Short.MIN_VALUE
                    else -> v.toInt().toShort()
                }
            }
        }
    }

    private fun bessel(x0: Double): Double {
        var v = 1.0
        var lastV = 0.0
        var t = 1.0
        val x = x0 * x0 / 4
        var i = 1
        while (v != lastV) {
            lastV = v
            t *= x / (i.toDouble() * i)
            v += t
            i++
        }
        return v
    }
}
