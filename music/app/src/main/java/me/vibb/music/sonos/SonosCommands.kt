package me.vibb.music.sonos

import androidx.media3.common.Player

/**
 * What a Sonos room's player offers its controllers (design 26a §3.2, QA M1): play/pause and stop,
 * the four skips (KidPlayer routes them to the service's rules), seeks within and between items, the
 * three GET commands without which the platform session has no metadata (the launcher's card would
 * have no title), and the remote device volume (the phone's volume keys, §5.3).
 */
val SONOS_COMMANDS: IntArray = intArrayOf(
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_STOP,
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_GET_DEVICE_VOLUME,
    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
)
