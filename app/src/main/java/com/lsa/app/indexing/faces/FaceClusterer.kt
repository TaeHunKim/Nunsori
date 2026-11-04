package com.lsa.app.indexing.faces

import kotlin.math.sqrt

/**
 * Grouping of face vectors (L2-normalized) into people, without a model: a face joins the group whose centroid
 * (mean of its members, normalized) is most similar, when that similarity reaches [threshold]; otherwise it starts a
 * group. Groups whose centroids are that similar are merged afterwards. Named groups are never merged with each
 * other (two names are two people for the user), and a face never joins a group it was taken out of, nor is its group
 * merged with that group.
 */
class FaceClusterer(private val threshold: Float = DEFAULT_THRESHOLD) {
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

    /** One stored face for [regroupAll]: its current group ([oldPerson]) and the group it was taken out of. */
    class FaceInput(val faceId: Long, val vector: FloatArray, val score: Float, val oldPerson: Long?, val blockedOld: Long?)

    /**
     * A group made by [regroupAll]. [inherits] is the old group whose id (and name, cover) it takes over, or null for a
     * new group; [blockedGroup] maps each face that was taken out of an old group to the index of the new group that
     * stands for that old group now (null when none does).
     */
    class NewGroup(val faceIds: List<Long>, val inherits: Long?)
    class Regrouped(val groups: List<NewGroup>, val blockedGroup: Map<Long, Int>)

    /** A group while regrouping from scratch: cached centroid, the old groups its faces came from, constraints. */
    private class Fresh(dim: Int) {
        val sum = FloatArray(dim)
        var centroid = FloatArray(dim)
        val faces = mutableListOf<FaceInput>()
        /** Old groups of its members, of which the named ones. */
        val olds = mutableSetOf<Long>()
        val namedOlds = mutableSetOf<Long>()
        /** Old groups some member was taken out of: no member of those may join. */
        val blockedOlds = mutableSetOf<Long>()
        fun add(f: FaceInput, named: Set<Long>) {
            for (i in sum.indices) sum[i] += f.vector[i]
            centroid = normalize(sum)
            faces += f
            f.oldPerson?.let { olds += it; if (it in named) namedOlds += it }
            f.blockedOld?.let { blockedOlds += it }
        }
        fun absorb(o: Fresh) {
            for (i in sum.indices) sum[i] += o.sum[i]
            centroid = normalize(sum)
            faces += o.faces; olds += o.olds; namedOlds += o.namedOlds; blockedOlds += o.blockedOlds
        }
        fun accepts(f: FaceInput, named: Set<Long>): Boolean {
            if (f.blockedOld != null && f.blockedOld in olds) return false
            val p = f.oldPerson ?: return true
            if (p in blockedOlds) return false
            return !(p in named && namedOlds.any { it != p })
        }
        fun canMerge(o: Fresh) = blockedOlds.none { it in o.olds } && o.blockedOlds.none { it in olds } &&
            (namedOlds + o.namedOlds).size <= 1
    }

    /**
     * Groups every face again from scratch (a new threshold or method), keeping what the user did: faces of two
     * differently named groups never end up together (two names are two people), a face taken out of a group never
     * joins the faces of that group again, and each old group hands its id (name, cover) to the new group that got
     * most of its faces. Better faces ([FaceInput.score]) are placed first so that they seed the groups.
     */
    fun regroupAll(faces: List<FaceInput>, namedOld: Set<Long>): Regrouped {
        if (faces.isEmpty()) return Regrouped(emptyList(), emptyMap())
        val dim = faces[0].vector.size
        val groups = mutableListOf<Fresh>()
        for (f in faces.sortedWith(compareByDescending<FaceInput> { it.score }.thenBy { it.faceId })) {
            var best: Fresh? = null
            var bestSim = threshold
            for (g in groups) {
                if (!g.accepts(f, namedOld)) continue
                val s = dot(f.vector, g.centroid)
                if (s >= bestSim) { best = g; bestSim = s }
            }
            (best ?: Fresh(dim).also { groups += it }).add(f, namedOld)
        }
        // merge groups whose centroids drifted together, most similar pair first, until none is left
        while (true) {
            var pair: Pair<Fresh, Fresh>? = null
            var bestSim = threshold
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val a = groups[i]; val b = groups[j]
                val s = dot(a.centroid, b.centroid)
                if (s >= bestSim && a.canMerge(b)) { bestSim = s; pair = a to b }
            }
            val (a, b) = pair ?: break
            val (into, from) = if (a.faces.size >= b.faces.size) a to b else b to a
            into.absorb(from)
            groups.remove(from)
        }
        // each old group goes to the new group holding most of its faces (ties: the earlier group)
        val bestOf = HashMap<Long, Int>()
        run {
            val counts = HashMap<Long, IntArray>()
            groups.forEachIndexed { gi, g ->
                for (f in g.faces) {
                    val p = f.oldPerson ?: continue
                    val c = counts.getOrPut(p) { IntArray(groups.size) }
                    c[gi]++
                }
            }
            for ((p, c) in counts) bestOf[p] = c.indices.maxBy { c[it] }
        }
        // a new group inherits its named old group (at most one), else the old group with most faces in it
        val inherits = arrayOfNulls<Long>(groups.size)
        for (gi in groups.indices) {
            val candidates = bestOf.filterValues { it == gi }.keys
            if (candidates.isEmpty()) continue
            val counts = groups[gi].faces.groupingBy { it.oldPerson }.eachCount()
            inherits[gi] = candidates.firstOrNull { it in namedOld }
                ?: candidates.maxWith(compareBy<Long> { counts[it] ?: 0 }.thenByDescending { it })
        }
        val blocked = HashMap<Long, Int>()
        for (f in faces) {
            val p = f.blockedOld ?: continue
            bestOf[p]?.let { blocked[f.faceId] = it }
        }
        return Regrouped(groups.mapIndexed { gi, g -> NewGroup(g.faces.map { it.faceId }, inherits[gi]) }, blocked)
    }

    companion object {
        /**
         * Default cosine similarity at which two SFace vectors are taken for the same person. OpenCV recommends 0.363,
         * which separated the emulator fixtures (same person 0.54 to 0.90, different people at most 0.22) but mixed
         * people on real photo libraries; the default leans strict since a split person is fixed with one merge.
         */
        const val DEFAULT_THRESHOLD = 0.45f
        /** Range offered in the app (Grouping: looser … stricter). */
        const val MIN_THRESHOLD = 0.30f
        const val MAX_THRESHOLD = 0.60f
        /** Bumped when the grouping method changes: the groups are made again from the stored vectors. */
        const val GROUPING_VERSION = 2

        /** What the current groups were made with; a different key means "group everything again". */
        fun groupingKey(threshold: Float) = "g$GROUPING_VERSION|t${String.format(java.util.Locale.ROOT, "%.2f", threshold)}"

        /** Threshold snapped to the offered range and steps of 0.01. */
        fun clampThreshold(t: Float) = (Math.round(t.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) * 100) / 100f)

        fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

        fun normalize(v: FloatArray): FloatArray {
            val n = sqrt(dot(v, v)).takeIf { it > 0f } ?: return v.copyOf()
            return FloatArray(v.size) { v[it] / n }
        }
    }
}
