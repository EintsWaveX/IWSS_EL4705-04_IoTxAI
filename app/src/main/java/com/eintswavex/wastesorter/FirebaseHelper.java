package com.eintswavex.wastesorter;

import android.util.Log;
import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FirebaseHelper {
    private static final String TAG = "FirebaseHelper";
    private static FirebaseHelper instance;

    private DatabaseReference appStatusRef;
    private DatabaseReference inferenceDataRef;

    private String deviceId = "esp32_cam";

    private String lastUploadedImagePath = "";
    private float lastUploadedConfidence = 0.0f;
    private long lastUploadTime = 0;
    private static final long MIN_UPLOAD_INTERVAL = 1000; // 1 seconds minimum between uploads

    private FirebaseHelper() {
        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance("https://iwss-iotxaixes-default-rtdb.firebaseio.com/");
            appStatusRef = database.getReference("app_status");
            inferenceDataRef = database.getReference("inference_data");

            Log.d(TAG, "[FIREBASE] Firebase initialized successfully!");
        } catch (Exception e) {
            Log.e(TAG, "[FIREBASE] Failed to initialize Firebase: " + e.getMessage(), e);
        }
    }

    public static synchronized FirebaseHelper getInstance() {
        if (instance == null) {
            instance = new FirebaseHelper();
        }
        return instance;
    }

    // ==================== APP STATUS ====================
    public void updateAppStatus(boolean isIdle, boolean isRunning, boolean isPaused, int interval) {
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("on_idle", isIdle);
            status.put("on_running", isRunning);
            status.put("on_paused", isPaused);
            status.put("interval", interval);
            status.put("last_updated", getCurrentTimestamp());
            status.put("device_id", deviceId);

            appStatusRef.setValue(status)
                    .addOnSuccessListener(aVoid ->
                            Log.d(TAG, "[FIREBASE] App status updated successfully: on_idle=" + isIdle +
                                    ", on_running=" + isRunning + ", on_paused=" + isPaused + ", interval=" + interval))
                    .addOnFailureListener(e ->
                            Log.e(TAG, "[FIREBASE] Failed to update app status: " + e.getMessage()));

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Error updating app status: " + e.getMessage(), e);
        }
    }

    public void listenForAppStatusChanges(AppStatusListener listener) {
        appStatusRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    try {
                        Boolean idle = dataSnapshot.child("on_idle").getValue(Boolean.class);
                        Boolean running = dataSnapshot.child("on_running").getValue(Boolean.class);
                        Boolean paused = dataSnapshot.child("on_paused").getValue(Boolean.class);
                        String updatedDevice = dataSnapshot.child("device_id").getValue(String.class);
                        Integer interval = dataSnapshot.child("interval").getValue(Integer.class);

                        if (!deviceId.equals(updatedDevice)) {
                            listener.onAppStatusChanged(idle != null && idle,
                                    running != null && running,
                                    paused != null && paused,
                                    interval != null ? interval : 0);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "[ERROR] Error parsing app status: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "[FIREBASE] App status listener cancelled: " + databaseError.getMessage());
            }
        });
    }

    // ==================== INFERENCE DATA ====================
    public void uploadInferenceData(String imagePath, String category, float confidence, String modelVersion, String inferenceMode) {
        if (shouldUpload(imagePath, confidence)) {
            Log.d(TAG, "[FIREBASE] Skipping upload - duplicate or too soon!");
            return;
        }

        try {
            // Use fixed key instead of push ID - This overwrites previous data
            String inferenceId = "latest_inference";  // Fixed key for latest data
            // OR use: String inferenceId = "current"; // Alternative fixed key

            // Alternative: Use timestamp-based key for ordering
            // String inferenceId = "inference_" + System.currentTimeMillis();

            float weight = 0.0f; // Sent by the HX711 code from the Arduino client.

            // Determine if valid based on confidence threshold
            boolean valid = true; // For now, set every inference data as valid inferencing process.

            Map<String, Object> inference = new HashMap<>();
            inference.put("timestamp", getCurrentTimestamp());
            inference.put("device_id", deviceId);
            inference.put("category", category.toLowerCase());
            inference.put("confidence", Math.round(confidence * 10000) / 10000.0); // 4 decimal places
            inference.put("valid", valid);
            inference.put("weight", Math.round(weight * 100) / 100.0); // 2 decimal places
            inference.put("model_version", modelVersion);
            inference.put("inference_mode", inferenceMode);
            inference.put("image_path", imagePath); // Store image path for reference

            // Overwriting any existing data at /inference_data/latest_inference.
            inferenceDataRef.child(inferenceId).setValue(inference)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "[FIREBASE] Inference data uploaded and replaced previous: " + category + " (" + (confidence*100) + "%)");

                        lastUploadedImagePath = imagePath;
                        lastUploadedConfidence = confidence;
                        lastUploadTime = System.currentTimeMillis();
                    })
                    .addOnFailureListener(e ->
                            Log.e(TAG, "[FIREBASE] Failed to upload inference data: " + e.getMessage()));

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Error uploading inference data: " + e.getMessage(), e);
        }
    }

    // Original uploadInferenceData method, use to upload every entry without exceptions and replacing previous entries.
    public void __uploadInferenceData(String imagePath, String category, float confidence,
                                    String modelVersion, String inferenceMode) {
        if (shouldUpload(imagePath, confidence)) {
            Log.d(TAG, "[FIREBASE] Skipping upload - duplicate or too soon!");
            return;
        }

        try {
            String inferenceId = inferenceDataRef.push().getKey();
            if (inferenceId == null) {
                Log.e(TAG, "[FIREBASE] Failed to generate inference ID");
                return;
            }

            float weight = 0.0f; // Sent by the HX711 code from the Arduino client.

            // Determine if valid based on confidence threshold
            boolean valid = true; // For now, set every inference data as valid inferencing process.

            Map<String, Object> inference = new HashMap<>();
            inference.put("timestamp", getCurrentTimestamp());
            inference.put("device_id", deviceId);
            inference.put("category", category.toLowerCase());
            inference.put("confidence", Math.round(confidence * 10000) / 10000.0); // 4 decimal places
            inference.put("valid", valid);
            inference.put("weight", Math.round(weight * 100) / 100.0); // 2 decimal places
            inference.put("model_version", modelVersion);
            inference.put("inference_mode", inferenceMode);
            inference.put("image_path", imagePath); // Store image path for reference

            inferenceDataRef.child(inferenceId).setValue(inference)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "[FIREBASE] Inference data uploaded successfully: " + category + " (" + (confidence*100) + "%)");

                        // Update tracking
                        lastUploadedImagePath = imagePath;
                        lastUploadedConfidence = confidence;
                        lastUploadTime = System.currentTimeMillis();
                    })
                    .addOnFailureListener(e ->
                            Log.e(TAG, "[FIREBASE] Failed to upload inference data: " + e.getMessage()));

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Error uploading inference data: " + e.getMessage(), e);
        }
    }

    private boolean shouldUpload(String imagePath, float confidence) {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUploadTime < MIN_UPLOAD_INTERVAL) {
            return true;
        }

        if (imagePath.equals(lastUploadedImagePath)) {
            return true;
        }

        // Optional: Check if confidence is significantly different
        // 1% difference threshold
        return Math.abs(confidence - lastUploadedConfidence) < 0.01f;
    }

    // ==================== UTILITY METHODS ====================
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public void clearInferenceData(ClearDataListener listener) {
        try {
            inferenceDataRef.removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "[FIREBASE] All inference data cleared successfully!");
                        if (listener != null) {
                            listener.onClearSuccess();
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "[FIREBASE] Failed to clear inference data: " + e.getMessage());
                        if (listener != null) {
                            listener.onClearError(e.getMessage());
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Error clearing inference data: " + e.getMessage(), e);
            if (listener != null) {
                listener.onClearError(e.getMessage());
            }
        }
    }

    public interface ClearDataListener {
        void onClearSuccess();
        void onClearError(String error);
    }

    // ==================== LISTENER INTERFACES ====================
    public interface AppStatusListener {
        void onAppStatusChanged(boolean isIdle, boolean isRunning, boolean isPaused, int i);
    }
}