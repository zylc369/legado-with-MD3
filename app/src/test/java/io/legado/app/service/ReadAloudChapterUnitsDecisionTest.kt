package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudChapterUnitsDecisionTest {

    @Test
    fun `卷章无正文时读标题而不是静默停止`() {
        val decision = decideReadAloudChapterUnits(
            unitCount = 0,
            chapterTitle = "第八卷",
            startsAtChapterBeginning = true,
            moveToLast = false,
        )
        assertTrue(decision.readTitle)
        assertFalse(decision.skipChapter)
    }

    @Test
    fun `无标题无正文才跳过整章`() {
        val decision = decideReadAloudChapterUnits(
            unitCount = 0,
            chapterTitle = "",
            startsAtChapterBeginning = true,
            moveToLast = false,
        )
        assertFalse(decision.readTitle)
        assertTrue(decision.skipChapter)
    }

    @Test
    fun `有正文且从章首开始时先读标题`() {
        val decision = decideReadAloudChapterUnits(
            unitCount = 3,
            chapterTitle = "第一章",
            startsAtChapterBeginning = true,
            moveToLast = false,
        )
        assertTrue(decision.readTitle)
        assertFalse(decision.skipChapter)
    }

    @Test
    fun `从章中开始或跳到章末不读标题`() {
        assertFalse(
            decideReadAloudChapterUnits(
                unitCount = 3,
                chapterTitle = "第一章",
                startsAtChapterBeginning = false,
                moveToLast = false,
            ).readTitle
        )
        assertFalse(
            decideReadAloudChapterUnits(
                unitCount = 3,
                chapterTitle = "第一章",
                startsAtChapterBeginning = true,
                moveToLast = true,
            ).readTitle
        )
    }

    @Test
    fun `标题存在但不在章首不读也不跳过`() {
        val decision = decideReadAloudChapterUnits(
            unitCount = 3,
            chapterTitle = "第一章",
            startsAtChapterBeginning = false,
            moveToLast = false,
        )
        assertFalse(decision.readTitle)
        assertFalse(decision.skipChapter)
    }
}
