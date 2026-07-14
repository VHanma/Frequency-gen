package com.vaan.ultracarrier.audio

internal class FloatArrayBuilder(initialCapacity: Int = 65_536) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun add(value: Float) {
        ensure(size + 1)
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)

    private fun ensure(required: Int) {
        if (required <= data.size) return
        var next = data.size
        while (next < required) next = (next * 2).coerceAtLeast(required)
        data = data.copyOf(next)
    }
}
