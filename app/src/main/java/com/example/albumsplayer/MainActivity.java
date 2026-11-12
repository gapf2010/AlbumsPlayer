package com.example.albumsplayer;

import android.Manifest;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private ImageView cover;
    private TextView title;
    private TextView artist;
    private TextView mode;
    private TextView elapsed;
    private TextView duration;
    private SeekBar seekBar;
    private ImageButton playPause;
    private MaterialButton nextGroup;
    private View groupRow;
    private MaterialSwitch autoContinue;
    private PlaybackStateStore stateStore;
    private ListView songList;
    private TextView listHeader;
    @Nullable
    private AlbumTrackAdapter trackAdapter;
    private long shownAlbumId = -1;
    /** False in landscape: the tracks open in a pop-up instead of a list under the controls. */
    private boolean inlineTrackList;
    private final Random random = new Random();

    private ListenableFuture<MediaController> controllerFuture;
    @Nullable
    private MediaController controller;
    @Nullable
    private MusicLibrary library;
    private boolean userIsSeeking;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Refreshes the song list whenever the library is rescanned (e.g. new music was added). */
    private final Consumer<MusicLibrary> libraryListener = this::showLibrary;

    /** The service stores the current queue source in prefs; mirror it in the "mode" label. */
    private SharedPreferences statePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (prefs, key) -> {
        if (PlaybackStateStore.KEY_SOURCE.equals(key)) {
            updateModeLabel();
        } else if (PlaybackStateStore.KEY_AUTO_CONTINUE.equals(key)) {
            autoContinue.setChecked(stateStore.isAutoContinue()); // toggled from notification/Auto
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            updateUi(events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
                    || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION));
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasAudioPermission()) {
                    loadLibrary();
                } else {
                    Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cover = findViewById(R.id.cover);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        mode = findViewById(R.id.mode);
        elapsed = findViewById(R.id.elapsed);
        duration = findViewById(R.id.duration);
        seekBar = findViewById(R.id.seek_bar);
        playPause = findViewById(R.id.play_pause);
        songList = findViewById(R.id.song_list);
        listHeader = findViewById(R.id.list_header);
        inlineTrackList = getResources().getBoolean(R.bool.inline_track_list);
        if (!inlineTrackList) {
            listHeader.setOnClickListener(v -> showTrackDialog()); // landscape
        }
        ImageButton prev = findViewById(R.id.prev);
        ImageButton next = findViewById(R.id.next);
        ImageButton stop = findViewById(R.id.stop);

        playPause.setOnClickListener(v -> togglePlayPause());
        stop.setOnClickListener(v -> stopPlayback());

        // Tap = previous/next song. Long-press = rewind/fast-forward 10 seconds.
        // (seekToPrevious restarts the current song if it's been playing for more than ~3 s.)
        prev.setOnClickListener(v -> { if (controller != null) controller.seekToPrevious(); });
        prev.setOnLongClickListener(v -> { if (controller != null) controller.seekBack(); return true; });
        next.setOnClickListener(v -> { if (controller != null) controller.seekToNext(); });
        next.setOnLongClickListener(v -> { if (controller != null) controller.seekForward(); return true; });

        stateStore = new PlaybackStateStore(this);
        groupRow = findViewById(R.id.group_row);
        autoContinue = findViewById(R.id.auto_continue);
        autoContinue.setChecked(stateStore.isAutoContinue());
        autoContinue.setOnCheckedChangeListener((button, checked) -> {
            if (checked != stateStore.isAutoContinue()) {
                stateStore.setAutoContinue(checked);
            }
        });

        nextGroup = findViewById(R.id.next_group);
        nextGroup.setOnClickListener(v -> {
            if (controller != null) {
                controller.sendCustomCommand(PlaybackService.NEXT_GROUP_COMMAND, Bundle.EMPTY);
            }
        });

        findViewById(R.id.shuffle_all).setOnClickListener(v -> playMediaId(LibraryTree.SHUFFLE_ALL));
        // Tap: start a random unplayed artist/album right away. Long-press: choose one yourself.
        View shuffleArtist = findViewById(R.id.shuffle_artist);
        shuffleArtist.setOnClickListener(v -> playRandomArtist());
        shuffleArtist.setOnLongClickListener(v -> { pickArtistToShuffle(); return true; });
        View shuffleAlbum = findViewById(R.id.shuffle_album);
        shuffleAlbum.setOnClickListener(v -> playRandomAlbum());
        shuffleAlbum.setOnLongClickListener(v -> { pickAlbumToShuffle(); return true; });

        songList.setOnItemClickListener((parent, view, position, id) ->
                playTrack((MusicLibrary.Song) parent.getItemAtPosition(position)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    elapsed.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userIsSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userIsSeeking = false;
                if (controller != null) {
                    controller.seekTo(bar.getProgress());
                }
            }
        });

        statePrefs = PlaybackStateStore.prefs(this);
        statePrefs.registerOnSharedPreferenceChangeListener(prefsListener);
        MusicLibrary.addListener(libraryListener);
        updateModeLabel();

        requestPermissionsIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                updateUi(true);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Could not connect to player", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
        handler.post(progressTick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(progressTick);
        if (controller != null) {
            controller.removeListener(playerListener);
        }
        MediaController.releaseFuture(controllerFuture);
        controller = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        MusicLibrary.removeListener(libraryListener);
        statePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        io.shutdown();
        super.onDestroy();
    }

    // ---- Permissions & library ----

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsIfNeeded() {
        List<String> needed = new ArrayList<>();
        if (!hasAudioPermission()) {
            needed.add(audioPermission());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            loadLibrary();
        } else {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    /** Rescans MediaStore; the result arrives via {@link #libraryListener}. */
    private void loadLibrary() {
        io.execute(() -> MusicLibrary.reload(getApplicationContext()));
    }

    private void showLibrary(MusicLibrary lib) {
        boolean firstLoad = library == null;
        library = lib;
        updateTrackList(true); // album contents may have changed
        updateModeLabel();
        if (firstLoad && lib.getSongs().isEmpty()) {
            Toast.makeText(this, R.string.no_songs, Toast.LENGTH_LONG).show();
        }
    }

    private void updateModeLabel() {
        String source = statePrefs.getString(PlaybackStateStore.KEY_SOURCE, null);
        mode.setText(LibraryTree.describeSource(library, source));

        LibraryTree.Group group = LibraryTree.groupOf(source);
        if (group == null) {
            groupRow.setVisibility(View.GONE);
        } else {
            boolean artist = group == LibraryTree.Group.ARTIST;
            nextGroup.setText(artist ? R.string.next_artist : R.string.next_album);
            nextGroup.setIconResource(artist ? R.drawable.ic_next_artist : R.drawable.ic_next_group);
            groupRow.setVisibility(View.VISIBLE);
        }
    }

    // ---- Actions ----

    /** Sends a library media ID to the service, which resolves it into a queue and plays it. */
    private void playMediaId(String mediaId) {
        if (controller == null) {
            return;
        }
        controller.setMediaItem(new MediaItem.Builder().setMediaId(mediaId).build());
        controller.prepare();
        controller.play();
    }

    private void togglePlayPause() {
        if (controller == null) {
            return;
        }
        if (controller.getMediaItemCount() == 0) {
            playMediaId(LibraryTree.SHUFFLE_ALL); // nothing queued yet: shuffle everything
            return;
        }
        int state = controller.getPlaybackState();
        if (isActivelyPlaying()) {
            controller.pause();
        } else {
            if (state == Player.STATE_IDLE) {
                controller.prepare();
            } else if (state == Player.STATE_ENDED) {
                controller.seekToDefaultPosition(0);
            }
            controller.play();
        }
    }

    private void stopPlayback() {
        if (controller == null) {
            return;
        }
        controller.stop();
        controller.seekTo(0);
        updateUi(false);
    }

    private boolean libraryIsEmpty() {
        if (library == null || library.getSongs().isEmpty()) {
            Toast.makeText(this, R.string.no_songs, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private void playRandomArtist() {
        if (!libraryIsEmpty()) {
            playMediaId(LibraryTree.shuffleArtistId(randomUnplayedArtist(library.getArtists())));
        }
    }

    private void playRandomAlbum() {
        if (!libraryIsEmpty()) {
            playMediaId(LibraryTree.shuffleAlbumId(randomUnplayedAlbum(library.getAlbums()).id));
        }
    }

    /** Long-press on "Artist": choose a specific artist (then random artists continue). */
    private void pickArtistToShuffle() {
        if (libraryIsEmpty()) {
            return;
        }
        List<String> artists = library.getArtists();
        String[] labels = artists.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pick_artist)
                .setItems(labels, (d, which) -> playMediaId(LibraryTree.shuffleArtistId(artists.get(which))))
                .show();
    }

    /** Long-press on "Album": choose a specific album (then random albums continue). */
    private void pickAlbumToShuffle() {
        if (libraryIsEmpty()) {
            return;
        }
        List<MusicLibrary.Album> albums = library.getAlbums();
        String[] labels = new String[albums.size()];
        for (int i = 0; i < albums.size(); i++) {
            labels[i] = albums.get(i).toString();
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pick_album)
                .setItems(labels, (d, which) -> playMediaId(LibraryTree.shuffleAlbumId(albums.get(which).id)))
                .show();
    }

    /**
     * Random artist not yet played in this round. When all have been played, the history resets
     * and a new round starts (same rule as Next artist / auto-continue).
     */
    private String randomUnplayedArtist(List<String> artists) {
        Set<String> played = stateStore.loadPlayed(LibraryTree.Group.ARTIST);
        List<String> fresh = new ArrayList<>();
        for (String a : artists) {
            if (!played.contains(LibraryTree.groupKey(LibraryTree.artistId(a)))) {
                fresh.add(a);
            }
        }
        if (fresh.isEmpty()) {
            stateStore.resetPlayed(LibraryTree.Group.ARTIST, null);
            fresh = artists;
        }
        return fresh.get(random.nextInt(fresh.size()));
    }

    /** Random album not yet played in this round (new round when all have been played). */
    private MusicLibrary.Album randomUnplayedAlbum(List<MusicLibrary.Album> albums) {
        Set<String> played = stateStore.loadPlayed(LibraryTree.Group.ALBUM);
        List<MusicLibrary.Album> fresh = new ArrayList<>();
        for (MusicLibrary.Album a : albums) {
            if (!played.contains(LibraryTree.groupKey(LibraryTree.albumId(a.id)))) {
                fresh.add(a);
            }
        }
        if (fresh.isEmpty()) {
            stateStore.resetPlayed(LibraryTree.Group.ALBUM, null);
            fresh = albums;
        }
        return fresh.get(random.nextInt(fresh.size()));
    }

    // ---- Played-history reset (overflow menu) ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_reset_history) {
            showResetHistoryDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showResetHistoryDialog() {
        if (library == null || library.getSongs().isEmpty()) {
            Toast.makeText(this, R.string.no_songs, Toast.LENGTH_SHORT).show();
            return;
        }
        // Count only artists/albums still in the library (the history may mention deleted ones).
        List<String> artists = library.getArtists();
        Set<String> playedArtists = stateStore.loadPlayed(LibraryTree.Group.ARTIST);
        int artistsPlayed = 0;
        for (String a : artists) {
            if (playedArtists.contains(LibraryTree.groupKey(LibraryTree.artistId(a)))) {
                artistsPlayed++;
            }
        }
        List<MusicLibrary.Album> albums = library.getAlbums();
        Set<String> playedAlbums = stateStore.loadPlayed(LibraryTree.Group.ALBUM);
        int albumsPlayed = 0;
        for (MusicLibrary.Album a : albums) {
            if (playedAlbums.contains(LibraryTree.groupKey(LibraryTree.albumId(a.id)))) {
                albumsPlayed++;
            }
        }

        String[] options = {
                getString(R.string.reset_artists, artistsPlayed, artists.size()),
                getString(R.string.reset_albums, albumsPlayed, albums.size()),
                getString(R.string.reset_both),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_history_title)
                .setItems(options, (d, which) -> {
                    if (which == 0 || which == 2) {
                        resetHistory(LibraryTree.Group.ARTIST);
                    }
                    if (which == 1 || which == 2) {
                        resetHistory(LibraryTree.Group.ALBUM);
                    }
                    clearPlayer();
                    Toast.makeText(this, R.string.history_reset_done, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Clears the history completely (playback is stopped too, so nothing is "current"). */
    private void resetHistory(LibraryTree.Group group) {
        stateStore.resetPlayed(group, null);
    }

    /**
     * Back to the start screen: stops playback and empties the queue. The service then saves the
     * empty queue and clears the mode, so nothing is restored on the next launch either.
     */
    private void clearPlayer() {
        if (controller != null) {
            controller.stop();
            controller.clearMediaItems();
        }
        updateUi(true);
    }

    // ---- UI updates ----

    private boolean isActivelyPlaying() {
        if (controller == null) {
            return false;
        }
        int state = controller.getPlaybackState();
        return controller.getPlayWhenReady() && state != Player.STATE_IDLE && state != Player.STATE_ENDED;
    }

    private void updateUi(boolean metadataChanged) {
        if (controller == null) {
            return;
        }
        MediaMetadata md = controller.getMediaMetadata();
        title.setText(md.title != null ? md.title : getString(R.string.nothing_playing));
        artist.setText(md.artist != null ? md.artist : "");
        playPause.setImageResource(isActivelyPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        if (metadataChanged) {
            showArtwork(md);
        }
        updateTrackList(false);
        updateProgress();
    }

    // ---- Lower area: file count when idle, current album's tracks while a song is loaded ----

    @Nullable
    private MusicLibrary.Song currentSong() {
        if (controller == null || library == null) {
            return null;
        }
        MediaItem item = controller.getCurrentMediaItem();
        long id = item == null ? -1 : LibraryTree.songIdOf(item.mediaId);
        return id < 0 ? null : library.getSong(id);
    }

    /** @param force rebuild the album list even if it's the same album (e.g. after a rescan). */
    private void updateTrackList(boolean force) {
        MusicLibrary.Song song = currentSong();
        MusicLibrary.Album album = song == null ? null : library.getAlbum(song.albumId);
        if (album == null) {
            songList.setVisibility(View.GONE);
            trackAdapter = null;
            shownAlbumId = -1;
            int count = library == null ? 0 : library.getSongs().size();
            listHeader.setText(library == null ? ""
                    : getResources().getQuantityString(R.plurals.music_files_found, count, count));
            return;
        }

        boolean newAlbum = force || trackAdapter == null || album.id != shownAlbumId;
        if (newAlbum) {
            trackAdapter = new AlbumTrackAdapter(this, album.songs);
            songList.setAdapter(trackAdapter);
            shownAlbumId = album.id;
            listHeader.setText(getString(R.string.album_header, album.title, album.artist));
            songList.setVisibility(inlineTrackList ? View.VISIBLE : View.GONE);
        }
        boolean moved = trackAdapter.setCurrentSong(song.id);
        int pos = trackAdapter.currentPosition();
        if (pos >= 0) {
            if (newAlbum) {
                songList.setSelection(Math.max(0, pos - 1)); // jump, keeping one track above visible
            } else if (moved) {
                songList.smoothScrollToPosition(pos);
            }
        }
    }

    /** Landscape: the current album's tracks in a pop-up (highlight keeps updating while open). */
    private void showTrackDialog() {
        if (trackAdapter == null) {
            return; // nothing loaded: the header is just the file count
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(listHeader.getText())
                .setAdapter(trackAdapter, (d, which) -> playTrack(trackAdapter.getItem(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        int pos = trackAdapter.currentPosition();
        if (pos >= 0) {
            dialog.getListView().setSelection(Math.max(0, pos - 1));
        }
    }

    /** Tapping a track: jump to it in the current queue so the current mode keeps going. */
    private void playTrack(MusicLibrary.Song song) {
        if (controller == null) {
            return;
        }
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            if (LibraryTree.songIdOf(controller.getMediaItemAt(i).mediaId) == song.id) {
                if (controller.getPlaybackState() == Player.STATE_IDLE) {
                    controller.prepare();
                }
                controller.seekTo(i, 0);
                controller.play();
                return;
            }
        }
        // Not in the queue (rare, e.g. compilations in artist mode): play its album from here.
        playMediaId(LibraryTree.songId(LibraryTree.albumId(song.albumId), song.id));
    }

    /** Shows the cover art embedded in the file, falling back to a placeholder. */
    private void showArtwork(MediaMetadata md) {
        // ExoPlayer reads the ID3 APIC frame (embedded picture) into artworkData automatically.
        if (md.artworkData != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(md.artworkData, 0, md.artworkData.length);
            if (bitmap != null) {
                cover.setImageBitmap(bitmap);
                return;
            }
        }
        // Before the track has loaded, try the ArtworkProvider URI instead.
        if (md.artworkUri != null) {
            cover.setImageURI(null);
            cover.setImageURI(md.artworkUri);
            if (cover.getDrawable() != null) {
                return;
            }
        }
        cover.setImageResource(R.drawable.ic_album_placeholder);
    }

    private void updateProgress() {
        if (controller == null) {
            return;
        }
        long dur = controller.getDuration();
        if (dur == C.TIME_UNSET || dur < 0) {
            dur = 0;
        }
        long pos = Math.max(0, controller.getCurrentPosition());
        seekBar.setMax((int) dur);
        if (!userIsSeeking) {
            seekBar.setProgress((int) Math.min(pos, dur));
            elapsed.setText(formatTime(pos));
        }
        duration.setText(formatTime(dur));
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
