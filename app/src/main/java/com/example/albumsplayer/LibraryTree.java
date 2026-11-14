package com.example.albumsplayer;

import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Defines the browse tree shown in Android Auto and maps media IDs to playable queues.
 *
 * <pre>
 * root                              (only folders here: Android Auto shows them as tabs)
 *  ├─ artists
 *  │   ├─ random_artists              (play: random artists one after another, each shuffled)
 *  │   └─ artist/&lt;name&gt;
 *  │       ├─ shuffle_artist/&lt;name&gt;   (play: that artist, shuffled)
 *  │       └─ artist/&lt;name&gt;#&lt;songId&gt; (play: that artist in order, from this song)
 *  ├─ albums
 *  │   ├─ random_albums               (play: random albums one after another, in track order)
 *  │   └─ album/&lt;id&gt;
 *  │       ├─ shuffle_album/&lt;id&gt;
 *  │       └─ album/&lt;id&gt;#&lt;songId&gt;
 *  └─ songs
 *      ├─ shuffle_all                 (play: every song, shuffled)
 *      └─ songs#&lt;songId&gt;
 * </pre>
 *
 * The phone UI uses the same IDs, so both go through exactly the same code path.
 */
final class LibraryTree {

    static final String ROOT = "root";
    static final String SHUFFLE_ALL = "shuffle_all";
    static final String ARTISTS = "artists";
    static final String ALBUMS = "albums";
    static final String SONGS = "songs";
    /** "Shuffle all artists/albums": the service picks a random unplayed artist/album. */
    static final String RANDOM_ARTISTS = "random_artists";
    static final String RANDOM_ALBUMS = "random_albums";
    private static final String ARTIST_PREFIX = "artist/";
    private static final String ALBUM_PREFIX = "album/";
    private static final String SHUFFLE_ARTIST_PREFIX = "shuffle_artist/";
    private static final String SHUFFLE_ALBUM_PREFIX = "shuffle_album/";
    private static final char SONG_SEPARATOR = '#';

    /**
     * A queue to hand to the player, which item to start at, and its source: the shuffle ID
     * (e.g. "shuffle_artist/…") or the list it plays in order (e.g. "album/12"). The source is
     * remembered so that new music can be added to the queue later.
     */
    static final class Playlist {
        final List<MediaItem> items;
        final int startIndex;
        final String source;

        Playlist(List<MediaItem> items, int startIndex, String source) {
            this.items = items;
            this.startIndex = startIndex;
            this.source = source;
        }
    }

    private LibraryTree() {}

    // ---- ID helpers (artist names are URI-encoded so '/' and '#' can't break parsing) ----

    static String artistId(String artist) {
        return ARTIST_PREFIX + Uri.encode(artist);
    }

    static String shuffleArtistId(String artist) {
        return SHUFFLE_ARTIST_PREFIX + Uri.encode(artist);
    }

    static String albumId(long albumId) {
        return ALBUM_PREFIX + albumId;
    }

    static String shuffleAlbumId(long albumId) {
        return SHUFFLE_ALBUM_PREFIX + albumId;
    }

    static String songId(String parentId, long songId) {
        return parentId + SONG_SEPARATOR + songId;
    }

    // ---- Browsing ----

