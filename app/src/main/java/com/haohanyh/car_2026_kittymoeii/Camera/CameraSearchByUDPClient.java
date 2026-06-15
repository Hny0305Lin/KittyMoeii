package com.haohanyh.car_2026_kittymoeii.Camera;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * 摄像头 UDP 搜索客户端。
 *
 * <p>该类通过向局域网广播搜索报文来发现摄像头设备，并从返回报文中解析出
 * 摄像头 IP 地址。它只负责发现设备，不负责后续的图像获取与控制命令发送。</p>
 *
 * <p>职责边界：</p>
 * <p>1. 构造并发送 UDP 广播搜索包。</p>
 * <p>2. 接收摄像头响应报文并提取 IP 地址。</p>
 * <p>3. 对外返回搜索到的摄像头 IP，供上层 HTTP 连接流程使用。</p>
 */
public class CameraSearchByUDPClient {

    private static final String TAG = "CameraSearchIP";
    private static final int SOCKET_TIMEOUT_MS = 1500;
    private String CameraIP = "";
    private final int PORT = 3565;
    private final int SERVER_PORT = 8600;
    private final byte[] mbyte = new byte[]{68, 72, 1, 1};
    private DatagramSocket UDPSocket = null;
    private final byte[] msg = new byte[1024];
    private static volatile CameraSearchByUDPClient searchCameraIP;

    public static CameraSearchByUDPClient getSearchCameraIP(){
        if ( searchCameraIP == null ){
            synchronized ( CameraSearchByUDPClient.class ){
                searchCameraIP = new CameraSearchByUDPClient();
                return searchCameraIP;
            }
        }
        return searchCameraIP;
    }

    public void setCameraIP(String cameraIP) {
        CameraIP = cameraIP;
    }

    public CameraSearchByUDPClient() {};
    public static CameraSearchByUDPClient ManageCamera() { return CameraSearchByUDPClient.savedata.thing; }
    protected static class savedata { private static final CameraSearchByUDPClient thing = new CameraSearchByUDPClient(); }

    /**
     * 发送一次 UDP 广播搜索，并返回解析出的摄像头 IP。
     *
     * @return 搜索成功时返回摄像头 IP，失败时返回空字符串
     */
    public String send() {
        CameraIP = "";
        try {
            InetAddress local = InetAddress.getByName("255.255.255.255");
            if (UDPSocket != null) {
                UDPSocket.close();
                UDPSocket = null;
            }
            UDPSocket = new DatagramSocket(PORT);
            UDPSocket.setBroadcast(true);
            UDPSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

            DatagramPacket sendPacket = new DatagramPacket(mbyte, mbyte.length, local, SERVER_PORT);
            DatagramPacket recPacket = new DatagramPacket(msg, msg.length);
            UDPSocket.send(sendPacket);
            UDPSocket.receive(recPacket);
            String text = new String(recPacket.getData(), 0, recPacket.getLength(), StandardCharsets.UTF_8);
            if (text.startsWith("DH")) {
                getIP(text);
            }
        } catch (IOException e) {
            Log.w(TAG, "UDP 搜索摄像头失败", e);
        } finally {
            if (UDPSocket != null) {
                UDPSocket.close();
                UDPSocket = null;
            }
        }
        return CameraIP;
    }

    /**
     * 从摄像头返回报文中提取 IP 地址字符串。
     *
     * @param text 摄像头响应的原始文本内容
     */
    private void getIP(String text) {
        byte[] ipbyte = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder();
        for (int i = 4; i < 22 && i < ipbyte.length && ipbyte[i] != 0; ++i) {
            if (ipbyte[i] == 46) {
                builder.append('.');
            } else {
                builder.append(ipbyte[i] - 48);
            }
        }
        CameraIP = builder.toString();
    }
}
