# Valve VTF 工具 (Valve VTF Tool)

一个用于在 Valve 纹理文件 (.vtf) 和 PNG 图像之间进行转换的 Android 应用程序。

## 项目架构

该项目分为三个主要层级：

1.  **原生转换核心 (C/JNI)**：负责底层图像解析和格式转换。
2.  **Kotlin 库包装器**：提供简单的 API 桥梁。
3.  **用户界面 (Jetpack Compose)**：提供文件选择和异步处理逻辑。

---

## 原生转换核心 (`vtf_tool.c`) 深度分析

核心逻辑位于 `app/src/main/jni/vtf_tool.c`。以下是对其技术实现点的详细解析：

### 1. 内存与文件处理
-   **高效读取 (`mmap`)**：程序使用 `mmap` 系统调用将 VTF 文件映射到内存地址空间。相比于传统的 `read()`，这在处理大文件时能减少内核态与用户态之间的数据拷贝，并允许通过指针直接访问文件内容。
-   **内存对齐与结构体**：使用 `#pragma pack(1)` 确保 `vtf_header_t` 结构体在内存中是紧凑对齐的，这对于直接将文件字节流映射到 C 结构体至关重要。

### 2. VTF 格式解析与资源定位
-   **多版本支持**：解析逻辑支持 VTF 7.x 版本。
-   **资源发现机制**：对于 7.3 及以上版本，VTF 引入了资源列表（Resources）。代码实现了一个边界检查的搜索算法：
    -   遍历 `numResources` 个条目。
    -   识别标记为 `0x30`（通常是图像数据）的资源标签。
    -   精确定位图像数据在文件中的偏移量（Offset），从而兼容不同版本的元数据差异。

### 3. 图像格式解码算法
核心解码器支持多种压缩与非压缩格式：

#### A. 非压缩格式 (`decode_rgba`)
支持像素分量重排（Swizzling），能够处理以下排列：
-   **RGBA8888 / ARGB8888**
-   **ABGR8888 / BGRA8888**
-   **RGB888 / BGR888**

#### B. DXT 压缩解码 (BC1/BC2/BC3)
实现了高性能的块状解码逻辑：
-   **插值算法**：通过 `decode_dxt_colors` 函数实现，根据块中的两个参考颜色 (C0, C1) 线性插值出中间颜色。
-   **DXT1 (BC1)**：处理 4x4 块，每个块使用 64 位数据。
-   **DXT3 (BC2)**：增加 64 位明确的 Alpha 通道信息（每个像素 4 位）。
-   **DXT5 (BC3)**：使用复杂的 8 级 Alpha 插值算法，根据两个参考 Alpha 值计算出平滑透明度。

### 4. PNG 交互逻辑
-   **写入 (VTF -> PNG)**：通过 `libpng` 的结构化接口（`png_structp`, `png_infop`）进行细粒度控制，支持 8 位深度和 RGBA 颜色类型。
-   **读取 (PNG -> VTF)**：利用 `libpng` 的简化 API (`png_image`) 快速读取 PNG 内容，并将其转换为统一的 RGBA 格式以便封装进 VTF。

### 5. 日志与调试系统
-   **双重日志**：`write_log` 函数不仅调用 Android 的 `__android_log_print`（Logcat），还会将日志同步写入到 SD 卡上的本地文件中（`/sdcard/Android/data/zzh.bin.valvevtftool/files/vtf_tool.log`），方便在脱离调试器的情况下排查转换错误。

---

## 工作流程

1.  **文件预热**：APP 将 SAF (Storage Access Framework) 获取的 URI 文件复制到内部缓存，以获得原生代码所需的物理路径。
2.  **调用 JNI**：`VtfLib.kt` 调用 `vtfToPng` 或 `pngToVtf`。
3.  **核心处理**：`vtf_tool.c` 执行内存映射、头部校验、资源定位、解码并调用 `libpng` 生成目标文件。
4.  **结果导出**：转换完成后，生成的缓存文件被移回用户指定的输出目录。
