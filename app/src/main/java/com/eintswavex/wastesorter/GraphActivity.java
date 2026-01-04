package com.eintswavex.wastesorter;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
//import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Environment;
//import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
//import android.widget.LinearLayout;
//import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GraphActivity extends AppCompatActivity implements ProbabilityHistory.HistoryUpdateListener {

    private GraphView graphView;
    private Button btnSaveGraph, btnClearGraph, btnModelSummary, btnBack;
    private ProbabilityHistory history;
    private int graphId = 1;
    private static final String TAG = "GraphActivity";
    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_graph);
        setupFullscreen();

        history = ProbabilityHistory.getInstance();
        history.addListener(this);

        setupViews();
        setupButtons();
//        setupAutoRefresh();
    }

    @Override
    public void onHistoryUpdated() {
        Log.d(TAG, "onHistoryUpdated() - Graph should update");
        runOnUiThread(() -> {
            if (graphView != null) {
                graphView.updateGraph();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (history != null && isListening) {
            history.removeListener(this);
            isListening = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (history != null && !isListening) {
            history.addListener(this);
            isListening = true;
        }
        if (graphView != null) {
            graphView.invalidate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (history != null) {
            history.removeListener(this);
        }
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
        graphView = findViewById(R.id.graph_view);
        btnSaveGraph = findViewById(R.id.btn_save_graph);
        btnClearGraph = findViewById(R.id.btn_clear_graph);
        btnModelSummary = findViewById(R.id.btn_model_summary);
        btnBack = findViewById(R.id.btn_back);

        // Force initial draw
        if (graphView != null) {
            graphView.post(() -> {
                graphView.invalidate();
                graphView.requestLayout();
            });
        }
    }

    private void setupButtons() {
        btnSaveGraph.setOnClickListener(v -> saveGraph());
        btnClearGraph.setOnClickListener(v -> clearGraph());
        btnModelSummary.setOnClickListener(v -> {
            Intent intent = new Intent(GraphActivity.this, ModelSummaryActivity.class);
            startActivity(intent);
        });
        btnBack.setOnClickListener(v -> finish());
    }

    private void saveGraph() {
        if (graphView == null) return;

        // Force a draw before capturing
        graphView.setDrawingCacheEnabled(true);
        graphView.buildDrawingCache();
        Bitmap bitmap = Bitmap.createBitmap(graphView.getDrawingCache());
        graphView.setDrawingCacheEnabled(false);

        try {
            // Create directory if it doesn't exist
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "WasteSorter");
            if (!directory.exists()) {
                directory.mkdirs();
            }

            // Create file with timestamp
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "graph_" + graphId++ + "_" + timeStamp + ".png";
            File file = new File(directory, fileName);

            // Save bitmap
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            String message = "Saved " + fileName + " at " + directory.getAbsolutePath();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to save graph: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void clearGraph() {
        if (history != null) {
            history.clear();
        }
        if (graphView != null) {
            graphView.invalidate();
        }
        Toast.makeText(this, "Graph cleared", Toast.LENGTH_SHORT).show();
    }
}

// Custom View for drawing the graph
class GraphView extends View {
    private Paint paint;
    private Paint gridPaint;
    private Paint textPaint;
    private Path plasticPath, paperPath, metalPath;
    private ProbabilityHistory history;
    private boolean isInitialized = false;

    public GraphView(Context context) {
        super(context);
        init();
    }

    public GraphView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        history = ProbabilityHistory.getInstance();

        // Setup paints
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);

        gridPaint = new Paint();
        gridPaint.setColor(Color.DKGRAY);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setAlpha(100);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);  // Add this for smoother text

        // Setup paths for each waste type
        plasticPath = new Path();
        paperPath = new Path();
        metalPath = new Path();

        // Set background color
        setBackgroundColor(Color.parseColor("#1a1a1a"));

        isInitialized = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int old_w, int old_h) {
        super.onSizeChanged(w, h, old_w, old_h);
        // Redraw when size changes
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (!isInitialized) return;

        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) return;  // Skip if view not measured yet

        int padding = 50;
        int graphWidth = width - (2 * padding);
        int graphHeight = height - (2 * padding);

        // Draw grid
        drawGrid(canvas, width, height, padding);

        // Draw axis labels
        drawAxisLabels(canvas, width, height, padding);

        // Draw graph lines
        drawGraphLines(canvas, graphWidth, graphHeight, padding);

        // Draw legend
        drawLegend(canvas, width, height);
    }

    private void drawGrid(Canvas canvas, int width, int height, int padding) {
        int graphWidth = width - (2 * padding);
        int graphHeight = height - (2 * padding);

        // Vertical grid lines
        for (int i = 0; i <= 10; i++) {
            float x = padding + ((float) (graphWidth * i) / 10);
            canvas.drawLine(x, padding, x, height - padding, gridPaint);
        }

        // Horizontal grid lines
        for (int i = 0; i <= 10; i++) {
            float y = padding + ((float) (graphHeight * i) / 10);
            canvas.drawLine(padding, y, width - padding, y, gridPaint);
        }
    }

    private void drawAxisLabels(Canvas canvas, int width, int height, int padding) {
        int graphHeight = height - (2 * padding);

        // Y-axis labels (0-100%)
        for (int i = 0; i <= 10; i++) {
            float y = padding + ((float) (graphHeight * i) / 10);
            String label = String.format(Locale.getDefault(), "%.1f", (1 - (i / 10.0)));
            // Adjust text position for better alignment
            canvas.drawText(label, padding - 45, y + 8, textPaint);
        }

        // X-axis label
        float xLabelY = height - padding + 35;
        canvas.drawText("Inference Count", (float) width / 2 - 60, xLabelY, textPaint);

        // X-axis scale markers (optional)
        for (int i = 0; i <= 5; i++) {
            float x = padding + ((float) (width - 2 * padding) * i / 5);
            String marker = String.valueOf(i * 20);
            canvas.drawText(marker, x - 10, height - padding + 25, textPaint);
        }
    }

    private void drawGraphLines(Canvas canvas, int graphWidth, int graphHeight, int padding) {
        List<Float> plasticHistory = history.getPlasticHistory();
        List<Float> paperHistory = history.getPaperHistory();
        List<Float> metalHistory = history.getMetalHistory();

        if (plasticHistory.isEmpty()) {
            // Draw "No data yet" message
            textPaint.setTextSize(32f);
            textPaint.setColor(Color.LTGRAY);
            canvas.drawText("Nothing to show here yet, so please start the Inference process first.",
                    (float) getWidth() / 2 - 200, (float) getHeight() / 2, textPaint);
            textPaint.setTextSize(24f);
            textPaint.setColor(Color.WHITE);
            return;
        }

        int maxPoints = Math.min(plasticHistory.size(), 100);
        float xStep = (float) graphWidth / Math.max(maxPoints - 1, 1);

        // Draw plastic line (#00ff00)
        plasticPath.reset();
        paint.setColor(Color.parseColor("#00ff00"));
        paint.setStrokeWidth(3f);
        for (int i = 0; i < maxPoints; i++) {
            float x = padding + (i * xStep);
            float y = padding + graphHeight - (plasticHistory.get(i) * graphHeight / 100);

            if (i == 0) {
                plasticPath.moveTo(x, y);
            } else {
                plasticPath.lineTo(x, y);
            }
        }
        canvas.drawPath(plasticPath, paint);

        // Draw paper line (#ff6900)
        paperPath.reset();
        paint.setColor(Color.parseColor("#ff6900"));
        paint.setStrokeWidth(3f);
        for (int i = 0; i < maxPoints; i++) {
            float x = padding + (i * xStep);
            float y = padding + graphHeight - (paperHistory.get(i) * graphHeight / 100);

            if (i == 0) {
                paperPath.moveTo(x, y);
            } else {
                paperPath.lineTo(x, y);
            }
        }
        canvas.drawPath(paperPath, paint);

        // Draw metal line (#6600ff)
        metalPath.reset();
        paint.setColor(Color.parseColor("#6600ff"));
        paint.setStrokeWidth(3f);
        for (int i = 0; i < maxPoints; i++) {
            float x = padding + (i * xStep);
            float y = padding + graphHeight - (metalHistory.get(i) * graphHeight / 100);

            if (i == 0) {
                metalPath.moveTo(x, y);
            } else {
                metalPath.lineTo(x, y);
            }
        }
        canvas.drawPath(metalPath, paint);

        // Draw data points (optional circles)
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < maxPoints; i += 10) {  // Draw every 10th point
            float x = padding + (i * xStep);

            // Plastic point
            paint.setColor(Color.parseColor("#00ff00"));
            float plasticY = padding + graphHeight - (plasticHistory.get(i) * graphHeight / 100);
            canvas.drawCircle(x, plasticY, 4f, paint);

            // Paper point
            paint.setColor(Color.parseColor("#ff6900"));
            float paperY = padding + graphHeight - (paperHistory.get(i) * graphHeight / 100);
            canvas.drawCircle(x, paperY, 4f, paint);

            // Metal point
            paint.setColor(Color.parseColor("#6600ff"));
            float metalY = padding + graphHeight - (metalHistory.get(i) * graphHeight / 100);
            canvas.drawCircle(x, metalY, 4f, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawLegend(Canvas canvas, int width, int height) {
        int legendX = width - 200;
        int legendY = 50;
        int lineHeight = 40;

        Paint legendPaint = new Paint();
        legendPaint.setColor(Color.WHITE);
        legendPaint.setTextSize(20f);
        legendPaint.setAntiAlias(true);

        paint.setColor(Color.parseColor("#00ff00"));
        paint.setStrokeWidth(3f);
        canvas.drawText("Plastic", legendX, legendY, legendPaint);
        canvas.drawLine(legendX - 80, legendY, legendX - 30, legendY, paint);

        paint.setColor(Color.parseColor("#ff6900"));
        canvas.drawText("Paper", legendX, legendY + lineHeight, legendPaint);
        canvas.drawLine(legendX - 80, legendY + lineHeight, legendX - 30, legendY + lineHeight, paint);

        paint.setColor(Color.parseColor("#6600ff"));
        canvas.drawText("Metal", legendX, legendY + 2 * lineHeight, legendPaint);
        canvas.drawLine(legendX - 80, legendY + 2 * lineHeight, legendX - 30, legendY + 2 * lineHeight, paint);
    }

    // Public method to force redraw
    public void updateGraph() {
        invalidate();
    }
}