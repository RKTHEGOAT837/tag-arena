package com.aidj.core.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT with precomputed twiddles. Not thread-safe per instance. */
class Fft(val size: Int) {
    init { require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" } }

    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    private val rev = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }

    /**
     * Hann-windowed power spectrum of [input] starting at [offset] (zero padded past the end).
     * Writes size/2+1 magnitudes into [mag].
     */
    fun magnitude(input: FloatArray, offset: Int, mag: FloatArray) {
        for (i in 0 until size) {
            val idx = offset + i
            val v = if (idx in input.indices) input[idx] * window[i] else 0f
            re[rev[i]] = v.toDouble(); im[rev[i]] = 0.0
        }
        transform()
        for (k in 0..size / 2) mag[k] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
    }

    private fun transform() {
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }
}
