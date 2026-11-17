# 双摄 DualCam

给 **Pixel 10 Pro XL** 用的前后摄像头同时拍摄应用，对标 iPhone Dual Capture：

- **照片**：前后摄像头同时快门，相册里各存一张
- **录像**：两路画面合成一条视频（画中画 / 上下 / 左右）
- **后置镜头**：按系统并发组合自动提供 0.5× / 1× / 5×
- **互换主次画面**：点画中画小窗或右下角切换按钮

系统并发相机在 Android 上通常限制在 720p～1440p。这是 Pixel 的硬件/HAL 限制，不是应用人为降画质。

## 安装

用 Android Studio 打开 `DualCam`，连上 Pixel 10 Pro XL，运行 `app`。

或命令行：

```bash
cd DualCam
./gradlew :app:installDebug
```

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

首次打开请允许 **相机** 和 **麦克风**。照片和视频都写入 `DCIM/DualCam`。

## 用法

1. 选 **照片** 或 **录像**
2. 选构图：画中画、上下、左右
3. 需要的话切换后置 0.5× / 1× / 5×
4. 点快门。录像时再点一次结束

合拍录像时可以随时改构图、互换主次画面和切换后置镜头。设置里选择「分别保存」时，录像期间只能数码变焦，不能切换物理镜头。

## 实现

CameraX 1.5 Concurrent Camera：

- 照片：两路 Preview + ImageCapture，拍完在本地合成一张 JPEG。「高清」会向 HAL 申请更高的采集分辨率，但并发模式下实际尺寸由系统决定
- 合拍录像：两路 Preview + ImageAnalysis（RGBA），帧直接上传为 GL 纹理，旋转、镜像、裁切和画中画圆角全部在 GPU 着色器完成，渲染进 MediaCodec 输入 Surface（H.264 30fps + AAC），MediaMuxer 写 MP4
- 分别保存录像：两路 Preview + ImageCapture + VideoCapture，各写一个 MP4
