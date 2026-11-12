package com.example.albumsplayer;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Read-only provider that serves the cover art embedded in each music file
 * (MP3 ID3 picture, FLAC picture block, Vorbis METADATA_BLOCK_PICTURE).
 * URI format: content://com.example.albumsplayer.artwork/song/&lt;MediaStore id&gt;
 *
 * Android Auto loads artwork for browse lists from URIs, so the embedded image has to be
 * exposed this way. Extracted images are cached in the app's cache directory.
 */
public class ArtworkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.albumsplayer.artwork";

    public static Uri uriFor(long songId) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("song").appendPath(Long.toString(songId)).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        long songId;
        try {
            songId = ContentUris.parseId(uri);
        } catch (RuntimeException e) {
            throw new FileNotFoundException("Bad artwork uri: " + uri);
        }

        File dir = new File(getContext().getCacheDir(), "artwork");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File image = new File(dir, songId + ".img");
        File noImageMarker = new File(dir, songId + ".none");

        if (!image.exists()) {
            if (noImageMarker.exists()) {
                throw new FileNotFoundException("No embedded art for " + songId);
            }
            byte[] picture = extractEmbeddedPicture(songId);
            try {
                if (picture == null) {
                    //noinspection ResultOfMethodCallIgnored
                    noImageMarker.createNewFile();
                    throw new FileNotFoundException("No embedded art for " + songId);
                }
                File tmp = File.createTempFile(songId + "-", ".tmp", dir);
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(picture);
                }
                if (!tmp.renameTo(image)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            } catch (FileNotFoundException e) {
                throw e;
            } catch (IOException e) {
                throw new FileNotFoundException("Could not cache artwork: " + e.getMessage());
            }
        }
        return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Nullable
    private byte[] extractEmbeddedPicture(long songId) {
        Uri songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), songUri);
            return retriever.getEmbeddedPicture();
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "image/*";
    }

    // Read-only: everything else is unsupported.

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
