package com.noop.firmware

enum class FirmwareReferenceComparison { OLDER, EQUAL, NEWER, UNKNOWN }

object FirmwareVersionReference {
    fun compare(reported: String?, reference: String?): FirmwareReferenceComparison {
        val observedParts = parts(reported) ?: return FirmwareReferenceComparison.UNKNOWN
        val referenceParts = parts(reference) ?: return FirmwareReferenceComparison.UNKNOWN
        if (observedParts.size != referenceParts.size || observedParts.first() != referenceParts.first()) {
            return FirmwareReferenceComparison.UNKNOWN
        }
        for ((observed, target) in observedParts.zip(referenceParts)) {
            if (observed < target) return FirmwareReferenceComparison.OLDER
            if (observed > target) return FirmwareReferenceComparison.NEWER
        }
        return FirmwareReferenceComparison.EQUAL
    }

    private fun parts(value: String?): List<Long>? {
        val components = value?.split('.') ?: return null
        if (components.size < 2) return null
        return components.map { component ->
            if (component.isEmpty() || component.any { it !in '0'..'9' }) return null
            component.toLongOrNull() ?: return null
        }
    }
}
