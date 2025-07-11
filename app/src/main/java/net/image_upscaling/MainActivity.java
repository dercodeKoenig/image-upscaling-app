package net.image_upscaling;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements DefaultLifecycleObserver {
    private static final int PICK_IMAGE_REQUEST = 2001;
    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private static final String PREFS_NAME = "app_prefs";
    private static final String CLIENT_ID_KEY = "client_id";

    private String clientId;
    private OkHttpClient httpClient;
    private Handler handler;

    // UI Elements
    private Spinner spinnerScale;
    private CheckBox checkBoxFaceEnhance;
    private Button btnSelectImage;
    private Button btnAccount;
    private Button btnUpload;
    private ImageView imagePreview;
    private TextView tvNoImage;
    private TextView tvStatus;

    private TextView tvStatus2;
    private TextView infoline;
    private ProgressBar progressBar;

    // Image data
    private Uri selectedImageUri;
    private List<Uri> nextImageUris = new LinkedList<>();
    private float usedQuota = 0;
    private float freeQuota = 0;
    private float balance = 0;
    private int noDataCounter = 0;
    private boolean isActive = true;
    private boolean isScanning = true;

    private static final int MAX_DOWNLOADED_FILES_CACHE = 50;
    private LinkedHashSet<String> downloadedFiles = new LinkedHashSet<>();


    private String serverurl = "https://image-upscaling.net";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        spinnerScale = findViewById(R.id.spinnerScale);
        checkBoxFaceEnhance = findViewById(R.id.checkBoxFaceEnhance);
        btnSelectImage = findViewById(R.id.btnSelectImage);
        btnAccount = findViewById(R.id.btnAccount);
        btnUpload = findViewById(R.id.btnUpload);
        imagePreview = findViewById(R.id.imagePreview);
        tvNoImage = findViewById(R.id.tvNoImage);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatus2 = findViewById(R.id.tvStatus2);
        progressBar = findViewById(R.id.progressBar);
        infoline = findViewById(R.id.infoline);


        // Set up spinner with scale factors
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.scale_factors, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerScale.setAdapter(adapter);
        spinnerScale.setSelection(1); // Default to 2x

        // Generate or retrieve client ID
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        clientId = prefs.getString(CLIENT_ID_KEY, null);
        if (clientId == null) {
            clientId = generateClientId();
            prefs.edit().putString(CLIENT_ID_KEY, clientId).apply();
        }

        // Initialize HTTP client with cookie jar
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

        btnSelectImage.setOnClickListener(v -> {
            if (checkReadStoragePermission()) {
                selectImage();
            } else {
                requestReadStoragePermission();
            }
        });
        btnAccount.setOnClickListener(v -> {
            String url = serverurl+"/account?client_id=" + clientId;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            v.getContext().startActivity(intent);
        });


        btnUpload.setOnClickListener(v -> {
            if (selectedImageUri != null) {
                if (checkWriteStoragePermission()) {
                    uploadImage(selectedImageUri);
                } else {
                    requestWriteStoragePermission();
                }
            } else {
                Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show();
            }
        });

        // Handle shared image if app was launched from share intent
        handleSharedImage(getIntent());

        isScanning = true;
        startPeriodicCheck();

        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Handle when app receives a new intent (e.g., share from another app when already running)
        handleSharedImage(intent);
    }

    @Override
    public void onStart(LifecycleOwner owner) {
        isActive = true;
        if (!isScanning) {
            //Toast.makeText(MainActivity.this,
            //        "image-upscaler awaking from sleep mode after refocus",
            //        Toast.LENGTH_SHORT).show();

            noDataCounter = 0;
            startPeriodicCheck();
        }
    }

    @Override
    public void onStop(LifecycleOwner owner) {
        isActive = false;
    }

    private void handleSharedImage(Intent intent) {
        String action = intent.getAction();
        String type = intent.getType();

        nextImageUris.clear();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (type.startsWith("image/")) {
                Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (imageUri != null) {
                    prepareImage(imageUri);
                    updateStatus("Image shared from another app. Adjust settings and click 'Upload and Process'.");
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            if (type.startsWith("image/")) {
                ArrayList<Uri> imageUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (imageUris != null && !imageUris.isEmpty()) {
                    // Use the first image for preview
                    prepareImage(imageUris.get(0));

                    for (int i = 1; i < imageUris.size(); i++) {
                        nextImageUris.add(imageUris.get(i));
                    }

                    updateStatus(imageUris.size() + " Images shared. Adjust settings and click 'Upload and Process'.");
                }
            }
        }
    }

    private boolean checkReadStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestReadStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13+ (API 33+), use READ_MEDIA_IMAGES
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQUEST_STORAGE_PERMISSION);
        } else {
            // For Android 12 and below (API 32-), use READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    private boolean checkWriteStoragePermission() {
        // For Android 10 and below, check WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        // For Android 11+, the app can write to its own directory without permission
        return true;
    }

    private void requestWriteStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, continue with the operation
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show();

                // Check which permission was requested
                if (permissions[0].equals(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                        permissions[0].equals(Manifest.permission.READ_MEDIA_IMAGES)) {
                    selectImage();
                } else if (permissions[0].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) && selectedImageUri != null) {
                    uploadImage(selectedImageUri);
                }
            } else {
                // Permission denied, inform the user
                Toast.makeText(this, "Storage permission is required", Toast.LENGTH_LONG).show();
                updateStatus("Permission denied: Storage permission is required");
            }
        }
    }

    private void selectImage() {
        nextImageUris.clear();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    private void prepareImage(Uri uri){
        selectedImageUri = uri;
        displayImagePreview(selectedImageUri);
        btnUpload.setEnabled(true);
        updateStatus("Image selected. Click 'Upload and Process' to continue.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            prepareImage(data.getData());

        }
    }

    private void displayImagePreview(Uri uri) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            imagePreview.setImageBitmap(bitmap);
            tvNoImage.setVisibility(View.GONE);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void uploadImage(Uri uri) {
        btnUpload.setEnabled(false);

        try {
            // Get selected scale factor
            String scaleText = spinnerScale.getSelectedItem().toString();
            String scale = scaleText.substring(0, 1); // Extract just the number


            String url = serverurl+"/upscaling_upload";

            // Prepare file and form
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            builder.addFormDataPart("scale", scale);
            // Check if face enhancement is enabled
            if (checkBoxFaceEnhance.isChecked()) {
                builder.addFormDataPart("fx", "");
            }

            String path = FileUtils.getPath(this, uri);
            if (path == null) {
                updateStatus("Error: Could not resolve file path from URI");
                return;
            }

            File file = new File(path);
            if (!file.exists()) {
                updateStatus("Error: File does not exist at path: " + path);
                return;
            }

            // Track this upload
            String filename = file.getName();

            // Show upload progress indication
            progressBar.setVisibility(View.VISIBLE);
            updateStatus("Uploading image...");

            builder.addFormDataPart("image", filename, RequestBody.create(MediaType.parse("image/*"), file));
            RequestBody requestBody = builder.build();

            Request request = new Request.Builder().url(url)
                    .post(requestBody)
                    .header("Origin", "android_app")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        resetUIForNewImage(false);
                        updateStatus("error - do you have internet connection?");
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                responseBody,
                                Toast.LENGTH_SHORT).show();

                        // Reset the UI for more uploads
                        updateStatus(responseBody);
                        resetUIForNewImage(true);
                        checkProcessedImages();

                        if (!isScanning) {
                            //Toast.makeText(MainActivity.this,
                            //        "image-upscaler awaking from sleep mode",
                            //        Toast.LENGTH_SHORT).show();

                            noDataCounter = 0;
                            startPeriodicCheck();
                        }
                    });
                }
            });
        } catch (Exception e) {
            updateStatus("Upload error: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void resetUIForNewImage(boolean auto_upload) {
        btnSelectImage.setEnabled(true);
        imagePreview.setImageDrawable(null);
        tvNoImage.setVisibility(View.VISIBLE);
        selectedImageUri = null;
        btnUpload.setEnabled(false);
        progressBar.setVisibility(View.INVISIBLE);

        if (!nextImageUris.isEmpty()){ // continue with next image if multiple was selected
            Uri nextImage =nextImageUris.remove(0);
            prepareImage(nextImage);
            if (auto_upload){
                uploadImage(selectedImageUri);
            }
        }

    }

    private void startPeriodicCheck() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkProcessedImages();
                checkQuotaInfo();
                if (noDataCounter < 6 || isActive) {
                    isScanning = true;
                    handler.postDelayed(this, TimeUnit.SECONDS.toMillis(5));
                } else {
                    isScanning = false;
//                    Toast.makeText(MainActivity.this,
//                    "image-upscaler going sleep mode.",
//                            Toast.LENGTH_LONG).show();
                }
            }
        }, TimeUnit.SECONDS.toMillis(0));
    }


    private void updateInfoText(){
        runOnUiThread(() -> {
            if (balance >= 0) {
                infoline.setText("Quota used: " + usedQuota + " / " + freeQuota + "\nBalance: " + balance);
            }else{
                infoline.setText("Quota used: " + usedQuota + " / " + freeQuota);
            }
        });
    }
    private void checkQuotaInfo() {
        String url = serverurl+"/get_account_info";

        Request request = new Request.Builder().url(url).get().build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Log error if needed
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);

                    float free = (float) json.optDouble("quota_free", 0.0);
                    float used = (float) json.optDouble("quota_used", 0.0);
                    float bal = (float) json.optDouble("balance", 0.0);
                    String email = (String) json.optString("email", "");
                    if (email.isEmpty())
                        bal = -1;

                    freeQuota = Math.round(free * 100.0f) / 100.0f;
                    usedQuota = Math.round(used * 100.0f) / 100.0f;
                    balance = Math.round(bal * 100.0f) / 100.0f;

                } catch (Exception e) {
                    // Handle parse error if needed
                }
                updateInfoText();
            }
        });
    }

    private void checkProcessedImages() {
        String url = serverurl+"/upscaling_get_status";
        Request request = new Request.Builder().url(url).get().build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                //Log.e(TAG, "Check failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    JSONObject data = new JSONObject(response.body().string());
                    JSONArray completed = data.getJSONArray("processed");

                    JSONArray waiting1 = data.getJSONArray("pending");
                    JSONArray waiting2 = data.getJSONArray("processing");

                    int waiting = waiting1.length() + waiting2.length();

                    // Remove oldest entries if we exceed the limit
                    while (downloadedFiles.size() > MAX_DOWNLOADED_FILES_CACHE) {
                        String oldest = downloadedFiles.iterator().next();
                        downloadedFiles.remove(oldest);
                        //Log.d("image-upscaling", "Removed old download record: " + oldest);
                    }

                    for (int i = 0; i < completed.length(); i++) {
                        String filepath = completed.getString(i);
                        if (!downloadedFiles.contains(filepath)) {
                            downloadedFiles.add(filepath);
                            downloadFile(filepath);
                        }
                    }

                    updateStatus2(waiting + " image(s) are currently processing");

                    int totalImages = waiting;
                    if (totalImages == 0) {
                        noDataCounter++;
                    } else {
                        noDataCounter = 0;
                    }

                } catch (Exception e) {
                    //Log.e("image-upscaling", "Parse error: " + e.getMessage());
                }
            }
        });
    }

    private String generateClientId() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void updateStatus(String message) {
        //Log.d(TAG, message);
        runOnUiThread(() -> {
            tvStatus.setText(message);
        });
    }

    private void updateStatus2(String message) {
        //Log.d(TAG, message);
        runOnUiThread(() -> {
            tvStatus2.setText(message);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Remove any pending handlers
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }


    public void downloadFile(String filepath) {
        String[] split = filepath.split("/");
        String filename = split[split.length - 1];
        String downloadUrl = serverurl+"/download_upscaling_data/processed/"+filename+"?delete_after_download=&client_id="+clientId;
        //Log.e("image-upscaling", downloadUrl);

        File file = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                filename
        );

        Uri dest = Uri.fromFile(file);
        DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(downloadUrl))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setTitle("Image Upscale")
                        .setDescription("Downloading processed image")
                        .setDestinationUri(dest);

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        dm.enqueue(request);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this,
                        "downloading file "+filename,
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}