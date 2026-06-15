package com.haohanyh.car_2026_kittymoeii.Camera;

import android.graphics.Bitmap;

/**
 * 摄像头访问统一入口。
 *
 * <p>该类作为摄像头模块的门面类，对上层隐藏了 UDP 搜索与 HTTP 访问的实现细节。
 * 当前设计中，IP 搜索由 {@link CameraSearchByUDPClient} 完成，图像获取与控制命令
 * 由 {@link CameraControlByTCPClient} 完成，本类负责在两者之间协调调用。</p>
 *
 * <p>职责边界：</p>
 * <p>1. 维护当前缓存的摄像头 IP。</p>
 * <p>2. 对外提供统一的搜索、取图和发送控制命令接口。</p>
 * <p>3. 在显式传入 IP 与缓存 IP 之间做优先级选择。</p>
 */
public class CameraSearchIP {
    private String CameraIP = "";
    private final CameraSearchByUDPClient udpSearchClient = CameraSearchByUDPClient.ManageCamera();
    private final CameraControlByTCPClient httpClient = CameraControlByTCPClient.NeedReadCameraData();
    private static volatile CameraSearchIP searchCameraIP;

    public static CameraSearchIP getSearchCameraIP(){
        if ( searchCameraIP == null ){
            synchronized ( CameraSearchIP.class ){
                searchCameraIP = new CameraSearchIP();
                return searchCameraIP;
            }
        }
        return searchCameraIP;
    }

    public void setCameraIP(String cameraIP) {
        CameraIP = normalizeIp(cameraIP);
    }

    public CameraSearchIP() {};
    public static CameraSearchIP ManageCamera() { return CameraSearchIP.savedata.thing; }
    protected static class savedata { private static final CameraSearchIP thing = new CameraSearchIP(); }

    /**
     * 获取一帧摄像头快照。
     *
     * <p>优先使用调用方传入的 IP；如果传入为空，则回退到当前缓存 IP。</p>
     *
     * @param IP 摄像头 IP，可为空
     * @return 成功时返回快照位图，失败时返回 {@code null}
     */
    public Bitmap getphoto(String IP) {
        String resolvedIp = resolveIp(IP);
        if (resolvedIp.isEmpty()) {
            return null;
        }
        CameraIP = resolvedIp;
        return httpClient.getphoto(resolvedIp);
    }

    /**
     * 发送一条摄像头控制命令。
     *
     * <p>优先使用调用方传入的 IP；如果传入为空，则回退到当前缓存 IP。</p>
     *
     * @param IP 摄像头 IP，可为空
     * @param command 控制命令编号
     * @param step 步进参数
     */
    public void getcommand(String IP, int command, int step) {
        String resolvedIp = resolveIp(IP);
        if (resolvedIp.isEmpty()) {
            return;
        }
        CameraIP = resolvedIp;
        httpClient.getcommand(resolvedIp, command, step);
    }

    /**
     * 通过 UDP 广播搜索摄像头 IP，并更新当前缓存 IP。
     *
     * @return 搜索成功时返回摄像头 IP，失败时返回当前缓存值或空字符串
     */
    public String send() {
        String discoveredIp = normalizeIp(udpSearchClient.send());
        if (!discoveredIp.isEmpty()) {
            CameraIP = discoveredIp;
        }
        return CameraIP;
    }

    private String resolveIp(String cameraIp) {
        String resolvedIp = normalizeIp(cameraIp);
        if (!resolvedIp.isEmpty()) {
            return resolvedIp;
        }
        return CameraIP;
    }

    private String normalizeIp(String cameraIp) {
        if (cameraIp == null) {
            return "";
        }
        return cameraIp.trim();
    }
}
