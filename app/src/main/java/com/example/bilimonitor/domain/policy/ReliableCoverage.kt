package com.example.bilimonitor.domain.policy

data class CoverageSpan(val sessionStableId: String, val streamerId: Long, val startMillis: Long, val endMillis: Long)

object ReliableCoverage {
    fun seconds(spans: List<CoverageSpan>): Long {
        val millis = spans.groupBy { it.streamerId }.values.sumOf { group ->
            var coveredUntil = Long.MIN_VALUE
            var total = 0L
            for (span in group.sortedBy { it.startMillis }) {
                val start = maxOf(span.startMillis, coveredUntil)
                if (span.endMillis > start) {
                    total += span.endMillis - start
                    coveredUntil = span.endMillis
                }
            }
            total
        }
        return millis / 1000
    }
}
