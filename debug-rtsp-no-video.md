# Debug Session: rtsp-no-video [OPEN]

## Symptom
- RTSP 在模拟器和真机上都无法稳定出画面。
- 用户补充说明：之前测试时小车 WiFi 断开，当前需要重新确认恢复联网后的 RTSP 行为。
- 最新现象：真机端已经可以出画面，但画面不会继续更新，疑似播放冻结。

## Expected
- 在小车 WiFi 恢复后，RTSP 可以重新建立会话并正常显示视频。

## Hypotheses
- H1: 小车 WiFi 恢复后，Android 端仍在使用失效的网络状态或旧播放器会话，导致未重新建立有效拉流。
- H2: LibVLC 已建立 RTSP 会话，但 `VLCVideoLayout` 的视图绑定或 `vout` 生命周期异常，导致有流无画面。
- H3: 摄像头 RTP/UDP 已开始发送，但 Android 当前网络环境无法正确接收返回的 UDP 媒体包。
- H4: 摄像头音视频双轨协商成功，但音频轨或缓存策略导致播放状态卡在缓冲阶段。
- H5: 当前应用缺少足够的运行时证据，无法区分“未收到首帧”和“已收到首帧但未渲染”。
- H6: 当前 RTSP 会话虽然成功起播，但播放时间轴不再推进，属于播放器冻结而非网络断开。
- H7: 嵌入式设备持续闪烁说明数据仍在发送，冻结更可能发生在 Android 解码或渲染层。

## Evidence
- 2026-06-15 13:01 左右日志显示 `OPTIONS -> DESCRIBE -> SETUP -> PLAY` 全部成功，说明鉴权、SDP 协商与 H264 轨识别正常。
- 运行时日志出现 `Unable to determine our source address`，说明部分环境下 RTP/UDP 回流存在问题。
- 2026-06-15 13:09 左右日志显示 `Connection to server failed: Connection refused`，说明断网恢复后存在 RTSP 控制连接被拒绝的情况，旧会话不能自恢复。
- 2026-06-15 13:17 左右日志显示 `Password in a URI is DEPRECATED`，说明当前认证方式仍依赖 `rtsp://user:pass@host` 形式，需改为 LibVLC 媒体选项。
- 2026-06-15 13:22 左右日志显示 `NetworkOnMainThreadException`，根因为调试用端口预检在主线程执行了 `Socket.connect()`。
- 2026-06-15 13:26 左右日志显示 `PLAYING` 后频繁回到 `BUFFERING`，并且 `RTSP 心跳` 中 `time=0, position=0.0` 持续不前进，说明会话已建立但直播缓存无法稳定推进时间轴。

## Instrumentation
- 已添加：网络状态、RTSP 地址、VLC 事件名、播放状态、视频输出状态、视图附着状态。

## Fix
- 已实施最小修复：RTSP 模式改为后台自动重连，连接失败或断网恢复后会释放旧的 LibVLC 会话并重新建立播放会话。
- 已进一步修复：RTSP 认证改为 `:rtsp-user` / `:rtsp-pwd` 选项传入，不再把密码放进 URI。
- 已修复调试引入的问题：RTSP 端口预检改为后台线程执行，避免主线程网络访问导致的崩溃。
- 已进一步修复冻结问题：提高直播缓存、关闭音频轨、移除过于激进的低延迟帧策略，优先换取 UDP RTSP 的连续稳定播放。
