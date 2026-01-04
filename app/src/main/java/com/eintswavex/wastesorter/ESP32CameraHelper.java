package com.eintswavex.wastesorter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ESP32CameraHelper {
    private static final String TAG = "ESP32CameraHelper";
    private static ESP32CameraHelper instance;

    private final ExecutorService executor;
    private final Handler mainHandler;
    private boolean isStreaming = false;
    private String currentIpAddress = "";
    private CameraStreamListener streamListener;
    private final OkHttpClient okHttpClient;
    private Thread streamThread;

    private static final int STATE_BOUNDARY = 0;
    private static final int STATE_HEADERS = 1;
    private static final int STATE_JPEG_DATA = 2;

    public interface CameraStreamListener {
        void onFrameReceived(Bitmap frame);
        void onStreamError(String error);
        void onStreamStarted();
        void onStreamStopped();
    }

    private ESP32CameraHelper() {
        executor = Executors.newFixedThreadPool(2);
        mainHandler = new Handler(Looper.getMainLooper());

        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // No timeout for streaming
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized ESP32CameraHelper getInstance() {
        if (instance == null) {
            instance = new ESP32CameraHelper();
        }
        return instance;
    }

    public void startStream(String ipAddress, CameraStreamListener listener) {
        if (isStreaming && ipAddress.equals(currentIpAddress)) {
            Log.d(TAG, "[STREAM] Already streaming from " + ipAddress);
            return;
        }

        stopStream();

        this.currentIpAddress = ipAddress;
        this.streamListener = listener;

        String streamUrl = String.format(Locale.US, "http://%s:81/stream", ipAddress);
        Log.d(TAG, "[STREAM] Starting stream: " + streamUrl);

        streamThread = new Thread(new StreamRunnable(streamUrl));
        streamThread.setName("ESP32-Stream-Thread");
        streamThread.start();
    }

    private class StreamRunnable implements Runnable {
        private final String streamUrl;

        public StreamRunnable(String streamUrl) {
            this.streamUrl = streamUrl;
        }

        @Override
        public void run() {
            InputStream inputStream = null;
            Response response = null;

            try {
                Log.d(TAG, "[STREAM] Connecting to stream...");

                Request request = new Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "MJPEG-Client")
                        .header("Accept", "*/*")
                        .header("Connection", "keep-alive")
                        .build();

                response = okHttpClient.newCall(request).execute();

                if (!response.isSuccessful()) {
                    throw new Exception("[ERROR] HTTP error: " + response.code());
                }

                Log.d(TAG, "[STREAM] Stream connected! Headers: " + response.headers());

                String contentType = response.header("Content-Type", "");
                Log.d(TAG, "[STREAM] Content-Type: " + contentType);

                assert contentType != null;
                if (!contentType.contains("multipart/x-mixed-replace") &&
                        !contentType.contains("image/jpeg")) {
                    Log.w(TAG, "[STREAM] Unexpected Content-Type: " + contentType);
                }

                isStreaming = true;
                mainHandler.post(() -> {
                    if (streamListener != null) {
                        streamListener.onStreamStarted();
                    }
                });

                inputStream = response.body().byteStream();

                parseMJPEGStream(inputStream);

            } catch (Exception e) {
                Log.e(TAG, "[ERROR] Stream error: " + e.getMessage(), e);
                if (isStreaming) {
                    mainHandler.post(() -> {
                        if (streamListener != null) {
                            streamListener.onStreamError(e.getMessage());
                        }
                    });
                }
            } finally {
                try {
                    if (inputStream != null) inputStream.close();
                    if (response != null) response.close();
                } catch (Exception e) {
                    Log.e(TAG, "[ERROR] Error closing resources...", e);
                }

                isStreaming = false;
                mainHandler.post(() -> {
                    if (streamListener != null) {
                        streamListener.onStreamStopped();
                    }
                });
            }
        }

        private void parseMJPEGStream(InputStream inputStream) throws Exception {
            ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(65536); // 64KB initial
            byte[] readBuffer = new byte[8192];
            int bytesRead;
            int frameCount = 0;

            // Boundary info from Content-Type header
            String boundary = "--123456789000000000000987654321";
            byte[] boundaryBytes = boundary.getBytes();
            byte[] boundaryEndBytes = (boundary + "--").getBytes();

            Log.d(TAG, "[MJPEG] Starting MJPEG parser with boundary: " + boundary);

            int state = STATE_BOUNDARY;
            int matchPos = 0;

            while (isStreaming && (bytesRead = inputStream.read(readBuffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte currentByte = readBuffer[i];

                    switch (state) {
                        case STATE_BOUNDARY:
                            if (currentByte == boundaryBytes[matchPos]) {
                                matchPos++;

                                if (matchPos == boundaryBytes.length) {
                                    matchPos = 0;
                                    state = STATE_HEADERS;
                                    frameBuffer.reset();
                                    Log.v(TAG, "[MJPEG] Found boundary, moving to header parsing...");
                                }
                            } else {
                                matchPos = 0; // Reset on mismatch
                            }
                            break;

                        case STATE_HEADERS:
                            frameBuffer.write(currentByte);
                            byte[] headerData = frameBuffer.toByteArray();
                            int dataLen = headerData.length;

                            if (dataLen >= 4) {
                                if (headerData[dataLen-4] == '\r' &&
                                        headerData[dataLen-3] == '\n' &&
                                        headerData[dataLen-2] == '\r' &&
                                        headerData[dataLen-1] == '\n') {

                                    String headers = new String(headerData, 0, dataLen);
                                    Log.v(TAG, "[MJPEG] Frame headers:\n" + headers);

                                    frameBuffer.reset();
                                    state = STATE_JPEG_DATA;
                                    Log.v(TAG, "[MJPEG] Headers complete, starting JPEG data...");
                                }
                            }
                            break;

                        case STATE_JPEG_DATA:
                            frameBuffer.write(currentByte);

                            byte[] jpegData = frameBuffer.toByteArray();
                            int jpegLen = jpegData.length;

                            if (jpegLen >= boundaryBytes.length + 4) {
                                boolean foundNextBoundary = false;

                                for (int jpegEnd = Math.max(0, jpegLen - boundaryBytes.length - 10); jpegEnd < jpegLen; jpegEnd++) {
                                    boolean match = true;
                                    for (int k = 0; k < boundaryBytes.length; k++) {
                                        if (jpegEnd + k >= jpegLen || jpegData[jpegEnd + k] != boundaryBytes[k]) {
                                            match = false;
                                            break;
                                        }
                                    }

                                    if (match) {
                                        boolean hasValidEnd = false;
                                        for (int m = Math.max(0, jpegEnd - 10); m < jpegEnd; m++) {
                                            if (m + 1 < jpegEnd &&
                                                    jpegData[m] == (byte)0xFF &&
                                                    jpegData[m + 1] == (byte)0xD9) {
                                                hasValidEnd = true;
                                                break;
                                            }
                                        }

                                        if (hasValidEnd) {
                                            processJPEGFrame(jpegData, jpegEnd, frameCount++);

                                            frameBuffer.reset();
                                            state = STATE_BOUNDARY;
                                            matchPos = 0;
                                            foundNextBoundary = true;
                                            Log.v(TAG, "[MJPEG] Found next boundary, frame completed!");

                                            int skipBytes = boundaryBytes.length - (jpegLen - jpegEnd);
                                            if (skipBytes > 0 && i + skipBytes < bytesRead) {
                                                i += skipBytes - 1; // -1 because loop will increment
                                            }
                                        }
                                        break;
                                    }
                                }

                                if (foundNextBoundary) {
                                    break; // Skip rest of processing for this byte
                                }
                            }

                            // Check for final boundary
                            if (jpegLen >= boundaryEndBytes.length) {
                                boolean isFinal = true;
                                for (int j = 0; j < boundaryEndBytes.length; j++) {
                                    if (jpegLen - boundaryEndBytes.length + j < 0 ||
                                            jpegData[jpegLen - boundaryEndBytes.length + j] != boundaryEndBytes[j]) {
                                        isFinal = false;
                                        break;
                                    }
                                }
                                if (isFinal) {
                                    Log.d(TAG, "[MJPEG] Found final boundary, stream ending...");
                                    return;
                                }
                            }
                            break;
                    }
                }
            }

            Log.d(TAG, "[MJPEG] Stream parser ended...");
        }

        private void processJPEGFrame(byte[] frameData, int length, int frameNumber) {
            if (length <= 100) {
                Log.w(TAG, "[JPEG] Frame too small: " + length + " bytes.");
                return;
            }

            if (frameData[0] != (byte)0xFF || frameData[1] != (byte)0xD8) {
                Log.w(TAG, "[JPEG] Invalid JPEG start marker in frame " + frameNumber);
                return;
            }

            boolean hasEndMarker = false;
            for (int i = length - 2; i >= Math.max(0, length - 100); i--) {
                if (i + 1 < length && frameData[i] == (byte)0xFF && frameData[i + 1] == (byte)0xD9) {
                    hasEndMarker = true;
                    break;
                }
            }

            if (!hasEndMarker) {
                Log.w(TAG, "[JPEG] No JPEG end marker in frame " + frameNumber);
                return;
            }

            executor.execute(() -> {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Try ARGB instead of RGB_565
                    options.inMutable = true;

                    Bitmap frame = BitmapFactory.decodeByteArray(frameData, 0, length, options);

                    if (frame == null) {
                        options.inSampleSize = 2;
                        frame = BitmapFactory.decodeByteArray(frameData, 0, length, options);
                    }

                    if (frame != null) {
                        if (frameNumber < 10) {
                            Log.d(TAG, String.format("[JPEG] Frame %d decoded: %dx%d, %d bytes.",
                                    frameNumber, frame.getWidth(), frame.getHeight(), length));
                        }

                        final Bitmap finalFrame = frame;
                        mainHandler.post(() -> {
                            if (streamListener != null && isStreaming) {
                                streamListener.onFrameReceived(finalFrame);
                            }
                        });
                    } else {
                        if (frameNumber < 5) {
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < 20; i++) {
                                hex.append(String.format("%02X ", frameData[i]));
                            }
                            Log.w(TAG, "[JPEG] Decode failed. First 20 bytes: " + hex);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[ERROR} Error processing frame " + frameNumber + ": " + e.getMessage());
                }
            });
        }
    }

    public void stopStream() {
        Log.d(TAG, "[STREAM] Stopping stream...");
        isStreaming = false;

        if (streamThread != null) {
            streamThread.interrupt();
            try {
                streamThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            streamThread = null;
        }
    }

    public Bitmap prepareFrameForInference(Bitmap originalFrame, int targetWidth, int targetHeight) {
        if (originalFrame == null) return null;
        return Bitmap.createScaledBitmap(originalFrame, targetWidth, targetHeight, true);
    }

    public void cleanup() {
        stopStream();
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}