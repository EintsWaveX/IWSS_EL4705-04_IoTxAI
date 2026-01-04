package com.eintswavex.wastesorter;

import static android.os.Build.VERSION.SDK_INT;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.reflect.TypeToken;
import com.google.gson.Gson;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private BottomSheetBehavior<ConstraintLayout> bottomSheetBehavior;
    private FloatingActionButton fabSettings;
    private RelativeLayout bottomBarContainer;
    private EditText esp32Ip;
    private TextView metricsText, resultText, statusText;
    private TextView plasticProgressText, paperProgressText, metalProgressText, debugLog;
    private ProgressBar plasticProgressBar, paperProgressBar, metalProgressBar;
    private ImageView camView, rawView;
    private TextView camViewHint, rawViewHint;
    private Button btnStart, btnPause, btnStop, btnRestart, btnClearLog, btnViewGraph, btnClearFirebase;
    private RadioButton radioSingle, radioTemporal;
    private Spinner modelSpinner, modeSpinner, datasetSpinner;
    private SeekBar confidenceSlider, framesSlider, intervalSlider;
    private TextView confidenceValueText, framesValueText, intervalValueText;
    private CheckBox debugCheckBox;

    private List<String> imagePaths;
    private Interpreter interpreter;
    private int inputWidth, inputHeight;
    public static String currentModelName;
    private String selectedDataset;
    private DataType inputDataType, outputDataType;
    private float outputScale;
    private int outputZeroPoint;
    private ProbabilityHistory probabilityHistory;
    private boolean finishedInference = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ReentrantLock pauseLock = new ReentrantLock();
    private final Condition unpaused = pauseLock.newCondition();
    private volatile boolean isStopped = false;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private int currentImageIndex = 0;
    private int lastRecordedImageIndex = -1;
    private String lastImagePath = "";

    private ESP32CameraHelper esp32CameraHelper;
    private final AtomicBoolean isEsp32Mode = new AtomicBoolean(false);
    private String currentEsp32Ip = "";

    private final String[] wasteTypes = {"METAL", "PAPER", "PLASTIC"};

    private final float[] COLOR_RED = {1.0f, 0.0f, 0.0f, 1.0f};      // Red
    private final float[] COLOR_ORANGE = {1.0f, 0.6f, 0.0f, 1.0f};   // Orange
    private final float[] COLOR_GREEN = {0.0f, 1.0f, 0.0f, 1.0f};    // Green

    private float[] plasticBarColor = COLOR_RED;
    private float[] paperBarColor = COLOR_RED;
    private float[] metalBarColor = COLOR_RED;

    private static final Pattern IP_ADDRESS = Pattern.compile("((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]?)");

    private volatile Bitmap latestBitmap;
    private volatile String latestLabel = null;
    private volatile float latestConfidence = 0.0f;
    private volatile float[] latestProbabilities = null;
    private volatile long latestInferenceTime;
    private volatile float latestFps;
    private FirebaseHelper firebaseHelper;
    private long lastInferenceTime = 0;

    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isRunning || isPaused) return;

            updateUI(
                    latestBitmap,
                    latestLabel,
                    latestConfidence,
                    latestProbabilities,
                    latestInferenceTime,
                    latestFps
            );

            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        setContentView(R.layout.activity_main);
        setupFullscreen();

        setupViews();
        setupBottomSheet();
        setupSpinners();
        setupButtons();
        setupSliders();

        probabilityHistory = ProbabilityHistory.getInstance();

        firebaseHelper = FirebaseHelper.getInstance();
        String deviceId = "android_device_" + Build.MODEL.replace(" ", "_");
        firebaseHelper.setDeviceId(deviceId);

        setupFirebaseListeners();

        esp32CameraHelper = ESP32CameraHelper.getInstance();

        updateButtonStates(State.IDLE);
        updateHints("Simulation"); // Set initial hint state

        Toast.makeText(MainActivity.this, "Welcome to Intelligence Waster Sorter System!", Toast.LENGTH_SHORT).show();
        Toast.makeText(MainActivity.this, "Made by: Immanuel, Naufal, and Tinto.", Toast.LENGTH_SHORT).show();
        updateDebugLog("[INFO] Waste Sorter started!\n");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupFullscreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupFullscreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (firebaseHelper != null) {
            firebaseHelper.updateAppStatus(true, false, false, 1000);
        }
        if (esp32CameraHelper != null) {
            esp32CameraHelper.cleanup();
        }
    }

    private void setupFullscreen() {
        View decorView = getWindow().getDecorView();

        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;

        uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        decorView.setSystemUiVisibility(uiOptions);

        if (SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(params);
        }
    }

    private void setupFirebaseListeners() {
        if (firebaseHelper != null) {
            firebaseHelper.listenForAppStatusChanges((isIdle, isRunning, isPaused, i) -> runOnUiThread(() -> {
                Log.d(TAG, "[FIREBASE] Remote app status changed: idle=" + isIdle + ", running=" + isRunning + ", paused=" + isPaused);

                if (isRunning && !MainActivity.this.isRunning) {
                    Toast.makeText(MainActivity.this, "STARTING the inference process...", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[FIREBASE] Remote device STARTED the inference process...\n");
                } else if (isPaused && !MainActivity.this.isPaused) {
                    Toast.makeText(MainActivity.this, "PAUSING the inference process...", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[FIREBASE] Remote device PAUSED the inference process...\n");
                } else if (!isRunning && MainActivity.this.isRunning) {
                    Toast.makeText(MainActivity.this, "STOPPING the inference process...", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[FIREBASE] Remote device STOPPED the inference process...\n");
                } else if (!isPaused && MainActivity.this.isPaused) {
                    Toast.makeText(MainActivity.this, "RESUMING the inference process...", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[FIREBASE] Remote device RESUMED the inference process...\n");
                }
            }));
        }
    }

    private void setupViews() {
        bottomBarContainer = findViewById(R.id.bottom_bar_container);
        esp32Ip = findViewById(R.id.esp32_ip);
        metricsText = findViewById(R.id.metric_text);
        resultText = findViewById(R.id.result_text);
        statusText = findViewById(R.id.status_text);
        plasticProgressText = findViewById(R.id.plastic_text);
        paperProgressText = findViewById(R.id.paper_text);
        metalProgressText = findViewById(R.id.metal_text);
        plasticProgressBar = findViewById(R.id.bar_plastic);
        paperProgressBar = findViewById(R.id.bar_paper);
        metalProgressBar = findViewById(R.id.bar_metal);
        debugLog = findViewById(R.id.debug_log);
        camView = findViewById(R.id.cam_view);
        rawView = findViewById(R.id.raw_view);
        camViewHint = findViewById(R.id.cam_view_hint);
        rawViewHint = findViewById(R.id.raw_view_hint);
        btnStart = findViewById(R.id.btn_start);
        btnPause = findViewById(R.id.btn_pause);
        btnStop = findViewById(R.id.btn_stop);
        btnRestart = findViewById(R.id.btn_restart);
        btnClearLog = findViewById(R.id.btn_clear_log);
        btnViewGraph = findViewById(R.id.btn_view_graph);
        btnClearFirebase = findViewById(R.id.btn_clear_firebase);
        fabSettings = findViewById(R.id.fab_settings);
        radioSingle = findViewById(R.id.radio_single);
        radioTemporal = findViewById(R.id.radio_temporal);
        modelSpinner = findViewById(R.id.model_spinner);
        modeSpinner = findViewById(R.id.mode_spinner);
        datasetSpinner = findViewById(R.id.dataset_spinner);
        confidenceSlider = findViewById(R.id.confidence_slider);
        framesSlider = findViewById(R.id.frames_slider);
        intervalSlider = findViewById(R.id.interval_slider);
        confidenceValueText = findViewById(R.id.confidence_value_text);
        framesValueText = findViewById(R.id.frames_value_text);
        intervalValueText = findViewById(R.id.interval_value_text);
        debugCheckBox = findViewById(R.id.checkbox_debug);
    }

    private void setupBottomSheet() {
        ConstraintLayout bottomSheetLayout = findViewById(R.id.bottom_sheet_layout);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetLayout);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        ImageButton closeButton = findViewById(R.id.btn_close_sheet);

        fabSettings.setOnClickListener(v -> {
            if (bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            } else {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            }
        });

        closeButton.setOnClickListener(v -> bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN));

        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    fabSettings.setVisibility(View.GONE);
                    bottomBarContainer.setVisibility(View.GONE);
                } else if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    fabSettings.setVisibility(View.VISIBLE);
                    bottomBarContainer.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });
    }

    private void setupSpinners() {
        ArrayAdapter<CharSequence> modeAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_white, getResources().getTextArray(R.array.mode_array));
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);

        ArrayAdapter<CharSequence> datasetAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_white, getResources().getTextArray(R.array.dataset_array));
        datasetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        datasetSpinner.setAdapter(datasetAdapter);

        discoverModels();

        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isRunning) {
                    stopSimulation();
                }
                String selectedMode = parent.getItemAtPosition(position).toString();
                updateHints(selectedMode);

                if (selectedMode.equals("ESP32-CAM")) {
                    esp32Ip.setVisibility(View.VISIBLE);
                    datasetSpinner.setVisibility(View.GONE);
                    isEsp32Mode.set(true);

                    String ip = esp32Ip.getText().toString();
                    if (!ip.isEmpty() && !ip.equals("x.x.x.x")) {
                        currentEsp32Ip = ip;
                        startEsp32CameraStream(ip);
                    }

                    Toast.makeText(MainActivity.this, "Switching to ESP32-CAM mode...", Toast.LENGTH_SHORT).show();
                    updateDebugLog(String.format(Locale.US, "[MODE] ESP32-CAM mode selected. IP: %s\n", ip));
                } else {
                    esp32Ip.setVisibility(View.GONE);
                    datasetSpinner.setVisibility(View.VISIBLE);
                    isEsp32Mode.set(false);

                    stopEsp32CameraStream();

                    Toast.makeText(MainActivity.this, "Switching to Simulation mode...", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[MODE] Simulation mode selected.\n");
                    loadDataset();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isRunning) {
                    stopSimulation();
                }
                currentModelName = parent.getItemAtPosition(position).toString();
                loadModel(currentModelName);

                Toast.makeText(MainActivity.this, "Switching to a different model...", Toast.LENGTH_SHORT).show();
                updateDebugLog(String.format(Locale.US, "[INFO] Model switched to:\n    > %s\n", currentModelName));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        datasetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isRunning) {
                    stopSimulation();
                }
                selectedDataset = parent.getItemAtPosition(position).toString();

                Toast.makeText(MainActivity.this, "Switching to a different dataset...", Toast.LENGTH_SHORT).show();
                updateDebugLog("[INFO] Dataset switched to:\n    > " + selectedDataset + "\n");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> {
            if (modeSpinner.getSelectedItem().toString().equals("ESP32-CAM")) {
                String ip = esp32Ip.getText().toString();
                if (ip.isEmpty() || ip.equals("x.x.x.x")) {
                    Toast.makeText(MainActivity.this, "Please enter a valid ESP32-CAM IP address!", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[ERROR] Please enter a valid ESP32-CAM IP address!\n");
                    return;
                }
                if (!IP_ADDRESS.matcher(ip).matches()) {
                    Toast.makeText(MainActivity.this, "Invalid IP address format!", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[ERROR] Invalid IP address format!\n");
                    return;
                }

                saveIpAddress(ip);

                if (!isRunning) {
                    startEsp32CameraStream(ip);
                    isRunning = true;
                    updateButtonStates(State.RUNNING);
                    statusText.setText(R.string.running);
                    statusText.setTextColor(0xFF44FF44);
                    Toast.makeText(MainActivity.this, "ESP32-CAM streaming is now RUNNING!", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[STREAM] ESP32-CAM streaming is now RUNNING!\n");
                } else if (isPaused) {
                    resumeEsp32Inference();
                    updateButtonStates(State.RUNNING);
                    statusText.setText(R.string.running);
                    statusText.setTextColor(0xFF44FF44);
                    Toast.makeText(MainActivity.this, "ESP32-CAM streaming is now RESUMED!", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[STREAM] ESP32-CAM streaming is now RESUMED!\n");
                } else {
                    Toast.makeText(MainActivity.this, "ESP32-CAM streaming is already RUNNING!", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[STREAM] ESP32-CAM streaming is already RUNNING!\n");
                }
            } else {
                if (btnStart.getText().toString().equalsIgnoreCase(getString(R.string.resume))) {
                    resumeSimulation();
                } else {
                    startSimulation();
                }
            }
        });

        btnPause.setOnClickListener(v -> {
            if (modeSpinner.getSelectedItem().toString().equals("ESP32-CAM")) {
                pauseLock.lock();
                try {
                    isPaused = !isPaused;
                } finally {
                    pauseLock.unlock();
                }

                updateButtonStates(isPaused ? State.PAUSED : State.RUNNING);
                statusText.setText(isPaused ? R.string.paused : R.string.running);
                statusText.setTextColor(isPaused ? 0xFFFF8844 : 0xFF44FF44);
                Toast.makeText(MainActivity.this, "ESP32-CAM streaming is now " + (isPaused ? "PAUSED" : "RUNNING") + "!", Toast.LENGTH_SHORT).show();
                updateDebugLog(isPaused ?
                        "[STREAM] ESP32-CAM streaming is now PAUSED!\n" :
                        "[STREAM] ESP32-CAM streaming is now RESUMED!\n");
            } else {
                pauseSimulation();
            }
        });

        btnStop.setOnClickListener(v -> {
            if (modeSpinner.getSelectedItem().toString().equals("ESP32-CAM")) {
                stopEsp32CameraStream();
                updateButtonStates(State.IDLE);
                statusText.setText(R.string.stopped);
                statusText.setTextColor(0xFFFF4444);

                Toast.makeText(MainActivity.this, "ESP32-CAM streaming is STOPPED!", Toast.LENGTH_SHORT).show();
                updateDebugLog("[STREAM] ESP32-CAM streaming is STOPPED!\n");
            } else {
                stopSimulation();
            }
        });

        btnRestart.setOnClickListener(v -> {
            stopSimulation();
            Toast.makeText(MainActivity.this, "Restarting the inference process...", Toast.LENGTH_SHORT).show();
            updateDebugLog("[STREAM] Restarting the inference process...\n");
            handler.postDelayed(this::startSimulation, 100); // Give time for stop to complete
        });

        btnClearLog.setOnClickListener(v -> {
            String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String logMessage = String.format(Locale.US, "%s - Debug Log cleared...\n", timeStamp);
            debugLog.setText(logMessage);
        });

        btnViewGraph.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, GraphActivity.class);
            startActivity(intent);
        });

        btnClearFirebase.setOnClickListener(v -> showClearFirebaseDialog());
    }

    private void setupSliders() {
        confidenceSlider.setProgress(15); // 45% default
        framesSlider.setProgress(0); // 1 frame default
        intervalSlider.setProgress(9); // 1s default

        confidenceValueText.setText(String.format(Locale.US, "%d%%", confidenceSlider.getProgress() + 30));
        framesValueText.setText(String.valueOf(framesSlider.getProgress() + 1));
        intervalValueText.setText(String.format(Locale.US, "%.1fs", (intervalSlider.getProgress() + 1) / 10.0f));

        confidenceSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                confidenceValueText.setText(String.format(Locale.US, "%d%%", progress + 30));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        framesSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                framesValueText.setText(String.valueOf(progress + 1));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        intervalSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (modeSpinner.getSelectedItem().toString().equals("ESP32-CAM") && progress < 29) {
                    Toast.makeText(MainActivity.this, "It is recommended to use a longer interval (at least 3s) for ESP32-CAM streaming...", Toast.LENGTH_SHORT).show();
                }
                intervalValueText.setText(String.format(Locale.US, "%.1fs", (progress + 1) / 10.0f));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateHints(String mode) {
        if (mode.equals("Simulation")) {
            camViewHint.setText(R.string.raw_image);
            rawViewHint.setText(R.string.raw_image);
        } else { // ESP32-CAM
            camViewHint.setText(R.string.esp32_cam_view);
            rawViewHint.setText(R.string.predicted_raw_image);
        }

        camViewHint.setVisibility(View.VISIBLE);
        rawViewHint.setVisibility(View.VISIBLE);
    }

    private void saveIpAddress(String ipAddress) {
        SharedPreferences prefs = getSharedPreferences("ESP32_IPs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        String ipListJson = prefs.getString("ip_list", "[]");
        Gson gson = new Gson();
        Type type = new TypeToken<List<String>>(){}.getType();
        List<String> ipList = gson.fromJson(ipListJson, type);

        if (ipList == null) {
            ipList = new ArrayList<>();
        }

        if (!ipList.contains(ipAddress)) {
            ipList.add(ipAddress);
            String updatedJson = gson.toJson(ipList);
            editor.putString("ip_list", updatedJson);
            editor.apply();

            Toast.makeText(MainActivity.this, "New ESP32-CAM streaming IP address has been saved!", Toast.LENGTH_SHORT).show();
            updateDebugLog(String.format(Locale.US, "[INFO] IP Address %s saved to preferences\n", ipAddress));
        }
    }

    private void discoverModels() {
        AssetManager assetManager = getAssets();
        String modelPath = "models";
        try {
            String[] modelFiles = assetManager.list(modelPath);
            if (modelFiles != null && modelFiles.length > 0) {
                List<String> tfliteModels = new ArrayList<>();
                for (String file : modelFiles) {
                    if (file.endsWith(".tflite")) {
                        tfliteModels.add(file);
                    }
                }

                if (tfliteModels.isEmpty()) {
                    Toast.makeText(MainActivity.this, "No .tflite files found in assets/models!", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[ERROR] No .tflite files found in assets/models!\n");
                    return;
                }

                ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_white, tfliteModels);
                modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                modelSpinner.setAdapter(modelAdapter);

                Toast.makeText(MainActivity.this, String.format(Locale.US, "Found %d model(s) in assets/%s!", tfliteModels.size(), modelPath), Toast.LENGTH_SHORT).show();
                updateDebugLog(String.format(Locale.US, "[INFO] Found %d model(s) in assets/%s!\n", tfliteModels.size(), modelPath));

                if (!tfliteModels.isEmpty()) {
                    currentModelName = tfliteModels.get(0);
                    loadModel(currentModelName);
                } else {
                    Toast.makeText(MainActivity.this, "No models found in assets/models!", Toast.LENGTH_SHORT).show();
                    updateDebugLog("[ERROR] No models found in assets/models!\n");
                }

            } else {
                Toast.makeText(MainActivity.this, "No models found in assets/models!", Toast.LENGTH_SHORT).show();
                updateDebugLog("[ERROR] No models found in assets/models!\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "[ERROR] Error discovering models from path: assets/models!", e);
            updateDebugLog("[ERROR] Could not discover models: " + e.getMessage() + "\n");
        }
    }

    private void loadModel(String modelName) {
        try {
            String modelPath = "models/" + modelName;
            AssetFileDescriptor fileDescriptor = getAssets().openFd(modelPath);
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            MappedByteBuffer modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

            if (interpreter != null) {
                interpreter.close();
            }
            interpreter = new Interpreter(modelBuffer);
            interpreter.allocateTensors();

            Tensor inputTensor = interpreter.getInputTensor(0);
            inputWidth = inputTensor.shape()[2];
            inputHeight = inputTensor.shape()[1];
            inputDataType = inputTensor.dataType();

            float inputScale;
            int inputZeroPoint;
            if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                Tensor.QuantizationParams inputQuantParams = inputTensor.quantizationParams();
                if (inputQuantParams != null) {
                    inputScale = inputQuantParams.getScale();
                    inputZeroPoint = inputQuantParams.getZeroPoint();
                } else {
                    inputScale = 1.0f;
                    inputZeroPoint = 0;
                    updateDebugLog("[WARNING] No input quantization params for quantized model!\n");
                }
            } else {
                inputScale = 1.0f;
                inputZeroPoint = 0;
            }

            Tensor outputTensor = interpreter.getOutputTensor(0);
            outputDataType = outputTensor.dataType();

            if (outputDataType == DataType.UINT8 || outputDataType == DataType.INT8) {
                Tensor.QuantizationParams outputQuantParams = outputTensor.quantizationParams();
                if (outputQuantParams != null) {
                    outputScale = outputQuantParams.getScale();
                    outputZeroPoint = outputQuantParams.getZeroPoint();
                } else {
                    outputScale = 1.0f;
                    outputZeroPoint = 0;
                    updateDebugLog("[WARNING] No output quantization params for quantized model!\n");
                }
            } else {
                outputScale = 1.0f;
                outputZeroPoint = 0;
            }

            updateDebugLog("[INFO] Model Info:\n");
            updateDebugLog(String.format(Locale.US, "    Name:              %s\n", modelName));
            updateDebugLog(String.format(Locale.US, "    Input Shape:       [%d, %d, %d, %d]\n",
                    inputTensor.shape()[0], inputTensor.shape()[1],
                    inputTensor.shape()[2], inputTensor.shape()[3]));
            updateDebugLog(String.format(Locale.US, "    Input Type:        %s\n", inputDataType));
            updateDebugLog(String.format(Locale.US, "    Input Scale:       %.2f\n", inputScale));
            updateDebugLog(String.format(Locale.US, "    Input Zero Point:  %d\n", inputZeroPoint));
            updateDebugLog(String.format(Locale.US, "    Output Type:       %s\n", outputDataType));
            updateDebugLog(String.format(Locale.US, "    Output Scale:      %.2f\n", outputScale));
            updateDebugLog(String.format(Locale.US, "    Output Zero Point: %d\n", outputZeroPoint));

            Toast.makeText(MainActivity.this, String.format(Locale.US, "Successfully loaded model: %s (%dx%d)!", modelName, inputWidth, inputHeight), Toast.LENGTH_SHORT).show();
            updateDebugLog("[INFO] Model loaded successfully!\n");
            updateDebugLog("[INFO] See more detailed model's parameters at 'View Graph & Model Details >> Model Details >> Model Summary'...\n");

            String metrics = "";
            if (modeSpinner.getSelectedItem().toString().equals("ESP32-CAM")) {
                metrics = String.format(Locale.US, "Model: %s (%dx%d) | Inference: -- ms | IP: %s",
                        modelName, inputWidth, inputHeight, (currentEsp32Ip.isEmpty() ? "x.x.x.x" : currentEsp32Ip));
            } else {
                metrics = String.format(Locale.US, "Model: %s (%dx%d) | Inference: -- ms | FPS: --/--",
                        modelName, inputWidth, inputHeight);
            }

            metricsText.setText(metrics);

        } catch (IOException e) {
            Log.e(TAG, "[ERROR] Error loading model!", e);
            Toast.makeText(MainActivity.this, String.format(Locale.US, "Failed to load model: %s!", modelName), Toast.LENGTH_SHORT).show();
            updateDebugLog(String.format(Locale.US, "[ERROR] Failed to load model: %s\n", modelName));
            updateDebugLog(String.format(Locale.US, "     ...%s.\n", e.getMessage()));
        }
    }

    private void loadDataset() {
        imagePaths = new ArrayList<>();
        AssetManager assetManager = getAssets();
        String datasetPath = "dataset";
        selectedDataset = datasetSpinner.getSelectedItem().toString();

        try {
            List<String> categoriesToLoad = new ArrayList<>();
            if (selectedDataset.equalsIgnoreCase("All Dataset")) {
                Collections.addAll(categoriesToLoad, "plastic", "paper", "metal");
            } else {
                categoriesToLoad.add(selectedDataset.toLowerCase());
            }

            for (String category : categoriesToLoad) {
                String categoryPath = datasetPath + "/" + category;
                String[] images = assetManager.list(categoryPath);
                if (images != null && images.length > 0) {
                    for (String image : images) {
                        imagePaths.add(categoryPath + "/" + image);
                    }
                } else {
                    Toast.makeText(MainActivity.this, String.format(Locale.US, "No images found in assets/%s!", categoryPath), Toast.LENGTH_SHORT).show();
                    updateDebugLog(String.format(Locale.US, "[WARNING] No images found in assets/%s!\n", categoryPath));
                }
            }
            Toast.makeText(MainActivity.this, String.format(Locale.US, "Dataset loaded! Found %d images!", imagePaths.size()), Toast.LENGTH_SHORT).show();
            updateDebugLog(String.format(Locale.US, "[INFO] Dataset loaded! Found %d images!\n", imagePaths.size()));
        } catch (IOException e) {
            Log.e(TAG, "[ERROR] Error loading dataset", e);
            Toast.makeText(MainActivity.this, "Failed to load dataset '" + selectedDataset + "'!", Toast.LENGTH_SHORT).show();
            updateDebugLog("[ERROR] Failed to load dataset '" + selectedDataset + "'!\n");
        }
    }

    private void startSimulation() {
        if (isRunning) return;
        if (interpreter == null) {
            Log.e(TAG, "[ERROR] Interpreter not initialized.");
            Toast.makeText(MainActivity.this, "Interpreter not initialized. Select a model first!", Toast.LENGTH_SHORT).show();
            updateDebugLog("[ERROR] Interpreter not initialized. Select a model first!\n");
            return;
        }
        if (imagePaths == null || imagePaths.isEmpty()) {
            Log.e(TAG, "[ERROR] Dataset not loaded or is empty.");
            Toast.makeText(MainActivity.this, "Dataset not loaded or is empty. Select Simulation mode first!", Toast.LENGTH_SHORT).show();
            updateDebugLog("[ERROR] Dataset not loaded or is empty. Select Simulation mode first!\n");
            return;
        }

        isRunning = true;
        isPaused = false;
        currentImageIndex = 0;
        updateButtonStates(State.RUNNING);
        statusText.setText(R.string.running);
        statusText.setTextColor(0xFF44FF44);
        lastRecordedImageIndex = -1;
        lastImagePath = "";

        Toast.makeText(MainActivity.this, "Starting the inference process in Simulation mode...", Toast.LENGTH_SHORT).show();
        updateDebugLog("[STATUS] SIMULATION STARTED!\n");

        handler.post(uiUpdater);

        executor.execute(() -> {
            long frameCount = 0;
            long startTime = System.currentTimeMillis();

            while (isRunning) {
                long loopStartTime = System.currentTimeMillis();
                pauseLock.lock();
                try {
                    while (isPaused) {
                        unpaused.await();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    pauseLock.unlock();
                }

                if (!isRunning) break;
                if (currentImageIndex >= imagePaths.size()) {
                    handler.post(() -> {
                        Toast.makeText(MainActivity.this, String.format(Locale.US, "Finished processing %s dataset. Waiting for next run...", selectedDataset), Toast.LENGTH_SHORT).show();
                        pauseSimulation();
                        finishedInference = true;
                    });
                    break;
                }

                long inferenceStartTime = System.currentTimeMillis();
                String imagePath = imagePaths.get(currentImageIndex);

                if (imagePath.equals(lastImagePath)) {
                    currentImageIndex++;
                    continue;
                }

                lastImagePath = imagePath;

                Bitmap bitmap = loadBitmapFromAssets(imagePath);
                ByteBuffer inputBuffer = preprocessImage(bitmap);

                if (inputBuffer == null) {
                    Log.e(TAG, "[ERROR] Skipping image, failed to preprocess: " + imagePath);
                    Toast.makeText(MainActivity.this, String.format(Locale.US, "Failed to preprocess image: %s!", imagePath), Toast.LENGTH_SHORT).show();
                    updateDebugLog(String.format(Locale.US, "[ERROR] Failed to preprocess image: %s\n", imagePath));
                    currentImageIndex++;
                    continue;
                }

                Object output = (outputDataType == DataType.FLOAT32)
                        ? new float[1][wasteTypes.length]
                        : new byte[1][wasteTypes.length];

                interpreter.run(inputBuffer, output);

                float[] probabilities = dequantizeAndApplySoftmax(output);

                long inferenceTime = System.currentTimeMillis() - inferenceStartTime;
                frameCount++;
                long elapsedTime = System.currentTimeMillis() - startTime;
                float fps = (elapsedTime > 0) ? (frameCount * 1000.0f) / elapsedTime : 0;

                int maxIndex = getIndexOfLargest(probabilities);
                if (maxIndex == -1) {
                    currentImageIndex++;
                    continue;
                }

                float confidence = probabilities[maxIndex];
                String label = wasteTypes[maxIndex];

                latestBitmap = bitmap;
                latestLabel = label;
                latestConfidence = confidence;
                latestProbabilities = probabilities;
                latestInferenceTime = inferenceTime;
                latestFps = fps;

                if (!debugCheckBox.isChecked()) {
                    updateDebugLog(String.format(Locale.US, "[DEBUG] Image: %s | Label: %s | Confidence: %.2f%%\n", imagePath, label, confidence * 100));
                }

                currentImageIndex++;
                
                long loopTime = System.currentTimeMillis() - loopStartTime;
                long targetInterval = (long)(intervalSlider.getProgress() + 1) * 100;
                long sleepTime = targetInterval - loopTime;
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (isStopped) {
                handler.post(this::clearVisuals);
                isStopped = false;
            }

            lastRecordedImageIndex = -1;
            lastImagePath = "";
        });
    }

    private void pauseSimulation() {
        if (!isRunning || isPaused) return;

        pauseLock.lock();
        try {
            isPaused = true;
        } finally {
            pauseLock.unlock();
        }

        handler.removeCallbacks(uiUpdater);

        updateButtonStates(State.PAUSED);
        statusText.setText(R.string.paused);
        statusText.setTextColor(0xFFFF8844);
        Toast.makeText(MainActivity.this, "Pausing the inference process in Simulation mode...", Toast.LENGTH_SHORT).show();
        updateDebugLog("[STATUS] SIMULATION PAUSED!\n");
    }

    private void resumeSimulation() {
        if (!isRunning || !isPaused) return;
        if (finishedInference) {
            finishedInference = false;
            stopSimulation();
            startSimulation();
            return;
        }

        pauseLock.lock();
        try {
            isPaused = false;
            unpaused.signalAll();
        } finally {
            pauseLock.unlock();
        }

        handler.post(uiUpdater);

        updateButtonStates(State.RUNNING);
        statusText.setText(R.string.running);
        statusText.setTextColor(0xFF44FF44);
        Toast.makeText(MainActivity.this, "Resuming the inference process in Simulation mode...", Toast.LENGTH_SHORT).show();
        updateDebugLog("[STATUS] SIMULATION RESUMED!\n");
    }

    private void stopSimulation() {
        if (!isRunning) return;

        isStopped = true;
        isRunning = false;

        lastRecordedImageIndex = -1;
        lastImagePath = "";

        handler.removeCallbacks(uiUpdater);

        pauseLock.lock();
        try {
            if (isPaused) {
                isPaused = false;
                unpaused.signalAll();
            }
        } finally {
            pauseLock.unlock();
        }

        updateButtonStates(State.IDLE);
        statusText.setText(R.string.stopped);
        statusText.setTextColor(0xFFFF4444);
        Toast.makeText(MainActivity.this, "Stopping the inference process in Simulation mode...", Toast.LENGTH_SHORT).show();
        updateDebugLog("[STATUS] SIMULATION STOPPED!\n");
    }

    private void startEsp32CameraStream(String ipAddress) {
        if (ipAddress.isEmpty() || ipAddress.equals("x.x.x.x")) {
            updateDebugLog("[ERROR] Please enter a valid ESP32-CAM IP address!\n");
            return;
        }

        currentEsp32Ip = ipAddress;
        isEsp32Mode.set(true);

        camView.setImageResource(android.R.color.transparent);
        rawView.setImageResource(android.R.color.transparent);
        camViewHint.setText(R.string.esp32_cam_view);
        rawViewHint.setText(R.string.predicted_raw_image);
        camViewHint.setVisibility(View.VISIBLE);
        rawViewHint.setVisibility(View.VISIBLE);

        updateDebugLog(String.format(Locale.US, "[ESP32] Connecting to camera at %s...\n", ipAddress));

        esp32CameraHelper.startStream(ipAddress, new ESP32CameraHelper.CameraStreamListener() {
            @Override
            public void onFrameReceived(Bitmap frame) {
                runOnUiThread(() -> {
                    if (camView != null && frame != null) {
                        camView.setImageBitmap(frame);
                        camViewHint.setVisibility(View.GONE);

                        if (isRunning && !isPaused && interpreter != null) {
                            processEsp32Frame(frame);
                        }
                    }
                });
            }

            @Override
            public void onStreamError(String error) {
                runOnUiThread(() -> {
                    updateDebugLog(String.format(Locale.US,
                            "[ESP32] Error: %s\n", error));

                    // Check if it's a cleartext error
                    if (error.contains("Cleartext") || error.contains("cleartext")) {
                        camViewHint.setText("[ERROR] HTTP not allowed. Add to AndroidManifest.xml:\nandroid:usesCleartextTraffic=\"true\"");
                    } else if (error.contains("ENETUNREACH") || error.contains("ECONNREFUSED")) {
                        camViewHint.setText(String.format(Locale.US, "[ERROR] Cannot connect to %s:81/stream\nCheck IP and ESP32-CAM is running!", currentEsp32Ip));
                    } else {
                        camViewHint.setText(String.format(Locale.US, "[ERROR] %s", error));
                    }
                    camViewHint.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onStreamStarted() {
                runOnUiThread(() -> {
                    updateDebugLog("[ESP32] Stream started on port 81\n");
                    statusText.setText(R.string.running);
                    statusText.setTextColor(0xFF44FF44);

                    updateButtonStates(State.RUNNING);
                });
            }

            @Override
            public void onStreamStopped() {
                runOnUiThread(() -> {
                    updateButtonStates(State.IDLE);
                    updateDebugLog("[ESP32] Stream stopped\n");

                    if (isEsp32Mode.get()) {
                        statusText.setText(R.string.stopped);
                        statusText.setTextColor(0xFFFF4444);
                    }
                    if (isStopped) {
                        handler.post(this::clearVisuals);
                        isStopped = false;
                    }

                    lastRecordedImageIndex = -1;
                    lastImagePath = "";
                });
            }

            private void clearVisuals() {
                resultText.setText(R.string.result);
                metricsText.setText(String.format(Locale.US,
                        "Model: %s (%dx%d) | Inference: -- ms | IP: %s",
                        currentModelName, inputWidth, inputHeight, currentEsp32Ip));
                plasticProgressText.setText(R.string.plastic_progress);
                paperProgressText.setText(R.string.paper_progress);
                metalProgressText.setText(R.string.metal_progress);
                plasticProgressBar.setProgress(0);
                paperProgressBar.setProgress(0);
                metalProgressBar.setProgress(0);
                camView.setImageResource(android.R.color.transparent);
                rawView.setImageResource(android.R.color.transparent);
                updateHints(modeSpinner.getSelectedItem().toString());
                statusText.setText(R.string.stopped);
                updateButtonStates(State.IDLE);
                updateDebugLog("[STATUS] STOPPED!\n");
            }
        });
    }

    private void processEsp32Frame(Bitmap frame) {
        pauseLock.lock();
        try {
            if (isPaused) {
                return;
            }
        } finally {
            pauseLock.unlock();
        }

        long currentTime = System.currentTimeMillis();
        long targetInterval = (long)(intervalSlider.getProgress() + 1) * 100;

        if (shouldProcessFrame(currentTime, targetInterval)) {
            if (interpreter != null && isRunning) {
                Bitmap preparedFrame = esp32CameraHelper.prepareFrameForInference(frame, inputWidth, inputHeight);

                if (preparedFrame != null) {
                    executor.execute(() -> runInferenceOnFrame(preparedFrame));
                }
            }
        }
    }

    private boolean shouldProcessFrame(long currentTime, long targetInterval) {
        if (lastInferenceTime == 0) {
            lastInferenceTime = currentTime;
            return true;
        }

        long timeSinceLastInference = currentTime - lastInferenceTime;
        if (timeSinceLastInference >= targetInterval) {
            lastInferenceTime = currentTime;
            return true;
        }

        return false;
    }

    private void runInferenceOnFrame(Bitmap preparedFrame) {
        try {
            long inferenceStartTime = System.currentTimeMillis();

            ByteBuffer inputBuffer = preprocessImage(preparedFrame);
            if (inputBuffer == null) return;

            Object output = (outputDataType == DataType.FLOAT32)
                    ? new float[1][wasteTypes.length]
                    : new byte[1][wasteTypes.length];

            interpreter.run(inputBuffer, output);

            long inferenceTime = System.currentTimeMillis() - inferenceStartTime;

            float[] probabilities = dequantizeAndApplySoftmax(output);
            int maxIndex = getIndexOfLargest(probabilities);
            if (maxIndex == -1) return;

            float confidence = probabilities[maxIndex];
            String label = wasteTypes[maxIndex];

            runOnUiThread(() -> {
                resultText.setText(String.format(Locale.US, "Label: %s (%.2f%%)", label.toUpperCase(), confidence * 100));

                rawView.setImageBitmap(preparedFrame);
                rawViewHint.setVisibility(View.GONE);

                if (probabilities.length >= 3) {
                    float plastic = probabilities[2] * 100;
                    float paper = probabilities[1] * 100;
                    float metal = probabilities[0] * 100;

                    probabilityHistory.addData(plastic, paper, metal);

                    plasticProgressText.setText(String.format(Locale.US, "Plastic %.2f%%", plastic));
                    paperProgressText.setText(String.format(Locale.US, "Paper %.2f%%", paper));
                    metalProgressText.setText(String.format(Locale.US, "Metal %.2f%%", metal));

                    plasticProgressBar.setProgress((int)plastic);
                    paperProgressBar.setProgress((int)paper);
                    metalProgressBar.setProgress((int)metal);

                    updateBarColors(probabilities[0], probabilities[1], probabilities[2]);
                }

                metricsText.setText(String.format(Locale.US,
                        "Model: %s (%dx%d) | Inference: %d ms | IP: %s",
                        currentModelName, inputWidth, inputHeight, inferenceTime, currentEsp32Ip));

                if (firebaseHelper != null && confidence >= 0.5f) {
                    String modelVersion = currentModelName != null ? currentModelName : "unknown";
                    String inferenceMode = getInferenceMode();

                    firebaseHelper.uploadInferenceData(
                            "esp32_frame_" + System.currentTimeMillis(),
                            label.toLowerCase(),
                            confidence,
                            modelVersion,
                            inferenceMode
                    );
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] ESP32 inference error: " + e.getMessage(), e);
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Oops, something went wrong with ESP32-CAM inference: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                updateDebugLog(String.format(Locale.US, "[ERROR] ESP32 inference error: %s\n", e.getMessage()));
            });
        }
    }

    private void resumeEsp32Inference() {
        if (!isRunning || !isPaused) return;

        pauseLock.lock();
        try {
            isPaused = false;
            unpaused.signalAll();
        } finally {
            pauseLock.unlock();
        }

        updateDebugLog("[STATUS] ESP32-CAM inference resumed!\n");
    }

    private void stopEsp32CameraStream() {
        if (esp32CameraHelper != null) {
            esp32CameraHelper.stopStream();

            if (isRunning) {
                isRunning = false;
                isPaused = false;
            }

            handler.post(this::clearVisuals);

            runOnUiThread(() -> {
                camView.setImageResource(android.R.color.transparent);
                rawView.setImageResource(android.R.color.transparent);
                camViewHint.setVisibility(View.VISIBLE);
                rawViewHint.setVisibility(View.VISIBLE);

                updateButtonStates(State.IDLE);
                statusText.setText(R.string.stopped);
                statusText.setTextColor(0xFFFF4444);
            });
        }
    }

    private void updateUI(Bitmap bitmap, String label, float confidence, float[] probabilities, long inferenceTime, float fps) {
        if (label == null || probabilities == null) {
            resultText.setText(R.string.result);
            return;
        }

        if (bitmap != null) {
            camView.setImageBitmap(bitmap);
            rawView.setImageBitmap(bitmap);
            camViewHint.setVisibility(View.GONE);
            rawViewHint.setVisibility(View.GONE);
        }

        resultText.setText(String.format(Locale.US, "Label: %s (%.2f%%)", label.toUpperCase(), confidence * 100));

        float targetInterval = (intervalSlider.getProgress() + 1) / 10.0f;
        float targetFps = 1.0f / targetInterval;

        metricsText.setText(String.format(Locale.US,
                "Model: %s (%dx%d) | Inference: %d ms | FPS: %.0f/%.0f",
                currentModelName, inputWidth, inputHeight, inferenceTime, fps, targetFps));

        if (currentImageIndex != lastRecordedImageIndex &&probabilities.length >= 3) {
            float metal = probabilities[0] * 100;
            float paper = probabilities[1] * 100;
            float plastic = probabilities[2] * 100;

            probabilityHistory.addData(plastic, paper, metal);

            if (currentImageIndex != lastRecordedImageIndex && imagePaths != null && currentImageIndex < imagePaths.size()) {
                String imagePath = imagePaths.get(currentImageIndex);
                String modelVersion = currentModelName != null ? currentModelName : "unknown";
                String inferenceMode = getInferenceMode();
                String category = label.toLowerCase();

                if (firebaseHelper != null) {
                    firebaseHelper.uploadInferenceData(imagePath, category, confidence,
                            modelVersion, inferenceMode);
                }
            }

            lastRecordedImageIndex = currentImageIndex;

            plasticProgressBar.setVisibility(View.VISIBLE);
            paperProgressBar.setVisibility(View.VISIBLE);
            metalProgressBar.setVisibility(View.VISIBLE);

            plasticProgressText.setText(String.format(Locale.US, "Plastic %.2f%%", plastic));
            paperProgressText.setText(String.format(Locale.US, "Paper %.2f%%", paper));
            metalProgressText.setText(String.format(Locale.US, "Metal %.2f%%", metal));

            plasticProgressBar.setMax(100);
            paperProgressBar.setMax(100);
            metalProgressBar.setMax(100);

            plasticProgressBar.setProgress((int)plastic);
            paperProgressBar.setProgress((int)paper);
            metalProgressBar.setProgress((int)metal);

            updateBarColors(probabilities[0], probabilities[1], probabilities[2]);
        }
    }

    private void updateBarColors(float metalProb, float paperProb, float plasticProb) {
        if (isPaused) return;

        Map<String, Float> probMap = new HashMap<>();
        probMap.put("METAL", metalProb);
        probMap.put("PAPER", paperProb);
        probMap.put("PLASTIC", plasticProb);

        List<Map.Entry<String, Float>> sortedEntries = new ArrayList<>(probMap.entrySet());
        sortedEntries.sort((a, b) -> Float.compare(a.getValue(), b.getValue()));

        Map<String, float[]> colorMap = new HashMap<>();
        colorMap.put(sortedEntries.get(0).getKey(), COLOR_RED);    // Lowest = Red
        colorMap.put(sortedEntries.get(1).getKey(), COLOR_ORANGE); // Middle = Orange
        colorMap.put(sortedEntries.get(2).getKey(), COLOR_GREEN);  // Highest = Green

        plasticBarColor = colorMap.get("PLASTIC");
        paperBarColor = colorMap.get("PAPER");
        metalBarColor = colorMap.get("METAL");

        updateProgressBarColors();
    }

    private void updateProgressBarColors() {
        if (SDK_INT >= Build.VERSION_CODES.O) {
            handler.post(() -> {
                plasticProgressBar.setProgressTintList(ColorStateList.valueOf(
                        Color.argb(plasticBarColor[3], plasticBarColor[0], plasticBarColor[1], plasticBarColor[2])));
                paperProgressBar.setProgressTintList(ColorStateList.valueOf(
                        Color.argb(paperBarColor[3], paperBarColor[0], paperBarColor[1], paperBarColor[2])));
                metalProgressBar.setProgressTintList(ColorStateList.valueOf(
                        Color.argb(metalBarColor[3], metalBarColor[0], metalBarColor[1], metalBarColor[2])));
            });
        } else {
            updateProgressBarColorsLegacy();
        }
    }

    private void updateProgressBarColorsLegacy() {
        GradientDrawable plasticDrawable = new GradientDrawable();
        plasticDrawable.setColor(Color.argb(
                (int)(plasticBarColor[3] * 255),
                (int)(plasticBarColor[0] * 255),
                (int)(plasticBarColor[1] * 255),
                (int)(plasticBarColor[2] * 255)
        ));
        plasticDrawable.setCornerRadius(10);

        GradientDrawable paperDrawable = new GradientDrawable();
        paperDrawable.setColor(Color.argb(
                (int)(paperBarColor[3] * 255),
                (int)(paperBarColor[0] * 255),
                (int)(paperBarColor[1] * 255),
                (int)(paperBarColor[2] * 255)
        ));
        paperDrawable.setCornerRadius(10);

        GradientDrawable metalDrawable = new GradientDrawable();
        metalDrawable.setColor(Color.argb(
                (int)(metalBarColor[3] * 255),
                (int)(metalBarColor[0] * 255),
                (int)(metalBarColor[1] * 255),
                (int)(metalBarColor[2] * 255)
        ));
        metalDrawable.setCornerRadius(10);

        plasticProgressBar.setProgressDrawable(plasticDrawable);
        paperProgressBar.setProgressDrawable(paperDrawable);
        metalProgressBar.setProgressDrawable(metalDrawable);
    }

    private void clearVisuals() {
        resultText.setText(R.string.result);
        metricsText.setText(String.format(Locale.US,
                "Model: %s (%dx%d) | Inference: -- ms | FPS: --/--",
                currentModelName, inputWidth, inputHeight));
        plasticProgressText.setText(R.string.plastic_progress);
        paperProgressText.setText(R.string.paper_progress);
        metalProgressText.setText(R.string.metal_progress);
        plasticProgressBar.setProgress(0);
        paperProgressBar.setProgress(0);
        metalProgressBar.setProgress(0);
        camView.setImageResource(android.R.color.transparent);
        rawView.setImageResource(android.R.color.transparent);
        updateHints(modeSpinner.getSelectedItem().toString());
        statusText.setText(R.string.stopped);
        updateButtonStates(State.IDLE);
        updateDebugLog("[STATUS] STOPPED!\n");
    }

    private void updateDebugLog(String message) {
        handler.post(() -> {
            debugLog.append(message);
            final NestedScrollView scrollView = findViewById(R.id.debug_log_scroll_y);
            if (scrollView != null) {
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private Bitmap loadBitmapFromAssets(String path) {
        try (InputStream inputStream = getAssets().open(path)) {
            return BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            Log.e(TAG, "Error loading bitmap from assets", e);
            return null;
        }
    }

    private ByteBuffer preprocessImage(Bitmap bitmap) {
        if (bitmap == null) return null;

        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        int[] intValues = new int[inputWidth * inputHeight];
        resizedBitmap.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        ByteBuffer byteBuffer;
        int pixel = 0;

        switch (inputDataType) {
            case FLOAT32:
                byteBuffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3);
                byteBuffer.order(ByteOrder.nativeOrder());
                for (int i = 0; i < inputHeight; ++i) {
                    for (int j = 0; j < inputWidth; ++j) {
                        final int val = intValues[pixel++];
                        byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);  // R
                        byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);   // G
                        byteBuffer.putFloat((val & 0xFF) / 255.0f);          // B
                    }
                }
                Log.d(TAG, "FLOAT32: Normalized to 0.0-1.0");
                break;

            case INT8:
                byteBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3);
                byteBuffer.order(ByteOrder.nativeOrder());
                for (int i = 0; i < inputHeight; ++i) {
                    for (int j = 0; j < inputWidth; ++j) {
                        final int val = intValues[pixel++];
                        int r = ((val >> 16) & 0xFF) - 128;
                        int g = ((val >> 8) & 0xFF) - 128;
                        int b = (val & 0xFF) - 128;

                        byteBuffer.put((byte) r);
                        byteBuffer.put((byte) g);
                        byteBuffer.put((byte) b);
                    }
                }
                Log.d(TAG, "INT8: Centered around 0 with clipping");
                break;

            case UINT8:
                byteBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3);
                byteBuffer.order(ByteOrder.nativeOrder());
                for (int i = 0; i < inputHeight; ++i) {
                    for (int j = 0; j < inputWidth; ++j) {
                        final int val = intValues[pixel++];
                        byteBuffer.put((byte) ((val >> 16) & 0xFF));  // R
                        byteBuffer.put((byte) ((val >> 8) & 0xFF));   // G
                        byteBuffer.put((byte) (val & 0xFF));          // B
                    }
                }
                Log.d(TAG, "UINT8: Direct 0-255 values");
                break;

            default:
                Log.e(TAG, "Unsupported dtype: " + inputDataType);
                return null;
        }

        byteBuffer.rewind();
        return byteBuffer;
    }

    private float[] dequantizeAndApplySoftmax(Object output) {
        float[] logits;

        if (outputDataType == DataType.INT8) {
            byte[] rawOutput = ((byte[][]) output)[0];
            logits = new float[rawOutput.length];

            for (int i = 0; i < rawOutput.length; i++) {
                logits[i] = (rawOutput[i] - outputZeroPoint) * outputScale;
            }
        } else if (outputDataType == DataType.UINT8) {
            byte[] rawOutput = ((byte[][]) output)[0];
            logits = new float[rawOutput.length];

            for (int i = 0; i < rawOutput.length; i++) {
                int unsigned = rawOutput[i] & 0xFF;
                logits[i] = (unsigned - outputZeroPoint) * outputScale;
            }
        } else { // FLOAT32
            logits = ((float[][]) output)[0];
            Log.d(TAG, "FLOAT32 raw: " + logits[0] + ", " + logits[1] + ", " + logits[2]);
        }

        float maxLogit = -Float.MAX_VALUE;
        float minLogit = Float.MAX_VALUE;
        for (float logit : logits) {
            if (logit > maxLogit) maxLogit = logit;
            if (logit < minLogit) minLogit = logit;
        }

        if (maxLogit > 1.0f || minLogit < 0.0f) {
            Log.d(TAG, "Applying softmax (outside 0-1 range)");

            float[] expLogits = new float[logits.length];
            float sumExp = 0.0f;

            for (int i = 0; i < logits.length; i++) {
                expLogits[i] = (float) Math.exp(logits[i] - maxLogit);
                sumExp += expLogits[i];
            }
            for (int i = 0; i < logits.length; i++) {
                logits[i] = expLogits[i] / sumExp;
            }
        }

        return logits;
    }

    private int getIndexOfLargest(float[] array) {
        if (array == null || array.length == 0) return -1;
        int largest = 0;
        for (int i = 1; i < array.length; i++) {
            if (array[i] > array[largest]) largest = i;
        }
        return largest;
    }

    private enum State { IDLE, RUNNING, PAUSED }

    private void updateButtonStates(State state) {
        switch (state) {
            case IDLE:
                btnStart.setText(R.string.start);
                btnStart.setTextColor(0xFF44FF44);
                btnPause.setTextColor(0xFF888888);
                btnStop.setTextColor(0xFF888888);
                btnRestart.setTextColor(0xFF888888);

                btnStart.setEnabled(true);
                btnPause.setEnabled(false);
                btnStop.setEnabled(false);
                btnRestart.setEnabled(false);
                modeSpinner.setEnabled(true);

                updateFirebaseAppStatus(true, false, false, intervalSlider.getProgress() * 100);
                break;
            case RUNNING:
                btnStart.setTextColor(0xFF888888);
                btnPause.setTextColor(0xFFFF8844);
                btnStop.setTextColor(0xFFFF4444);
                btnRestart.setTextColor(0xFF2196F3);

                btnStart.setEnabled(false);
                btnPause.setEnabled(true);
                btnStop.setEnabled(true);
                btnRestart.setEnabled(true);
                modeSpinner.setEnabled(false);

                updateFirebaseAppStatus(false, true, false, intervalSlider.getProgress() * 100);
                break;
            case PAUSED:
                btnStart.setText(R.string.resume);
                btnStart.setTextColor(0xFF44FF44);
                btnPause.setTextColor(0xFF888888);
                btnStop.setTextColor(0xFFFF4444);
                btnRestart.setTextColor(0xFF2196F3);

                btnStart.setEnabled(true);
                btnPause.setEnabled(false);
                btnStop.setEnabled(true);
                btnRestart.setEnabled(true);
                modeSpinner.setEnabled(false);

                updateFirebaseAppStatus(false, false, true, intervalSlider.getProgress() * 100);
                break;
        }
    }

    private void updateFirebaseAppStatus(boolean idle, boolean running, boolean paused, int interval) {
        if (firebaseHelper != null) {
            firebaseHelper.updateAppStatus(idle, running, paused, interval);
        }
    }

    private void showClearFirebaseDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Clear Firebase Data")
                .setMessage("This will delete ALL inference data from Firebase, but the app status will be kept. This action cannot be undone!")
                .setPositiveButton("[DELETE ALL DATA]", (dialog, which) -> clearFirebaseData())
                .setNegativeButton("[CANCEL]", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void clearFirebaseData() {
        if (firebaseHelper != null) {
            firebaseHelper.clearInferenceData(new FirebaseHelper.ClearDataListener() {
                @Override
                public void onClearSuccess() {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "Firebase inference data cleared successfully!",
                                Toast.LENGTH_LONG).show();
                        updateDebugLog("[FIREBASE] All inference data cleared!\n");
                    });
                }

                @Override
                public void onClearError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                String.format("Failed to clear Firebase data: %s", error),
                                Toast.LENGTH_LONG).show();
                        updateDebugLog(String.format("[FIREBASE] Error clearing data: %s!\n", error));
                    });
                }
            });
        } else {
            Toast.makeText(this, "Firebase not initialized", Toast.LENGTH_SHORT).show();
            updateDebugLog("[FIREBASE] Firebase helper not initialized!\n");
        }
    }

    private String getInferenceMode() {
        String inferenceMode = "";

        if (radioSingle.isChecked()) inferenceMode = "single_frame";
        else if (radioTemporal.isChecked()) inferenceMode = "temporal_vote";

        return inferenceMode;
    }
}
