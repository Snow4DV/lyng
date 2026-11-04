/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Script
import net.sergeych.lyng.eval
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bytecode compiler keeps a static per-slot type view. A path that may not run
 * (an `if` branch, a `when` case, a loop body) used to leak the type it stored into a
 * slot past the join, so later reads hit a stale primitive register instead of the
 * value the runtime tag points at.
 */
class BranchSlotTypeJoinTest {

    private suspend fun run(src: String): Int = eval(src).toInt()

    @Test
    fun aSlotRetypedInAnUntakenBranchIsReadByItsRuntimeTag() = runTest {
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            if (x == 0) { x = 1500 }
            return x + 0 }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            if (x == 0) { x = 1500 }
            val y = x
            return y }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            if (x == 0) { x = 1500 } else { x = x }
            return x }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            if (x == 0) { x = 1500 }
            if (x < 100) { x = 100 }
            return x }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            if (x < 1) { x = 1500 }
            val y = x
            return y }
            g({ r: 2000 })
        """.trimIndent()))
    }

    @Test
    fun topLevelAndTypedFormsJoinToo() = runTest {
        assertEquals(2000, run("""
            val p = { r: 2000 }
            var x = 0
            x = p["r"]
            if (x == 0) { x = 1500 }
            x + 0
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x: Int = 0
            x = p["r"] as Int
            if (x == 0) { x = 1500 }
            return x + 0 }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(5, run("""
            fun g(p): Int { var x: String = p["s"]
            if (x == "") { x = "z" }
            return x.size }
            g({ s: "hello" })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            val r = if (x == 0) { x = 1500; 1 } else { 2 }
            return x + r - r }
            g({ r: 2000 })
        """.trimIndent()))
    }

    @Test
    fun whenAndLoopBodiesJoinTheSameWay() = runTest {
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            when (x) { 0 -> { x = 1500 } }
            return x + 0 }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            while (x == 0) { x = 1500 }
            return x + 0 }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            for (i in 0..<0) { x = 1500 }
            return x + 0 }
            g({ r: 2000 })
        """.trimIndent()))
        assertEquals(2000, run("""
            fun g(p): Int { var x = 0
            x = p["r"]
            var n = 0
            do { if (x == 0) { x = 1500 }; n = n + 1 } while (n < 1)
            return x + 0 }
            g({ r: 2000 })
        """.trimIndent()))
    }

    @Test
    fun aLoopBodyThatRetypesASlotReadsItRightOnTheSecondIteration() = runTest {
        assertEquals(4000, run("""
            fun g(p): Int { var x = 0
            var i = 0
            var acc = 0
            while (i < 3) { acc = acc + x; x = p["r"]; i = i + 1 }
            return acc }
            g({ r: 2000 })
        """.trimIndent()))
    }

    @Test
    fun anIntCounterLoopStaysOnPrimitiveOps() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            fun calc(): Int { var s = 0
            var i = 0
            while (i < 10) { if (i % 2 == 0) { s = s + i } ; i = i + 1 }
            return s }
        """.trimIndent())
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("ADD_INT"), disasm)
        assertFalse(disasm.contains("BOX_OBJ"), disasm)
        assertEquals(20, scope.eval("calc()").toInt())
    }
}
