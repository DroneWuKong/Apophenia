package com.dronewukong.apophenia.tile
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationStore
class WeirdTileService:TileService(){override fun onStartListening(){qsTile?.apply{label="That Was Weird";state=Tile.STATE_INACTIVE;updateTile()}};override fun onClick(){val timestamp=System.currentTimeMillis();ObservationStore.repository(this).log(ObservationKind.WEIRD,"That was weird",timestampMs=timestamp,origin=ObservationOrigin.TILE);qsTile?.apply{state=Tile.STATE_ACTIVE;if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)subtitle="Logged";updateTile()}}}
