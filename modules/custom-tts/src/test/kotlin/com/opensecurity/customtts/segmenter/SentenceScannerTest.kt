package com.opensecurity.customtts.segmenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceScannerTest {

    @Test
    fun `句末标点切分`() {
        assertEquals(
            listOf("你好。", "再见！"),
            SentenceScanner.splitSentences("你好。再见！"),
        )
    }

    @Test
    fun `小数点不切分`() {
        assertEquals(
            listOf("圆周率是3.14。"),
            SentenceScanner.splitSentences("圆周率是3.14。"),
        )
    }

    @Test
    fun `闭引号并入当前句`() {
        assertEquals(
            listOf("他说“走了。”", "然后离开。"),
            SentenceScanner.splitSentences("他说“走了。”然后离开。"),
        )
    }

    @Test
    fun `连续句末符并入`() {
        assertEquals(
            listOf("真的吗？！", "是的。"),
            SentenceScanner.splitSentences("真的吗？！是的。"),
        )
    }

    @Test
    fun `换行断句`() {
        assertEquals(
            listOf("第一行", "第二行"),
            SentenceScanner.splitSentences("第一行\n第二行"),
        )
    }

    @Test
    fun `无句末符的尾巴`() {
        assertEquals(
            listOf("没有结尾"),
            SentenceScanner.splitSentences("没有结尾"),
        )
    }

    @Test
    fun `空文本与纯空白`() {
        assertTrue(SentenceScanner.splitSentences("").isEmpty())
        assertTrue(SentenceScanner.splitSentences("  \n \n  ").isEmpty())
    }

    @Test
    fun `英文句号句末切分`() {
        assertEquals(
            listOf("Hello world.", "Next one."),
            SentenceScanner.splitSentences("Hello world. Next one."),
        )
    }
}
