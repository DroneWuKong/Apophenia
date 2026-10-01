package com.dronewukong.apophenia.tile
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationRepository
class WeirdTileService:TileService(){override fun onStartListening(){qsTile?.apply{label="That Was Weird";state=Tile.STATE_INACTIVE;updateTile()}};override fun onClick(){ObservationRepository(this).log(ObservationKind.WEIRD,"That was weird");qsTile?.apply{state=Tile.STATE_ACTIVE;if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)subtitle="Logged";updateTile()}}}
