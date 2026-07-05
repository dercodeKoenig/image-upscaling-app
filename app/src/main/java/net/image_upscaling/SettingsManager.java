package net.image_upscaling;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

public class SettingsManager {
    private static final String PREFS_NAME = "UpscalingAppPrefs";
    private static final String KEY_CACHED_CONFIG = "cached_config";
    private static final String KEY_LAST_MODEL = "last_model";
    private static final String KEY_LAST_SCALE = "last_scale";
    private static final String KEY_LAST_FACE_ENHANCE = "last_face_enhance";
    private static final String KEY_USE_WEBP = "use_webp";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveConfigToCache(JSONObject config) {
        prefs.edit().putString(KEY_CACHED_CONFIG, config.toString()).apply();
    }

    public String getCachedConfig() {
        return prefs.getString(KEY_CACHED_CONFIG, null);
    }

    public void clearCachedConfig() {
        prefs.edit().remove(KEY_CACHED_CONFIG).apply();
    }

    public void saveLastUsedSettings(String model, String scale, boolean faceEnhance) {
        prefs.edit()
                .putString(KEY_LAST_MODEL, model)
                .putString(KEY_LAST_SCALE, scale)
                .putBoolean(KEY_LAST_FACE_ENHANCE, faceEnhance)
                .apply();
    }

    public String getLastUsedModel() {
        return prefs.getString(KEY_LAST_MODEL, null);
    }

    public String getLastUsedScale() {
        return prefs.getString(KEY_LAST_SCALE, null);
    }

    public boolean getLastUsedFaceEnhanceValue() {
        return prefs.getBoolean(KEY_LAST_FACE_ENHANCE, false);
    }

    public void setUseWebP(boolean useWebP) {
        prefs.edit().putBoolean(KEY_USE_WEBP, useWebP).apply();
    }

    public boolean isUseWebP() {
        return prefs.getBoolean(KEY_USE_WEBP, true);
    }
}
