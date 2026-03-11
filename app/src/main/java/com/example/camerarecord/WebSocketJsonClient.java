package com.example.camerarecord;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 用于连接指定 wss 地址，并根据配置监听字符串或二进制消息，同时提供消息发送能力。
 */
public class WebSocketJsonClient {

    private static final String TAG = "WebSocketJsonClient";
    private static final int NORMAL_CLOSE_CODE = 1000;

    public enum ReceiveMode {
        STRING,
        BINARY
    }

    public interface MessageListener {
        void onConnected();

        void onMessage(@NonNull String text);

        void onMessage(@NonNull byte[] data);

        void onError(Throwable throwable);

        void onClosed(int code, String reason);
    }

    private final OkHttpClient client;
    private final String wssUrl;
    private final ReceiveMode receiveMode;
    @Nullable
    private final MessageListener listener;

    @Nullable
    private WebSocket webSocket;

    public WebSocketJsonClient(@NonNull String wssUrl,
                               @NonNull ReceiveMode receiveMode,
                               @Nullable MessageListener listener) {
        this.wssUrl = wssUrl;
        this.receiveMode = receiveMode;
        this.listener = listener;
        this.client = new OkHttpClient.Builder().build();
    }

    public WebSocketJsonClient(@NonNull String wssUrl, @Nullable MessageListener listener) {
        this(wssUrl, ReceiveMode.STRING, listener);
    }

    public void connect() {
        Request request = new Request.Builder()
                .url(wssUrl)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket connected: " + response.message());
                if (listener != null) {
                    listener.onConnected();
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (receiveMode != ReceiveMode.STRING) {
                    Log.d(TAG, "Ignored text message because receiveMode=" + receiveMode);
                    return;
                }
                if (listener != null) {
                    listener.onMessage(text);
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                if (receiveMode != ReceiveMode.BINARY) {
                    Log.d(TAG, "Ignored binary message because receiveMode=" + receiveMode);
                    return;
                }
                if (listener != null) {
                    listener.onMessage(bytes.toByteArray());
                }
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                webSocket.close(code, reason);
                if (listener != null) {
                    listener.onClosed(code, reason);
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.e(TAG, "WebSocket failure", t);
                if (listener != null) {
                    listener.onError(t);
                }
            }
        });
    }

    public boolean sendJson(@NonNull JSONObject jsonObject) {
        return sendText(jsonObject.toString());
    }

    public boolean sendText(@NonNull String text) {
        if (webSocket == null) {
            return false;
        }
        return webSocket.send(text);
    }

    public boolean sendBinary(@NonNull byte[] data) {
        if (webSocket == null) {
            return false;
        }
        return webSocket.send(ByteString.of(data));
    }

    @Nullable
    public JSONObject parseToJson(@NonNull String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            if (listener != null) {
                listener.onError(e);
            }
            return null;
        }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(NORMAL_CLOSE_CODE, "Client close");
            webSocket = null;
        }
        client.dispatcher().executorService().shutdown();
    }
}
