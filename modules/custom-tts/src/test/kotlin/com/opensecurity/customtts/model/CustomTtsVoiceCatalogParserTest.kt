package com.opensecurity.customtts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTtsVoiceCatalogParserTest {

    @Test
    fun `解析完整目录`() {
        val json = """
            {"voices":[
              {"id":"zf_xiaobei","name":"小北","gender":"female","description":"甜美女声",
               "is_default":true,"available":true},
              {"id":"v2","name":"旁白","gender":"male","available":false}
            ]}
        """.trimIndent()
        val list = CustomTtsVoiceCatalogParser.parse(json)
        assertEquals(2, list.size)
        assertEquals("zf_xiaobei", list[0].id)
        assertEquals("小北", list[0].name)
        assertEquals("female", list[0].gender)
        assertTrue(list[0].isDefault)
        assertTrue(list[0].available)
        assertEquals("v2", list[1].id)
        assertEquals(false, list[1].available)
    }

    @Test
    fun `缺字段用默认值`() {
        val list = CustomTtsVoiceCatalogParser.parse("""{"voices":[{"id":"v1"}]}""")
        assertEquals(1, list.size)
        assertEquals("v1", list[0].name) // name 缺省 = id
        assertEquals("", list[0].gender)
        assertEquals(false, list[0].isDefault)
        assertEquals(true, list[0].available)
    }

    @Test
    fun `非法条目跳过`() {
        val list = CustomTtsVoiceCatalogParser.parse(
            """{"voices":[{"name":"无 id"},{"id":""},{"id":"ok"}]}"""
        )
        assertEquals(1, list.size)
        assertEquals("ok", list[0].id)
    }

    @Test
    fun `整体非法返回空列表`() {
        assertTrue(CustomTtsVoiceCatalogParser.parse("not json").isEmpty())
        assertTrue(CustomTtsVoiceCatalogParser.parse("{}").isEmpty())
        assertTrue(CustomTtsVoiceCatalogParser.parse("""{"voices":123}""").isEmpty())
        assertTrue(CustomTtsVoiceCatalogParser.parse("[]").isEmpty())
    }
}
