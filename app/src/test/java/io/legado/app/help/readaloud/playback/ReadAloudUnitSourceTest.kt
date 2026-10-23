package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.PlaybackPositionMap
import io.legado.app.domain.model.readaloud.PlaybackUnit
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue
import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudParagraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudUnitSourceTest {

    private fun paragraph(text: String, start: Int) =
        ReaderReadAloudParagraph(text = text, chapterPosition = start, isParagraphEnd = true)

    // ── 恒等单元（普通系统/HTTP/云路径） ──

    @Test
    fun `段落恒等单元与旧算术逐位一致`() {
        val units = ReadAloudUnitSource.fromParagraphs(
            listOf(paragraph("　　第一段。", 0), paragraph("　　第二段。", 8)),
        )
        assertEquals(listOf("　　第一段。", "　　第二段。"), units.map { it.text })
        assertEquals(listOf(0, 8), units.map { it.startPosition })
        units.forEach { unit ->
            val start = unit.startPosition
            for (i in unit.text.indices) {
                assertEquals(start + i, unit.map!!.positionAt(i))
            }
        }
    }

    @Test
    fun `恒等单元净化禁读字符但长度不变`() {
        val units = ReadAloudUnitSource.fromParagraphs(listOf(paragraph("A袮B", 5)))
        assertEquals("A B", units.single().text)
        assertEquals(5, units.single().map!!.positionAt(0))
        assertEquals(7, units.single().map!!.positionAt(2))
    }

    @Test
    fun `标题cue成为标题单元正文cue恒等`() {
        val units = ReadAloudUnitSource.fromCues(
            listOf(
                ReadAloudPlaybackCue(
                    text = "标题",
                    chapterStart = 0,
                    chapterEnd = 0,
                    paragraphIndex = -1,
                    voice = null,
                    fallbackVoices = emptyList(),
                    roleType = io.legado.app.domain.model.readaloud.SpeechRoleType.Narrator,
                    characterId = null,
                    isChapterTitle = true,
                ),
                ReadAloudPlaybackCue(
                    text = "正文",
                    chapterStart = 0,
                    chapterEnd = 2,
                    paragraphIndex = 0,
                    voice = null,
                    fallbackVoices = emptyList(),
                    roleType = io.legado.app.domain.model.readaloud.SpeechRoleType.Narrator,
                    characterId = null,
                ),
            ),
        )
        assertTrue(units[0].isChapterTitle)
        assertEquals(null, units[0].map)
        assertEquals(0, units[1].map!!.positionAt(0))
        assertEquals(1, units[1].map!!.positionAt(1))
    }

    // ── 游标定位（起点裁剪的入口） ──

    @Test
    fun `游标定位-命中单元内部`() {
        val units = ReadAloudUnitSource.fromParagraphs(
            listOf(paragraph("abcdef", 0), paragraph("ghij", 7)),
        )
        assertEquals(0 to 3, ReadAloudUnitSource.cursorAt(units, 3))
        assertEquals(1 to 2, ReadAloudUnitSource.cursorAt(units, 9))
    }

    @Test
    fun `游标定位-分隔符间隙对齐到下一段首字`() {
        val units = ReadAloudUnitSource.fromParagraphs(
            listOf(paragraph("abcdef", 0), paragraph("ghij", 7)),
        )
        // 位置 6 是 '\n'：对齐到第二段首字
        assertEquals(1 to 0, ReadAloudUnitSource.cursorAt(units, 6))
    }

    @Test
    fun `游标定位-早于首段对齐首字越界收敛末字`() {
        val units = ReadAloudUnitSource.fromParagraphs(
            listOf(paragraph("abcdef", 10), paragraph("ghij", 17)),
        )
        assertEquals(0 to 0, ReadAloudUnitSource.cursorAt(units, 0))
        assertEquals(0 to 0, ReadAloudUnitSource.cursorAt(units, 9))
        assertEquals(1 to 3, ReadAloudUnitSource.cursorAt(units, 999))
    }

    @Test
    fun `游标定位-标题单元不参与正文定位`() {
        val units = listOf(
            PlaybackUnit.chapterTitle("标题"),
        ) + ReadAloudUnitSource.fromParagraphs(listOf(paragraph("正文", 0)))
        assertEquals(1 to 0, ReadAloudUnitSource.cursorAt(units, 0))
    }

    // ── 清洗单元（混合路径）：事故复现 + 逐字恒等不变量 ──

    private fun incidentChapter(): Pair<String, ReaderReadAloudChapter> {
        val paragraphs = listOf(
            "　　赵阳林快步而前，脚下布鞋在草皮上滑动。",
            "　　四个守灵卫，则全部朝向梁丽进行逼近，他们打算先将梁丽斩杀，再去助阵赵阳林。",
            "　　梁丽重心下压，一双匕首微微发颤。",
            "　　就在这时，本处于进攻状态的守灵卫，一个个内心警兆升腾，全部转身，将兵器格挡于身前，化进攻为防御。",
            "　　赵毅能和赵阳林打得有来有回，靠的从来不是蛮力。",
        )
        val semantic = paragraphs.joinToString("\n")
        val chapter = ReaderReadAloudChapter.create(
            chapterIndex = 315,
            title = "第三百零七章",
            semanticContent = semantic,
            pageStarts = listOf(0),
            contentSplitMode = ReadAloudContentSplitMode.Paragraph,
        )
        return semantic to chapter
    }

    /** 主不变量：任何单元的任何偏移查表得到的章节位置，其原文必须与单元文本一致。 */
    private fun assertCharByCharExact(semantic: String, units: List<PlaybackUnit>) {
        units.forEach { unit ->
            val map = unit.map ?: return@forEach
            for (i in unit.text.indices) {
                val position = map.positionAt(i)
                assertEquals(
                    "unit[${unit.text.take(6)}…] offset=$i",
                    unit.text[i],
                    semantic[position],
                )
            }
        }
    }

    @Test
    fun `事故复现-起点9276必须落在梁而不是行`() {
        val (semantic, chapter) = incidentChapter()
        val units = ReadAloudUnitSource.fromChapterCleaned(
            paragraphs = chapter.paragraphs(splitByPage = false),
            firstTargets = listOf(20, 40, 60, 80),
            target = 80,
        )
        assertCharByCharExact(semantic, units)

        val liang = semantic.indexOf("梁丽进行逼近")
        val cursor = ReadAloudUnitSource.cursorAt(units, liang)
        assertNotNull(cursor)
        val (index, offset) = cursor!!
        // 裁剪前：请求位置的查表命中必须精确（旧实现这里多跳 3 个字符从"行"开始）
        assertEquals(liang, units[index].map!!.positionAt(offset))
        // 准备期裁剪（与 newReadAloud 相同）后：首字必须是"梁"
        val cropped = units[index].let {
            it.copy(text = it.text.substring(offset), map = it.map!!.advancedBy(offset))
        }
        assertTrue(cropped.text.startsWith("梁丽进行逼近"))
        assertEquals(liang, cropped.map!!.positionAt(0))
    }

    @Test
    fun `事故复现-念到就在这时时位置精确落在本段`() {
        val (semantic, chapter) = incidentChapter()
        val units = ReadAloudUnitSource.fromChapterCleaned(
            paragraphs = chapter.paragraphs(splitByPage = false),
            firstTargets = listOf(20, 40, 60, 80),
            target = 80,
        )
        val jiu = semantic.indexOf("就在这时")
        val hit = units.asSequence()
            .flatMapIndexed { index, unit ->
                val map = unit.map ?: return@flatMapIndexed emptySequence<Pair<Int, Int>>().asSequence()
                unit.text.indices.asSequence().map { i -> index to map.positionAt(i) }
            }
            .firstOrNull { it.second == jiu }
        assertNotNull("没有任何单元位置命中'就在这时'", hit)
        assertEquals('就', units[hit!!.first].text[UnitOffsetOf(units[hit.first], jiu)])
    }

    private fun UnitOffsetOf(unit: PlaybackUnit, position: Int): Int =
        unit.map!!.offsetAt(position)

    @Test
    fun `清洗单元存在跨段锚点且阶梯全局推进`() {
        val (semantic, chapter) = incidentChapter()
        val units = ReadAloudUnitSource.fromChapterCleaned(
            paragraphs = chapter.paragraphs(splitByPage = false),
            firstTargets = listOf(20, 40, 60, 80),
            target = 80,
        )
        assertTrue("应有跨段单元（锚点>1）", units.any { (it.map?.anchors?.size ?: 0) > 1 })
        assertTrue("应有恒等单元（锚点=1）", units.any { it.map?.anchors?.size == 1 })
        // 覆盖率：清洗文本拼接应还原全章正文（空白被删）
        val cleaned = units.joinToString("") { it.text }
        assertTrue(cleaned.isNotEmpty())
        assertEquals(semantic.replace("\n", "").replace("　", ""), cleaned.replace("　", ""))
        assertCharByCharExact(semantic, units)
    }

    @Test
    fun `清洗空章节返回空`() {
        val units = ReadAloudUnitSource.fromChapterCleaned(
            paragraphs = emptyList(),
            firstTargets = listOf(20, 40, 80),
            target = 80,
        )
        assertTrue(units.isEmpty())
    }
}
