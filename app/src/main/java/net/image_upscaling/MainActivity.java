package net.image_upscaling;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.InetAddresses;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;

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
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "UpscalingApp";
    static final String SERVER_URL = "https://image-upscaling.net";
    //static final String SERVER_URL = "https://test.image-upscaling.net";

    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private static final int PICK_IMAGE_REQUEST = 2001;

    // Cache keys for SharedPreferences
    private static final String PREFS_NAME = "UpscalingAppPrefs";
    private static final String KEY_CACHED_CONFIG = "cached_config";
    private static final String KEY_LAST_MODEL = "last_model";
    private static final String KEY_LAST_SCALE = "last_scale";
    private static final String KEY_LAST_FACE_ENHANCE = "last_face_enhance";

    private String clientId;
    private OkHttpClient httpClient;
    private JSONObject upscalersConfig;
    private int selectedImageSize = 0;
    private Uri selectedImageUri;

    // UI Elements
    private Spinner spinnerModel;
    private Spinner spinnerScale;
    private CheckBox checkBoxFaceEnhance;
    private Button btnSelectImage;
    private Button btnAccount;
    private Button btnUpload;
    private ImageView imagePreview;
    private TextView tvNoImage;
    private TextView tvStatus;
    private TextView tvModelInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        clientId = utils.getClientId(this);
        initViews();
        initHttpClient();
        requestStoragePermissions();

        // Load cached config first, then check for updates
        loadCachedConfig();
        loadUpscalersConfig();

        // Handle shared image if app was launched from share intent
        onNewIntent(getIntent());

        // Start polling service in case there is already a request waiting
        Intent serviceIntent = new Intent(MainActivity.this, UpscalingPollingService.class);
        startForegroundService(serviceIntent);
    }

    private void initViews() {
        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerScale = findViewById(R.id.spinnerScale);
        checkBoxFaceEnhance = findViewById(R.id.checkBoxFaceEnhance);
        btnSelectImage = findViewById(R.id.btnSelectImage);
        btnAccount = findViewById(R.id.btnAccount);
        btnUpload = findViewById(R.id.btnUpload);
        imagePreview = findViewById(R.id.imagePreview);
        tvNoImage = findViewById(R.id.tvNoImage);
        tvStatus = findViewById(R.id.tvStatus);
        tvModelInfo = findViewById(R.id.tvModelInfo);

        btnSelectImage.setOnClickListener(v -> selectImage());
        btnAccount.setOnClickListener(v -> openAccountPage());
        btnUpload.setOnClickListener(v -> submitUpscalingRequest());

        // Set up spinner change listeners
        spinnerModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onModelChanged();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // Initialize UI state
        selectImage(null);
        updateStatus("Select an image to begin");
    }

    private void initHttpClient() {
        httpClient = new OkHttpClient.Builder()
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, java.util.List<Cookie> cookies) {}

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
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedImage(intent);
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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String cachedConfigJson = prefs.getString(KEY_CACHED_CONFIG, null);

        if (cachedConfigJson != null) {
            try {
                upscalersConfig = new JSONObject(cachedConfigJson);

                populateModelSpinner();

                Log.i(TAG, "Loaded cached config successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error parsing cached config: " + e.getMessage());
                // Clear invalid cached config
                prefs.edit().remove(KEY_CACHED_CONFIG).apply();
            }
        }
    }

    private void saveConfigToCache(JSONObject config) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String configJson = config.toString();

            prefs.edit()
                    .putString(KEY_CACHED_CONFIG, configJson)
                    .apply();

            Log.i(TAG, "Config saved to cache");
        } catch (Exception e) {
            Log.e(TAG, "Error saving config to cache: " + e.getMessage());
        }
    }

    private boolean hasConfigChanged(JSONObject newConfig) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String cachedConfig = prefs.getString(KEY_CACHED_CONFIG, null);
            if (cachedConfig == null) return true;
            String cachedConfigHash = String.valueOf(cachedConfig.hashCode());

            String newConfigJson = newConfig.toString();
            String newHash = String.valueOf(newConfigJson.hashCode());

            boolean changed = !cachedConfigHash.equals(newHash);
            Log.i(TAG, "Config changed: " + changed + " (cached: " + cachedConfigHash + ", new: " + newHash + ")");
            return changed;
        } catch (Exception e) {
            Log.e(TAG, "Error checking config changes: " + e.getMessage());
            return true; // Assume changed on error
        }
    }

    private String getLastUsedModel(){
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_LAST_MODEL, null);
    }
    private String getLastUsedScale(){
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_LAST_SCALE, null);
    }
    private boolean getLastUsedFaceEnhanceValue(){
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_LAST_FACE_ENHANCE, false);
    }
    private void saveLastUsedSettings(String model, String scale, boolean faceEnhance) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_LAST_MODEL, model)
                .putString(KEY_LAST_SCALE, scale)
                .putBoolean(KEY_LAST_FACE_ENHANCE, faceEnhance)
                .apply();

        Log.i(TAG, "Saved last used settings: model=" + model + ", scale=" + scale + ", faceEnhance=" + faceEnhance);
    }

    private void loadUpscalersConfig() {
        Log.i(TAG, "checking for new config...");
        String url = SERVER_URL + "/get_upscalers_config";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Origin", "android_app")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to load upscalers config: " + e.getMessage());
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    loadUpscalersConfig();
                }, 5000);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.i(TAG, responseBody);
                    JSONObject newConfig = new JSONObject(responseBody);

                    // Check if config has changed
                    if (hasConfigChanged(newConfig)) {
                        upscalersConfig = newConfig;
                        saveConfigToCache(newConfig);

                        runOnUiThread(() -> {
                            populateModelSpinner();
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing upscalers config: " + e.getMessage());
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        loadUpscalersConfig();
                    }, 5000);
                }
            }
        });
    }

    private void selectImage() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    private void selectImage(Uri imageUri) {
        selectedImageUri = imageUri;

        if (selectedImageUri != null) {
            imagePreview.setImageURI(selectedImageUri);
            tvNoImage.setVisibility(View.GONE);
            imagePreview.setVisibility(View.VISIBLE);
            btnSelectImage.setText("Select a different Image");

            spinnerModel.setVisibility(View.VISIBLE);
            spinnerScale.setVisibility(View.VISIBLE);

            // Calculate image size and update available models
            calculateImageSizeAndUpdateModels();
        } else {
            tvNoImage.setVisibility(View.VISIBLE);
            imagePreview.setVisibility(View.GONE);
            btnSelectImage.setText("Select Image");
            btnUpload.setEnabled(false);
            selectedImageSize = 0;

            spinnerModel.setVisibility(View.INVISIBLE);
            spinnerScale.setVisibility(View.INVISIBLE);
            tvModelInfo.setText("");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            selectImage(data.getData());
        }
    }

    private void calculateImageSizeAndUpdateModels() {
        if (selectedImageUri == null) return;

        try {
            ContentResolver contentResolver = getContentResolver();
            InputStream inputStream = contentResolver.openInputStream(selectedImageUri);
            if (inputStream != null) {
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                inputStream.close();

                if (bitmap != null) {
                    selectedImageSize = bitmap.getWidth() * bitmap.getHeight();
                    Log.d(TAG, "Selected image size: " + selectedImageSize + " pixels");

                    populateModelSpinner();
                    updateStatus("Image selected. Choose model and scale, then click 'Upload and Process'.");
                } else {
                    updateStatus("Error: Could not decode image");
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error calculating image size: " + e.getMessage());
            updateStatus("Error: Could not analyze image");
        }
    }

    private void populateModelSpinner() {
        if (upscalersConfig == null) return;

        ArrayList<String> availableModels = new ArrayList<>();
        ArrayList<String> modelLabels = new ArrayList<>();

        try {
            Iterator<String> keys = upscalersConfig.keys();
            while (keys.hasNext()) {
                String modelKey = keys.next();
                JSONObject modelConfig = upscalersConfig.getJSONObject(modelKey);

                // Check if this model supports the selected image size
                boolean isSupported = false;
                if (selectedImageSize > 0) {
                    JSONObject scales = modelConfig.getJSONObject("scales");
                    Iterator<String> scaleKeys = scales.keys();
                    while (scaleKeys.hasNext()) {
                        String scaleKey = scaleKeys.next();
                        JSONObject scaleConfig = scales.getJSONObject(scaleKey);
                        int maxSizeInput = scaleConfig.getInt("max_size_input");
                        if (maxSizeInput >= selectedImageSize) {
                            isSupported = true;
                            break;
                        }
                    }
                } else {
                    return; // no image selected
                }

                if (isSupported) {
                    availableModels.add(modelKey);
                    String help = modelConfig.optString("help", modelKey);
                    //modelLabels.add(modelKey + " - " + help);
                    modelLabels.add(modelKey);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error populating model spinner: " + e.getMessage());
        }

        if (availableModels.isEmpty()) {
            resetUI();
            updateStatus("Error: Image too large for available models");
            return;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modelLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapter);

        // Set selection based on priority: last used > plus > general > first available
        int selectedIndex = -1;

        // First, try to find the last used model
        String lastUsedModel = getLastUsedModel();
        if (lastUsedModel != null) {
            for (int i = 0; i < availableModels.size(); i++) {
                if (availableModels.get(i).equals(lastUsedModel)) {
                    selectedIndex = i;
                    Log.i(TAG, "Selected last used model: " + lastUsedModel);
                    break;
                }
            }
        }
        if (selectedIndex == -1) {
            // Fallback to preferred defaults
            for (int i = 0; i < availableModels.size(); i++) {
                if (availableModels.get(i).equals("general")) {
                    selectedIndex = i;
                    break;
                }
            }
            for (int i = 0; i < availableModels.size(); i++) {
                if (availableModels.get(i).equals("plus")) {
                    selectedIndex = i;
                    break;
                }
            }
        }

        spinnerModel.setSelection(selectedIndex);

        onModelChanged();
    }

    private void onModelChanged() {

        String selectedModel = spinnerModel.getSelectedItem().toString();
        checkBoxFaceEnhance.setChecked(false);

        JSONObject modelConfig;
        try {
            modelConfig = upscalersConfig.getJSONObject(selectedModel);
        } catch (Exception e) {
            Log.e(TAG, "Error in onModelChanged: " + e.getMessage());
            return;
        }

        // Update model info text
        String help = modelConfig.optString("help", "");
        tvModelInfo.setText(help);

        // Update face enhancement checkbox visibility and state
        boolean supportsFx = modelConfig.optBoolean("fx", false);
        checkBoxFaceEnhance.setVisibility(supportsFx ? View.VISIBLE : View.INVISIBLE);

        // Set face enhancement state from last used settings if this is the same model
        if (supportsFx) {
            checkBoxFaceEnhance.setChecked(getLastUsedFaceEnhanceValue());
        }

        // Populate scale spinner
        populateScaleSpinner(modelConfig);
    }

    private void populateScaleSpinner(JSONObject modelConfig) {
        try {
            JSONObject scales = modelConfig.getJSONObject("scales");
            ArrayList<String> availableScales = new ArrayList<>();

            Iterator<String> scaleKeys = scales.keys();
            while (scaleKeys.hasNext()) {
                String scaleKeyString = scaleKeys.next();
                JSONObject scaleConfig = scales.getJSONObject(scaleKeyString);
                int maxSizeInput = scaleConfig.getInt("max_size_input");

                if (selectedImageSize == 0 || maxSizeInput >= selectedImageSize) {
                    if (scaleKeyString.equals("-1"))
                        availableScales.add("4MP");
                    else if (scaleKeyString.equals("-2"))
                        availableScales.add("8MP");
                    else if (scaleKeyString.equals("-3"))
                        availableScales.add("16MP");
                    else
                        availableScales.add(scaleKeyString + "x");
                }
            }

            ArrayAdapter<String> scaleAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, availableScales);
            scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerScale.setAdapter(scaleAdapter);

            // Set selection based on last used scale or default to highest
            int selectedScaleIndex = availableScales.size() - 1; // Default to last element
            String lastUsedScale = getLastUsedScale();

            if (lastUsedScale != null) {
                for (int i = 0; i < availableScales.size(); i++) {
                    if (availableScales.get(i).equals(lastUsedScale)) {
                        selectedScaleIndex = i;
                        Log.i(TAG, "Selected last used scale: " + availableScales.get(i));
                        break;
                    }
                }
            }

            spinnerScale.setSelection(selectedScaleIndex);

            // Enable upload button if everything is ready
            btnUpload.setEnabled(selectedImageUri != null && !availableScales.isEmpty());

        } catch (Exception e) {
            Log.e(TAG, "Error populating scale spinner: " + e.getMessage());
        }
    }

    private void openAccountPage() {
        String url = SERVER_URL + "/account?client_id=" + clientId;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        startActivity(intent);
    }

    private void submitUpscalingRequest() {
        if (selectedImageUri == null) {
            Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show();
            return;
        }

        btnUpload.setEnabled(false);
        updateStatus("Uploading image...");

        String selectedModel = spinnerModel.getSelectedItem().toString();
        String scaleText = spinnerScale.getSelectedItem().toString();
        String scale;
        if (scaleText.equals("4MP"))
            scale = "-1";
        else if (scaleText.equals("8MP"))
            scale = "-2";
        else if (scaleText.equals("16MP"))
            scale = "-3";
        else
            scale = scaleText.replace("x", "");
        boolean faceEnhance = checkBoxFaceEnhance.isChecked();

        // Save current settings as last used
        saveLastUsedSettings(selectedModel, scaleText, faceEnhance);

        Log.i(TAG, "submit request: "+selectedModel+":"+scale);

        try {
            ContentResolver contentResolver = getContentResolver();
            String filename = "image_" + System.currentTimeMillis() + ".jpg";
            String mimeType = contentResolver.getType(selectedImageUri);
            if (mimeType == null) mimeType = "image/*";

            InputStream inputStream = contentResolver.openInputStream(selectedImageUri);
            if (inputStream == null) {
                updateStatus("Error: Could not open file stream");
                btnUpload.setEnabled(true);
                return;
            }

            // Convert InputStream to byte array
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int bytesRead;
            byte[] data = new byte[16384];

            while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            inputStream.close();

            byte[] imageBytes = buffer.toByteArray();

            // Upload image first
            String uploadUrl = SERVER_URL + "/upscaling_upload";

            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            builder.addFormDataPart("image", filename,
                    RequestBody.create(MediaType.parse(mimeType), imageBytes));

            builder.addFormDataPart("scale", String.valueOf(scale));
            builder.addFormDataPart("model", selectedModel);

            RequestBody requestBody = builder.build();

            Request request = new Request.Builder()
                    .url(uploadUrl)
                    .post(requestBody)
                    .header("Origin", "android_app")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        updateStatus("Upload failed - check internet connection");
                        resetUI();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    Log.i(TAG, responseBody);
                    runOnUiThread(() -> {
                        if (response.code() != 200) {
                            Toast.makeText(MainActivity.this, responseBody, Toast.LENGTH_LONG).show();
                            updateStatus(responseBody);
                            resetUI();
                            return;
                        }

                        // Start polling service
                        Intent serviceIntent = new Intent(MainActivity.this, UpscalingPollingService.class);
                        startForegroundService(serviceIntent);

                        Toast.makeText(MainActivity.this,
                                "Job submitted! Processing in background...",
                                Toast.LENGTH_LONG).show();

                        updateStatus("Job submitted! Your upscaled image will appear in your gallery within 1–5 minutes.");
                        resetUI();
                    });
                }
            });

        } catch (Exception e) {
            updateStatus("Upload error: " + e.getMessage());
            resetUI();
            Log.e(TAG, "Error in submitUpscalingRequest: " + e.getMessage());
        }
    }

    private void resetUI() {
        btnUpload.setEnabled(selectedImageUri != null);
        selectImage(null);
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> tvStatus.setText(message));
    }

    // Permission handling methods
    private void requestStoragePermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        ActivityCompat.requestPermissions(this,
                permissionsToRequest.toArray(new String[0]),
                REQUEST_STORAGE_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            } else {
                Toast.makeText(this, "Storage permission is required", Toast.LENGTH_LONG).show();
                updateStatus("Permission denied: Storage permission is required");
            }
        }
    }
}