package com.example.albumsplayer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.List;
import java.util.Locale;

/** The current album's tracks in track order, with the song that's playing highlighted. */
final class AlbumTrackAdapter extends ArrayAdapter<MusicLibrary.Song> {

    private final LayoutInflater inflater;
    private long currentSongId = -1;

    AlbumTrackAdapter(Context context, List<MusicLibrary.Song> tracks) {
        super(context, 0, tracks);
        inflater = LayoutInflater.from(context);
    }

    /** Returns true if the highlighted song changed. */
    boolean setCurrentSong(long songId) {
        if (songId == currentSongId) {
            return false;
        }
        currentSongId = songId;
        notifyDataSetChanged();
        return true;
    }

    /** Position of the highlighted song, or -1. */
    int currentPosition() {
        for (int i = 0; i < getCount(); i++) {
            MusicLibrary.Song s = getItem(i);
            if (s != null && s.id == currentSongId) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View row = convertView != null ? convertView : inflater.inflate(R.layout.item_track, parent, false);
        TextView number = row.findViewById(R.id.track_number);
        TextView title = row.findViewById(R.id.track_title);
        TextView duration = row.findViewById(R.id.track_duration);

        MusicLibrary.Song song = getItem(position);
        if (song == null) {
            return row;
        }
        boolean current = song.id == currentSongId;

        // MediaStore stores disc * 1000 + track; show just the track number.
        int track = song.track % 1000;
        number.setText(current ? "▶" : (track > 0 ? Integer.toString(track) : "–"));
        title.setText(song.title);
        duration.setText(formatDuration(song.durationMs));

        int textColor = MaterialColors.getColor(row, current
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnSurface);
        int secondaryColor = current ? textColor : MaterialColors.getColor(row,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        row.setBackgroundColor(current
                ? MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimaryContainer)
                : Color.TRANSPARENT);
        title.setTextColor(textColor);
        title.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
        number.setTextColor(secondaryColor);
        duration.setTextColor(secondaryColor);
        return row;
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
