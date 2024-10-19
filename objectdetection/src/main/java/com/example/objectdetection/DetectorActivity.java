package com.example.objectdetection;

import static android.content.ContentValues.TAG;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.objectdetection.customview.OverlayView;
import com.example.objectdetection.customview.OverlayView.DrawCallback;
import com.example.objectdetection.env.BorderedText;
import com.example.objectdetection.env.ImageUtils;
import com.example.objectdetection.env.Logger;
import com.example.objectdetection.tflite.Classifier;
import com.example.objectdetection.tflite.TFLiteObjectDetectionAPIModel;
import com.example.objectdetection.tracking.MultiBoxTracker;
import android.view.GestureDetector;
import android.view.MotionEvent;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener, OnInitListener {
    private static final Logger LOGGER = new Logger();

    // Configuration values for the prepackaged SSD model.
    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final boolean TF_OD_API_IS_QUANTIZED = true;
    private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
    private static final String TF_OD_API_LABELS_FILE = "file:////android_asset/labelmap.txt";
    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(1920, 1080);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;
    OverlayView trackingOverlay;

    private Classifier detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;
    private TextToSpeech textToSpeech;

    private GestureDetector gestureDetector;
    private int rightSwipeCount = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize TextToSpeech
        textToSpeech = new TextToSpeech(this, this);

        // Initialize GestureDetector
        gestureDetector = new GestureDetector(this, new MyGestureListener());

    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            // Detect right swipe
            if (e1.getX() - e2.getX() > 100 && Math.abs(velocityX) > 100) {
                rightSwipeCount++;

                // Check if two right swipes have been detected
                if (rightSwipeCount == 2) {
                    // Perform action on two right swipes (e.g., navigate back)
                    navigateBack();
                    rightSwipeCount = 0; // Reset swipe count
                }
            }
            return super.onFling(e1, e2, velocityX, velocityY);
        }
    }

    private void navigateBack() {
        // Perform any required action to navigate back
        finish(); // This will close the current activity and return to the previous one
    }

    // Implement the onInit method to handle initialization completion
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language (if needed)
            int result = textToSpeech.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language is not supported");
            }
        } else {
            Log.e(TAG, "Initialization failed");
        }
    }

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        BorderedText borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector =
                    TFLiteObjectDetectionAPIModel.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_INPUT_SIZE,
                            TF_OD_API_IS_QUANTIZED);
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        int sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        tracker.draw(canvas);
                        if (isDebug()) {
                            tracker.drawDebug(canvas);
                        }
                    }
                });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
    }
    private List<Classifier.Recognition> lastRecognitions = new LinkedList<>();

    private Map<String, Long> lastDetectionTimestamps = new HashMap<>();
    private static final long MINIMUM_SPEAK_DELAY_MS = 300; // Minimum delay between speaking the same object (in milliseconds)

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        LOGGER.i("Running detection on image " + currTimestamp);
                        final long startTime = SystemClock.uptimeMillis();
                        final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        final Canvas canvas = new Canvas(cropCopyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Style.STROKE);
                        paint.setStrokeWidth(2.0f);

                        final List<Classifier.Recognition> mappedRecognitions =
                                new LinkedList<>();

                        StringBuilder objectsDetected = new StringBuilder(); // To store the names of detected objects

                        for (final Classifier.Recognition result : results) {
                            final RectF location = result.getLocation();
                            if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API) {
                                canvas.drawRect(location, paint);

                                cropToFrameTransform.mapRect(location);

                                result.setLocation(location);
                                mappedRecognitions.add(result);

                                // Extracting the name of the detected object
                                String objectName = result.getTitle();

                                // Check if the confidence level is above 55%
                                if (result.getConfidence() > 0.55) {
                                    // Check if similar object has been detected recently
                                    if (isSimilarObjectDetected(objectName, currTimestamp)) {
                                        continue; // Skip speaking this object
                                    }

                                    // Speak the name of the detected object
                                    textToSpeech.speak(objectName, TextToSpeech.QUEUE_ADD, null, null);

                                    // Record the timestamp of this detection
                                    lastDetectionTimestamps.put(objectName, currTimestamp);

                                    // Append the name of the detected object to the string
                                    objectsDetected.append(objectName).append(", ");
                                }
                            }
                        }

                        // Remove the trailing comma and space from the string
                        if (objectsDetected.length() > 2) {
                            objectsDetected.setLength(objectsDetected.length() - 2);
                        }

                        tracker.trackResults(mappedRecognitions, currTimestamp);
                        trackingOverlay.postInvalidate();

                        computingDetection = false;

                        runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        showFrameInfo(previewWidth + "x" + previewHeight);
                                        showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                                        showInference(lastProcessingTimeMs + "ms");
                                    }
                                });
                    }
                });
    }

    private boolean isSimilarObjectDetected(String objectName, long currTimestamp) {
        Long lastTimestamp = lastDetectionTimestamps.get(objectName);
        return lastTimestamp != null && (currTimestamp - lastTimestamp) < MINIMUM_SPEAK_DELAY_MS;
    }


    @Override
    protected int getLayoutId() {
        return R.layout.tfe_od_camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    protected void setUseNNAPI(final boolean isChecked) {
        runInBackground(() -> detector.setUseNNAPI(isChecked));
    }

    @Override
    protected void setNumThreads(final int numThreads) {
        runInBackground(() -> detector.setNumThreads(numThreads));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Release resources used by TextToSpeech
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        // Clean up any other resources or ongoing processes
        // Stop the detector if it's running
        if (detector != null) {
            detector.close();
        }
    }

}