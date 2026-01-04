package com.eintswavex.wastesorter;

import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
//import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ModelSummaryActivity extends AppCompatActivity {

    // UI Components from the new layout
    private ImageView imgDisplay;
    private ScrollView scrollTextContainer;
    private TextView txtModelSummary;
    private TextView txtPlaceholder;

    // Buttons
    private Button btnTrainingResult;
    private Button btnConfusionMatrix;
    private Button btnModelSummary;
    private Button btnBack;

    // Data
    private Map<String, ModelInfo> modelInfoMap;
    private String currentModelSize;

    private final int COLOR_TRAINING = 0xFF44DDBB;
    private final int COLOR_CONFUSION = 0xFFBBFF44;
    private final int COLOR_SUMMARY = 0xFFEE66BB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_model_summary);
        setupFullscreen();

        setupViews();
        setupModelInfo();
        loadCurrentModel();
        setupButtonListeners();

        // Default: Show Training Result on startup
        showTrainingResult();
    }

    private void setupFullscreen() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
    }

    private void setupViews() {
        // Find all views from the new layout
        imgDisplay = findViewById(R.id.img_display);
        scrollTextContainer = findViewById(R.id.scroll_text_container);
        txtModelSummary = findViewById(R.id.txt_model_summary);
        txtPlaceholder = findViewById(R.id.txt_placeholder);

        btnTrainingResult = findViewById(R.id.btn_training_result);
        btnConfusionMatrix = findViewById(R.id.btn_confusion_matrix);
        btnModelSummary = findViewById(R.id.btn_model_summary);
        btnBack = findViewById(R.id.btn_back_summary);

        // Set monospace font for summary text
        txtModelSummary.setTypeface(android.graphics.Typeface.MONOSPACE);

        // Set initial button colors (from XML)
        btnTrainingResult.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_TRAINING));
        btnConfusionMatrix.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_CONFUSION));
        btnModelSummary.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_SUMMARY));
        // Red
        int COLOR_BACK = 0xFFF44336;
        btnBack.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_BACK));
    }

    private void setupButtonListeners() {
        btnTrainingResult.setOnClickListener(v -> showTrainingResult());
        btnConfusionMatrix.setOnClickListener(v -> showConfusionMatrix());
        btnModelSummary.setOnClickListener(v -> showModelSummary());
        btnBack.setOnClickListener(v -> finish());
    }

    private void setupModelInfo() {
        modelInfoMap = new HashMap<>();

        String[] modelSizes = {"96", "128", "160", "240"};

        for (String size : modelSizes) {
            modelInfoMap.put(size, new ModelInfo(
                    String.format(Locale.getDefault(), "img/training_result/tr-%s.png", size),
                    String.format(Locale.getDefault(), "img/confusion_matrix/cm-%s.png", size),
                    String.format(Locale.getDefault(), "summary/sequential-%s.txt", size)
            ));
        }
    }

    private void loadCurrentModel() {
        String currentModel = MainActivity.currentModelName;

        if (currentModel == null) {
            txtPlaceholder.setText(getString(R.string.no_model_selected));
            return;
        }

        // Extract model size from filename
        currentModelSize = extractModelSize(currentModel);
    }

    private String extractModelSize(String modelName) {
        String numbers = modelName.replaceAll("[^0-9]", "");

        if (numbers.contains("96")) return "96";
        if (numbers.contains("128")) return "128";
        if (numbers.contains("160")) return "160";
        if (numbers.contains("240")) return "240";

        return numbers.isEmpty() ? "96" : numbers;
    }

    private void showTrainingResult() {
        if (currentModelSize == null) {
            showPlaceholder(getString(R.string.no_model_selected));
            return;
        }

        ModelInfo info = modelInfoMap.get(currentModelSize);
        if (info == null || info.trainingResultPath == null) {
            showPlaceholder(getString(R.string.training_result_not_available));
            return;
        }

        // Show image, hide text and placeholder
        scrollTextContainer.setVisibility(View.GONE);
        imgDisplay.setVisibility(View.VISIBLE);
        txtPlaceholder.setVisibility(View.GONE);

        // Load and display the training result image
        loadImageFromAssets(info.trainingResultPath, imgDisplay);

        // Update button colors
        updateButtonColors("training");
    }

    private void showConfusionMatrix() {
        if (currentModelSize == null) {
            showPlaceholder(getString(R.string.no_model_selected));
            return;
        }

        ModelInfo info = modelInfoMap.get(currentModelSize);
        if (info == null || info.confusionMatrixPath == null) {
            showPlaceholder(getString(R.string.confusion_matrix_not_available));
            return;
        }

        // Show image, hide text and placeholder
        scrollTextContainer.setVisibility(View.GONE);
        imgDisplay.setVisibility(View.VISIBLE);
        txtPlaceholder.setVisibility(View.GONE);

        // Load and display the confusion matrix image
        loadImageFromAssets(info.confusionMatrixPath, imgDisplay);

        // Update button colors
        updateButtonColors("confusion");
    }

    private void showModelSummary() {
        if (currentModelSize == null) {
            showPlaceholder(getString(R.string.no_model_selected));
            return;
        }

        ModelInfo info = modelInfoMap.get(currentModelSize);
        if (info == null || info.summaryPath == null) {
            showPlaceholder(getString(R.string.model_summary_not_available));
            return;
        }

        // Show text, hide image and placeholder
        imgDisplay.setVisibility(View.GONE);
        scrollTextContainer.setVisibility(View.VISIBLE);
        txtPlaceholder.setVisibility(View.GONE);

        // Load and display the model summary text
        String summaryText = loadTextFromAssets(info.summaryPath);
        txtModelSummary.setText(summaryText);

        // Update button colors
        updateButtonColors("summary");
    }

    private void showPlaceholder(String message) {
        imgDisplay.setVisibility(View.GONE);
        scrollTextContainer.setVisibility(View.GONE);
        txtPlaceholder.setVisibility(View.VISIBLE);
        txtPlaceholder.setText(message);

        // Reset content buttons to unselected state
        updateButtonColors("none");
    }

    private void updateButtonColors(String selectedMode) {
        // Highlight selected button with brighter/selected color
        int COLOR_UNSELECTED_BG = 0xFF333333; // Dark gray
        int COLOR_UNSELECTED_TC = 0xFF999999; // Light gray

        // Reset content buttons to their base colors (not selected)
        btnTrainingResult.setBackgroundTintList(ColorStateList.valueOf(COLOR_UNSELECTED_BG));
        btnConfusionMatrix.setBackgroundTintList(ColorStateList.valueOf(COLOR_UNSELECTED_BG));
        btnModelSummary.setBackgroundTintList(ColorStateList.valueOf(COLOR_UNSELECTED_BG));
        btnTrainingResult.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
        btnConfusionMatrix.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
        btnModelSummary.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));

        int COLOR_SELECTED = selectedMode.equals("training") ? COLOR_TRAINING : selectedMode.equals("confusion") ? COLOR_CONFUSION : selectedMode.equals("summary") ? COLOR_SUMMARY : COLOR_UNSELECTED_TC;
        switch (selectedMode) {
            case "training":
                btnTrainingResult.setTextColor(ColorStateList.valueOf(COLOR_SELECTED));
                btnConfusionMatrix.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
                btnModelSummary.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
                break;
            case "confusion":
                btnTrainingResult.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
                btnConfusionMatrix.setTextColor(ColorStateList.valueOf(COLOR_SELECTED));
                btnModelSummary.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
                break;
            case "summary":
                btnTrainingResult.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
                btnConfusionMatrix.setTextColor(ColorStateList.valueOf(COLOR_UNSELECTED_TC));
                btnModelSummary.setTextColor(ColorStateList.valueOf(COLOR_SELECTED));
                break;
            case "none":
                // Do nothing
                break;
        }

        // "training", "confusion", "summary", "none"
    }

    private void loadImageFromAssets(String path, ImageView imageView) {
        try {
            AssetManager assetManager = getAssets();
            InputStream is = assetManager.open(path);
            Bitmap bitmap = BitmapFactory.decodeStream(is);

            // Wait for layout to be measured
            imageView.post(() -> {
                int maxWidth = imageView.getWidth();
                int maxHeight = imageView.getHeight();

                if (maxWidth <= 0 || maxHeight <= 0) {
                    // Use fallback dimensions if view not measured yet
                    maxWidth = getResources().getDisplayMetrics().widthPixels - 48; // Account for padding
                    maxHeight = (int)(getResources().getDisplayMetrics().heightPixels * 0.75) - 48;
                }

                // Scale bitmap to fit while maintaining aspect ratio
                float scale = Math.min(
                        (float) maxWidth / bitmap.getWidth(),
                        (float) maxHeight / bitmap.getHeight()
                );

                if (scale < 1.0f) {
                    int newWidth = (int) (bitmap.getWidth() * scale);
                    int newHeight = (int) (bitmap.getHeight() * scale);
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
                    imageView.setImageBitmap(scaledBitmap);
                    bitmap.recycle();
                } else {
                    imageView.setImageBitmap(bitmap);
                }
            });

            is.close();
        } catch (IOException e) {
            e.printStackTrace();
            // Show error icon
            imageView.setImageResource(android.R.drawable.ic_menu_report_image);
            imageView.setBackgroundColor(0xFF333333);
        }
    }

    private String loadTextFromAssets(String path) {
        StringBuilder content = new StringBuilder();

        try {
            AssetManager assetManager = getAssets();
            InputStream is = assetManager.open(path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }

            reader.close();
            is.close();

        } catch (IOException e) {
            e.printStackTrace();
            content.append(getString(R.string.error_loading_summary_file)).append(": ").append(path).append("\n");
            content.append(e.getMessage());
        }

        return content.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupFullscreen();
        }
    }

    private static class ModelInfo {
        String trainingResultPath;
        String confusionMatrixPath;
        String summaryPath;

        ModelInfo(String trainingResultPath, String confusionMatrixPath, String summaryPath) {
            this.trainingResultPath = trainingResultPath;
            this.confusionMatrixPath = confusionMatrixPath;
            this.summaryPath = summaryPath;
        }
    }
}