package com.example.albumsplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Snapshot of the music files (MP3, WAV, FLAC, OGG) that Android's MediaStore knows about, grouped by artist and album.
 * Shared (in-process) by the activity and the playback service.
 */
public final class MusicLibrary {

    private static final String TAG = "MusicLibrary";

    /**
     * Formats ExoPlayer can play with Android's built-in decoders: MP3, WAV, FLAC, OGG (Vorbis/Opus).
     * APE (Monkey's Audio) isn't supported by Android or ExoPlayer, so it is left out.
     */
    private static final String[] SUPPORTED_MIME_TYPES = {
            "audio/mpeg", "audio/mp3",
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
            "audio/flac", "audio/x-flac",
            "audio/ogg", "application/ogg", "audio/x-ogg", "audio/vorbis", "audio/opus",
    };
    private static final String[] SUPPORTED_EXTENSIONS = {"mp3", "wav", "flac", "ogg", "oga", "opus"};

    public static final class Song {
        public final long id;
        public final String title;
        public final String artist;
        public final String album;
        public final long albumId;
        public final int track;
        public final long durationMs;
        public final Uri uri;

        Song(long id, String title, String artist, String album, long albumId, int track, long durationMs) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.albumId = albumId;
            this.track = track;
            this.durationMs = durationMs;
            this.uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }

        /** Content URI that serves the cover art embedded in this file (see {@link ArtworkProvider}). */
        public Uri artworkUri() {
            return ArtworkProvider.uriFor(id);
        }

        @Override
        public String toString() {
            return title + " — " + artist;
        }
    }

    public static final class Album {
        public final long id;
        public final String title;
        public final String artist;
        public final List<Song> songs = new ArrayList<>();

        Album(long id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }

        @Override
        public String toString() {
            return title + " — " + artist;
        }
    }

    private static MusicLibrary instance;
    private static final List<Consumer<MusicLibrary>> listeners = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<Song> songs;
    private final Map<Long, Song> songsById = new HashMap<>();
    private final Map<String, List<Song>> songsByArtist = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<Long, Album> albumsById = new HashMap<>();
    private final List<Album> albums;

    /** Returns the cached library, querying MediaStore if it hasn't been loaded (or was empty). */
    public static synchronized MusicLibrary get(Context context) {
        if (instance == null || instance.songs.isEmpty()) {
            instance = query(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Forces a fresh MediaStore query (after permission is granted, or when files were added or
     * removed) and notifies listeners on the main thread.
     */
    public static synchronized MusicLibrary reload(Context context) {
        MusicLibrary lib = query(context.getApplicationContext());
        instance = lib;
        mainHandler.post(() -> {
            for (Consumer<MusicLibrary> l : listeners) {
                l.accept(lib);
            }
        });
        return lib;
    }

    /** Called on the main thread after every {@link #reload}. */
    public static void addListener(Consumer<MusicLibrary> listener) {
        listeners.add(listener);
    }

    public static void removeListener(Consumer<MusicLibrary> listener) {
        listeners.remove(listener);
    }

    private MusicLibrary(List<Song> songs) {
        this.songs = Collections.unmodifiableList(songs);
        for (Song s : songs) {
            songsById.put(s.id, s);
            List<Song> artistSongs = songsByArtist.get(s.artist);
            if (artistSongs == null) {
                artistSongs = new ArrayList<>();
                songsByArtist.put(s.artist, artistSongs);
            }
            artistSongs.add(s);

            Album album = albumsById.get(s.albumId);
            if (album == null) {
                album = new Album(s.albumId, s.album, s.artist);
                albumsById.put(s.albumId, album);
            }
            album.songs.add(s);
        }
        List<Album> sortedAlbums = new ArrayList<>(albumsById.values());
        sortedAlbums.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        Comparator<Song> byTrack = Comparator.<Song>comparingInt(s -> s.track)
                .thenComparing(s -> s.title, String.CASE_INSENSITIVE_ORDER);
        for (Album a : sortedAlbums) {
            a.songs.sort(byTrack);
        }
        this.albums = Collections.unmodifiableList(sortedAlbums);
    }

    private static MusicLibrary query(Context context) {
        List<Song> result = new ArrayList<>();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION,
        };
        // Music files in a supported format: matched by MIME type (devices report several variants)
        // or, as a fallback, by file extension.
        StringBuilder selection = new StringBuilder(MediaStore.Audio.Media.IS_MUSIC + " != 0 AND (");
        List<String> args = new ArrayList<>();
        selection.append(MediaStore.Audio.Media.MIME_TYPE).append(" IN (");
        for (int i = 0; i < SUPPORTED_MIME_TYPES.length; i++) {
            selection.append(i == 0 ? "?" : ",?");
            args.add(SUPPORTED_MIME_TYPES[i]);
        }
        selection.append(")");
        for (String ext : SUPPORTED_EXTENSIONS) {
            selection.append(" OR ").append(MediaStore.Audio.Media.DISPLAY_NAME).append(" LIKE ?");
            args.add("%." + ext); // LIKE is case-insensitive for ASCII, so .FLAC matches too
        }
        selection.append(")");
        String order = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor c = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection.toString(),
                args.toArray(new String[0]), order)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
                int durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                while (c.moveToNext()) {
                    result.add(new Song(
                            c.getLong(idCol),
                            orDefault(c.getString(titleCol), "Unknown title"),
                            orDefault(c.getString(artistCol), "Unknown artist"),
                            orDefault(c.getString(albumCol), "Unknown album"),
                            c.getLong(albumIdCol),
                            c.getInt(trackCol),
                            c.getLong(durationCol)));
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to read the music library yet", e);
        }
        return new MusicLibrary(result);
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isEmpty() || "<unknown>".equals(value)) ? fallback : value;
    }

    public List<Song> getSongs() {
        return songs;
    }

    @Nullable
    public Song getSong(long id) {
        return songsById.get(id);
    }

    public List<String> getArtists() {
        return new ArrayList<>(songsByArtist.keySet());
    }

    public List<Song> getSongsByArtist(String artist) {
        List<Song> list = songsByArtist.get(artist);
        return list != null ? list : Collections.emptyList();
    }

    public List<Album> getAlbums() {
        return albums;
    }

    public Album getAlbum(long albumId) {
        return albumsById.get(albumId);
    }
}
