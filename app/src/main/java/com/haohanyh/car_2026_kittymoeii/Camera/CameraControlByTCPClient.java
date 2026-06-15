package com.haohanyh.car_2026_kittymoeii.Camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 摄像头 HTTP 访问客户端。
 *
 * <p>该类负责通过 {@link HttpURLConnection} 与摄像头建立基于 HTTP 的通信，
 * 用于获取实时快照以及发送控制命令。虽然类名保留了 TCPClient 的历史命名，
 * 但当前实现实际封装的是 HTTP 请求流程。</p>
 *
 * <p>职责边界：</p>
 * <p>1. 根据摄像头 IP 拼接快照与控制命令接口地址。</p>
 * <p>2. 管理 HTTP 连接超时、输入流读取与连接释放。</p>
 * <p>3. 向上层返回快照 {@link Bitmap}，或发送一次控制指令。</p>
 */
public class CameraControlByTCPClient {
    private static final String TAG = "CameraHttp";
    private static final String PORT = ":81";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;

    public CameraControlByTCPClient() {}

    public static CameraControlByTCPClient NeedReadCameraData() {
        return saveandctrldata.thing;
    }

    protected static class saveandctrldata {
        private static final CameraControlByTCPClient thing = new CameraControlByTCPClient();
    }

    /**
     * 通过摄像头 HTTP 快照接口拉取一帧图像。
     *
     * @param IP 摄像头 IP 地址，例如 {@code 192.168.31.100}
     * @return 成功时返回快照位图，失败时返回 {@code null}
     */
    public Bitmap getphoto(String IP) {
        if (IP == null || IP.trim().isEmpty()) {
            Log.w(TAG, "摄像头 IP 为空，无法获取快照");
            return null;
        }

        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            URL cameraUrl = new URL("http://" + IP.trim() + PORT + "/snapshot.cgi?loginuse=admin&loginpas=888888&res=0");
            connection = (HttpURLConnection) cameraUrl.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "获取摄像头快照失败，HTTP Code = " + connection.getResponseCode());
                return null;
            }

            inputStream = connection.getInputStream();
            return BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            Log.e(TAG, "HTTP 获取摄像头快照失败", e);
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 通过摄像头 HTTP 控制接口发送一次控制命令。
     *
     * @param IP 摄像头 IP 地址
     * @param command 控制命令编号
     * @param step 步进参数，通常用于控制命令执行方式
     */
    public void getcommand(String IP, int command, int step) {
        if (IP == null || IP.trim().isEmpty()) {
            Log.w(TAG, "摄像头 IP 为空，无法发送控制命令");
            return;
        }

        HttpURLConnection urlConnection = null;
        InputStream inputStream = null;
        try {
            URL commandUrl = new URL("http://" + IP.trim() + PORT + "/decoder_control.cgi?loginuse=admin&loginpas=888888&command=" + command + "&onestep=" + step);
            urlConnection = (HttpURLConnection) commandUrl.openConnection();
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            urlConnection.setReadTimeout(READ_TIMEOUT_MS);
            urlConnection.connect();
            inputStream = urlConnection.getInputStream();
        } catch (IOException e) {
            Log.e(TAG, "发送摄像头控制命令失败", e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }
}
