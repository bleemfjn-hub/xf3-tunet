package com.xf3.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ShellRunner 纯函数单元测试（无需 Android 环境）。
 */
class ShellRunnerTest {

    // ---------- parseGetOutput ----------

    @Test
    fun parseGetOutput_normalValue() {
        assertEquals("120", ShellRunner.parseGetOutput("120\n"))
    }

    @Test
    fun parseGetOutput_nullStringIsNull() {
        assertNull(ShellRunner.parseGetOutput("null\n"))
    }

    @Test
    fun parseGetOutput_emptyIsNull() {
        assertNull(ShellRunner.parseGetOutput(""))
        assertNull(ShellRunner.parseGetOutput("\n"))
    }

    @Test
    fun parseGetOutput_takesLastLine() {
        // settings get 有时会带警告行，取最后一行
        assertEquals("0.5", ShellRunner.parseGetOutput("WARNING: something\n0.5\n"))
    }

    @Test
    fun parseGetOutput_trimsWhitespace() {
        assertEquals("true", ShellRunner.parseGetOutput("  true  \n"))
    }

    // ---------- parseMarkedOutput ----------

    @Test
    fun parseMarkedOutput_singleEntry() {
        val m = ShellRunner.parseMarkedOutput("@@0=120\n")
        assertEquals("120", m[0])
    }

    @Test
    fun parseMarkedOutput_multipleEntries() {
        val m = ShellRunner.parseMarkedOutput("@@0=1\n@@1=0.5\n@@2=true\n")
        assertEquals("1", m[0])
        assertEquals("0.5", m[1])
        assertEquals("true", m[2])
    }

    @Test
    fun parseMarkedOutput_ignoresNonMarkedLines() {
        val m = ShellRunner.parseMarkedOutput("noise\n@@0=1\nmore noise\n")
        assertEquals(1, m.size)
        assertEquals("1", m[0])
    }

    @Test
    fun parseMarkedOutput_handlesNullValue() {
        val m = ShellRunner.parseMarkedOutput("@@0=null\n")
        assertEquals("null", m[0])
    }

    @Test
    fun parseMarkedOutput_emptyInput() {
        assertEquals(0, ShellRunner.parseMarkedOutput("").size)
    }

    @Test
    fun parseMarkedOutput_skipsMalformedIndex() {
        val m = ShellRunner.parseMarkedOutput("@@abc=1\n@@0=2\n")
        assertEquals(1, m.size)
        assertEquals("2", m[0])
    }
}
