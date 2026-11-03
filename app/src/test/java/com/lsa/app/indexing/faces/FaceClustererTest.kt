package com.lsa.app.indexing.faces

import com.lsa.app.indexing.faces.FaceClusterer.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FaceClustererTest {
    private val c = FaceClusterer()
    private val rnd = Random(7)

    /** A random unit vector (a "person") and noisy samples around it (that person's faces). */
    private fun person(dim: Int = 128) = FaceClusterer.normalize(FloatArray(dim) { rnd.nextFloat() - 0.5f })
    private fun faceOf(p: FloatArray, noise: Float = 0.6f) = FaceClusterer.normalize(FloatArray(p.size) { p[it] + (rnd.nextFloat() - 0.5f) * noise / 4 })

    private fun group(id: Long, vararg faces: FloatArray, named: Boolean = false) =
        Group(id, named, FloatArray(faces[0].size), 0).also { g -> faces.forEach(g::add) }

    @Test fun aFaceJoinsItsPersonNotAStranger() {
        val a = person(); val b = person()
        val groups = listOf(group(1, faceOf(a), faceOf(a)), group(2, faceOf(b)))
        assertEquals(1L, c.match(faceOf(a), groups, null)!!.id)
        assertEquals(2L, c.match(faceOf(b), groups, null)!!.id)
        assertNull("a new person matches nobody", c.match(faceOf(person()), groups, null))
    }

    @Test fun aFaceTakenOutOfAGroupDoesNotGoBack() {
        val a = person()
        val groups = listOf(group(1, faceOf(a)))
        assertNull(c.match(faceOf(a), groups, blocked = 1))
    }

    @Test fun groupsOfTheSamePersonAreMergedIntoTheLargerOne() {
        val a = person(); val b = person()
        val big = group(1, faceOf(a), faceOf(a), faceOf(a)); val small = group(2, faceOf(a)); val other = group(3, faceOf(b))
        assertEquals(listOf(2L to 1L), c.merges(listOf(small, big, other)))
        assertEquals(4, big.count)
    }

    @Test fun aNamedGroupAbsorbsButTwoNamesStaySeparate() {
        val a = person()
        val named = group(1, faceOf(a), named = true); val unnamed = group(2, faceOf(a), faceOf(a))
        assertEquals("the named group stays even when smaller", listOf(2L to 1L), c.merges(listOf(named, unnamed)))
        val n1 = group(3, faceOf(a), named = true); val n2 = group(4, faceOf(a), named = true)
        assertTrue("two names are two people for the user", c.merges(listOf(n1, n2)).isEmpty())
    }

    @Test fun differentPeopleStayApart() {
        val groups = (1L..20L).map { group(it, faceOf(person())) }
        assertTrue(c.merges(groups).isEmpty())
    }

    @Test fun theBestOfSeveralMatchesWins() {
        val a = person()
        val close = group(1, faceOf(a, noise = 0.1f)); val far = group(2, faceOf(a, noise = 1.4f))
        assertSame(close, c.match(faceOf(a, noise = 0.1f), listOf(far, close), null))
    }

    @Test fun aGroupMadeOfARejectedFaceIsNotMergedBack() {
        val a = person()
        val original = group(1, faceOf(a), faceOf(a)); val rejected = group(2, faceOf(a)).also { it.blocked += 1L }
        assertTrue(c.merges(listOf(original, rejected)).isEmpty())
        // nor into a group that absorbed the original
        val third = group(3, faceOf(a), faceOf(a), faceOf(a))
        val o2 = group(1, faceOf(a)); val r2 = group(2, faceOf(a)).also { it.blocked += 1L }
        val merged = c.merges(listOf(o2, r2, third))
        assertEquals("one of the two joins the third group, the other cannot follow", 1, merged.size)
        assertEquals(3L, merged.single().second)
    }
}
