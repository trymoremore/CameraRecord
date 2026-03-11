package com.example.camerarecord;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class CameraEncoder {
    private static final String TAG = "CameraEncoder";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int BIT_RATE = 2000000;

    private final Context context;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec mediaCodec;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean isEncoding = false;
    private String cameraId = "1";
    private Size videoSize = new Size(1280, 720);
    private Surface encoderSurface;
    private int sensorOrientation = 0;
    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;

    private OnEncodedDataListener onEncodedDataListener;
    private OnEncoderStartedListener onEncoderStartedListener;

    public interface OnEncodedDataListener {
        void onEncodedData(byte[] data, int flags, boolean isKeyFrame);
    }

    public interface OnEncoderStartedListener {
        void onEncoderStarted(int width, int height, int sensorOrientation, int lensFacing);
    }

    public CameraEncoder(Context context) {
        this.context = context;
    }

    public void setOnEncodedDataListener(OnEncodedDataListener listener) {
        this.onEncodedDataListener = listener;
    }

    public void setOnEncoderStartedListener(OnEncoderStartedListener listener) {
        this.onEncoderStartedListener = listener;
    }

    public void setCameraId(String cameraId) {
        this.cameraId = cameraId;
    }

    public void setVideoSize(int width, int height) {
        this.videoSize = new Size(width, height);
    }

    public int getSensorOrientation() {
        return sensorOrientation;
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public void startEncoding() {
        if (isEncoding) {
            Log.w(TAG, "startEncoding called while encoder is already running");
            return;
        }
        Log.d(TAG, "startEncoding: cameraId=" + cameraId + ", requested size=" + videoSize.getWidth() + "x" + videoSize.getHeight());
        startBackgroundThread();
        openCamera();
        isEncoding = true;
    }

    public void stopEncoding() {
        isEncoding = false;
        closeCamera();
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraEncoder");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private void setupMediaCodec() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, videoSize.getWidth(), videoSize.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            format.setInteger(MediaFormat.KEY_ROTATION, 180);

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = mediaCodec.createInputSurface();
            mediaCodec.start();

            Log.d(TAG, "MediaCodec configured: " + videoSize.getWidth() + "x" + videoSize.getHeight());
            Log.d(TAG, "MediaCodec format: " + format);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up MediaCodec", e);
        }
    }

    private void stopMediaCodec() {
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaCodec", e);
            }
            mediaCodec = null;
            encoderSurface = null;
        }
    }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            return;
        }

        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "Available cameras: " + Arrays.toString(cameraManager.getCameraIdList()));
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 0;
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            lensFacing = facing != null ? facing : CameraCharacteristics.LENS_FACING_BACK;
            Log.d(TAG, "Camera sensor orientation: " + sensorOrientation + ", lensFacing=" + lensFacing);
            android.hardware.camera2.params.StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(MediaRecorder.class);
                if (sizes == null || sizes.length == 0) {
                    Log.w(TAG, "MediaRecorder output sizes unavailable, fallback to SurfaceTexture output sizes");
                    sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                }
                if (sizes != null && sizes.length > 0) {
                    Size selected = null;
                    for (Size size : sizes) {
                        if (selected == null || (size.getWidth() * size.getHeight()) > (selected.getWidth() * selected.getHeight())) {
                            selected = size;
                        }
                    }
                    if (selected != null) {
                        videoSize = selected;
                    } else {
                        videoSize = sizes[0];
                    }
                    Log.d(TAG, "Using video size: " + videoSize.getWidth() + "x" + videoSize.getHeight());
                }
            }

            setupMediaCodec();

            Log.d(TAG, "Opening camera id=" + cameraId + " on thread=" + Thread.currentThread().getName());
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera opened: " + camera.getId());
            cameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "Camera disconnected: " + camera.getId());
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: id=" + camera.getId() + ", errorCode=" + error);
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCaptureSession() {
        try {
            cameraDevice.createCaptureSession(
                    Arrays.asList(encoderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(encoderSurface);

                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

//                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//                                    Integer rotateAndCropValue = CaptureRequest.SCALER_ROTATE_AND_CROP_90;
//                                    if (rotateAndCropValue != null) {
//                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                                            builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP, rotateAndCropValue);
//                                        }
//                                    }
//                                }

                                captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                Log.d(TAG, "Capture session configured, repeating request started");

                                if (onEncoderStartedListener != null) {
                                    onEncoderStartedListener.onEncoderStarted(videoSize.getWidth(), videoSize.getHeight(), sensorOrientation, lensFacing);
                                }

                                new Thread(encodeOutputThread).start();
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error creating capture request", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure capture session");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session", e);
        }
    }

    private final Runnable encodeOutputThread = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "encodeOutputThread started");
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int frameCount = 0;
            while (isEncoding) {
                try {
                    int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed: " + mediaCodec.getOutputFormat());
                    } else if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            byte[] data = new byte[bufferInfo.size];
                            outputBuffer.get(data);

                            boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            boolean isCodecConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            frameCount++;
                            Log.d(TAG, "Encoded frame: " + frameCount + ", size: " + bufferInfo.size + ", keyFrame: " + isKeyFrame + ", codecConfig=" + isCodecConfig + ", pts=" + bufferInfo.presentationTimeUs + ", offset=" + bufferInfo.offset);

                            if (onEncodedDataListener != null) {
                                onEncodedDataListener.onEncodedData(data, bufferInfo.flags, isKeyFrame);
                            }
                        }
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                    } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.v(TAG, "Encoder output not ready yet");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during encoding", e);
                }
            }
            Log.d(TAG, "encodeOutputThread stopped");
        }
    };

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopMediaCodec();
    }
}
