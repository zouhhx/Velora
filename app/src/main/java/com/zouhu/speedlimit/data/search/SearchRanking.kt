package com.zouhu.speedlimit.data.search

/**
 * Search result ordering (VA-O04, 2026-11-20 decision).
 *
 * Policy: relevance tier first, distance inside the same tier.
 *
 *  - T0: the name equals the query;
 *  - T1: the name contains the full query;
 *  - T2: the query contains the name (a shorter, still-relevant place);
 *  - T3: category / fuzzy matches such as 健身房 -> XX健身俱乐部.
 *
 * This keeps a cross-city exact destination first, while nearby category results
 * (gyms, charging stations) are ordered by distance instead of being pushed behind a
 * farther same-category hit. Unknown distance (-1) sorts last within its tier.
 */
object SearchRanking {
    const val LIMIT = 30

    fun rank(
        query: String,
        local: List<SearchPlace>,
        remote: List<SearchPlace>,
        limit: Int = LIMIT,
    ): List<SearchPlace> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val merged = (local + remote).distinctBy { keyOf(it) }
        return merged
            .sortedWith(
                compareBy<SearchPlace> { tier(it.name, q) }
                    .thenBy { distanceKey(it) }
                    .thenBy { it.name },
            )
            .take(limit)
    }

    private fun tier(name: String, query: String): Int {
        val n = normalize(name)
        if (n.isEmpty()) return 3
        return when {
            n == query -> 0
            n.contains(query) -> 1
            query.length >= 2 && query.contains(n) -> 2
            else -> 3
        }
    }

    private fun distanceKey(place: SearchPlace): Int =
        if (place.distanceM >= 0) place.distanceM else Int.MAX_VALUE

    private fun keyOf(place: SearchPlace): String =
        place.uid.ifBlank { place.name + "@" + place.city }

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(" ", "")
}
