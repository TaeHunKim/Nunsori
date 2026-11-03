package com.lsa.app.indexing.faces

import kotlin.math.sqrt

/**
 * Grouping of face vectors (L2-normalized) into people, without a model: a face joins the group whose centroid
 * (mean of its members, normalized) is most similar, when that similarity reaches [threshold]; otherwise it starts a
 * group. Groups whose centroids are that similar are merged afterwards. Named groups are never merged with each
 * other (two names are two people for the user), and a face never joins a group it was taken out of, nor is its group
 * merged with that group.
 */
class FaceClusterer(private val threshold: Float = SAME_PERSON) {
    /** A group while clustering: running sum of its member vectors. */
    class Group(val id: Long, val named: Boolean, val sum: FloatArray, var count: Int) {
        /** Groups that some face of this group was taken out of ("not this person"): never merge with them. */
        val blocked: MutableSet<Long> = mutableSetOf()
        fun centroid(): FloatArray = normalize(sum)
        fun add(v: FloatArray) { for (i in sum.indices) sum[i] += v[i]; count++ }
        fun absorb(o: Group) { for (i in sum.indices) sum[i] += o.sum[i]; count += o.count; blocked += o.blocked }
        fun canMerge(o: Group) = !(named && o.named) && o.id !in blocked && id !in o.blocked
    }

    /** The best group for [v] (not [blocked]) if it is similar enough, else null. */
    fun match(v: FloatArray, groups: Collection<Group>, blocked: Long?): Group? {
        var best: Group? = null
        var bestSim = threshold
        for (g in groups) {
            if (g.id == blocked) continue
            val s = dot(v, g.centroid())
            if (s >= bestSim) { best = g; bestSim = s }
        }
        return best
    }

    /**
     * Pairs (from, into) to merge so that no two remaining groups are this similar; a named group always absorbs an
     * unnamed one, two named groups are never merged. Merged sums are updated in place, so later pairs see the
     * grown groups.
     */
    fun merges(groups: List<Group>): List<Pair<Long, Long>> {
        val alive = groups.associateBy { it.id }.toMutableMap()
        val out = mutableListOf<Pair<Long, Long>>()
        while (true) {
            var bestPair: Pair<Group, Group>? = null
            var bestSim = threshold
            val list = alive.values.toList()
            val cents = list.map { it.centroid() }
            for (i in list.indices) for (j in i + 1 until list.size) {
                if (!list[i].canMerge(list[j])) continue
                val s = dot(cents[i], cents[j])
                if (s >= bestSim) { bestSim = s; bestPair = list[i] to list[j] }
            }
            val (a, b) = bestPair ?: return out
            // keep the named one, else the larger one
            val (into, from) = when {
                a.named -> a to b
                b.named -> b to a
                a.count >= b.count -> a to b
                else -> b to a
            }
            into.absorb(from)
            alive.remove(from.id)
            // whoever could not join [from] cannot join the group it became part of either
            for (g in alive.values) if (from.id in g.blocked) g.blocked += into.id
            out += from.id to into.id
        }
    }

    companion object {
        /**
         * Cosine similarity at which two SFace vectors are taken for the same person (OpenCV's recommended value;
         * on the emulator fixtures same-person pairs scored 0.54 to 0.90 and different people at most 0.22).
         */
        const val SAME_PERSON = 0.363f

        fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

        fun normalize(v: FloatArray): FloatArray {
            val n = sqrt(dot(v, v)).takeIf { it > 0f } ?: return v.copyOf()
            return FloatArray(v.size) { v[it] / n }
        }
    }
}
