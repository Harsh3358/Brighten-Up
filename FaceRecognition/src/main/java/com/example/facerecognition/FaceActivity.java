package com.example.facerecognition;

import android.Manifest;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.Locale;
import android.os.Handler;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;


import androidx.appcompat.app.AlertDialog;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.Pair;
import android.util.Size;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.util.List;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class FaceActivity extends AppCompatActivity {
    FaceDetector detector;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    PreviewView previewView;
    ImageView face_preview;
    Interpreter tfLite;
    TextView reco_name,preview_info,textAbove_preview;
    Button recognize,camera_switch, actions;
    ImageButton add_face;
    CameraSelector cameraSelector;
    boolean developerMode=false;
    float distance= 1.0f;
    boolean start=true,flipX=false;
    Context context=FaceActivity.this;
    int cam_face=CameraSelector.LENS_FACING_BACK; //Default Back Camera
    private SpeechRecognizer speechRecognizer;
    private final int SPEECH_REQUEST_CODE =  100;
    private Map<String, Integer> spokenNamesCount = new HashMap<>();


    int[] intValues;
    int inputSize=112;  //Input size for model
    boolean isModelQuantized=false;
    float[][] embeedings;
    float IMAGE_MEAN = 128.0f;
    float IMAGE_STD = 128.0f;
    int OUTPUT_SIZE=192; //Output size of model
    private static int SELECT_PICTURE = 1;
    ProcessCameraProvider cameraProvider;
    private static final int MY_CAMERA_REQUEST_CODE = 100;
    private TextToSpeech textToSpeech;
    private Handler handler = new Handler();
    View rootView;
    private long lastSpeakTime = 0;
    private static final long SPEAK_DELAY = 3000; // Delay in milliseconds before speaking again
    private static final long SPEAK_DELAY1 = 5000; // Delay in milliseconds before speaking again

    String modelFile="mobile_face_net.tflite"; //model name

    private GestureDetector gestureDetector;
    private int rightSwipeCount = 0;

    private HashMap<String, SimilarityClassifier.Recognition> registered = new HashMap<>(); //saved Faces
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registered = readFromSP(); //Load saved faces from memory when app starts0
        setContentView(R.layout.activity_main_fr);
        rootView = findViewById(android.R.id.content).getRootView();
        face_preview = findViewById(R.id.imageView);
        reco_name = findViewById(R.id.textView);
        preview_info = findViewById(R.id.textView2);
        textAbove_preview = findViewById(R.id.textAbovePreview);
        face_preview.setVisibility(View.INVISIBLE);
        recognize = findViewById(R.id.button3);
        camera_switch = findViewById(R.id.button5);
        actions = findViewById(R.id.button2);
        // textAbove_preview.setText("Recognized Face:");
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        // Initialize GestureDetector
        gestureDetector = new GestureDetector(this, new MyGestureListener());

        add_face = findViewById(R.id.imageButton);
        if (add_face !=null) {
            add_face.setVisibility(View.INVISIBLE);
        } else {
            Log.e("FaceActivity", "ImageButton not found!");
        }

        SharedPreferences sharedPref = getSharedPreferences("Distance", Context.MODE_PRIVATE);
        distance = sharedPref.getFloat("distance", 1.00f);


        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                //speakInstructions();
                // Set language for Text-to-Speech
                int result = textToSpeech.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Language not supported");
                } else {
                    // Speak the instructions once TTS is initialized
                    speakInstructions(); // Call to speak the instructions
                }
            } else {
                Log.e("TTS", "Initialization failed");
            }
        });

        //Camera Permission
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, MY_CAMERA_REQUEST_CODE);
        }

        //On-screen Action Button
        actions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Select Action:");

                // add a checkbox list
                String[] names = {"View Recognition List"};//, "Update Recognition List", "Save Recognitions", "Load Recognitions", "Clear All Recognitions", "Import Photo (Beta)", "Hyperparameters", "Developer Mode"};

                builder.setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        switch (which) {
                            case 0:
                                displaynameListview();
                                break;
                            case 1:
                                updatenameListview();
                                break;
                            case 2:
                                insertToSP(registered, 0); //mode: 0:save all, 1:clear all, 2:update all
                                break;
                            case 3:
                                registered.putAll(readFromSP());
                                break;
                            case 4:
                                clearnameList();
                                break;
                            case 5:
                                loadphoto();
                                break;
                              /*  case 6:
                                    testHyperparameter();
                                    break;
                                case 7:
                                    developerMode();
                                    break;*/
                        }

                    }
                });


                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                });
                builder.setNegativeButton("Cancel", null);

                // create and show the alert dialog
                AlertDialog dialog = builder.create();
                dialog.show();
            }
        });


        add_face.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addFace();
            }
        });


        recognize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (recognize.getText().toString().equals("Recognize")) {
                    start = true;
                    textAbove_preview.setText("Recognized Face:");
                    recognize.setText("Add Face");
                    add_face.setVisibility(View.INVISIBLE);
                    reco_name.setVisibility(View.VISIBLE);
                    face_preview.setVisibility(View.INVISIBLE);
                    preview_info.setText("");
                } else {
                    start = false;
                    textAbove_preview.setText("Face Preview: ");
                    recognize.setText("Recognize");
                    add_face.setVisibility(View.VISIBLE);
                    reco_name.setVisibility(View.INVISIBLE);
                    face_preview.setVisibility(View.VISIBLE);
                }

            }
        });


        //Load model
        try {
            tfLite = new Interpreter(loadModelFile(FaceActivity.this, modelFile));
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Initialize Face Detector
        FaceDetectorOptions highAccuracyOpts = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build();
        detector = FaceDetection.getClient(highAccuracyOpts);
        //speakInstructions();
        cameraBind();

    }
    private void speakInstructions() {
        String instructions = "To add a new face, " +
                "tap on the leftmost side of the screen, " +
                "then tap the rightmost side. " +
                "After the microphone sound, " +
                "speak the name of the person and wait for the message" +
                " 'Successfully saved'.";

        if (textToSpeech != null) {
            textToSpeech.speak(instructions, TextToSpeech.QUEUE_FLUSH, null, null);

        }
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

    private void switchCamera() {
        if (cam_face == CameraSelector.LENS_FACING_BACK) {
            cam_face = CameraSelector.LENS_FACING_FRONT;
            flipX = true;
        } else {
            cam_face = CameraSelector.LENS_FACING_BACK;
            flipX = false;
        }
        cameraProvider.unbindAll();
        cameraBind();
    }
    private void speakRecognizedNameIfNotSpoken(String name) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpeakTime > SPEAK_DELAY) {
            String textToSpeak = " It seems like it is "+ name;
            lastSpeakTime = currentTime;
            speakRecognizedNameWithDelay(textToSpeak, SPEAK_DELAY);
        }
    }
    private void speakRecognizedNameIfNotSpoken2(String name) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpeakTime > SPEAK_DELAY) {
            String textToSpeak = name;
            lastSpeakTime = currentTime;
            speakRecognizedNameWithDelay(textToSpeak, SPEAK_DELAY);
        }
    }
    private void speakRecognizedNameIfNotSpoken3(String name) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpeakTime > SPEAK_DELAY1) {
            String textToSpeak = name;
            lastSpeakTime = currentTime;
            speakRecognizedNameWithDelay(textToSpeak, SPEAK_DELAY1);
        }
    }

    private void speakRecognizedNameWithDelay(String name, long delayMillis) {
        handler.postDelayed(() -> speakRecognizedName(name), delayMillis);
    }
    private void speakRecognizedName(String name) {
        if (textToSpeech != null && !textToSpeech.isSpeaking()) {
            textToSpeech.speak(name, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }
    @Override
    protected void onPause() {
        super.onPause();
        insertToSP(registered, 0); // Save all faces
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        insertToSP(registered, 0); // Save all faces
        // Remove any pending delayed messages from the handler
        handler.removeCallbacksAndMessages(null);
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
  /*  private void testHyperparameter()
    {

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Select Hyperparameter:");

        // add a checkbox list
        String[] names= {"Maximum Nearest Neighbour Distance"};

        builder.setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                switch (which)
                {
                    case 0:
//                        Toast.makeText(context, "Clicked", Toast.LENGTH_SHORT).show();
                        hyperparameters();
                        break;

                }

            }

            });
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        builder.setNegativeButton("Cancel", null);

        // create and show the alert dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }*/
  /*  private void developerMode()
    {
        if (developerMode) {
            developerMode = false;
            Toast.makeText(context, "Developer Mode OFF", Toast.LENGTH_SHORT).show();
        }
        else {
            developerMode = true;
            Toast.makeText(context, "Developer Mode ON", Toast.LENGTH_SHORT).show();
        }
    }*/
        private void startSpeechRecognition() {
        // Create an intent to recognize speech
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your Input");

        // Start the speech recognition activity
        startActivityForResult(intent, SPEECH_REQUEST_CODE);
    }
private void addFace() {
    start = false; // Pause the face recognition process
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle("Enter Name");

    // Start speech recognition
    startSpeechRecognition();

    // Set up the negative button (Cancel) of the dialog
    builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            start = true; // Resume the face recognition process
            dialog.cancel(); // Dismiss the dialog
        }
    });

    // Show the AlertDialog
    AlertDialog dialog = builder.create();
    dialog.show();
}

    private void clearnameList() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Do you want to delete all Recognitions?");
        builder.setPositiveButton("Delete All", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                registered.clear();
                insertToSP(registered, 1); // Move the call inside the onClick method
                Toast.makeText(context, "Recognitions Cleared", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void updatenameListview()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        if(registered.isEmpty()) {
            builder.setTitle("No Faces Added!!");
            builder.setPositiveButton("OK",null);
        }
        else{
            builder.setTitle("Select Recognition to delete:");

            // add a checkbox list
            String[] names= new String[registered.size()];
            boolean[] checkedItems = new boolean[registered.size()];
            int i=0;
            for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet())
            {
                //System.out.println("NAME"+entry.getKey());
                names[i]=entry.getKey();
                checkedItems[i]=false;
                i=i+1;

            }

            builder.setMultiChoiceItems(names, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                    // user checked or unchecked a box
                    //Toast.makeText(MainActivity.this, names[which], Toast.LENGTH_SHORT).show();
                    checkedItems[which]=isChecked;

                }
            });


            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                    // System.out.println("status:"+ Arrays.toString(checkedItems));
                    for(int i=0;i<checkedItems.length;i++)
                    {
                        //System.out.println("status:"+checkedItems[i]);
                        if(checkedItems[i])
                        {
//                                Toast.makeText(MainActivity.this, names[i], Toast.LENGTH_SHORT).show();
                            registered.remove(names[i]);
                        }

                    }
                    insertToSP(registered,2); //mode: 0:save all, 1:clear all, 2:update all
                    Toast.makeText(context, "Recognitions Updated", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("Cancel", null);

            // create and show the alert dialog
            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }
    private void hyperparameters()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Euclidean Distance");
        builder.setMessage("0.00 -> Perfect Match\n1.00 -> Default\nTurn On Developer Mode to find optimum value\n\nCurrent Value:");
        // Set up the input
        final EditText input = new EditText(context);

        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        builder.setView(input);
        SharedPreferences sharedPref = getSharedPreferences("Distance",Context.MODE_PRIVATE);
        distance = sharedPref.getFloat("distance",1.00f);
        input.setText(String.valueOf(distance));
        // Set up the buttons
        builder.setPositiveButton("Update", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                //Toast.makeText(context, input.getText().toString(), Toast.LENGTH_SHORT).show();

                distance= Float.parseFloat(input.getText().toString());


                SharedPreferences sharedPref = getSharedPreferences("Distance",Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putFloat("distance", distance);
                editor.apply();

            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                dialog.cancel();
            }
        });

        builder.show();
    }


    private void displaynameListview()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        // System.out.println("Registered"+registered);
        if(registered.isEmpty())
            builder.setTitle("No Faces Added!!");
        else
            builder.setTitle("Recognitions:");

        // add a checkbox list
        String[] names= new String[registered.size()];
        boolean[] checkedItems = new boolean[registered.size()];
        int i=0;
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet())
        {
            //System.out.println("NAME"+entry.getKey());
            names[i]=entry.getKey();
            checkedItems[i]=false;
            i=i+1;

        }
        builder.setItems(names,null);



        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });

        // create and show the alert dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_CAMERA_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "camera permission granted", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "camera permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }

    private MappedByteBuffer loadModelFile(Activity activity, String MODEL_FILE) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    //Bind camera and preview view
    private void cameraBind()
    {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        previewView=findViewById(R.id.previewView);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this in Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));
    }
    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder()
                .build();

        cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(cam_face)
                .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) //Latest frame is shown
                        .build();

        Executor executor = Executors.newSingleThreadExecutor();
        imageAnalysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                try {
                    Thread.sleep(0);  //Camera preview refreshed every 10 millisec(adjust as required)
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                InputImage image = null;


                @SuppressLint({"UnsafeExperimentalUsageError", "UnsafeOptInUsageError"})
                // Camera Feed-->Analyzer-->ImageProxy-->mediaImage-->InputImage(needed for ML kit face detection)

                Image mediaImage = imageProxy.getImage();

                if (mediaImage != null) {
                    image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
//                    System.out.println("Rotation "+   imageProxy.getImageInfo().getRotationDegrees());
                }

//                System.out.println("ANALYSIS");

                //Process acquired image to detect faces
                Task<List<Face>> result =
                        detector.process(image)
                                .addOnSuccessListener(
                                        new OnSuccessListener<List<Face>>() {
                                            @Override
                                            public void onSuccess(List<Face> faces) {

                                                if(faces.size()!=0) {

                                                    Face face = faces.get(0); //Get first face from detected faces
//                                                    System.out.println(face);

                                                    //mediaImage to Bitmap
                                                    Bitmap frame_bmp = toBitmap(mediaImage);

                                                    int rot = imageProxy.getImageInfo().getRotationDegrees();

                                                    //Adjust orientation of Face
                                                    Bitmap frame_bmp1 = rotateBitmap(frame_bmp, rot, false, false);



                                                    //Get bounding box of face
                                                    RectF boundingBox = new RectF(face.getBoundingBox());

                                                    //Crop out bounding box from whole Bitmap(image)
                                                    Bitmap cropped_face = getCropBitmapByCPU(frame_bmp1, boundingBox);

                                                    if(flipX)
                                                        cropped_face = rotateBitmap(cropped_face, 0, flipX, false);
                                                    //Scale the acquired Face to 112*112 which is required input for model
                                                    Bitmap scaled = getResizedBitmap(cropped_face, 112, 112);

                                                    if(start)
                                                        recognizeImage(scaled); //Send scaled bitmap to create face embeddings.
//                                                    System.out.println(boundingBox);

                                                }
                                                else
                                                {
                                                    if(registered.isEmpty())
                                                        reco_name.setText("Add Face");
                                                    else{
                                                        reco_name.setText("No one is present");
                                                        speakRecognizedNameIfNotSpoken3("No one is present");
                                                    }
                                                }

                                            }
                                        })
                                .addOnFailureListener(
                                        new OnFailureListener() {
                                            @Override
                                            public void onFailure(@NonNull Exception e) {
                                                // Task failed with an exception
                                                // ...
                                            }
                                        })
                                .addOnCompleteListener(new OnCompleteListener<List<Face>>() {
                                    @Override
                                    public void onComplete(@NonNull Task<List<Face>> task) {

                                        imageProxy.close(); //v.important to acquire next frame for analysis
                                    }
                                });


            }
        });


        cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, imageAnalysis, preview);


    }


    // Declare a variable to track consecutive unknown detections
    private int unknownCount = 0;
    private static final int UNKNOWN_THRESHOLD = 3;

    public void recognizeImage(final Bitmap bitmap) {
        // set Face to Preview
        face_preview.setImageBitmap(bitmap);

        // Create ByteBuffer to store normalized image
        ByteBuffer imgData = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);
        imgData.order(ByteOrder.nativeOrder());
        intValues = new int[inputSize * inputSize];

        // Get pixel values from Bitmap to normalize
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        imgData.rewind();

        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                int pixelValue = intValues[i * inputSize + j];
                if (isModelQuantized) {
                    // Quantized model
                    imgData.put((byte) ((pixelValue >> 16) & 0xFF));
                    imgData.put((byte) ((pixelValue >> 8) & 0xFF));
                    imgData.put((byte) (pixelValue & 0xFF));
                } else { // Float model
                    imgData.putFloat((((pixelValue >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat((((pixelValue >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat(((pixelValue & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                }
            }
        }

        // imgData is input to our model
        Object[] inputArray = {imgData};

        Map<Integer, Object> outputMap = new HashMap<>();
        embeedings = new float[1][OUTPUT_SIZE]; // Output of model will be stored in this variable
        outputMap.put(0, embeedings);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap); // Run model

        float distance_local = Float.MAX_VALUE;
        String id = "0";
        String label = "?";

        // Compare new face with saved Faces.
        if (registered.size() > 0) {
            final List<Pair<String, Float>> nearest = findNearest(embeedings[0]); // Find 2 closest matching faces
            if (nearest.get(0) != null) {
                final String name = nearest.get(0).first; // Get name and distance of closest matching face
                distance_local = nearest.get(0).second;
                if (developerMode) {
                    if (distance_local < distance) // If distance between closest found face is more than 1.000, then output UNKNOWN face.
                        reco_name.setText("Nearest: " + name + "\nDist: " + String.format("%.3f", distance_local) + "\n2nd Nearest: " + nearest.get(1).first + "\nDist: " + String.format("%.3f", nearest.get(1).second));
                    else
                        reco_name.setText("Unknown " + "\nDist: " + String.format("%.3f", distance_local) + "\nNearest: " + name + "\nDist: " + String.format("%.3f", distance_local) + "\n2nd Nearest: " + nearest.get(1).first + "\nDist: " + String.format("%.3f", nearest.get(1).second));
                } else {
                    if (distance_local < distance) {
                        unknownCount = 0; // Reset unknown count on known face detection
                        reco_name.setText(name);
                        String textToSpeak = reco_name.getText().toString();
                        speakRecognizedNameIfNotSpoken(name);
                    } else {
                        unknownCount++; // Increment unknown count
                        if (unknownCount >= UNKNOWN_THRESHOLD) {
                            reco_name.setText("UNKNOWN");
                            String textToSpeak = reco_name.getText().toString();
                            speakRecognizedNameIfNotSpoken2("I am seeing this person for the first time");
                            unknownCount = 0; // Reset after reaching the threshold
                        }
                    }
                }
            }
        }
    }

    // Compare Faces by distance between face embeddings
    private List<Pair<String, Float>> findNearest(float[] emb) {
        List<Pair<String, Float>> neighbour_list = new ArrayList<>();
        Pair<String, Float> ret = null; // to get closest match
        Pair<String, Float> prev_ret = null; // to get second closest match
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet()) {
            final String name = entry.getKey();
            final float[][] extra = (float[][]) entry.getValue().getExtra();
            float[] knownEmb = extra[0]; // Assuming the first element contains the embeddings

            float distance = 0;
            for (int i = 0; i < emb.length; i++) {
                float diff = emb[i] - knownEmb[i];
                distance += diff * diff;
            }
            distance = (float) Math.sqrt(distance);
            if (ret == null || distance < ret.second) {
                prev_ret = ret;
                ret = new Pair<>(name, distance);
            }
        }
        if (prev_ret == null) prev_ret = ret;
        neighbour_list.add(ret);
        neighbour_list.add(prev_ret);

        return neighbour_list;
    }

    public Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
        bm.recycle();
        return resizedBitmap;
    }
    private static Bitmap getCropBitmapByCPU(Bitmap source, RectF cropRectF) {
        Bitmap resultBitmap = Bitmap.createBitmap((int) cropRectF.width(),
                (int) cropRectF.height(), Bitmap.Config.ARGB_8888);
        Canvas cavas = new Canvas(resultBitmap);

        // draw background
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        cavas.drawRect(
                new RectF(0, 0, cropRectF.width(), cropRectF.height()),
                paint);

        Matrix matrix = new Matrix();
        matrix.postTranslate(-cropRectF.left, -cropRectF.top);

        cavas.drawBitmap(source, matrix, paint);

        if (source != null && !source.isRecycled()) {
            source.recycle();
        }

        return resultBitmap;
    }

    private static Bitmap rotateBitmap(
            Bitmap bitmap, int rotationDegrees, boolean flipX, boolean flipY) {
        Matrix matrix = new Matrix();

        // Rotate the image back to straight.
        matrix.postRotate(rotationDegrees);

        // Mirror the image along the X or Y axis.
        matrix.postScale(flipX ? -1.0f : 1.0f, flipY ? -1.0f : 1.0f);
        Bitmap rotatedBitmap =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        // Recycle the old bitmap if it has changed.
        if (rotatedBitmap != bitmap) {
            bitmap.recycle();
        }
        return rotatedBitmap;
    }

    //IMPORTANT. If conversion not done ,the toBitmap conversion does not work on some devices.
    private static byte[] YUV_420_888toNV21(Image image) {

        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width*height;
        int uvSize = width*height/4;

        byte[] nv21 = new byte[ySize + uvSize*2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

        int rowStride = image.getPlanes()[0].getRowStride();
        assert(image.getPlanes()[0].getPixelStride() == 1);

        int pos = 0;

        if (rowStride == width) { // likely
            yBuffer.get(nv21, 0, ySize);
            pos += ySize;
        }
        else {
            long yBufferPos = -rowStride; // not an actual position
            for (; pos<ySize; pos+=width) {
                yBufferPos += rowStride;
                yBuffer.position((int) yBufferPos);
                yBuffer.get(nv21, pos, width);
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();

        assert(rowStride == image.getPlanes()[1].getRowStride());
        assert(pixelStride == image.getPlanes()[1].getPixelStride());

        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            byte savePixel = vBuffer.get(1);
            try {
                vBuffer.put(1, (byte)~savePixel);
                if (uBuffer.get(0) == (byte)~savePixel) {
                    vBuffer.put(1, savePixel);
                    vBuffer.position(0);
                    uBuffer.position(0);
                    vBuffer.get(nv21, ySize, 1);
                    uBuffer.get(nv21, ySize + 1, uBuffer.remaining());

                    return nv21; // shortcut
                }
            }
            catch (ReadOnlyBufferException ex) {
                // unfortunately, we cannot check if vBuffer and uBuffer overlap
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel);
        }

        // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
        // but performance gain would be less significant

        for (int row=0; row<height/2; row++) {
            for (int col=0; col<width/2; col++) {
                int vuPos = col*pixelStride + row*rowStride;
                nv21[pos++] = vBuffer.get(vuPos);
                nv21[pos++] = uBuffer.get(vuPos);
            }
        }

        return nv21;
    }

    private Bitmap toBitmap(Image image) {

        byte[] nv21=YUV_420_888toNV21(image);


        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);

        byte[] imageBytes = out.toByteArray();
        //System.out.println("bytes"+ Arrays.toString(imageBytes));

        //System.out.println("FORMAT"+image.getFormat());

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    //Save Faces to Shared Preferences.Conversion of Recognition objects to json string
    private void insertToSP(HashMap<String, SimilarityClassifier.Recognition> jsonMap, int mode) {
        if (mode == 1) {
            jsonMap.clear();
        } else if (mode == 0) {
            jsonMap.putAll(readFromSP());
        }
        String jsonString = new Gson().toJson(jsonMap);
        SharedPreferences sharedPreferences = getSharedPreferences("HashMap", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("map", jsonString);
        editor.apply();
        Toast.makeText(context, "Recognitions Saved", Toast.LENGTH_SHORT).show();
    }


    //Load Faces from Shared Preferences.Json String to Recognition object
    private HashMap<String, SimilarityClassifier.Recognition> readFromSP(){
        SharedPreferences sharedPreferences = getSharedPreferences("HashMap", MODE_PRIVATE);
        String defValue = new Gson().toJson(new HashMap<String, SimilarityClassifier.Recognition>());
        String json=sharedPreferences.getString("map",defValue);
        // System.out.println("Output json"+json.toString());
        TypeToken<HashMap<String,SimilarityClassifier.Recognition>> token = new TypeToken<HashMap<String,SimilarityClassifier.Recognition>>() {};
        HashMap<String,SimilarityClassifier.Recognition> retrievedMap=new Gson().fromJson(json,token.getType());
        // System.out.println("Output map"+retrievedMap.toString());

        //During type conversion and save/load procedure,format changes(eg float converted to double).
        //So embeddings need to be extracted from it in required format(eg.double to float).
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : retrievedMap.entrySet())
        {
            float[][] output=new float[1][OUTPUT_SIZE];
            ArrayList arrayList= (ArrayList) entry.getValue().getExtra();
            arrayList = (ArrayList) arrayList.get(0);
            for (int counter = 0; counter < arrayList.size(); counter++) {
                output[0][counter]= ((Double) arrayList.get(counter)).floatValue();
            }
            entry.getValue().setExtra(output);

            //System.out.println("Entry output "+entry.getKey()+" "+entry.getValue().getExtra() );

        }
//        System.out.println("OUTPUT"+ Arrays.deepToString(outut));
        Toast.makeText(context, "Recognitions Loaded", Toast.LENGTH_SHORT).show();
        return retrievedMap;
    }

    //Load Photo from phone storage
    private void loadphoto()
    {
        start=false;
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Picture"), SELECT_PICTURE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handling the result from speech recognition
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

            if (matches != null && !matches.isEmpty()) {
                String name = matches.get(0).trim();
                if (!name.isEmpty()) {
                    SimilarityClassifier.Recognition result = new SimilarityClassifier.Recognition("0", "", -1f);
                    result.setExtra(embeedings);
                    registered.put(name, result);
                    Toast.makeText(this, "Face added successfully!", Toast.LENGTH_SHORT).show();
                    if (textToSpeech != null && !textToSpeech.isSpeaking()) {
                        textToSpeech.speak("Saved successfully!", TextToSpeech.QUEUE_FLUSH, null, null);
                    }

                } else {
                    Toast.makeText(this, "Please try again!", Toast.LENGTH_SHORT).show();
                    if (textToSpeech != null && !textToSpeech.isSpeaking()) {
                        textToSpeech.speak("Please try again!", TextToSpeech.QUEUE_FLUSH, null, null);
                    }
                }
            } else {
                Toast.makeText(this, "No speech input detected!", Toast.LENGTH_SHORT).show();
                if (textToSpeech != null && !textToSpeech.isSpeaking()) {
                    textToSpeech.speak("No speech input detected!", TextToSpeech.QUEUE_FLUSH, null, null);
                }
            }
            start = true; // Resume the face recognition process
        }

        // Handling the result from image selection
        if (requestCode == SELECT_PICTURE && resultCode == RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            try {
                InputImage impphoto = InputImage.fromBitmap(getBitmapFromUri(selectedImageUri), 0);
                detector.process(impphoto).addOnSuccessListener(new OnSuccessListener<List<Face>>() {
                    @Override
                    public void onSuccess(List<Face> faces) {
                        if (faces.size() != 0) {
                            recognize.setText("Recognize");
                            add_face.setVisibility(View.VISIBLE);
                            reco_name.setVisibility(View.INVISIBLE);
                            face_preview.setVisibility(View.VISIBLE);
                            preview_info.setText("1.Bring Face in view of Camera.\n\n2.Your Face preview will appear here.\n\n3.Click Add button to save face.");
                            Face face = faces.get(0);

                            Bitmap frame_bmp = null;
                            try {
                                frame_bmp = getBitmapFromUri(selectedImageUri);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            Bitmap frame_bmp1 = rotateBitmap(frame_bmp, 0, flipX, false);

                            RectF boundingBox = new RectF(face.getBoundingBox());

                            Bitmap cropped_face = getCropBitmapByCPU(frame_bmp1, boundingBox);

                            Bitmap scaled = getResizedBitmap(cropped_face, 112, 112);

                            recognizeImage(scaled);
                            addFace();
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }).addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        start = true;
                        Toast.makeText(context, "Failed to add", Toast.LENGTH_SHORT).show();
                    }
                });
                face_preview.setImageBitmap(getBitmapFromUri(selectedImageUri));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Bitmap getBitmapFromUri(Uri uri) throws IOException {
        ParcelFileDescriptor parcelFileDescriptor =
                getContentResolver().openFileDescriptor(uri, "r");
        FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
        Bitmap image = BitmapFactory.decodeFileDescriptor(fileDescriptor);
        parcelFileDescriptor.close();
        return image;
    }

}
