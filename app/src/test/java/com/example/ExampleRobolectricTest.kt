package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.DeckRepository
import com.example.model.DeckWidgetCatalog
import com.example.model.PomodoroMode
import com.example.model.PomodoroState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DeskDeck", appName)
    }

    @Test
    fun `parse deskdeck qr pairing uri`() {
        val raw = "deskdeck://pair?host=192.168.1.50&port=9876&token=abc123&name=MyLaptop"
        val uri = Uri.parse(raw)
        assertEquals("deskdeck", uri.scheme)
        assertEquals("pair", uri.host)
        assertEquals("192.168.1.50", uri.getQueryParameter("host"))
        assertEquals("9876", uri.getQueryParameter("port"))
        assertEquals("abc123", uri.getQueryParameter("token"))
        assertEquals("MyLaptop", uri.getQueryParameter("name"))
    }

    @Test
    fun `default layout includes pomodoro timer and multiple swipeable pages`() {
        val layout = DeckRepository.defaultLayout()
        assertTrue(layout.pages.size >= 2)
        val firstPageWidgets = layout.pages.first().widgets
        assertTrue(firstPageWidgets.any { it.type == "POMODORO_TIMER" })
        assertTrue(DeckWidgetCatalog.items.any { it.type == "POMODORO_TIMER" })
    }

    @Test
    fun `pomodoro state defaults to 25 minutes focus`() {
        val state = PomodoroState()
        assertEquals(PomodoroMode.FOCUS, state.mode)
        assertEquals(25 * 60, state.remainingSeconds)
    }
}
