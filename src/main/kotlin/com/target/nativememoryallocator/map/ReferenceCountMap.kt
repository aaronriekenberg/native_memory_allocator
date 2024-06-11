package com.target.nativememoryallocator.map

import com.target.nativememoryallocator.allocator.NativeMemoryAllocator
import com.target.nativememoryallocator.buffer.NativeMemoryBuffer
import com.target.nativememoryallocator.buffer.OnHeapMemoryBuffer
import com.target.nativememoryallocator.buffer.OnHeapMemoryBufferFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private class ReferenceCountValue(
    val nativeMemoryBuffer: NativeMemoryBuffer,
) {
    private val referenceCount = AtomicInteger(0)

    fun incrementReferenceCount(): Int {
        return referenceCount.incrementAndGet()
    }

    fun decrementReferenceCount(): Int {
        return referenceCount.decrementAndGet()
    }

}

class ReferenceCountMap<KEY_TYPE : Any, VALUE_TYPE : Any>(
    private val valueSerializer: NativeMemoryMapSerializer<VALUE_TYPE>,
    private val nativeMemoryAllocator: NativeMemoryAllocator,
) {

    private val innerMap = ConcurrentHashMap<KEY_TYPE, ReferenceCountValue>()

    private fun freeNativeMemoryBuffer(
        nativeMemoryBuffer: NativeMemoryBuffer,
    ) {
        nativeMemoryAllocator.freeNativeMemoryBuffer(nativeMemoryBuffer)
    }

    private fun decrementReferenceCount(
        referenceCountValue: ReferenceCountValue,
    ) {
        if (referenceCountValue.decrementReferenceCount() == 0) {
            freeNativeMemoryBuffer(referenceCountValue.nativeMemoryBuffer)
        }
    }

    fun put(key: KEY_TYPE, value: VALUE_TYPE) {

        val newValueByteArray = valueSerializer.serializeToByteArray(value = value)
        val newCapacityBytes = newValueByteArray.size

        val nativeMemoryBuffer =
            nativeMemoryAllocator.allocateNativeMemoryBuffer(capacityBytes = newCapacityBytes)

        nativeMemoryBuffer.copyFromArray(byteArray = newValueByteArray)

        val newRefCountedValue = ReferenceCountValue(
            nativeMemoryBuffer = nativeMemoryBuffer,
        )
        newRefCountedValue.incrementReferenceCount()

        val previousValue = innerMap.put(key = key, value = newRefCountedValue)

        if (previousValue != null) {
            decrementReferenceCount(previousValue)
        }
    }

    fun get(key: KEY_TYPE): VALUE_TYPE? {
        val mapValue = innerMap.computeIfPresent(key) { _, currentValue ->
            currentValue.incrementReferenceCount()

            currentValue
        } ?: return null

        try {
            // copy NMA to onheap buffer
            val onHeapMemoryBuffer =
                OnHeapMemoryBufferFactory.newOnHeapMemoryBuffer(initialCapacityBytes = mapValue.nativeMemoryBuffer.capacityBytes)

            mapValue.nativeMemoryBuffer.copyToOnHeapMemoryBuffer(onHeapMemoryBuffer)

            val deserializedValue =
                valueSerializer.deserializeFromOnHeapMemoryBuffer(onHeapMemoryBuffer = onHeapMemoryBuffer)

            return deserializedValue
        } finally {
            decrementReferenceCount(mapValue)
        }
    }

    fun getWithBuffer(
        key: KEY_TYPE,
        onHeapMemoryBuffer: OnHeapMemoryBuffer,
    ): VALUE_TYPE? {
        val mapValue = innerMap.computeIfPresent(key) { _, currentValue ->
            currentValue.incrementReferenceCount()

            currentValue
        } ?: return null

        try {
            mapValue.nativeMemoryBuffer.copyToOnHeapMemoryBuffer(onHeapMemoryBuffer)

            val deserializedValue =
                valueSerializer.deserializeFromOnHeapMemoryBuffer(onHeapMemoryBuffer = onHeapMemoryBuffer)

            return deserializedValue
        } finally {
            decrementReferenceCount(mapValue)
        }
    }

    fun delete(key: KEY_TYPE) {
        val previousValue = innerMap.remove(key)

        if (previousValue != null) {
            decrementReferenceCount(previousValue)
        }
    }

    val size: Int
        get() = innerMap.size
}