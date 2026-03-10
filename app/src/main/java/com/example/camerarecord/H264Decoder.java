package com.example.camerarecord;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class H264Decoder {
    private static final String TAG = "H264Decoder";
    private static final String MIME_TYPE = "video/avc";
    private static final int TIMEOUT_US = 10000;
    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int QUEUE_CAPACITY = 30;

    private MediaCodec mediaCodec;
    private Surface outputSurface;
    private HandlerThread decodeThread;
    private Handler decodeHandler;

    public boolean isDecoding = false;
    private BlockingQueue<EncodedPacket> dataQueue;

    private int width = VIDEO_WIDTH;
    private int height = VIDEO_HEIGHT;

    private OnDecoderListener listener;

    public interface OnDecoderListener {
        void onDecoderStarted();
        void onDecoderStopped();
        void onDecoderError(String error);
    }

    private static class EncodedPacket {
        final byte[] data;
        final int flags;

        EncodedPacket(byte[] data, int flags) {
            this.data = data;
            this.flags = flags;
        }
    }

    public H264Decoder() {
        dataQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    }

    public boolean isDecoding() {
        return isDecoding;
    }

    public void setOnDecoderListener(OnDecoderListener listener) {
        this.listener = listener;
    }

    public void setVideoSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public Surface getSurface() {
        return outputSurface;
    }

    public void startDecoding(String filePath, Surface surface) {
        if (isDecoding) {
            Log.w(TAG, "Decoder is already running");
            return;
        }

        this.outputSurface = surface;

        startDecodeThread();
        setupDecoder();
        isDecoding = true;

        new Thread(decodeRunnable).start();

        if (listener != null) {
            listener.onDecoderStarted();
        }
    }

    public void feedData(byte[] data, int flags) {
        if (isDecoding && data != null && data.length > 0) {
            try {
                byte[] copy = new byte[data.length];
                System.arraycopy(data, 0, copy, 0, data.length);
                boolean offered = dataQueue.offer(new EncodedPacket(copy, flags));
                Log.v(TAG, "feedData: " + data.length + " bytes, flags: " + flags + ", queue size: " + dataQueue.size() + ", offered: " + offered);
            } catch (Exception e) {
                Log.e(TAG, "Error feeding data", e);
            }
        }
    }

    public void stopDecoding() {
        isDecoding = false;

        if (decodeHandler != null) {
            decodeHandler.post(() -> {
                if (mediaCodec != null) {
                    try {
                        mediaCodec.stop();
                        mediaCodec.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping decoder", e);
                    }
                    mediaCodec = null;
                }
            });
        }

        stopDecodeThread();

        dataQueue.clear();

        if (listener != null) {
            listener.onDecoderStopped();
        }
    }

    private void startDecodeThread() {
        decodeThread = new HandlerThread("H264Decoder");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    private void stopDecodeThread() {
        if (decodeThread != null) {
            decodeThread.quitSafely();
            try {
                decodeThread.join();
                decodeThread = null;
                decodeHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping decode thread", e);
            }
        }
    }

    private void setupDecoder() {
        try {
            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mediaCodec.configure(format, outputSurface, null, 0);
            mediaCodec.start();

            Log.d(TAG, "Decoder configured successfully");
        } catch (IOException e) {
            Log.e(TAG, "Error setting up decoder", e);
            if (listener != null) {
                listener.onDecoderError("Failed to setup decoder: " + e.getMessage());
            }
        }
    }

    private final Runnable decodeRunnable = new Runnable() {
        @Override
        public void run() {
            ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long presentationTimeUs = 0;
            int frameCount = 0;
            int decodeErrorCount = 0;
            while (isDecoding) {
                try {
                    int inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();

                        EncodedPacket packet = dataQueue.poll();
                        if (packet != null) {
                            inputBuffer.put(packet.data);
                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, packet.data.length, presentationTimeUs, packet.flags);
                            presentationTimeUs += 33333;
                            Log.v(TAG, "Queued data, size: " + packet.data.length + ", flags: " + packet.flags + ", queue size: " + dataQueue.size() + ", pts: " + presentationTimeUs);
                        } else {
                            Log.v(TAG, "No data in queue, waiting...");
                        }
                    }

                    int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = mediaCodec.getOutputFormat();
                        Log.d(TAG, "Output format changed: " + newFormat);
                        if (newFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                            width = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                            height = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                        }
                    } else if (outputBufferIndex >= 0) {
                        frameCount++;
                        if (frameCount % 30 == 0) {
                            Log.d(TAG, "Decoded frame: " + frameCount + ", size: " + bufferInfo.size + ", pts: " + bufferInfo.presentationTimeUs);
                        }
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                    } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        decodeErrorCount++;
                        if (decodeErrorCount % 60 == 0) {
                            Log.w(TAG, "No output buffer available, count: " + decodeErrorCount + ", queue: " + dataQueue.size());
                        }
                        } else {
                            decodeErrorCount++;
                            if (decodeErrorCount % 60 == 0) {
                                Log.w(TAG, "Unknown output: " + outputBufferIndex + ", count: " + decodeErrorCount);
                            }
                        }
                        
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            break;
                        }
                } catch (Exception e) {
                    Log.e(TAG, "Error during decoding", e);
                }
            }
        }
    };
}
