package io.legado.app.ui.main

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigatorReadAloudTest {

    @Test
    fun `opens cloud TTS manager on top of reader`() {
        val reader = MainRouteReadBook(bookUrl = "book")
        val cloudTts = MainRouteCloudTtsEngines(bookUrl = "book")
        val backStack = mutableListOf<NavKey>(MainRouteHome, reader)

        MainNavigator.navigateToRoute(backStack, MainRouteCloudTtsEngines())

        assertEquals(listOf(MainRouteHome, reader, MainRouteCloudTtsEngines()), backStack)
    }

    @Test
    fun `resets to home before cloud TTS manager from unrelated route`() {
        val cloudTts = MainRouteCloudTtsEngines()
        val backStack = mutableListOf<NavKey>(
            MainRouteHome,
            MainRouteSettings,
        )

        MainNavigator.navigateToRoute(backStack, MainRouteCloudTtsEngines())

        assertEquals(listOf(MainRouteHome, MainRouteCloudTtsEngines()), backStack)
    }

    @Test
    fun `opens read aloud settings on top of reader`() {
        val reader = MainRouteReadBook(bookUrl = "book")
        val backStack = mutableListOf<NavKey>(MainRouteHome, reader)

        MainNavigator.navigateToRoute(backStack, MainRouteReadAloudSettings("book"))

        assertEquals(
            listOf(MainRouteHome, reader, MainRouteReadAloudSettings("book")),
            backStack,
        )
    }

    @Test
    fun `opens tts engines on top of read aloud settings instead of resetting to home`() {
        val reader = MainRouteReadBook(bookUrl = "book")
        val settings = MainRouteReadAloudSettings("book")
        val backStack = mutableListOf<NavKey>(MainRouteHome, reader, settings)

        MainNavigator.navigateToRoute(backStack, MainRouteCloudTtsEngines("book"))

        assertEquals(
            listOf(MainRouteHome, reader, settings, MainRouteCloudTtsEngines("book")),
            backStack,
        )
    }

    @Test
    fun `opens tts cache on top of read aloud settings`() {
        val reader = MainRouteReadBook(bookUrl = "book")
        val settings = MainRouteReadAloudSettings("book")
        val backStack = mutableListOf<NavKey>(MainRouteHome, reader, settings)

        MainNavigator.navigateToRoute(backStack, MainRouteTtsCache)

        assertEquals(
            listOf(MainRouteHome, reader, settings, MainRouteTtsCache),
            backStack,
        )
    }

    @Test
    fun `opens voice casting on top of read aloud settings`() {
        val reader = MainRouteReadBook(bookUrl = "book")
        val settings = MainRouteReadAloudSettings("book")
        val backStack = mutableListOf<NavKey>(MainRouteHome, reader, settings)

        MainNavigator.navigateToRoute(backStack, MainRouteBookVoiceCasting("book"))

        assertEquals(
            listOf(MainRouteHome, reader, settings, MainRouteBookVoiceCasting("book")),
            backStack,
        )
    }
}