    static MediaItem root() {
        return folder(ROOT, "Music", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
    }

    /** Children of a browsable node, or null if the ID is unknown. */
    @Nullable
    static List<MediaItem> children(MusicLibrary lib, String parentId) {
        List<MediaItem> out = new ArrayList<>();
        switch (parentId) {
            case ROOT:
                // No playable items at the top: Android Auto would put them in a "More" tab.
                out.add(folder(ARTISTS, "Artists", null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS));
                out.add(folder(ALBUMS, "Albums", null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS));
                out.add(folder(SONGS, "All songs", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED));
                return out;
            case ARTISTS:
                out.add(shuffleAllArtistsAction(lib));
                for (String artist : lib.getArtists()) {
                    out.add(artistFolder(lib, artist));
                }
                return out;
            case ALBUMS:
                out.add(shuffleAllAlbumsAction(lib));
                for (MusicLibrary.Album album : lib.getAlbums()) {
                    out.add(albumFolder(album));
                }
                return out;
            default:
                break;
        }

        List<MusicLibrary.Song> songs = songsFor(lib, parentId);
        if (songs == null) {
            return null;
        }
        if (SONGS.equals(parentId)) {
            out.add(shuffleAllSongsAction(lib));
        } else if (parentId.startsWith(ARTIST_PREFIX)) {
            String encoded = parentId.substring(ARTIST_PREFIX.length());
            out.add(action(SHUFFLE_ARTIST_PREFIX + encoded, "Shuffle this artist", songs.size() + " songs", null));
        } else if (parentId.startsWith(ALBUM_PREFIX)) {
            String id = parentId.substring(ALBUM_PREFIX.length());
            Uri art = songs.isEmpty() ? null : songs.get(0).artworkUri();
            out.add(action(SHUFFLE_ALBUM_PREFIX + id, "Play, then random albums", songs.size() + " songs", art));
        }
        for (MusicLibrary.Song s : songs) {
            out.add(songItem(songId(parentId, s.id), s));
        }
        return out;
    }

    /** A single node by ID (needed by MediaLibrarySession#onGetItem). */
    @Nullable
    static MediaItem item(MusicLibrary lib, String mediaId) {
        if (ROOT.equals(mediaId)) {
            return root();
        }
        for (MediaItem top : children(lib, ROOT)) {
            if (top.mediaId.equals(mediaId)) {
                return top;
            }
        }
        switch (mediaId) {
            case SHUFFLE_ALL:
                return shuffleAllSongsAction(lib);
            case RANDOM_ARTISTS:
                return shuffleAllArtistsAction(lib);
            case RANDOM_ALBUMS:
                return shuffleAllAlbumsAction(lib);
            default:
                break;
        }
        if (mediaId.startsWith(SHUFFLE_ARTIST_PREFIX) || mediaId.startsWith(SHUFFLE_ALBUM_PREFIX)) {
            return action(mediaId, "Shuffle", null, null);
        }
        int sep = mediaId.lastIndexOf(SONG_SEPARATOR);
        if (sep > 0) {
            Playlist p = resolve(lib, mediaId);
            return p == null ? null : p.items.get(p.startIndex);
        }
        if (mediaId.startsWith(ARTIST_PREFIX)) {
            return artistFolder(lib, Uri.decode(mediaId.substring(ARTIST_PREFIX.length())));
        }
        if (mediaId.startsWith(ALBUM_PREFIX)) {
            List<MusicLibrary.Song> songs = songsFor(lib, mediaId);
            if (songs != null && !songs.isEmpty()) {
                return albumFolder(lib.getAlbum(songs.get(0).albumId));
            }
        }
        return null;
    }

    // ---- Search (the magnifier button in Android Auto) ----

    /** Android Auto shows items that share this extra under one heading. */
    private static final String GROUP_TITLE_EXTRA = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";

    /**
     * Artists, then albums, then songs whose names contain every word typed, ignoring case and
     * accents ("beyonce" finds "Beyoncé"). Album and artist results open as folders; a song plays
     * its album in track order, starting from that song.
     */
    static List<MediaItem> search(MusicLibrary lib, String query) {
        List<MediaItem> out = new ArrayList<>();
        String[] words = normalize(query).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return out;
        }
        for (String artist : lib.getArtists()) {
            if (matches(words, artist)) {
                out.add(withGroupTitle(artistFolder(lib, artist), "Artists"));
            }
        }
        for (MusicLibrary.Album album : lib.getAlbums()) {
            if (matches(words, album.title + " " + album.artist)) {
                out.add(withGroupTitle(albumFolder(album), "Albums"));
            }
        }
        for (MusicLibrary.Song song : lib.getSongs()) {
            if (matches(words, song.title + " " + song.artist + " " + song.album)) {
                out.add(withGroupTitle(songItem(songId(albumId(song.albumId), song.id), song), "Songs"));
            }
        }
        return out;
    }

