package net.image_upscaling;

import android.content.ContextWrapper;
import android.util.Log;
import androidx.annotation.NonNull;
import okhttp3.*;
import java.io.IOException;

public class ApiService {
    private static final String TAG = "ApiService";
    public static final String SERVER_URL = "https://image-upscaling.net";
    private final OkHttpClient httpClient;
    private final String clientId;

    public ApiService(ContextWrapper context) {
        this.clientId = Utils.getClientId(context);
        this.httpClient = new OkHttpClient.Builder()
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(@NonNull HttpUrl url, @NonNull java.util.List<Cookie> cookies) {}

                    @Override
                    @NonNull
                    public java.util.List<Cookie> loadForRequest(@NonNull HttpUrl url) {
                        return java.util.Collections.singletonList(new Cookie.Builder()
                                .name("client_id")
                                .value(clientId)
                                .domain(url.host())
                                .path("/")
                                .build());
                    }
                })
                .build();
    }

    public void getUpscalersConfig(Callback callback) {
        Log.d(TAG, "GET " + SERVER_URL + "/get_upscalers_config");
        Request request = new Request.Builder()
                .url(SERVER_URL + "/get_upscalers_config")
                .get()
                .header("Origin", "android_app")
                .build();
        httpClient.newCall(request).enqueue(callback);
    }

    public void uploadImage(RequestBody imageBody, String filename, String model, String scale, boolean fx, boolean useWebP, Callback callback) {
        Log.d(TAG, "POST " + SERVER_URL + "/upscaling_upload");
        Log.d(TAG, "Args: model=" + model + ", scale=" + scale + ", fx=" + fx + ", useWebP=" + useWebP + ", filename=" + filename);
        
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        builder.addFormDataPart("image", filename, imageBody);
        builder.addFormDataPart("scale", scale);
        builder.addFormDataPart("model", model);
        if (fx) {
            builder.addFormDataPart("fx", "true");
        }
        if (useWebP) {
            builder.addFormDataPart("use_webp", "true");
        }

        Request request = new Request.Builder()
                .url(SERVER_URL + "/upscaling_upload")
                .post(builder.build())
                .header("Origin", "android_app")
                .build();
        httpClient.newCall(request).enqueue(callback);
    }

    public void downloadFile(String url, Callback callback) {
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(callback);
    }

    public void checkPendingRequests(Callback callback) {
        Log.d(TAG, "GET " + SERVER_URL + "/upscaling_get_status");
        Request request = new Request.Builder()
                .url(SERVER_URL + "/upscaling_get_status")
                .get()
                .header("Origin", "android_app")
                .build();
        httpClient.newCall(request).enqueue(callback);
    }

    public String getClientId() {
        return clientId;
    }
}
