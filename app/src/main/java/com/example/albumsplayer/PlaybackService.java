package com.example.albumsplayer;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Owns the ExoPlayer and exposes it through a MediaLibrarySession. This single service powers
 * the in-app controls, the media notification / lock screen, Bluetooth buttons and Android Auto.
 *
 * It also persists the queue (see {@link PlaybackStateStore}) and keeps it in sync with the
 * device's music library: new songs that match the current source are added, deleted ones removed.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlaybackService extends MediaLibraryService {

    private static final long SEEK_INCREMENT_MS = 10_000;
    private static final long POSITION_SAVE_INTERVAL_MS = 5_000;
    private static final long RESCAN_DELAY_MS = 3_000;

    /** Custom command: jump to a random different artist/album (sent by the app, notification, Auto). */
    static final String CMD_NEXT_GROUP = "com.example.albumsplayer.NEXT_GROUP";
    static final SessionCommand NEXT_GROUP_COMMAND = new SessionCommand(CMD_NEXT_GROUP, Bundle.EMPTY);

    /** Custom command: flip the auto-continue setting (notification / Android Auto button). */
    static final String CMD_TOGGLE_AUTO_CONTINUE = "com.example.albumsplayer.TOGGLE_AUTO_CONTINUE";
    static final SessionCommand TOGGLE_AUTO_CONTINUE_COMMAND =
            new SessionCommand(CMD_TOGGLE_AUTO_CONTINUE, Bundle.EMPTY);

    /** Keeps the notification / Auto toggle in sync when the setting changes in the app. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (prefs, key) -> {
        if (PlaybackStateStore.KEY_AUTO_CONTINUE.equals(key)) {
            updateCustomLayout();
        }
    };

    private MediaLibrarySession session;
    private PlaybackStateStore store;
    private final ListeningExecutorService executor =
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private boolean destroyed;

    /** Where the current queue came from, e.g. "shuffle_all" or "album/12". Null = unknown. */
    @Nullable
    private volatile String currentSource;

    private final Runnable periodicSave = new Runnable() {
        @Override
        public void run() {
            savePosition();
            mainHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS);
        }
    };

    /** MediaStore changes arrive in bursts while files are scanned, so wait for things to settle. */
    private final Runnable rescan = () -> executor.execute(() -> MusicLibrary.reload(this));

    private final ContentObserver libraryObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mainHandler.removeCallbacks(rescan);
            mainHandler.postDelayed(rescan, RESCAN_DELAY_MS);
        }
    };

    private final Consumer<MusicLibrary> libraryListener = this::applyLibraryChanges;

    @Override
    public void onCreate() {
        super.onCreate();
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
                .build();
        player.addListener(new PersistingListener());

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaLibrarySession.Builder(this, player, new LibraryCallback())
                .setSessionActivity(openApp)
                .build();

        store = new PlaybackStateStore(this);
        currentSource = store.loadSource();
        PlaybackStateStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener);
        updateCustomLayout();
        MusicLibrary.addListener(libraryListener);
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, libraryObserver);
        restoreState();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        savePosition();
        Player player = session.getPlayer();
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        savePosition();
        mainHandler.removeCallbacksAndMessages(null);
        getContentResolver().unregisterContentObserver(libraryObserver);
        MusicLibrary.removeListener(libraryListener);
        PlaybackStateStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        session.getPlayer().release();
        session.release();
        executor.shutdown(); // queued writes still finish
        super.onDestroy();
    }

    // ---- Persistence ----

    private void savePosition() {
        Player p = session.getPlayer();
        if (p.getMediaItemCount() > 0) {
            store.savePosition(p.getCurrentMediaItemIndex(), Math.max(0, p.getCurrentPosition()));
        }
    }

    private void saveQueue() {
        Player p = session.getPlayer();
        List<String> ids = new ArrayList<>(p.getMediaItemCount());
        for (int i = 0; i < p.getMediaItemCount(); i++) {
            ids.add(p.getMediaItemAt(i).mediaId);
        }
        executor.execute(() -> store.saveQueue(ids));
        savePosition();
    }

    private final class PersistingListener implements Player.Listener {
        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                saveQueue();
                if (session.getPlayer().getMediaItemCount() == 0 && currentSource != null) {
                    setSource(null); // queue cleared: no mode any more (hides Next/auto buttons)
                }
            }
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            savePosition();
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition, int reason) {
            savePosition(); // seeks, stop
        }

        /** Auto-continue: when the last song of an artist/album ends, start an unplayed one. */
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED
                    && store.isAutoContinue()
                    && LibraryTree.groupOf(currentSource) != null) {
                executor.submit(PlaybackService.this::playNextGroup);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            savePosition();
            mainHandler.removeCallbacks(periodicSave);
            if (isPlaying) {
                mainHandler.postDelayed(periodicSave, POSITION_SAVE_INTERVAL_MS);
            }
        }
    }

    /** The saved queue rebuilt against the current library (missing files dropped). */
    private static final class SavedQueue {
        final List<MediaItem> items;
        final int index;
        final long positionMs;
        final MusicLibrary library;

        SavedQueue(List<MediaItem> items, int index, long positionMs, MusicLibrary library) {
            this.items = items;
            this.index = index;
            this.positionMs = positionMs;
            this.library = library;
        }
    }

    /** Runs on the background executor. Returns null if there is nothing to restore. */
    @Nullable
    private SavedQueue loadSavedQueue() {
        MusicLibrary lib = MusicLibrary.get(this);
        List<String> ids = store.loadQueue();
        if (ids.isEmpty() || lib.getSongs().isEmpty()) {
            return null;
        }
        int savedIndex = store.loadIndex();
        long savedPosition = store.loadPosition();
        List<MediaItem> items = new ArrayList<>(ids.size());
        int survivorsBefore = 0;
        boolean currentSurvived = false;
        for (int i = 0; i < ids.size(); i++) {
            MediaItem item = LibraryTree.songItemFromId(lib, ids.get(i));
            if (item == null) {
                continue; // file was deleted
            }
            if (i < savedIndex) {
                survivorsBefore++;
            } else if (i == savedIndex) {
                currentSurvived = true;
            }
            items.add(item);
        }
        if (items.isEmpty()) {
            return null;
        }
        int index = Math.min(survivorsBefore, items.size() - 1);
        return new SavedQueue(items, index, currentSurvived ? savedPosition : 0, lib);
    }

    /** Puts the saved queue back into the player (paused), unless something is already queued. */
    private void restoreState() {
        executor.execute(() -> {
            SavedQueue saved = loadSavedQueue();
            if (saved == null) {
                return;
            }
            mainHandler.post(() -> {
                Player p = session.getPlayer();
                if (destroyed || p.getMediaItemCount() > 0) {
                    return; // the user already started something else
                }
                p.setMediaItems(saved.items, saved.index, saved.positionMs);
                p.prepare();
                applyLibraryChanges(saved.library); // pick up music added while we were closed
            });
        });
    }

    // ---- Keeping the queue in sync with the library ----

    /**
     * Called on the main thread whenever the library is reloaded. Removes queued songs whose
     * files are gone and adds new songs that belong to the current source.
     */
    private void applyLibraryChanges(MusicLibrary lib) {
        if (destroyed) {
            return;
        }
        Player p = session.getPlayer();
        if (p.getMediaItemCount() == 0) {
            restoreState(); // e.g. permission was only just granted
            return;
        }
        if (lib.getSongs().isEmpty()) {
            return; // storage not ready / no permission: don't wipe the queue
        }

        // 1. Drop songs that no longer exist.
        Set<Long> queued = new HashSet<>();
        for (int i = p.getMediaItemCount() - 1; i >= 0; i--) {
            long id = LibraryTree.songIdOf(p.getMediaItemAt(i).mediaId);
            if (id >= 0 && lib.getSong(id) == null) {
                p.removeMediaItem(i);
            } else {
                queued.add(id);
            }
        }

        // 2. Add new songs that belong to the current source.
        String source = currentSource;
        if (source == null) {
            return;
        }
        List<MusicLibrary.Song> sourceSongs = LibraryTree.songsForSource(lib, source);
        if (sourceSongs == null) {
            return;
        }
        List<MusicLibrary.Song> newSongs = new ArrayList<>();
        for (MusicLibrary.Song s : sourceSongs) {
            if (!queued.contains(s.id)) {
                newSongs.add(s);
            }
        }
        if (newSongs.isEmpty()) {
            return;
        }

        String parent = LibraryTree.parentForSource(source);
        if (LibraryTree.shufflesSongs(source)) {
            // Shuffle: scatter the new songs randomly among the songs still to be played.
            Collections.shuffle(newSongs, random);
            for (MusicLibrary.Song s : newSongs) {
                int firstUpcoming = p.getCurrentMediaItemIndex() + 1;
                int insertAt = firstUpcoming + random.nextInt(p.getMediaItemCount() - firstUpcoming + 1);
                p.addMediaItem(insertAt, LibraryTree.songItem(LibraryTree.songId(parent, s.id), s));
            }
        } else {
            // In order (albums, lists): put each new song right after the song that precedes it
            // in the source's order, e.g. a new track 4 goes between tracks 3 and 5.
            for (int k = 0; k < sourceSongs.size(); k++) {
                MusicLibrary.Song s = sourceSongs.get(k);
                if (queued.contains(s.id)) {
                    continue;
                }
                int insertAt = 0;
                for (int j = k - 1; j >= 0; j--) {
                    int idx = indexOfSong(p, sourceSongs.get(j).id);
                    if (idx >= 0) {
                        insertAt = idx + 1;
                        break;
                    }
                }
                p.addMediaItem(insertAt, LibraryTree.songItem(LibraryTree.songId(parent, s.id), s));
                queued.add(s.id);
            }
        }
    }

    private static int indexOfSong(Player p, long songId) {
        for (int i = 0; i < p.getMediaItemCount(); i++) {
            if (LibraryTree.songIdOf(p.getMediaItemAt(i).mediaId) == songId) {
                return i;
            }
        }
        return -1;
    }

    // ---- Session callback ----

    private final class LibraryCallback implements MediaLibrarySession.Callback {

        /** Allow every controller (our app, notification, Android Auto) to send NEXT_GROUP. */
        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(
                @NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
            SessionCommands commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(NEXT_GROUP_COMMAND)
                    .add(TOGGLE_AUTO_CONTINUE_COMMAND)
                    .build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build();
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller,
                @NonNull SessionCommand customCommand, @NonNull Bundle args) {
            if (CMD_NEXT_GROUP.equals(customCommand.customAction)) {
                return executor.submit(PlaybackService.this::playNextGroup);
            }
            if (CMD_TOGGLE_AUTO_CONTINUE.equals(customCommand.customAction)) {
                // The prefs listener refreshes the buttons; the app's switch follows too.
                store.setAutoContinue(!store.isAutoContinue());
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @Nullable LibraryParams params) {
            return Futures.immediateFuture(LibraryResult.ofItem(LibraryTree.root(), params));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String parentId, int page, int pageSize, @Nullable LibraryParams params) {
            return executor.submit(() -> {
                MusicLibrary lib = MusicLibrary.get(PlaybackService.this);
                List<MediaItem> children = LibraryTree.children(lib, parentId);
                if (children == null) {
                    return LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE);
                }
                return LibraryResult.ofItemList(paginate(children, page, pageSize), params);
            });
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String mediaId) {
            return executor.submit(() -> {
                MediaItem item = LibraryTree.item(MusicLibrary.get(PlaybackService.this), mediaId);
                return item != null
                        ? LibraryResult.ofItem(item, null)
                        : LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE);
            });
        }

        /**
         * Controllers (our activity, Android Auto) only send media IDs. Here they're turned into a
         * real queue: shuffle IDs expand to a shuffled list, a song ID expands to its whole list.
         * The queue's source is remembered so new music can be added to it later.
         */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                @NonNull MediaSession mediaSession, @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
            return executor.submit(() -> {
                MusicLibrary lib = MusicLibrary.get(PlaybackService.this);
                if (mediaItems.size() == 1) {
                    LibraryTree.Playlist playlist = LibraryTree.resolve(lib, mediaItems.get(0).mediaId);
                    if (playlist != null) {
                        setSource(playlist.source);
                        return new MediaSession.MediaItemsWithStartPosition(
                                playlist.items, playlist.startIndex, C.TIME_UNSET);
                    }
                }
                setSource(null);
                List<MediaItem> resolved = expand(lib, mediaItems);
                int index = (startIndex == C.INDEX_UNSET || startIndex >= resolved.size()) ? 0 : startIndex;
                return new MediaSession.MediaItemsWithStartPosition(resolved, index, startPositionMs);
            });
        }

        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                @NonNull MediaSession mediaSession, @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems) {
            return executor.submit(() -> expand(MusicLibrary.get(PlaybackService.this), mediaItems));
        }

        /**
         * System playback resumption (Android 13+): lets "Play" on Bluetooth, the car or the
         * media controls resume the saved queue after the app was killed or the phone rebooted.
         */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                @NonNull MediaSession mediaSession, @NonNull MediaSession.ControllerInfo controller) {
            return executor.submit(() -> {
                SavedQueue saved = loadSavedQueue();
                if (saved == null) {
                    throw new IllegalStateException("Nothing to resume");
                }
                return new MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs);
            });
        }
    }

    private void setSource(@Nullable String source) {
        currentSource = source;
        store.saveSource(source);
        // Remember this artist/album so "next" and auto-continue don't repeat it.
        LibraryTree.Group group = LibraryTree.groupOf(source);
        if (group != null) {
            store.addPlayed(group, LibraryTree.groupKey(source));
        }
        mainHandler.post(this::updateCustomLayout);
    }

    // ---- "Next artist / Next album" ----

    /** Shows the Next artist/album button in the notification and Android Auto when it applies. */
    private void updateCustomLayout() {
        if (destroyed) {
            return;
        }
        LibraryTree.Group group = LibraryTree.groupOf(currentSource);
        if (group == null) {
            session.setCustomLayout(ImmutableList.of());
            return;
        }
        CommandButton nextButton = new CommandButton.Builder()
                .setDisplayName(getString(group == LibraryTree.Group.ARTIST
                        ? R.string.next_artist : R.string.next_album))
                .setIconResId(group == LibraryTree.Group.ARTIST
                        ? R.drawable.ic_next_artist : R.drawable.ic_next_group)
                .setSessionCommand(NEXT_GROUP_COMMAND)
                .build();
        boolean auto = store.isAutoContinue();
        CommandButton autoButton = new CommandButton.Builder()
                .setDisplayName(getString(auto ? R.string.auto_continue_on : R.string.auto_continue_off))
                .setIconResId(auto ? R.drawable.ic_auto_continue_on : R.drawable.ic_auto_continue_off)
                .setSessionCommand(TOGGLE_AUTO_CONTINUE_COMMAND)
                .build();
        session.setCustomLayout(ImmutableList.of(nextButton, autoButton));
    }

    /**
     * Runs on the executor: picks an artist/album that hasn't been played in this round and
     * starts it. When every one has been played, the history resets and a new round begins.
     */
    private SessionResult playNextGroup() {
        MusicLibrary lib = MusicLibrary.get(this);
        String source = currentSource;
        LibraryTree.Group group = LibraryTree.groupOf(source);
        if (group == null) {
            return new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE);
        }
        String next = LibraryTree.nextGroupSource(lib, source, random, store.loadPlayed(group));
        if (next == null) {
            // Everything has been heard: start a new round (keeping the current one excluded).
            store.resetPlayed(group, LibraryTree.groupKey(source));
            next = LibraryTree.nextGroupSource(lib, source, random, store.loadPlayed(group));
        }
        LibraryTree.Playlist playlist = next == null ? null : LibraryTree.resolve(lib, next);
        if (playlist == null) {
            return new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE);
        }
        setSource(playlist.source);
        mainHandler.post(() -> {
            if (destroyed) {
                return;
            }
            Player p = session.getPlayer();
            p.setMediaItems(playlist.items, playlist.startIndex, C.TIME_UNSET);
            p.prepare();
            p.play();
        });
        return new SessionResult(SessionResult.RESULT_SUCCESS);
    }

    private static List<MediaItem> expand(MusicLibrary lib, List<MediaItem> requested) {
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem item : requested) {
            if (item.localConfiguration != null) {
                out.add(item); // already playable
                continue;
            }
            LibraryTree.Playlist playlist = LibraryTree.resolve(lib, item.mediaId);
            if (playlist == null) {
                continue;
            }
            if (LibraryTree.isSingleSong(item.mediaId)) {
                out.add(playlist.items.get(playlist.startIndex));
            } else {
                out.addAll(playlist.items);
            }
        }
        return out;
    }

    private static <T> List<T> paginate(List<T> list, int page, int pageSize) {
        if (page < 0 || pageSize <= 0) {
            return list;
        }
        long from = (long) page * pageSize;
        if (from >= list.size()) {
            return new ArrayList<>();
        }
        long to = Math.min(list.size(), from + pageSize);
        return list.subList((int) from, (int) to);
    }
}
