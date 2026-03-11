package com.example.camerarecord;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;

    private SurfaceView surfaceView;
    private Button btnStart;
    private CameraEncoder cameraEncoder;

    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceView);
        btnStart = findViewById(R.id.btnStart);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "surfaceCreated");
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

    private String generateOutputPath() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "VID_" + timestamp + ".mp4";
        
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) {
            dir = getFilesDir();
        }
        
        return new File(dir, fileName).getAbsolutePath();
    }

    private void startCamera() {
        String outputPath = generateOutputPath();
        Log.d(TAG, "Output file: " + outputPath);

        cameraEncoder = new CameraEncoder(this);
        cameraEncoder.setCameraId("1");
        
        Log.d(TAG, "Starting encoder...");
        cameraEncoder.startEncoding(outputPath);

        isRunning = true;
        btnStart.setText("Stop");
    }

    private void stopCamera() {
        if (cameraEncoder != null) {
            String savedPath = cameraEncoder.getOutputPath();
            cameraEncoder.stopEncoding();
            cameraEncoder = null;
            Log.d(TAG, "Video saved to: " + savedPath);
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
