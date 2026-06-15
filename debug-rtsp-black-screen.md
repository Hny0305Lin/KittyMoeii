# RTSP Intermittent Black Screen Debug

## Symptom
- 真机上 RTSP 偶发始终黑屏。
- 停留一会儿或重启 App 后，RTSP 又可能恢复为连续播放。

## Hypotheses
- H1: `MainActivity` 的 RTSP 轮询线程在已经播放时仍然重复重建会话，导致黑屏来自应用自身的会话抖动。
- H2: `startRtspSession()` 与 `stopRtspSession()` 都异步投递到主线程，偶发时序下旧会话释放晚于新会话启动，导致新会话被误拆。
- H3: `LibVLC` 已建立播放会话，但 `Vout` 建立或视图绑定不稳定，导致播放器状态正常而界面保持黑屏。

## Instrumentation Plan
- 给每次 RTSP 启动分配 `sessionId`。
- 记录后台轮询线程每次决定“启动/继续/停止”的原因。
- 记录主线程真正执行 `start` / `release` 的先后顺序。
- 记录 `LibVLC` 事件对应的 `sessionId` 和当前 RTSP 地址。

## Findings
- H1 confirmed: 现有 `startRtspPreview()` 会在每轮循环重新执行 `startRtspSession()`，即使上一轮会话已经健康播放。
- 证据：日志显示 `sessionId=5` 失败后，后台线程继续准备 `sessionId=6`；而旧逻辑在成功场景下也会在下一轮继续重建会话，只是之前未显式打印。
- 影响：应用会周期性主动 `release` 当前 `LibVLC` 会话，导致偶发黑屏、重新握手、以及“停一会或重启后恢复”的表象。

## Fix
- 已将 RTSP 轮询改为“健康会话保活，不健康会话重建”。
- 新逻辑只在以下情况重建 RTSP：
  - 首次尚未启动会话
  - 播放器报错
  - 启动超时后仍未进入健康状态
