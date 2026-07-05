package net.image_upscaling;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.security.SecureRandom;

public class Utils {
    private static final String PREFS_NAME = "UpscalingAppPrefs";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static String getClientId(ContextWrapper context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, ContextWrapper.MODE_PRIVATE);
        String clientId = prefs.getString(KEY_CLIENT_ID, null);
        if (clientId == null || clientId.length() != 32) {
            clientId = generateRandomId(32);
            prefs.edit().putString(KEY_CLIENT_ID, clientId).apply();
        }
        return clientId;
    }

    private static String generateRandomId(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
