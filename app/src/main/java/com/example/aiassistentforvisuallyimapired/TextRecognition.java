package com.example.aiassistentforvisuallyimapired;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.text.Text;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public class TextRecognition extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int CAMERA_PERMISSION_REQUEST = 100;
     //static final long RIGHT_SWIPE_DELAY = 1000;
     static final float RIGHT_SWIPE_THRESHOLD = 700;
    private static final long DOUBLE_TAP_DELAY = 300;
    private static final long SPEECH_RECOGNITION_DELAY = 500;
    private static final long MICROPHONE_PAUSE_DELAY = 5000;
    private boolean isMicrophoneActive = false;

    private CameraSource mCameraSource;
    private SurfaceView mCameraView;
    private TextView mTextView;
    private Handler textHandler;
    private boolean textStable = false;
    private TextToSpeech textToSpeech;
    private long lastTapTime = 0;
    private static final int SPEECH_REQUEST_CODE = 123;
    private String stringOutput;

    private String detectedText;
    private long lastTextDetectionTime = 0;

    private static final long TEXT_STABLE_DELAY = 2000;
    private static final long BASE_DELAY = 1000;
    private static final long MAX_DELAY = 60000;
    private static final long MIN_DELAY = 1000;
    private static final int SIGNIFICANT_TEXT_DIFF_THRESHOLD = 5;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_recognition);

        // Initialize textToSpeech before using it
        textToSpeech = new TextToSpeech(this, this);

        speakText("Text Recognition opened");

        textHandler = new Handler(Looper.getMainLooper());

        mCameraView = findViewById(R.id.surfaceView);
        mTextView = findViewById(R.id.text_view);

        // Check camera permission and start camera source accordingly
        if (checkCameraPermission()) {
            startCameraSource();
        } else {
            requestCameraPermission();
        }

        mCameraView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    handleSingleTap();
                    break;
                case MotionEvent.ACTION_MOVE:
                    handleRightSwipe(event);
                    break;
            }
            return true;
        });
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraSource();
            } else {
                Toast.makeText(this, "Camera permission is required to run the app", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void handleSingleTap() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastTapTime > DOUBLE_TAP_DELAY) {
            lastTapTime = currentTime;
        } else {
            promptRightSwipeMessage();
        }
    }

    private void promptRightSwipeMessage() {
        speakText("Right swipe to close Text Recognition");
    }

    private void handleRightSwipe(MotionEvent event) {
        float x1 = event.getX();

        if (x1 < RIGHT_SWIPE_THRESHOLD) {
            finish();
        }
    }

    private void startSpeechRecognition() {
        stopTextDetection();

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your Input");

        startActivityForResult(intent, SPEECH_REQUEST_CODE);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

            if (results != null && !results.isEmpty()) {
                String input_text = results.get(0);
                handleKeywordResponses(input_text);
                speakText(stringOutput);
                startTextDetection();
            }
        }
    }

    private void stopTextDetection() {
        mTextView.setText("");
        if (mCameraSource != null) {
            mCameraSource.stop();
        }
    }

    private void startTextDetection() {
        if (mCameraSource != null && checkCameraPermission()) {
            try {
                mCameraSource.start(mCameraView.getHolder());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    private void startCameraSource() {
        TextRecognizer textRecognizer = new TextRecognizer.Builder(getApplicationContext()).build();

        if (!textRecognizer.isOperational()) {
            Log.w("TAG", "Detector dependencies not loaded yet");
        } else {
            mCameraSource = new CameraSource.Builder(getApplicationContext(), textRecognizer)
                    .setFacing(CameraSource.CAMERA_FACING_BACK)
                    .setRequestedPreviewSize(1280, 1024)
                    .setAutoFocusEnabled(true)
                    .setRequestedFps(2.0f)
                    .build();

            mCameraView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    try {
                        if (checkCameraPermission()) {
                            mCameraSource.start(mCameraView.getHolder());
                        } else {
                            requestCameraPermission();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    mCameraSource.stop();
                }
            });

            textRecognizer.setProcessor(new Detector.Processor<TextBlock>() {
                @Override
                public void release() {
                }

                @Override
                public void receiveDetections(Detector.Detections<TextBlock> detections) {
                    SparseArray<TextBlock> items = detections.getDetectedItems();
                    if (items.size() != 0) {
                        StringBuilder sentenceBuilder = new StringBuilder();

                        for (int i = 0; i < items.size(); i++) {
                            TextBlock item = items.valueAt(i);
                            sentenceBuilder.append(extractWords(item)).append("\n");
                        }

                        detectedText = sentenceBuilder.toString().trim();

                        long currentTime = System.currentTimeMillis();

                        if (currentTime - lastTextDetectionTime > TEXT_STABLE_DELAY) {
                            if (textStable && isTextSignificantlyDifferent(detectedText, mTextView.getText().toString())) {
                                textHandler.removeCallbacksAndMessages(null);
                                updateAndSpeakText(detectedText);
                            } else {
                                long delay = calculateDelay(detectedText.length());
                                textHandler.postDelayed(() -> {
                                    textStable = true;
                                    updateAndSpeakText(detectedText);
                                }, delay);
                            }

                            textStable = false;
                            lastTextDetectionTime = currentTime;
                        }
                    }
                }
            });
        }
    }

    private long calculateDelay(int textLength) {
        long delay = BASE_DELAY + textLength * 50;
        return Math.min(MAX_DELAY, Math.max(MIN_DELAY, delay));
    }

    private boolean isTextSignificantlyDifferent(String newText, String oldText) {
        int diff = Math.abs(newText.length() - oldText.length());
        return diff > SIGNIFICANT_TEXT_DIFF_THRESHOLD;
    }

    private String extractWords(TextBlock textBlock) {
        StringBuilder wordsBuilder = new StringBuilder();
        for (Text component : textBlock.getComponents()) {
            wordsBuilder.append(component.getValue()).append(" ");
        }
        return wordsBuilder.toString().trim();
    }

    private void updateAndSpeakText(String text) {
        mTextView.setText(text);
        speakText(text);
    }

    private void speakText(String text) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void handleKeywordResponses(String input) {
        if (input.toLowerCase().contains("text recognition") && input.toLowerCase().contains("close")) {
            startActivity(new Intent(TextRecognition.this, MainActivity.class));
        } else {
            stringOutput = "Text Recognition is open right now, to close it say 'Close Text Recognition'";
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.getDefault());
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TextToSpeech", "Language is not supported");
            }
        } else {
            Log.e("TextToSpeech", "Initialization failed");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}
