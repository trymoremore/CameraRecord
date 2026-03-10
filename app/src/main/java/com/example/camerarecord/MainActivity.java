package com.example.camerarecord;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
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
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
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
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            }
        }
    }

    private void startCamera() {
        if (decoderSurface == null || !decoderSurface.isValid()) {
            Log.e(TAG, "Surface not ready");
            return;
        }

        h264Decoder = new H264Decoder();
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

        cameraEncoder = new CameraEncoder(this);
        cameraEncoder.setOnEncodedDataListener(new CameraEncoder.OnEncodedDataListener() {
            @Override
            public void onEncodedData(byte[] data, boolean isKeyFrame) {
                if (h264Decoder != null && h264Decoder.isDecoding()) {
                    h264Decoder.feedData(data);
                }
            }
        });

        h264Decoder.startDecoding(null, decoderSurface);
        cameraEncoder.startEncoding();

        isRunning = true;
        btnStart.setText("Stop");
    }

    private void stopCamera() {
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
