package net.image_upscaling.imageupscaler;

import android.content.ContextWrapper;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class utils {
    static String getClientId(ContextWrapper contextWrapper) {
        String androidId = Settings.Secure.getString(contextWrapper.getContentResolver(), Settings.Secure.ANDROID_ID) + "-" + Build.FINGERPRINT;
        Log.i("", "android id: " + androidId);

        String[] algorithms = {"SHA-256", "SHA-1", "MD5"};
        for (String algorithm : algorithms) {
            try {
                MessageDigest digest = MessageDigest.getInstance(algorithm);
                byte[] hash = digest.digest(androidId.getBytes());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    sb.append(String.format("%02x", hash[i]));
                }
                String hashString = sb.toString();
                Log.i("", hashString);
                return hashString;
            } catch (NoSuchAlgorithmException e) {
                continue; // Try next algorithm
            }
        }

        throw new RuntimeException("No hash algorithm available");
    }
}
