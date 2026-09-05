package com.kirianov.kiasoulevplus2.tools.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `numbers survive a round trip`() {
        val line = MiniJson.encode(mapOf("distance" to 12.5, "duration" to 900L, "count" to 3))

        val back = MiniJson.decode(line)

        assertEquals(12.5, back["distance"] as Double, 1e-9)
        assertEquals(900.0, back["duration"] as Double, 1e-9)
        assertEquals(3.0, back["count"] as Double, 1e-9)
    }

    @Test
    fun `negative and exponent numbers survive a round trip`() {
        val line = MiniJson.encode(mapOf("power" to -12.75, "tiny" to 1.0e-7, "huge" to 6.02e23))

        val back = MiniJson.decode(line)

        assertEquals(-12.75, back["power"] as Double, 1e-9)
        assertEquals(1.0e-7, back["tiny"] as Double, 1e-16)
        assertEquals(6.02e23, back["huge"] as Double, 1e14)
    }

    @Test
    fun `strings booleans and nulls survive a round trip`() {
        val line = MiniJson.encode(mapOf("name" to "поїздка", "charging" to true, "temp" to null))

        val back = MiniJson.decode(line)

        assertEquals("поїздка", back["name"])
        assertEquals(true, back["charging"])
        assertNull(back["temp"])
        assertTrue("ключ має лишитися навіть із null", back.containsKey("temp"))
    }

    @Test
    fun `arrays of numbers survive a round trip`() {
        val line = MiniJson.encode(mapOf("theta" to doubleArrayOf(1.5, -2.0, 0.0)))

        @Suppress("UNCHECKED_CAST")
        val back = MiniJson.decode(line)["theta"] as List<Double>

        assertEquals(listOf(1.5, -2.0, 0.0), back)
    }

    @Test
    fun `quotes and newlines inside a string do not break the line`() {
        val awkward = "лапки \" зворотний \\ перенос \n край"

        val line = MiniJson.encode(mapOf("debug" to awkward))

        assertEquals(1, line.lines().size)
        assertEquals(awkward, MiniJson.decode(line)["debug"])
    }

    /** NaN у JSON не існує: краще null, ніж рядок, який більше не прочитається. */
    @Test
    fun `not a number is written as null`() {
        val line = MiniJson.encode(mapOf("bad" to Double.NaN, "worse" to Double.POSITIVE_INFINITY))

        val back = MiniJson.decode(line)

        assertNull(back["bad"])
        assertNull(back["worse"])
    }

    /** Журнал дописується на ходу, тож обірваний рядок — звичайна річ, а не аварія. */
    @Test
    fun `a truncated line decodes to nothing instead of throwing`() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.decode("""{"distance":12.5,"dur"""))
        assertEquals(emptyMap<String, Any?>(), MiniJson.decode(""))
        assertEquals(emptyMap<String, Any?>(), MiniJson.decode("не json взагалі"))
    }

    @Test
    fun `an empty object decodes to an empty map`() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.decode("{}"))
    }

    @Test
    fun `spaces between tokens are ignored`() {
        val back = MiniJson.decode("""  { "a" : 1 , "b" : [ 2 , 3 ] }  """)

        assertEquals(1.0, back["a"] as Double, 1e-9)
        assertEquals(listOf(2.0, 3.0), back["b"])
    }
}
