package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.ui.navigation.NavPlacement
import io.github.tophtab.lspstore.ui.navigation.navPlacement
import io.github.tophtab.lspstore.ui.navigation.selectedTopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationLayoutTest {

    private val roots = setOf("installed", "home", "settings")

    @Test
    fun compactWidthAlwaysGetsTheCompactBar() {
        assertEquals(NavPlacement.CompactBar, navPlacement(widthDp = 599, heightDp = 800, tabletop = false))
        // Compact width wins over a tabletop posture too.
        assertEquals(NavPlacement.CompactBar, navPlacement(widthDp = 599, heightDp = 800, tabletop = true))
    }

    @Test
    fun normalWideWindowGetsTheRail() {
        assertEquals(NavPlacement.Rail, navPlacement(widthDp = 600, heightDp = 800, tabletop = false))
        assertEquals(NavPlacement.Rail, navPlacement(widthDp = 839, heightDp = 800, tabletop = false))
        assertEquals(NavPlacement.Rail, navPlacement(widthDp = 840, heightDp = 800, tabletop = false))
        assertEquals(NavPlacement.Rail, navPlacement(widthDp = 1280, heightDp = 800, tabletop = false))
    }

    @Test
    fun shortWindowKeepsABottomBar() {
        assertEquals(NavPlacement.MediumBar, navPlacement(widthDp = 800, heightDp = 479, tabletop = false))
        assertEquals(NavPlacement.Rail, navPlacement(widthDp = 800, heightDp = 480, tabletop = false))
    }

    @Test
    fun tabletopKeepsABottomBar() {
        assertEquals(NavPlacement.MediumBar, navPlacement(widthDp = 1280, heightDp = 800, tabletop = true))
    }

    @Test
    fun rootRouteSelectsItself() {
        assertEquals("home", selectedTopLevelRoute("home", "installed", roots))
    }

    @Test
    fun detailRouteKeepsTheLastRootSelected() {
        assertEquals("installed", selectedTopLevelRoute("module/app.lsposed.demo", "installed", roots))
        assertEquals("home", selectedTopLevelRoute("author/someone", "home", roots))
        assertEquals("settings", selectedTopLevelRoute("library/favourites", "settings", roots))
    }

    @Test
    fun noRootHistorySelectsNothing() {
        assertNull(selectedTopLevelRoute("module/app.lsposed.demo", null, roots))
    }
}
