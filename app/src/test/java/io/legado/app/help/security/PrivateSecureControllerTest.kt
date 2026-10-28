package io.legado.app.help.security

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FLAG_SECURE 的引用计数信号：
 * 书架与阅读器可能短暂并存（压栈动画），布尔会在其中一侧 dispose 的瞬间错误关掉遮挡。
 */
class PrivateSecureControllerTest {

    @Test
    fun `单个持有者开关翻转`() = runTest {
        val controller = PrivateSecureController()
        assertFalse(controller.secureActive.first())

        controller.retain()
        assertTrue(controller.secureActive.first())

        controller.release()
        assertFalse(controller.secureActive.first())
    }

    @Test
    fun `两个持有者并存时释放一侧仍保持遮挡`() = runTest {
        val controller = PrivateSecureController()
        controller.retain()
        controller.retain()

        controller.release()
        assertTrue(controller.secureActive.first())

        controller.release()
        assertFalse(controller.secureActive.first())
    }

    @Test
    fun `多余释放不会把计数打穿成负`() = runTest {
        val controller = PrivateSecureController()
        controller.release()
        controller.release()
        assertFalse(controller.secureActive.first())

        controller.retain()
        assertTrue(controller.secureActive.first())
    }
}
