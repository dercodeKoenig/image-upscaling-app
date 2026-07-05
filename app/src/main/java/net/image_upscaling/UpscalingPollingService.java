package net.image_upscaling;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class UpscalingPollingService extends Service {
    private static final String TAG = "UpscalingPollingService";
    private static final String CHANNEL_ID = "upscaling_polling_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final int POLL_INTERVAL = 5000;

    private Handler handler;
    private Runnable pollingRunnable;
    private ApiService apiService;
    private int noRequestsCount = 0;
    private static final int MAX_EMPTY_POLLS = 12; // Wait about 1 minute before giving up

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        apiService = new ApiService(this);
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        
        Notification notification = createStatusNotification("Upscaling service is running...");
        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "startForeground called successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error in startForeground: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        
        // Reset count when service is (re)started
        noRequestsCount = 0;
        
        // If we are already polling, don't start a second loop
        if (pollingRunnable != null) {
            Log.d(TAG, "Polling already active");
        } else {
            startPolling();
        }
        
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startPolling() {
        if (pollingRunnable != null) return;
        Log.d(TAG, "Starting polling loop");

        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (pollingRunnable == null) return; // Guard against late execution after stop
                Log.d(TAG, "Polling check...");
                checkPendingRequests();
            }
        };
        handler.post(pollingRunnable);
    }

    private void scheduleNextPoll() {
        if (pollingRunnable != null) {
            handler.postDelayed(pollingRunnable, POLL_INTERVAL);
        }
    }

    private void checkPendingRequests() {
        apiService.checkPendingRequests(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to check status: " + e.getMessage());
                scheduleNextPoll();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                boolean shouldStop = false;
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Unsuccessful status check: " + response.code());
                        return;
                    }
                    String body = responseBody != null ? responseBody.string() : "{}";
                    Log.d(TAG, "Status Response: " + body);
                    JSONObject json = new JSONObject(body);
                    JSONArray processed = json.getJSONArray("processed");
                    JSONArray pending = json.getJSONArray("pending");
                    JSONArray processing = json.getJSONArray("processing");

                    int pendingAndProcessingCount = pending.length() + processing.length();
                    int processedCount = processed.length();
                    Log.d(TAG, "Queue Status: " + pendingAndProcessingCount + " waiting, " + processedCount + " just finished.");

                    if (pendingAndProcessingCount > 0) {
                        noRequestsCount = 0; // Reset counter
                        updateNotification("Processing " + pendingAndProcessingCount + " image(s)...");
                    } else {
                        updateNotification("Upscaling service is idle...");
                        if (processedCount == 0) {
                            // No active or processed requests found.
                            noRequestsCount++;
                            Log.d(TAG, "No requests found (" + noRequestsCount + "/" + MAX_EMPTY_POLLS + ")");

                            if (noRequestsCount >= MAX_EMPTY_POLLS) {
                                Log.d(TAG, "Timeout waiting for jobs, stopping service");
                                shouldStop = true;
                            }
                        } else {
                            // Jobs were finished, but nothing else is left in queue.
                            // We reset the noRequestsCount to 0 to be sure we handle processed ones.
                            noRequestsCount = 0;
                            Log.d(TAG, "Batch of jobs finished.");
                        }
                    }

                    for (int i = 0; i < processedCount; i++) {
                        String url = processed.getString(i);
                        String filename = url.substring(url.lastIndexOf('/') + 1);
                        downloadProcessedImage(url + "?delete_after_download=&client_id=" + apiService.getClientId(), filename);
                    }
                    
                    // Final check to stop if everything is quiet and no more jobs
                    if (pendingAndProcessingCount == 0 && processedCount == 0 && noRequestsCount >= MAX_EMPTY_POLLS) {
                        Log.d(TAG, "All done, stopping service");
                        shouldStop = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing status: " + e.getMessage());
                } finally {
                    if (shouldStop) {
                        stopSelf();
                    } else {
                        scheduleNextPoll();
                    }
                }
            }
        });
    }

    private void downloadProcessedImage(String url, String filename) {
        apiService.downloadFile(url, new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Download failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String contentType = response.header("Content-Type", "image/jpeg");
                    try (InputStream is = response.body().byteStream()) {
                        saveFileToGallery(is, filename, contentType);
                        showSuccessNotification();
                    }
                }
            }
        });
    }

    private void saveFileToGallery(InputStream is, String filename, String mimeType) throws IOException {
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension != null && !filename.toLowerCase().endsWith("." + extension.toLowerCase())) {
            if (filename.contains(".")) {
                filename = filename.substring(0, filename.lastIndexOf('.')) + "." + extension;
            } else {
                filename = filename + "." + extension;
            }
        }
        
        final String finalFilename = filename;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalFilename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Upscaled");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            copyStream(is, os);
                        }
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                } else {
                    throw new IOException("Failed to create MediaStore entry");
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Upscaled");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                }
                File file = new File(dir, finalFilename);
                try (OutputStream os = new FileOutputStream(file)) {
                    copyStream(is, os);
                }
                Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                intent.setData(Uri.fromFile(file));
                sendBroadcast(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving to gallery: " + e.getMessage());
            handler.post(() -> {
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("Gallery Save Failed")
                        .setContentText("Permission issue or storage full while saving " + finalFilename)
                        .setSmallIcon(R.drawable.ic_notification_icon)
                        .setAutoCancel(true)
                        .build();
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) manager.notify((int) System.currentTimeMillis(), notification);
            });
            throw new IOException("Gallery save failed", e);
        }
    }

    private void copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Upscaling Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows upscaling progress");
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createStatusNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Image Upscaling")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            Log.d(TAG, "Updating notification: " + text);
            manager.notify(NOTIFICATION_ID, createStatusNotification(text));
        }
    }

    private void showSuccessNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Upscaling Complete")
                .setContentText("Your image has been saved to the gallery.")
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy - stopping polling");
        if (handler != null && pollingRunnable != null) {
            handler.removeCallbacks(pollingRunnable);
            pollingRunnable = null;
        }
    }
}
