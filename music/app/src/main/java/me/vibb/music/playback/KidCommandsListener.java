package me.vibb.music.playback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import java.util.List;
import kotlin.jvm.functions.Function0;

/**
 * One listener of {@code KidPlayer} as the session sees it (r12 emulator rounds E-1 and E-1-Q-1). Media3's
 * {@code ForwardingPlayer} hands a listener the <em>wrapped</em> player's commands in
 * {@link #onAvailableCommandsChanged}, and the session sends exactly those to every controller (the app's own, the
 * launcher's lock tile, the notification); ExoPlayer drops {@code COMMAND_SEEK_TO_NEXT} on a queue's last item, so a
 * controller's next never reached {@code KidPlayer.seekToNext}. This forwards <em>every</em> {@link Player.Listener}
 * method to the inner listener unchanged - as Media3's own {@code ForwardingPlayer.ForwardingListener} does - except that
 * one, which gets {@code KidPlayer}'s commands instead.
 *
 * <p>Java on purpose: Kotlin's {@code by} delegation generates no forwarding for an interface's Java default methods (all
 * of {@code Player.Listener}'s are), so a delegating Kotlin class silently swallowed the session's play state, items,
 * timeline, metadata, positions and errors (qa-r12-music-code E-1-Q-1). {@code KidPlayerTest} calls every method of the
 * interface by reflection, so a Media3 bump that adds one fails the build until it is forwarded here.
 */
@SuppressWarnings("deprecation")
final class KidCommandsListener implements Player.Listener {
    final Player.Listener inner;
    private final Function0<Player.Commands> commands;

    KidCommandsListener(Player.Listener inner, Function0<Player.Commands> commands) {
        this.inner = inner;
        this.commands = commands;
    }

    @Override
    public void onAvailableCommandsChanged(@NonNull Player.Commands availableCommands) {
        inner.onAvailableCommandsChanged(commands.invoke());
    }

    // ForwardingPlayer's own listener already passes KidPlayer here: forwarded as it comes.
    @Override public void onEvents(@NonNull Player player, @NonNull Player.Events events) { inner.onEvents(player, events); }
    @Override public void onTimelineChanged(@NonNull Timeline timeline, int reason) { inner.onTimelineChanged(timeline, reason); }
    @Override public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) { inner.onMediaItemTransition(mediaItem, reason); }
    @Override public void onTracksChanged(@NonNull Tracks tracks) { inner.onTracksChanged(tracks); }
    @Override public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) { inner.onMediaMetadataChanged(mediaMetadata); }
    @Override public void onPlaylistMetadataChanged(@NonNull MediaMetadata mediaMetadata) { inner.onPlaylistMetadataChanged(mediaMetadata); }
    @Override public void onIsLoadingChanged(boolean isLoading) { inner.onIsLoadingChanged(isLoading); }
    @Override public void onLoadingChanged(boolean isLoading) { inner.onLoadingChanged(isLoading); }
    @Override public void onTrackSelectionParametersChanged(@NonNull TrackSelectionParameters parameters) { inner.onTrackSelectionParametersChanged(parameters); }
    @Override public void onPlayerStateChanged(boolean playWhenReady, int playbackState) { inner.onPlayerStateChanged(playWhenReady, playbackState); }
    @Override public void onPlaybackStateChanged(int playbackState) { inner.onPlaybackStateChanged(playbackState); }
    @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) { inner.onPlayWhenReadyChanged(playWhenReady, reason); }
    @Override public void onPlaybackSuppressionReasonChanged(int reason) { inner.onPlaybackSuppressionReasonChanged(reason); }
    @Override public void onIsPlayingChanged(boolean isPlaying) { inner.onIsPlayingChanged(isPlaying); }
    @Override public void onRepeatModeChanged(int repeatMode) { inner.onRepeatModeChanged(repeatMode); }
    @Override public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) { inner.onShuffleModeEnabledChanged(shuffleModeEnabled); }
    @Override public void onPlayerError(@NonNull PlaybackException error) { inner.onPlayerError(error); }
    @Override public void onPlayerErrorChanged(@Nullable PlaybackException error) { inner.onPlayerErrorChanged(error); }
    @Override public void onPositionDiscontinuity(int reason) { inner.onPositionDiscontinuity(reason); }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
        inner.onPositionDiscontinuity(oldPosition, newPosition, reason);
    }

    @Override public void onPlaybackParametersChanged(@NonNull PlaybackParameters parameters) { inner.onPlaybackParametersChanged(parameters); }
    @Override public void onSeekBackIncrementChanged(long ms) { inner.onSeekBackIncrementChanged(ms); }
    @Override public void onSeekForwardIncrementChanged(long ms) { inner.onSeekForwardIncrementChanged(ms); }
    @Override public void onMaxSeekToPreviousPositionChanged(long ms) { inner.onMaxSeekToPreviousPositionChanged(ms); }
    @Override public void onAudioSessionIdChanged(int audioSessionId) { inner.onAudioSessionIdChanged(audioSessionId); }
    @Override public void onAudioAttributesChanged(@NonNull AudioAttributes audioAttributes) { inner.onAudioAttributesChanged(audioAttributes); }
    @Override public void onVolumeChanged(float volume) { inner.onVolumeChanged(volume); }
    @Override public void onSkipSilenceEnabledChanged(boolean enabled) { inner.onSkipSilenceEnabledChanged(enabled); }
    @Override public void onDeviceInfoChanged(@NonNull DeviceInfo deviceInfo) { inner.onDeviceInfoChanged(deviceInfo); }
    @Override public void onDeviceVolumeChanged(int volume, boolean muted) { inner.onDeviceVolumeChanged(volume, muted); }
    @Override public void onVideoSizeChanged(@NonNull VideoSize videoSize) { inner.onVideoSizeChanged(videoSize); }
    @Override public void onSurfaceSizeChanged(int width, int height) { inner.onSurfaceSizeChanged(width, height); }
    @Override public void onRenderedFirstFrame() { inner.onRenderedFirstFrame(); }
    @Override public void onCues(@NonNull List<Cue> cues) { inner.onCues(cues); }
    @Override public void onCues(@NonNull CueGroup cueGroup) { inner.onCues(cueGroup); }
    @Override public void onMetadata(@NonNull Metadata metadata) { inner.onMetadata(metadata); }

    // Media3's own ForwardingListener compares by what it wraps: a removal with the same inner finds this one.
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof KidCommandsListener && inner.equals(((KidCommandsListener) o).inner));
    }

    @Override
    public int hashCode() {
        return inner.hashCode();
    }
}
