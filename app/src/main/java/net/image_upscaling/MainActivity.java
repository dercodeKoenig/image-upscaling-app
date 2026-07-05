package net.image_upscaling;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import net.image_upscaling.databinding.ActivityMainBinding;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "UpscalingApp";

    private ActivityMainBinding binding;
    private ApiService apiService;
    private SettingsManager settingsManager;

    private JSONObject upscalersConfig;
    private int selectedImageSize = 0;
    private Uri selectedImageUri;
    private ArrayList<String> currentScaleKeys;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private int configRetryCount = 0;
    private static final int MAX_CONFIG_RETRIES = 5;

    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    selectImage(uri);
                }
            }
    );

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (Boolean isGranted : result.values()) {
                    if (!isGranted) {
                        allGranted = false;
                        break;
                    }
                }
                if (!allGranted) {
                    Toast.makeText(this, "Permissions are required for full functionality", Toast.LENGTH_LONG).show();
                }
                updateUploadButtonState();
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        apiService = new ApiService(this);
        settingsManager = new SettingsManager(this);

        initViews();
        checkPermissions();

        loadCachedConfig();
        loadUpscalersConfig();

        onNewIntent(getIntent());

        Intent serviceIntent = new Intent(this, UpscalingPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void initViews() {
        binding.btnSelectImage.setOnClickListener(v -> {
            if (hasStoragePermission()) {
                pickImageLauncher.launch("image/*");
            } else {
                checkPermissions();
            }
        });
        binding.btnAccount.setOnClickListener(v -> openAccountPage());
        binding.btnUpload.setOnClickListener(v -> submitUpscalingRequest());

        binding.checkBoxWebP.setChecked(settingsManager.isUseWebP());
        binding.checkBoxWebP.setOnCheckedChangeListener((buttonView, isChecked) -> settingsManager.setUseWebP(isChecked));

        binding.spinnerModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onModelChanged();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        selectImage(null);
        updateUploadButtonState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUploadButtonState();
    }

    private void checkPermissions() {
        ArrayList<String> permissions = new ArrayList<>();

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // ONLY request WRITE for Android 9 and older
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissions.isEmpty()) {
            requestPermissionsLauncher.launch(permissions.toArray(new String[0]));
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android 10+, we don't strictly need WRITE permission for MediaStore to save to Pictures
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void updateUploadButtonState() {
        boolean hasPermission = hasStoragePermission();
        if (!hasPermission) {
            updateStatus(getString(R.string.status_permission_required));
            binding.btnUpload.setEnabled(false);
            binding.btnSelectImage.setText(R.string.btn_select_image);
        } else {
            if (selectedImageUri == null) {
                updateStatus(getString(R.string.status_initial));
                binding.btnUpload.setEnabled(false);
                binding.btnSelectImage.setText(R.string.btn_select_image);
            } else {
                updateStatus(getString(R.string.status_image_selected));
                binding.btnUpload.setEnabled(currentScaleKeys != null && !currentScaleKeys.isEmpty());
                binding.btnSelectImage.setText(R.string.btn_select_different_image);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            handleSharedImage(intent);
        }
    }

    private void handleSharedImage(Intent intent) {
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (type.startsWith("image/")) {
                Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (imageUri != null) {
                    selectImage(imageUri);
                }
            }
        }
    }

    private void loadCachedConfig() {
        String cachedConfigJson = settingsManager.getCachedConfig();
        if (cachedConfigJson != null) {
            try {
                upscalersConfig = new JSONObject(cachedConfigJson);
                populateModelSpinner();
                Log.i(TAG, "Loaded cached config successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error parsing cached config: " + e.getMessage());
                settingsManager.clearCachedConfig();
            }
        }
    }

    private void loadUpscalersConfig() {
        apiService.getUpscalersConfig(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to load upscalers config: " + e.getMessage());
                if (configRetryCount < MAX_CONFIG_RETRIES) {
                    configRetryCount++;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> loadUpscalersConfig(), 5000 * configRetryCount);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JSONObject newConfig = new JSONObject(responseBody);

                    if (hasConfigChanged(newConfig)) {
                        upscalersConfig = newConfig;
                        settingsManager.saveConfigToCache(newConfig);
                        runOnUiThread(() -> populateModelSpinner());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing upscalers config: " + e.getMessage());
                }
            }
        });
    }

    private boolean hasConfigChanged(JSONObject newConfig) {
        String cachedConfig = settingsManager.getCachedConfig();
        if (cachedConfig == null) return true;
        return cachedConfig.hashCode() != newConfig.toString().hashCode();
    }

    private void selectImage(Uri imageUri) {
        selectedImageUri = imageUri;
        if (selectedImageUri != null) {
            Bitmap previewBitmap = loadDownsampledBitmap(selectedImageUri, 1024, 1024);
            if (previewBitmap != null) {
                binding.imagePreview.setImageBitmap(previewBitmap);
                binding.tvNoImage.setVisibility(View.GONE);
            } else {
                binding.tvNoImage.setText("image preview error");
                binding.tvNoImage.setVisibility(View.VISIBLE);
            }
            binding.imagePreview.setVisibility(View.VISIBLE);
            binding.btnSelectImage.setText(R.string.btn_select_different_image);
            binding.spinnerModel.setVisibility(View.VISIBLE);
            binding.spinnerScale.setVisibility(View.VISIBLE);

            calculateImageSizeAndUpdateModels();
            updateUploadButtonState();
        } else {
            binding.tvNoImage.setText(R.string.no_image_text);
            binding.tvNoImage.setVisibility(View.VISIBLE);
            binding.imagePreview.setVisibility(View.GONE);
            binding.imagePreview.setImageURI(null);
            binding.btnSelectImage.setText(R.string.btn_select_image);
            binding.btnUpload.setEnabled(false);
            selectedImageSize = 0;
            binding.spinnerModel.setVisibility(View.INVISIBLE);
            binding.spinnerScale.setVisibility(View.INVISIBLE);
            binding.tvModelInfo.setText("");
            binding.checkBoxFaceEnhance.setVisibility(View.GONE);
        }
    }

    private Bitmap loadDownsampledBitmap(Uri uri, int reqWidth, int reqHeight) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            // First decode with inJustDecodeBounds=true to check dimensions
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, options);

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

            // Decode bitmap with inSampleSize set
            options.inJustDecodeBounds = false;

            // Re-open stream because it's been consumed
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is2, null, options);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading downsampled bitmap", e);
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private void calculateImageSizeAndUpdateModels() {
        if (selectedImageUri == null) return;
        updateStatus(getString(R.string.status_analyzing));
        executorService.execute(() -> {
            try {
                ContentResolver contentResolver = getContentResolver();
                InputStream inputStream = contentResolver.openInputStream(selectedImageUri);
                if (inputStream != null) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(inputStream, null, options);
                    inputStream.close();

                    int width = options.outWidth;
                    int height = options.outHeight;
                    selectedImageSize = width * height;

                    runOnUiThread(() -> {
                        populateModelSpinner();
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Error calculating image size: " + e.getMessage());
                runOnUiThread(() -> updateStatus(getString(R.string.status_error_analyze)));
            }
        });
    }

    private void populateModelSpinner() {
        if (upscalersConfig == null || selectedImageSize == 0) return;

        ArrayList<String> availableModels = new ArrayList<>();
        try {
            Iterator<String> keys = upscalersConfig.keys();
            while (keys.hasNext()) {
                String modelKey = keys.next();
                JSONObject modelConfig = upscalersConfig.getJSONObject(modelKey);
                JSONObject scales = modelConfig.getJSONObject("scales");
                Iterator<String> scaleKeys = scales.keys();
                while (scaleKeys.hasNext()) {
                    if (scales.getJSONObject(scaleKeys.next()).getInt("max_size_input") >= selectedImageSize) {
                        availableModels.add(modelKey);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error populating model spinner: " + e.getMessage());
        }

        if (availableModels.isEmpty()) {
            currentScaleKeys = null;
            binding.spinnerModel.setVisibility(View.INVISIBLE);
            binding.spinnerScale.setVisibility(View.INVISIBLE);
            binding.tvModelInfo.setText("");
            binding.checkBoxFaceEnhance.setVisibility(View.GONE);
            resetUI();
            updateStatus(getString(R.string.status_error_large));
            return;
        }

        binding.spinnerModel.setVisibility(View.VISIBLE);
        binding.spinnerScale.setVisibility(View.VISIBLE);
        updateStatus(getString(R.string.status_image_selected));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, availableModels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerModel.setAdapter(adapter);

        String lastUsedModel = settingsManager.getLastUsedModel();
        int selectedIndex = 0;
        if (lastUsedModel != null && availableModels.contains(lastUsedModel)) {
            selectedIndex = availableModels.indexOf(lastUsedModel);
        } else if (availableModels.contains("general")) {
            selectedIndex = availableModels.indexOf("general");
        }
        binding.spinnerModel.setSelection(selectedIndex);
        onModelChanged();
        updateUploadButtonState();
    }

    private void onModelChanged() {
        Object selectedItem = binding.spinnerModel.getSelectedItem();
        if (selectedItem == null) return;
        String selectedModel = selectedItem.toString();
        binding.checkBoxFaceEnhance.setChecked(false);

        try {
            JSONObject modelConfig = upscalersConfig.getJSONObject(selectedModel);
            binding.tvModelInfo.setText(modelConfig.optString("help", ""));
            boolean supportsFx = modelConfig.optBoolean("fx", false);
            binding.checkBoxFaceEnhance.setVisibility(supportsFx ? View.VISIBLE : View.GONE);
            if (supportsFx) {
                binding.checkBoxFaceEnhance.setChecked(settingsManager.getLastUsedFaceEnhanceValue());
            }
            populateScaleSpinner(modelConfig);
        } catch (Exception e) {
            Log.e(TAG, "Error in onModelChanged: " + e.getMessage());
        }
    }

    private void populateScaleSpinner(JSONObject modelConfig) {
        try {
            JSONObject scales = modelConfig.getJSONObject("scales");
            ArrayList<String> displayNames = new ArrayList<>();
            currentScaleKeys = new ArrayList<>();

            Iterator<String> keys = scales.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject config = scales.getJSONObject(key);
                if (selectedImageSize <= config.getInt("max_size_input") && selectedImageSize >= config.optInt("min_size_input", -1)) {
                    displayNames.add(config.optString("display_name", key));
                    currentScaleKeys.add(key);
                }
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, displayNames);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            binding.spinnerScale.setAdapter(adapter);

            String lastUsedScale = settingsManager.getLastUsedScale();
            int index = displayNames.size() - 1;
            if (lastUsedScale != null && displayNames.contains(lastUsedScale)) {
                index = displayNames.indexOf(lastUsedScale);
            }
            binding.spinnerScale.setSelection(index);
            updateUploadButtonState();
        } catch (Exception e) {
            Log.e(TAG, "Error populating scale spinner: " + e.getMessage());
        }
    }

    private void openAccountPage() {
        String url = apiService.SERVER_URL + "/account?client_id=" + apiService.getClientId();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    private void submitUpscalingRequest() {
        if (selectedImageUri == null) return;

        Object selectedModelObj = binding.spinnerModel.getSelectedItem();
        Object selectedScaleObj = binding.spinnerScale.getSelectedItem();

        if (selectedModelObj == null || selectedScaleObj == null) {
            updateUploadButtonState();
            return;
        }

        binding.btnUpload.setEnabled(false);
        updateStatus(getString(R.string.status_uploading));

        String model = selectedModelObj.toString();
        String scale = currentScaleKeys.get(binding.spinnerScale.getSelectedItemPosition());
        boolean fx = binding.checkBoxFaceEnhance.isChecked();
        boolean useWebP = binding.checkBoxWebP.isChecked();

        settingsManager.saveLastUsedSettings(model, selectedScaleObj.toString(), fx);
        settingsManager.setUseWebP(useWebP);

        executorService.execute(() -> {
            try {
                ContentResolver cr = getContentResolver();
                String mime = cr.getType(selectedImageUri);
                if (mime == null) mime = "image/jpeg";
                String name = "img_" + System.currentTimeMillis() + ".jpg";

                MediaType mediaType = MediaType.parse(mime);
                RequestBody imageBody = new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return mediaType;
                    }

                    @Override
                    public void writeTo(@NonNull BufferedSink sink) throws IOException {
                        try (InputStream is = getContentResolver().openInputStream(selectedImageUri)) {
                            if (is == null) throw new IOException("Failed to open input stream");
                            Source source = Okio.source(is);
                            sink.writeAll(source);
                        }
                    }
                };

                apiService.uploadImage(imageBody, name, model, scale, fx, useWebP, new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        runOnUiThread(() -> {
                            updateStatus("Upload failed: " + e.getMessage());
                            binding.btnUpload.setEnabled(true);
                        });
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        String body = response.body() != null ? response.body().string() : "Empty Body";
                        Log.i(TAG, "Upload Response: " + response.code() + " - " + body);
                        runOnUiThread(() -> {
                            if (response.code() != 200) {
                                updateStatus("Error: " + body);
                                binding.btnUpload.setEnabled(true);
                            } else {
                                updateStatus(getString(R.string.status_job_submitted));
                                Toast.makeText(MainActivity.this, getString(R.string.status_job_submitted), Toast.LENGTH_LONG).show();

                                // Start service explicitly after successful upload
                                Intent serviceIntent = new Intent(MainActivity.this, UpscalingPollingService.class);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(serviceIntent);
                                } else {
                                    startService(serviceIntent);
                                }

                                selectImage(null);
                            }
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateStatus("Error: " + e.getMessage());
                    resetUI();
                });
            }
        });
    }

    private void resetUI() {
        updateUploadButtonState();
    }

    private void updateStatus(String status) {
        binding.tvStatus.setText(status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
