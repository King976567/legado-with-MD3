package io.legado.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeDashboardSectionTest {
    @Test
    fun `both optional modules can be restored from saved settings`() {
        assertEquals(
            setOf(HomeDashboardSection.NasLibrary, HomeDashboardSection.DimensionalBookshelf),
            HomeDashboardSection.fromStorage("nas_library,dimensional_bookshelf"),
        )
    }

    @Test
    fun `default and invalid stored settings do not enable optional modules`() {
        assertFalse(HomeDashboardSection.NasLibrary in DEFAULT_HOME_DASHBOARD_SECTIONS)
        assertFalse(HomeDashboardSection.DimensionalBookshelf in DEFAULT_HOME_DASHBOARD_SECTIONS)
        assertEquals(DEFAULT_HOME_DASHBOARD_SECTIONS, HomeDashboardSection.fromStorage("unknown"))
    }

    @Test
    fun `an explicitly empty saved selection stays empty`() {
        assertEquals(emptySet<HomeDashboardSection>(), HomeDashboardSection.fromStorage(""))
    }
}
