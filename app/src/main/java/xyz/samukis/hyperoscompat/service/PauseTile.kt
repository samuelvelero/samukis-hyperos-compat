package xyz.samukis.hyperoscompat.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.prefs.Settings

/** Quick Settings tile: one tap pauses or resumes every island. */
class PauseTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        val settings = Settings(this)
        settings.paused = !settings.paused
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val paused = Settings(this).paused
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (paused) R.string.tile_paused else R.string.tile_active)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_island)
        tile.updateTile()
    }
}
