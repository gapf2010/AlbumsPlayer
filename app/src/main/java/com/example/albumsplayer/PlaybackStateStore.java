package com.example.albumsplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists the playback state so it survives the app being closed or the phone rebooting:
 * <ul>
 *   <li>the queue (media IDs, in play order) in an atomically-written file,</li>
 *   <li>the current index, position and queue source (e.g. "shuffle_artist/…") in SharedPreferences.</li>
 * </ul>
 */
final class PlaybackStateStore {

    static final String PREFS_NAME = "playback_state";
    static final String KEY_SOURCE = "source";
    static final String KEY_AUTO_CONTINUE = "auto_continue";
    private static final String KEY_PLAYED_ARTISTS = "played_artists";
    private static final String KEY_PLAYED_ALBUMS = "played_albums";
    private static final String KEY_INDEX = "index";
    private static final String KEY_POSITION = "position";

    private final SharedPreferences prefs;
    private final AtomicFile queueFile;

    PlaybackStateStore(Context context) {
        prefs = prefs(context);
        queueFile = new AtomicFile(new File(context.getFilesDir(), "queue.txt"));
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void saveSource(@Nullable String source) {
        prefs.edit().putString(KEY_SOURCE, source).apply();
    }

    @Nullable
    String loadSource() {
        return prefs.getString(KEY_SOURCE, null);
    }

    // ---- Auto-continue (when an artist/album finishes, start an unplayed one) ----

    boolean isAutoContinue() {
        return prefs.getBoolean(KEY_AUTO_CONTINUE, true);
    }

    void setAutoContinue(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_CONTINUE, enabled).apply();
    }

    // ---- History of played artists / albums (keys from LibraryTree.groupKey) ----

    private static String playedKey(LibraryTree.Group group) {
        return group == LibraryTree.Group.ARTIST ? KEY_PLAYED_ARTISTS : KEY_PLAYED_ALBUMS;
    }

    synchronized Set<String> loadPlayed(LibraryTree.Group group) {
        // getStringSet's result must not be modified, so always copy it.
        return new HashSet<>(prefs.getStringSet(playedKey(group), Collections.emptySet()));
    }

    synchronized void addPlayed(LibraryTree.Group group, String key) {
        Set<String> played = loadPlayed(group);
        if (played.add(key)) {
            prefs.edit().putStringSet(playedKey(group), played).apply();
        }
    }

    /** Starts a new round: forget everything except the artist/album playing now. */
    synchronized void resetPlayed(LibraryTree.Group group, @Nullable String keepKey) {
        Set<String> played = new HashSet<>();
        if (keepKey != null) {
            played.add(keepKey);
        }
        prefs.edit().putStringSet(playedKey(group), played).apply();
    }

    void savePosition(int index, long positionMs) {
        prefs.edit().putInt(KEY_INDEX, index).putLong(KEY_POSITION, positionMs).apply();
    }

    int loadIndex() {
        return prefs.getInt(KEY_INDEX, 0);
    }

    long loadPosition() {
        return prefs.getLong(KEY_POSITION, 0);
    }

    /** Media IDs never contain '\n' (artist names are URI-encoded), so one ID per line is safe. */
    synchronized void saveQueue(List<String> mediaIds) {
        FileOutputStream out = null;
        try {
            out = queueFile.startWrite();
            out.write(String.join("\n", mediaIds).getBytes(StandardCharsets.UTF_8));
            queueFile.finishWrite(out);
        } catch (IOException e) {
            if (out != null) {
                queueFile.failWrite(out);
            }
        }
    }

    synchronized List<String> loadQueue() {
        try {
            String text = new String(queueFile.readFully(), StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(Arrays.asList(text.split("\n")));
        } catch (FileNotFoundException e) {
            return new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }
}
