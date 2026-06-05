# Valve VTF 工具 (Valve VTF Tool)

一个用于在 Valve 纹理文件 (.vtf) 和 PNG 图像之间进行转换的 Android 应用程序。

## 项目架构

为了提高可集成性和模块化，项目已重构为多模块结构：

### 1. 核心库模块 (`:vtf-core`)
这是一个独立的 Android Library 模块，包含了所有的转换逻辑，可以轻松集成到其他 Android 项目中。
-   **原生核心 (C/JNI)**：位于 `src/main/cpp/`，包含 `vtf_tool.c`、`libpng`、`stb_dxt` 等。
-   **Java/Kotlin 接口**：位于 `zzh.bin.valvevtftool.VtfLib`，提供跨语言调用的标准 API 和格式常量。
-   **导出能力**：编译后可生成 `.aar` 文件供第三方使用。

### 2. 用户界面模块 (`:app`)
-   **技术栈**：Jetpack Compose。
-   **职责**：负责 UI 交互、SAF 文件访问权限管理、异步任务调度，并依赖 `:vtf-core` 执行实际转换。

---

## 如何集成到你的项目

1.  将 `vtf-core` 文件夹复制到你的项目根目录。
2.  在 `settings.gradle.kts` 中添加：
    ```kotlin
    include(":vtf-core")
    ```
3.  在你的模块 `build.gradle.kts` 中添加依赖：
    ```kotlin
    implementation(project(":vtf-core"))
    ```
4.  直接使用 `VtfLib` 调用相关转换功能。

---

## 原生转换核心 (`vtf_tool.c`) 深度分析

核心逻辑位于 `vtf-core/src/main/cpp/vtf_tool.c`。以下是对其技术实现点的详细解析：

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

#### B. DXT 压缩与编码 (BC1/BC2/BC3)
实现了高性能的块状编解码逻辑：
-   **解码**：自主识别并解码 DXT1, DXT3, DXT5 格式。
-   **编码 (自助切换)**：集成 `stb_dxt` 库，支持在转换 PNG 到 VTF 时自主选择压缩模式：
    -   **RGBA8888**：高保真未压缩格式。
    -   **DXT1 (BC1)**：适用于不带透明通道或只有一位透明通道的图像。
    -   **DXT3 (BC2)**：适用于具有锐利透明度变化的图像（显式 Alpha）。
    -   **DXT5 (BC3)**：适用于具有平滑透明度梯度的图像（插值 Alpha）。
-   **插值算法**：通过 `decode_dxt_colors` 函数实现，根据块中的两个参考颜色 (C0, C1) 线性插值出中间颜色。

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
