package com.example.albumsplayer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * The user's light/dark theme choice: automatic (follow the phone's setting), light or dark.
 * Saved in the "settings" preferences and applied to the whole app.
 */
public final class ThemeSettings {

    public static final int AUTOMATIC = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS_NAME = "settings";
    private static final String KEY_THEME = "theme";

    private ThemeSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** One of {@link #AUTOMATIC}, {@link #LIGHT}, {@link #DARK}. */
    public static int get(Context context) {
        int theme = prefs(context).getInt(KEY_THEME, AUTOMATIC);
        return (theme == LIGHT || theme == DARK) ? theme : AUTOMATIC;
    }

    /** Saves the choice and applies it; open screens are redrawn in the new theme. */
    public static void set(Context context, int theme) {
        prefs(context).edit().putInt(KEY_THEME, theme).apply();
        apply(theme);
    }

    /** Applies the saved choice. Called once when the app process starts. */
    public static void applySaved(Context context) {
        apply(get(context));
    }

    private static void apply(int theme) {
        int mode;
        if (theme == LIGHT) {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if (theme == DARK) {
            mode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }
}
