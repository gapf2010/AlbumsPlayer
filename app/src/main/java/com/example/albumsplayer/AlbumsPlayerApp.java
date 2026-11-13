package com.example.albumsplayer;

import android.app.Application;

/**
 * Runs once when the app starts, before any screen is shown, so the saved light/dark
 * theme is in place from the first frame.
 */
public class AlbumsPlayerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeSettings.applySaved(this);
    }
}
