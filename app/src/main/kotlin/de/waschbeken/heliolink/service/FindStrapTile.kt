package de.waschbeken.heliolink.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import de.waschbeken.heliolink.app

/** Quick settings tile: lets the strap vibrate to find it, without opening the app. */
class FindStrapTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val connected = app.repo.state.value.connected
        tile.state = if (connected) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = "Strap finden"
        tile.updateTile()
    }

    override fun onClick() {
        val controller = app.repo.controller
        if (controller == null || !app.repo.state.value.connected) {
            Toast.makeText(this, "Strap nicht verbunden", Toast.LENGTH_SHORT).show()
            return
        }
        controller.findStrap()
        Toast.makeText(this, "Strap vibriert", Toast.LENGTH_SHORT).show()
    }
}
