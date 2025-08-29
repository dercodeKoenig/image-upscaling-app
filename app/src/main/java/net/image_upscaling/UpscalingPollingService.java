package net.image_upscaling;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class UpscalingPollingService extends Service {
    private static final String TAG = "UpscalingPollingService";
    private static final String CHANNEL_ID = "upscaling_polling_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final int POLL_INTERVAL = 5000; // 5 seconds

    private Handler handler;
    private Runnable pollingRunnable;
    private OkHttpClient httpClient;
    private String serverUrl;
    private String clientId;

    private int pendingRequests = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        serverUrl = MainActivity.SERVER_URL;
        clientId = utils.getClientId(this);

        httpClient = new OkHttpClient.Builder()
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, java.util.List<Cookie> cookies) {
                    }

                    @Override
                    public java.util.List<Cookie> loadForRequest(HttpUrl url) {
                        return java.util.Collections.singletonList(new Cookie.Builder()
                                .name("client_id")
                                .value(clientId)
                                .domain(url.host())
                                .build());
                    }
                })
                .build();

        handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createStatusNotification("Checking for completed images..."));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startPolling() {
        pendingRequests = 9999; // so that it keeps running until it receives the actual amount
        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                checkPendingRequests();
                if (pendingRequests > 0) {
                    handler.postDelayed(this, POLL_INTERVAL);

                    String notificationString = "Checking for completed images...";
                    if (pendingRequests != 9999) {
                        if (pendingRequests == 1)
                            notificationString = "1 upscaling request processing...";
                        else
                            notificationString = pendingRequests + " upscaling requests processing...";
                    }
                    updateNotification(notificationString);

                } else {
                    Log.d(TAG, "No pending requests, stopping service");
                    stopForeground(true);
                    updateNotification("");
                    stopSelf();
                }
            }
        };

        handler.post(pollingRunnable);
    }

    private void checkPendingRequests() {
        Log.d(TAG, "Checking upscaling requests...");

        String url = serverUrl + "/upscaling_get_status";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Origin", "android_app")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to check upscaling status: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    JSONArray processed = jsonResponse.getJSONArray("processed");
                    JSONArray pending = jsonResponse.getJSONArray("pending");
                    JSONArray processsing = jsonResponse.getJSONArray("processing");

                    pendingRequests =pending.length() + processsing.length() + processed.length();
                    Log.i(TAG, "pending requests: "+pendingRequests);


                    // Check if this request is in the processed list
                    for (int i = 0; i < processed.length(); i++) {
                        String processedFileUrl = processed.getString(i);

                        // Extract filename from path
                        String[] pathParts = processedFileUrl.split("/");
                        String filename = pathParts[pathParts.length - 1];

                        // Build download URL
                        String downloadUrl = processedFileUrl + "?delete_after_download=&client_id=" + clientId;

                        // Download the image
                        downloadProcessedImage(downloadUrl, filename);
                    }


                } catch (Exception e) {
                    Log.e(TAG, "Error parsing upscaling status response: " + e.getMessage());
                }
            }
        });
    }

    private void downloadProcessedImage(String downloadUrl, String filename) {
        Log.d(TAG, "Download URL: " + downloadUrl);
        Log.d(TAG, "Filename: " + filename);

        Request request = new Request.Builder()
                .url(downloadUrl)
                .get()
                .addHeader("User-Agent", "Android App")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Download failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "Response code: " + response.code());

                if (!response.isSuccessful()) {
                    Log.e(TAG, "Server error: " + response.code());
                    return;
                }

                try {
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        throw new IOException("Response body is null");
                    }

                    byte[] fileBytes = responseBody.bytes();
                    Log.d(TAG, "Downloaded " + fileBytes.length + " bytes successfully");

                    if (fileBytes.length < 100) {
                        Log.e(TAG, "Downloaded file too small, might be corrupted: " + fileBytes.length);
                    }

                    handler.post(() -> saveFileToGallery(fileBytes, filename));

                } catch (IOException e) {
                    Log.e(TAG, "Error reading response: " + e.getMessage());
                }
            }
        });
    }

    private void saveFileToGallery(byte[] fileBytes, String filename) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(fileBytes, filename);
            } else {
                saveWithTraditionalMethod(fileBytes, filename);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving file: " + e.getMessage());
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private void saveWithMediaStore(byte[] fileBytes, String filename) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri itemUri = resolver.insert(collection, values);

        if (itemUri != null) {
            try (OutputStream outputStream = resolver.openOutputStream(itemUri)) {
                outputStream.write(fileBytes);
                outputStream.flush();
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(itemUri, values, null, null);

            Log.d(TAG, "File saved successfully with MediaStore: " + filename);
            showSuccessNotification();
        } else {
            throw new IOException("Failed to create MediaStore entry");
        }
    }

    private void saveWithTraditionalMethod(byte[] fileBytes, String filename) throws IOException {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "WRITE_EXTERNAL_STORAGE permission not granted");
            throw new IOException("Storage permission not granted");
        }

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs();
        }

        File file = new File(downloadsDir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(fileBytes);
            fos.flush();
        }

        MediaScannerConnection.scanFile(this,
                new String[]{file.getAbsolutePath()},
                new String[]{"image/png"},
                (path, uri) -> Log.d(TAG, "Media scan completed for: " + path));

        Log.d(TAG, "File saved successfully (traditional method): " + filename);
        showSuccessNotification();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Image Upscaling Processing",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Background upscaling processing status");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createStatusNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(text)
                .setContentText("This can take some time...")
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!text.isEmpty()) {
            notificationManager.notify(NOTIFICATION_ID, createStatusNotification(text));
        } else {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    private void showSuccessNotification() {

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Upscaling Complete!")
                .setContentText("Your upscaled image is ready in your gallery")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Your upscaled image has been saved and should now be visible in your image gallery"))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setAutoCancel(true)
                .setSilent(false)
                .build();

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify((int) System.currentTimeMillis(), notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && pollingRunnable != null) {
            handler.removeCallbacks(pollingRunnable);
        }
    }
}