    private static boolean matches(String[] words, String text) {
        String haystack = normalize(text);
        for (String w : words) {
            if (!haystack.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** Lower case without accents, so searches typed on a car keyboard still match. */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static MediaItem withGroupTitle(MediaItem item, String groupTitle) {
        Bundle extras = new Bundle();
        extras.putString(GROUP_TITLE_EXTRA, groupTitle);
        return item.buildUpon()
                .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
                .build();
    }

    // ---- Playback resolution ----

    /** Turns a media ID (shuffle action, song, or folder) into a queue for the player. */
    @Nullable
    static Playlist resolve(MusicLibrary lib, String mediaId) {
        if (isShuffleSource(mediaId)) {
            String parent = parentForSource(mediaId);
            if (!shufflesSongs(mediaId)) {
                // Random-album mode: the album itself always plays in track order.
                return inOrder(songsFor(lib, parent), parent, mediaId);
            }
            return shuffled(songsFor(lib, parent), parent, mediaId);
        }

        // A song tapped inside a list: queue that whole list in order, starting at the song.
        int sep = mediaId.lastIndexOf(SONG_SEPARATOR);
        if (sep > 0) {
            String parent = mediaId.substring(0, sep);
            long songId;
            try {
                songId = Long.parseLong(mediaId.substring(sep + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            List<MusicLibrary.Song> songs = songsFor(lib, parent);
            if (songs == null) {
                return null;
            }
            List<MediaItem> items = new ArrayList<>(songs.size());
            int start = -1;
            for (int i = 0; i < songs.size(); i++) {
                MusicLibrary.Song s = songs.get(i);
                if (s.id == songId) {
                    start = i;
                }
                items.add(songItem(songId(parent, s.id), s));
            }
            return start < 0 ? null : new Playlist(items, start, parent);
        }

        // A folder asked to play (e.g. via voice / "play" on an artist): play it in order.
        return inOrder(songsFor(lib, mediaId), mediaId, mediaId);
    }

    static boolean isSingleSong(String mediaId) {
        return mediaId.lastIndexOf(SONG_SEPARATOR) > 0;
    }

    /** ARTIST for {@link #RANDOM_ARTISTS}, ALBUM for {@link #RANDOM_ALBUMS}, otherwise null. */
    @Nullable
    static Group randomGroupAction(String mediaId) {
        if (RANDOM_ARTISTS.equals(mediaId)) {
            return Group.ARTIST;
        }
        if (RANDOM_ALBUMS.equals(mediaId)) {
            return Group.ALBUM;
        }
        return null;
    }

    /**
     * Source for a random artist (shuffled) or album (track order) not yet played in this round.
     * When all have been played, that history resets and a new round starts (same rule as
     * Next artist/album and auto-continue). Null if the library has none.
     */
    @Nullable
    static String randomGroupSource(MusicLibrary lib, Group group, Random random, PlaybackStateStore store) {
        Set<String> played = store.loadPlayed(group);
        if (group == Group.ARTIST) {
            List<String> artists = lib.getArtists();
            if (artists.isEmpty()) {
                return null;
            }
            List<String> fresh = new ArrayList<>();
            for (String a : artists) {
                if (!played.contains(groupKey(artistId(a)))) {
                    fresh.add(a);
                }
            }
            if (fresh.isEmpty()) {
                store.resetPlayed(group, null);
                fresh = artists;
            }
            return shuffleArtistId(fresh.get(random.nextInt(fresh.size())));
        }
        List<MusicLibrary.Album> albums = lib.getAlbums();
        if (albums.isEmpty()) {
            return null;
        }
        List<MusicLibrary.Album> fresh = new ArrayList<>();
        for (MusicLibrary.Album a : albums) {
            if (!played.contains(groupKey(albumId(a.id)))) {
                fresh.add(a);
            }
        }
        if (fresh.isEmpty()) {
            store.resetPlayed(group, null);
            fresh = albums;
        }
        return shuffleAlbumId(fresh.get(random.nextInt(fresh.size())).id);
    }

    // ---- Queue source helpers (used to restore state and to add new music) ----

    /** Random-pick modes: shuffle all, random artists, random albums. */
    static boolean isShuffleSource(String source) {
        return SHUFFLE_ALL.equals(source)
                || source.startsWith(SHUFFLE_ARTIST_PREFIX)
                || source.startsWith(SHUFFLE_ALBUM_PREFIX);
    }

    /**
     * Whether the songs themselves are shuffled. True for shuffle all and random artists; false
     * for random albums, whose songs always play in track order (as do all in-order lists).
     */
    static boolean shufflesSongs(String source) {
        return isShuffleSource(source) && !source.startsWith(SHUFFLE_ALBUM_PREFIX);
    }

    /** The list a source draws its songs from, e.g. "shuffle_artist/X" → "artist/X". */
    static String parentForSource(String source) {
        if (SHUFFLE_ALL.equals(source)) {
            return SONGS;
        }
        if (source.startsWith(SHUFFLE_ARTIST_PREFIX)) {
            return ARTIST_PREFIX + source.substring(SHUFFLE_ARTIST_PREFIX.length());
        }
        if (source.startsWith(SHUFFLE_ALBUM_PREFIX)) {
            return ALBUM_PREFIX + source.substring(SHUFFLE_ALBUM_PREFIX.length());
        }
        return source;
    }

    /** All songs that currently belong to a source, or null if the source is unknown. */
    @Nullable
    static List<MusicLibrary.Song> songsForSource(MusicLibrary lib, String source) {
        return songsFor(lib, parentForSource(source));
    }

    /** The MediaStore song ID inside a song media ID ("album/12#345" → 345), or -1. */
    static long songIdOf(String mediaId) {
        int sep = mediaId.lastIndexOf(SONG_SEPARATOR);
        if (sep <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(mediaId.substring(sep + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Rebuilds a playable item from a saved song media ID, or null if the file is gone. */
    @Nullable
    static MediaItem songItemFromId(MusicLibrary lib, String mediaId) {
        long id = songIdOf(mediaId);
        MusicLibrary.Song song = id < 0 ? null : lib.getSong(id);
        return song == null ? null : songItem(mediaId, song);
    }

    /** Whether a source is tied to one artist or one album (enables "Next artist/album"). */
    enum Group { ARTIST, ALBUM }

    @Nullable
    static Group groupOf(@Nullable String source) {
        if (source == null) {
            return null;
        }
        String parent = parentForSource(source);
        if (parent.startsWith(ARTIST_PREFIX)) {
            return Group.ARTIST;
        }
        if (parent.startsWith(ALBUM_PREFIX)) {
            return Group.ALBUM;
        }
        return null;
    }

    /**
     * Stable identity of the artist/album behind a source, used for the "already played" history.
     * Shuffled and in-order playback of the same album share a key. Null for non-group sources.
     */
    @Nullable
    static String groupKey(@Nullable String source) {
        if (groupOf(source) == null) {
            return null;
        }
        // Artist names are matched case-insensitively elsewhere, so do the same here.
        return parentForSource(source).toLowerCase(Locale.ROOT);
    }

    /**
     * For "Next artist/album" and auto-continue: a source for a random artist (or album) that is
     * neither the current one nor in {@code played}, keeping the current mode (shuffled vs in
     * order). Null if the source isn't artist/album based or every candidate was already played.
     */
    @Nullable
    static String nextGroupSource(MusicLibrary lib, @Nullable String source, Random random,
                                  Set<String> played) {
        Group group = groupOf(source);
        if (group == null) {
            return null;
        }
        boolean shuffle = isShuffleSource(source);
        String currentKey = groupKey(source);
        if (group == Group.ARTIST) {
            List<String> artists = lib.getArtists();
            artists.removeIf(a -> {
                String key = groupKey(artistId(a));
                return key.equals(currentKey) || played.contains(key);
            });
            if (artists.isEmpty()) {
                return null;
            }
            String pick = artists.get(random.nextInt(artists.size()));
            return shuffle ? shuffleArtistId(pick) : artistId(pick);
        }
        List<MusicLibrary.Album> albums = new ArrayList<>(lib.getAlbums());
        albums.removeIf(a -> {
            String key = groupKey(albumId(a.id));
            return key.equals(currentKey) || played.contains(key);
        });
        if (albums.isEmpty()) {
            return null;
        }
        MusicLibrary.Album pick = albums.get(random.nextInt(albums.size()));
        return shuffle ? shuffleAlbumId(pick.id) : albumId(pick.id);
    }

    /** Human-readable mode, e.g. "Shuffle: Queen" or "In order: all songs". */
    static String describeSource(@Nullable MusicLibrary lib, @Nullable String source) {
        if (source == null) {
            return "";
        }
        String prefix = source.startsWith(SHUFFLE_ALBUM_PREFIX) ? "Random albums: "
                : isShuffleSource(source) ? "Shuffle: " : "In order: ";
        String parent = parentForSource(source);
        String name = parent;
        if (SONGS.equals(parent)) {
            name = "all songs";
        } else if (parent.startsWith(ARTIST_PREFIX)) {
            name = Uri.decode(parent.substring(ARTIST_PREFIX.length()));
        } else if (parent.startsWith(ALBUM_PREFIX)) {
            name = "album";
            if (lib != null) {
                try {
                    MusicLibrary.Album album = lib.getAlbum(Long.parseLong(parent.substring(ALBUM_PREFIX.length())));
                    if (album != null) {
                        name = album.title;
                    }
                } catch (NumberFormatException ignored) {
                    // keep generic name
                }
            }
        }
        return prefix + name;
    }

    @Nullable
    private static Playlist inOrder(@Nullable List<MusicLibrary.Song> songs, String parentId, String source) {
        if (songs == null || songs.isEmpty()) {
            return null;
        }
        List<MediaItem> items = new ArrayList<>(songs.size());
        for (MusicLibrary.Song s : songs) {
            items.add(songItem(songId(parentId, s.id), s));
        }
        return new Playlist(items, 0, source);
    }

    @Nullable
    private static Playlist shuffled(@Nullable List<MusicLibrary.Song> songs, String parentId, String source) {
        if (songs == null || songs.isEmpty()) {
            return null;
        }
        List<MusicLibrary.Song> copy = new ArrayList<>(songs);
        Collections.shuffle(copy);
        List<MediaItem> items = new ArrayList<>(copy.size());
        for (MusicLibrary.Song s : copy) {
            items.add(songItem(songId(parentId, s.id), s));
        }
        return new Playlist(items, 0, source);
    }

    @Nullable
    private static List<MusicLibrary.Song> songsFor(MusicLibrary lib, String parentId) {
        if (SONGS.equals(parentId)) {
            return lib.getSongs();
        }
        if (parentId.startsWith(ARTIST_PREFIX)) {
            return lib.getSongsByArtist(Uri.decode(parentId.substring(ARTIST_PREFIX.length())));
        }
        if (parentId.startsWith(ALBUM_PREFIX)) {
            try {
                MusicLibrary.Album album = lib.getAlbum(Long.parseLong(parentId.substring(ALBUM_PREFIX.length())));
                return album == null ? null : album.songs;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ---- MediaItem builders ----

    static MediaItem songItem(String mediaId, MusicLibrary.Song s) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setArtworkUri(s.artworkUri())
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build();
        return new MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(s.uri)
                .setMediaMetadata(metadata)
                .build();
    }

    private static MediaItem shuffleAllSongsAction(MusicLibrary lib) {
        return action(SHUFFLE_ALL, "Shuffle all songs", lib.getSongs().size() + " songs", null);
    }

    private static MediaItem shuffleAllArtistsAction(MusicLibrary lib) {
        return action(RANDOM_ARTISTS, "Shuffle all artists",
                "Random artists, " + lib.getArtists().size() + " in total", null);
    }

    private static MediaItem shuffleAllAlbumsAction(MusicLibrary lib) {
        return action(RANDOM_ALBUMS, "Shuffle all albums",
                "Random albums in track order, " + lib.getAlbums().size() + " in total", null);
    }

    private static MediaItem artistFolder(MusicLibrary lib, String artist) {
        int count = lib.getSongsByArtist(artist).size();
        return folder(artistId(artist), artist, count + " songs", MediaMetadata.MEDIA_TYPE_ARTIST);
    }

    private static MediaItem albumFolder(MusicLibrary.Album album) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(album.title)
                .setSubtitle(album.artist)
                .setArtist(album.artist)
                .setArtworkUri(album.songs.isEmpty() ? null : album.songs.get(0).artworkUri())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .build();
        return new MediaItem.Builder().setMediaId(albumId(album.id)).setMediaMetadata(metadata).build();
    }

    private static MediaItem folder(String id, String title, @Nullable String subtitle, int mediaType) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build();
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build();
    }

    private static MediaItem action(String id, String title, @Nullable String subtitle, @Nullable Uri art) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(art)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build();
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build();
    }
}
