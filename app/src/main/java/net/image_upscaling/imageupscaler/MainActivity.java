package net.image_upscaling.imageupscaler;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

    private String clientId;
    private OkHttpClient httpClient;
    private JSONObject upscalersConfig;
    private int selectedImageSize = 0;

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

    private Uri selectedImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        clientId = utils.getClientId(this);
        initViews();
        initHttpClient();
        requestStoragePermissions();
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

    private void loadUpscalersConfig() {
        String url = SERVER_URL + "/get_upscalers_config"; // Adjust endpoint as needed

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Origin", "android_app")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to load upscalers config: " + e.getMessage());
                updateStatus("unable to load model config");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.i(TAG, responseBody);
                    upscalersConfig = new JSONObject(responseBody);

                    runOnUiThread(() -> {
                        populateModelSpinner();
                        updateStatus("Configuration loaded. Select an image to continue.");
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing upscalers config: " + e.getMessage());
                    updateStatus("unable to load model config");
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

            // Calculate image size and update available models
            calculateImageSizeAndUpdateModels();
        } else {
            tvNoImage.setVisibility(View.VISIBLE);
            imagePreview.setVisibility(View.GONE);
            btnSelectImage.setText("Select Image");
            btnUpload.setEnabled(false);
            selectedImageSize = 0;
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
                    isSupported = true; // Show all models when no image selected
                }

                if (isSupported) {
                    availableModels.add(modelKey);
                    String help = modelConfig.optString("help", modelKey);
                    modelLabels.add(modelKey + " - " + help);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error populating model spinner: " + e.getMessage());
        }

        if (availableModels.isEmpty()) {
            updateStatus("Error: Image too large for available models");
            btnUpload.setEnabled(false);
            return;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modelLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapter);

        // Set preferred default selection
        int preferredIndex = -1;
        for (int i = 0; i < availableModels.size(); i++) {
            if (availableModels.get(i).equals("general")) {
                preferredIndex = i;
                break;
            }
        }
        for (int i = 0; i < availableModels.size(); i++) {
            if (availableModels.get(i).equals("plus")) {
                preferredIndex = i;
                break;
            }
        }
        if (preferredIndex >= 0) {
            spinnerModel.setSelection(preferredIndex);
        }

        // Store available models for later use
        spinnerModel.setTag(availableModels);

        onModelChanged();
    }

    private void onModelChanged() {
        ArrayList<String> availableModels = (ArrayList<String>) spinnerModel.getTag();

        if (availableModels == null || spinnerModel.getSelectedItemPosition() < 0) return;

        String selectedModel = availableModels.get(spinnerModel.getSelectedItemPosition());



        try {
            JSONObject modelConfig = upscalersConfig.getJSONObject(selectedModel);

            // Update model info text
            String help = modelConfig.optString("help", "");
            tvModelInfo.setText(help);

            // Update face enhancement checkbox visibility
            boolean supportsFx = modelConfig.optBoolean("fx", false);
            checkBoxFaceEnhance.setVisibility(supportsFx ? View.VISIBLE : View.GONE);

            // Populate scale spinner
            populateScaleSpinner(selectedModel, modelConfig);

        } catch (Exception e) {
            Log.e(TAG, "Error in onModelChanged: " + e.getMessage());
        }
    }

    private void populateScaleSpinner(String modelKey, JSONObject modelConfig) {
        try {
            JSONObject scales = modelConfig.getJSONObject("scales");
            ArrayList<String> availableScales = new ArrayList<>();

            Iterator<String> scaleKeys = scales.keys();
            while (scaleKeys.hasNext()) {
                String scaleKey = scaleKeys.next();
                JSONObject scaleConfig = scales.getJSONObject(scaleKey);
                int maxSizeInput = scaleConfig.getInt("max_size_input");

                if (selectedImageSize == 0 || maxSizeInput >= selectedImageSize) {
                    availableScales.add(scaleKey + "x");
                }
            }

            ArrayAdapter<String> scaleAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, availableScales);
            scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerScale.setAdapter(scaleAdapter);
            spinnerScale.setSelection(availableScales.size()-1);

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

        // Get selected model and scale
        @SuppressWarnings("unchecked")
        ArrayList<String> availableModels = (ArrayList<String>) spinnerModel.getTag();
        String selectedModel = availableModels.get(spinnerModel.getSelectedItemPosition());
        String scaleText = spinnerScale.getSelectedItem().toString();
        String scale = scaleText.replace("x", "");
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

            builder.addFormDataPart("scale", scale);
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
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Storage permission is required", Toast.LENGTH_LONG).show();
                updateStatus("Permission denied: Storage permission is required");
            }
        }
    }
}