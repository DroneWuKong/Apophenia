package com.dronewukong.apophenia.garmin

/**
 * Connect IQ always exposes incoming payloads as a list. When Monkey C sends an
 * array of dictionaries, SDK versions may expose either the dictionaries
 * directly or a nested list containing them. Walk the payload without assuming
 * either representation.
 */
object GarminMessageDecoder {
    fun observationPackets(message: List<Any>?): List<Map<*, *>> {
        val packets = mutableListOf<Map<*, *>>()

        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    if (value["type"]?.toString() == "observation") packets += value
                }
                is Iterable<*> -> value.forEach(::visit)
                is Array<*> -> value.forEach(::visit)
            }
        }

        visit(message)
        return packets
    }
}
