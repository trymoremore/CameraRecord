package com.example.camerarecord;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;

    private SurfaceView surfaceView;
    private Button btnStart;
    private CameraEncoder cameraEncoder;
    private H264Decoder h264Decoder;

    private boolean isRunning = false;
    private Surface decoderSurface;
    private int videoWidth = 640;
    private int videoHeight = 480;
    private int sensorOrientation = 0;
    private int lensFacing = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceView);
        btnStart = findViewById(R.id.btnStart);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                decoderSurface = holder.getSurface();
                Log.d(TAG, "surfaceCreated: valid=" + (decoderSurface != null && decoderSurface.isValid()));
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "surfaceChanged: format=" + format + ", size=" + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "surfaceDestroyed");
                decoderSurface = null;
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isRunning) {
                    if (checkCameraPermission()) {
                        startCamera();
                    } else {
                        requestCameraPermission();
                    }
                } else {
                    stopCamera();
                }
            }
        });
    }

    private boolean checkCameraPermission() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "checkCameraPermission: " + granted);
        return granted;
    }

    private void requestCameraPermission() {
        Log.d(TAG, "requestCameraPermission");
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            Log.d(TAG, "onRequestPermissionsResult: result=" + (grantResults.length > 0 ? grantResults[0] : -1));
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            }
        }
    }

    private void startCamera() {
        if (decoderSurface == null || !decoderSurface.isValid()) {
            Log.e(TAG, "Surface not ready, waiting...");
            surfaceView.postDelayed(this::startCamera, 100);
            return;
        }

        Log.d(TAG, "Surface ready, starting camera pipeline...");
        
        cameraEncoder = new CameraEncoder(this);
        cameraEncoder.setOnEncoderStartedListener((width, height, orientation, facing) -> {
            videoWidth = width;
            videoHeight = height;
            sensorOrientation = orientation;
            lensFacing = facing;
            Log.d(TAG, "Encoder started with size: " + width + "x" + height + ", sensorOrientation=" + sensorOrientation + ", lensFacing=" + lensFacing);
            startDecoder();
        });
        cameraEncoder.setOnEncodedDataListener(new CameraEncoder.OnEncodedDataListener() {
            private int count = 0;
            @Override
            public void onEncodedData(byte[] data, int flags, boolean isKeyFrame) {
                count++;
                Log.d(TAG, "Encoder callback: frame " + count + ", size: " + data.length + ", flags: " + flags + ", keyFrame: " + isKeyFrame);
                if (h264Decoder != null && h264Decoder.isDecoding()) {
                    h264Decoder.feedData(data, flags);
                } else {
                    Log.w(TAG, "Decoder not ready, dropping frame " + count + ", flags=" + flags);
                }
            }
        });

        Log.d(TAG, "Starting encoder...");
        cameraEncoder.startEncoding();

        isRunning = true;
        btnStart.setText("Stop");
    }

    private void startDecoder() {
        Log.d(TAG, "Starting decoder with size: " + videoWidth + "x" + videoHeight);
        Log.d(TAG, "Decoder surface valid: " + (decoderSurface != null && decoderSurface.isValid()));
        
        float correctionRotation = calculatePreviewRotationDegrees();
//        surfaceView.setRotation(correctionRotation);
        Log.d(TAG, "Applied surface rotation correction: " + correctionRotation + " degrees");

        h264Decoder = new H264Decoder();
        h264Decoder.setVideoSize(videoWidth, videoHeight);
        h264Decoder.setOnDecoderListener(new H264Decoder.OnDecoderListener() {
            @Override
            public void onDecoderStarted() {
                Log.d(TAG, "Decoder started");
            }

            @Override
            public void onDecoderStopped() {
                Log.d(TAG, "Decoder stopped");
            }

            @Override
            public void onDecoderError(String error) {
                Log.e(TAG, "Decoder error: " + error);
            }
        });

        h264Decoder.startDecoding(null, decoderSurface);
    }

    private float calculatePreviewRotationDegrees() {
        Display display = getWindowManager().getDefaultDisplay();
        int displayRotation = display != null ? display.getRotation() : Surface.ROTATION_0;

        int deviceDegrees;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                deviceDegrees = 90;
                break;
            case Surface.ROTATION_180:
                deviceDegrees = 180;
                break;
            case Surface.ROTATION_270:
                deviceDegrees = 270;
                break;
            case Surface.ROTATION_0:
            default:
                deviceDegrees = 0;
                break;
        }

        int neededRotation;
        if (lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            neededRotation = (sensorOrientation + deviceDegrees) % 360;
            neededRotation = (360 - neededRotation) % 360;
        } else {
            neededRotation = (sensorOrientation - deviceDegrees + 360) % 360;
        }

        Log.d(TAG, "calculatePreviewRotationDegrees: sensor=" + sensorOrientation
                + ", device=" + deviceDegrees
                + ", lensFacing=" + lensFacing
                + ", neededRotation=" + neededRotation);
        return neededRotation;
    }

    private void stopCamera() {
        Log.d(TAG, "stopCamera called, isRunning=" + isRunning);
        if (cameraEncoder != null) {
            cameraEncoder.stopEncoding();
            cameraEncoder = null;
        }

        if (h264Decoder != null) {
            h264Decoder.stopDecoding();
            h264Decoder = null;
        }

        isRunning = false;
        btnStart.setText("Start");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
    }
}
