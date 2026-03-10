package com.example.camerarecord;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
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
    private ImageReader imageReader;
    private MediaCodec mediaCodec;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean isEncoding = false;
    private String cameraId = "0";
    private Size videoSize = new Size(1280, 720);

    private OnEncodedDataListener onEncodedDataListener;

    public interface OnEncodedDataListener {
        void onEncodedData(byte[] data, boolean isKeyFrame);
    }

    public CameraEncoder(Context context) {
        this.context = context;
    }

    public void setOnEncodedDataListener(OnEncodedDataListener listener) {
        this.onEncodedDataListener = listener;
    }

    public void setCameraId(String cameraId) {
        this.cameraId = cameraId;
    }

    public void setVideoSize(int width, int height) {
        this.videoSize = new Size(width, height);
    }

    public void startEncoding() {
        if (isEncoding) {
            return;
        }
        startBackgroundThread();
        setupMediaCodec();
        openCamera();
        isEncoding = true;
    }

    public void stopEncoding() {
        isEncoding = false;
        closeCamera();
        stopMediaCodec();
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

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mediaCodec.start();

            Log.d(TAG, "MediaCodec configured successfully");
        } catch (IOException e) {
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
        }
    }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            return;
        }

        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            android.hardware.camera2.params.StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                videoSize = map.getOutputSizes(ImageFormat.YUV_420_888)[0];
                for (Size size : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    if (size.getWidth() == 1280 && size.getHeight() == 720) {
                        videoSize = size;
                        break;
                    }
                }
            }

            imageReader = ImageReader.newInstance(videoSize.getWidth(), videoSize.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCaptureSession() {
        try {
            Surface encoderSurface = mediaCodec.createInputSurface();

            cameraDevice.createCaptureSession(
                    Arrays.asList(imageReader.getSurface(), encoderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(encoderSurface);
                                builder.addTarget(imageReader.getSurface());

                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

                                captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);

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

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                feedImageToEncoder(image);
                image.close();
            }
        }
    };

    private void feedImageToEncoder(Image image) {
        if (mediaCodec == null) return;

        try {
            ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer yBuffer = planes[0].getBuffer();
                ByteBuffer uBuffer = planes[1].getBuffer();
                ByteBuffer vBuffer = planes[2].getBuffer();

                byte[] yData = new byte[yBuffer.remaining()];
                byte[] uData = new byte[uBuffer.remaining()];
                byte[] vData = new byte[vBuffer.remaining()];

                yBuffer.get(yData);
                uBuffer.get(uData);
                vBuffer.get(vData);

                int ySize = yData.length;
                int uSize = uData.length;
                int vSize = vData.length;

                byte[] nv12 = new byte[ySize + uSize + vSize];
                System.arraycopy(yData, 0, nv12, 0, ySize);
                System.arraycopy(uData, 0, nv12, ySize, uSize);
                System.arraycopy(vData, 0, nv12, ySize + uSize, vSize);

                inputBuffer.put(nv12);
                long presentationTimeUs = System.nanoTime() / 1000;
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, nv12.length, presentationTimeUs, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error feeding image to encoder", e);
        }
    }

    private final Runnable encodeOutputThread = new Runnable() {
        @Override
        public void run() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (isEncoding) {
                int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Output format changed: " + mediaCodec.getOutputFormat());
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] data = new byte[bufferInfo.size];
                        outputBuffer.get(data);

                        boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        if (onEncodedDataListener != null) {
                            onEncodedDataListener.onEncodedData(data, isKeyFrame);
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                }
            }
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
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
}
