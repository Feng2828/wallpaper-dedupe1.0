# 壁纸查重（Wallpaper Dedupe）

面向 **Redmi K60E / HyperOS 2.0.25.0** 的安卓原生 **Java** 壁纸查重工具。
整包由 GitHub Actions 云端编译，**本地不需要任何 Android 开发环境**。

> **最重要的一句话**：本应用**只读取图片、只生成一份 TXT 待删除清单**。
> 代码里没有任何删除文件的调用，也没有申请任何写入/删除用户文件的权限。
> 删不删、删哪张，完全由你在文件管理器里手动决定。

---

## 1. 功能一览

| 功能 | 实现方式 |
| --- | --- |
| 绕过 HyperOS 沙箱读取壁纸目录 | SAF `ACTION_OPEN_DOCUMENT_TREE` 目录树授权，`takePersistableUriPermission` 持久化，可同时授权多个目录 |
| 备选读取通道 | 「全部图片」按钮走 MediaStore，用于 SAF 进不去某些目录时兜底 |
| 识别重复图 | 64bit DCT 感知哈希（pHash）+ 差分哈希（dHash） |
| 识别压缩/缩放/锐化变体 | pHash 低频系数对 JPEG 重压缩、分辨率缩放天然稳健 |
| 识别调色变体 | 灰度均值/方差归一化后再算一遍 pHash + 归一化灰度向量 NCC；色彩直方图只给极低权重，不惩罚调色 |
| 识别裁剪变体 | 8 种「中心 90/80/70/60% + 去上/下/左/右」裁剪视图哈希做双向交叉匹配 + 3×3 分块 dHash 错位匹配 |
| 相似分组预览 | 纵向分组卡片 + 横向缩略图列表，异步加载 + LRU 缓存 |
| 勾选框 | 每张图独立勾选；提供「保留最佳·勾选其余」快捷按钮（只改勾选状态，不删文件）；默认**全不勾选** |
| 阈值调节 | SeekBar 60% ~ 98%，默认 82%；调整后点「仅重新分组」可复用已提取特征，无需重新解码图片 |
| 导出清单 | `ACTION_CREATE_DOCUMENT` 由你选择保存位置，同时在应用私有目录留一份备份，可复制全文到剪贴板 |

---

## 2. 项目文件树

```
wallpaper-dedupe/
├── .github/workflows/build.yml          # GitHub Actions 云端编译
├── settings.gradle                      # 仓库与插件版本
├── build.gradle                         # AGP 8.5.2
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew / gradlew.bat                # 精简版 wrapper 启动脚本
├── .gitignore
├── app/
│   ├── build.gradle                     # 模块配置（唯一依赖：RecyclerView）
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/workbuddy/wallpapergedupe/
│       │   ├── MainActivity.java        # 界面、SAF 授权、后台任务、清单导出
│       │   ├── core/
│       │   │   ├── ImageScanner.java        # SAF 递归遍历 + MediaStore 扫描
│       │   │   ├── FeatureExtractor.java    # 32×32 灰度 / pHash / dHash / 裁剪 / 分块
│       │   │   ├── SimilarityEngine.java    # 两级评分 + 并查集分组
│       │   │   └── ThumbnailLoader.java     # 缩略图异步加载 + LruCache
│       │   ├── model/
│       │   │   ├── ImageEntry.java
│       │   │   ├── Feature.java
│       │   │   └── DuplicateGroup.java
│       │   ├── ui/
│       │   │   ├── GroupAdapter.java
│       │   │   └── ThumbAdapter.java
│       │   └── util/ExportHelper.java       # 纯文本清单生成
│       └── res/ (layout / values / drawable)
└── README.md                            # 本文件
```

---

## 3. 依赖清单与版本对照

全部由云端 runner 自动准备，本地无需安装任何东西。

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| GitHub Actions runner | `ubuntu-latest` | 编译机 |
| JDK | Temurin **17** | AGP 8.x 强制要求 JDK 17 |
| Android Gradle Plugin | **8.5.2** | 构建插件 |
| Gradle | **8.7** | 构建工具（与 AGP 8.5.2 兼容） |
| `compileSdk` | **34**（Android 14） | 编译 SDK |
| `build-tools` | **34.0.0** | aapt2 / d8 |
| `minSdk` | **26**（Android 8.0） | 最低系统 |
| `targetSdk` | **34** | 刻意不选 35，避开 Android 15 强制边到边适配带来的界面问题 |
| Java 语言级别 | **17**（源码按 Java 8 语法书写） | `compileOptions` |
| 第三方库 | **仅** `androidx.recyclerview:recyclerview:1.3.2` | 分组列表；其余全部 Android 原生 API |
| 图像算法 | 自实现，**无 OpenCV / 无 NDK / 无 ML Kit** | 纯 Java，APK 体积小、无 so 库兼容问题 |

**为什么不用 OpenCV**：Android 版 OpenCV SDK 体积上百 MB，CI 下载与打包都麻烦；壁纸查重属于"整图感知相似"而非局部特征点匹配，pHash + 分块哈希 + 归一化 NCC 已经够用，而且纯 Java 在手机上更快、更省电。

**关于 `gradle-wrapper.jar`**：它是二进制文件，没有随源码提交（`.gitignore` 已排除）。workflow 会自动从 Gradle 官方仓库拉取；如果该地址不可用，会自动降级为下载完整 Gradle 8.7 发行版并直接调用其 `bin/gradle`。两条路都失败才会构建失败，这种情况下看 Actions 日志里的 `Prepare Gradle Wrapper` 步骤即可定位。

---

## 4. 权限说明

| 权限 | 声明位置 | 何时使用 | 能否省略 |
| --- | --- | --- | --- |
| **无任何权限**（SAF 目录树） | 不需要声明 | 主流程：`ACTION_OPEN_DOCUMENT_TREE` 由用户在系统文件选择器里主动授权，`takePersistableUriPermission` 后长期有效 | — |
| `READ_MEDIA_IMAGES` | Manifest | Android 13+（HyperOS 2 属于此类）点「全部图片」时动态申请 | 可以，不用该按钮就不申请 |
| `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`） | Manifest | Android 12 及以下点「全部图片」时动态申请 | 可以 |
| `INTERNET` | **未声明** | — | 应用完全离线，无法联网上传任何数据 |
| 写入 / 删除类权限 | **未声明** | — | 应用物理上不具备删除文件的能力 |

补充说明：

- Android 14 引入了「仅允许访问所选照片」（`READ_MEDIA_VISUAL_USER_SELECTED`）。如果授权时选了"仅选择部分照片"，MediaStore 只能看到你选中的那部分，属正常现象；此时改用 SAF 目录授权更可控。
- 导出清单时用的 `ACTION_CREATE_DOCUMENT` 会向**你选中的那个新文本文件**申请一次性写权限，这是系统级授权，不涉及任何已有图片。
- 应用私有副本写在 `Android/data/com.workbuddy.wallpapergedupe/files/reports/`，卸载应用即消失。

---

## 5. 云端编译（GitHub Actions）完整步骤

### 5.1 准备仓库

1. 登录 GitHub → 右上角 `+` → **New repository**。
2. 仓库名随意（如 `wallpaper-dedupe`），**Public 或 Private 都可以**（Private 仓库每月也有免费 Actions 额度）。
3. 不要勾选 "Add a README file"（避免冲突），直接 Create repository。
4. 在仓库首页点 **Add file → Upload files**，把本目录 `wallpaper-dedupe/` 里的**全部文件**拖进去。
   > 注意：`.github` 是隐藏目录，上传时务必一起带上；也可以直接用 Git 命令行：
   > ```bash
   > git init
   > git remote add origin https://github.com/<你的用户名>/wallpaper-dedupe.git
   > git add -A
   > git commit -m "init: wallpaper dedupe"
   > git branch -M main
   > git push -u origin main
   > ```
5. 确认分支名是 `main` 或 `master`（workflow 里两个都监听了）。

### 5.2 触发构建

推送代码后会自动触发一次。也可以手动触发：

1. 仓库页 → **Actions** 标签 → 左侧选 `Build Wallpaper Dedupe APK`。
2. 右侧 **Run workflow** → 选择分支 → 可选勾选 `同时生成 release 包` → 绿色 **Run workflow**。
3. 一般 3~8 分钟完成（首次要下载 Gradle 和 Android SDK，后续有缓存会更快）。

### 5.3 下载 APK

1. 进入本次 workflow run → 页面底部 **Artifacts** 区 → 点 `WallpaperDedupe-APK` 下载 zip。
2. 解压得到 `WallpaperDedupe-1.0.0-debug.apk`（只有 RecyclerView 一个依赖，体积通常不到 1 MB）。

**可选：自动生成 Release**
```bash
git tag v1.0.0
git push origin v1.0.0
```
推送 `v*` 标签时，workflow 会额外创建一个 GitHub Release 并把 APK 挂上去，手机浏览器可直接打开 Release 页下载，比 Artifact 更方便（Artifact 需要登录 GitHub 才能下载）。

> debug 包使用 AGP 自动生成的 debug 签名，**可以直接安装**，不需要自己配 keystore。
> 若你勾选了 `include_release`，`build.gradle` 里让 release 复用了同一个 debug 签名，同样可直接安装。

### 5.4 常见构建失败排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `SDK location not found` | `android-actions/setup-android@v3` 步骤被跳过，检查 yaml 是否被编辑器改坏；确认 `local.properties` 没有被上传（不该上传） |
| `gradle-wrapper.jar` 相关报错 | workflow 有自动降级；若两条路都失败，多为网络抖动，直接点 **Re-run all jobs** |
| `Dependency requires ... compileSdk 35` | 不要改动 `compileSdk 34` 与 `recyclerview:1.3.2` 的版本组合 |
| `Unsupported class file major version` | JDK 不是 17，检查 `setup-java` 步骤 |
| `Could not resolve com.android.tools.build:gradle:8.5.2` | `settings.gradle` 的 `pluginManagement` 里必须有 `google()` 仓库 |

---

## 6. 安装到 Redmi K60E（HyperOS 2）

### 方式 A：手机直接安装（最省事）

1. 把 APK 传到手机（微信文件传输助手 / QQ / USB 复制 / 手机浏览器打开 GitHub Release 页下载均可）。
2. 用系统「文件管理」找到 APK，点击安装。
3. HyperOS 会弹安全提示，按需处理：
   - **纯净模式**：`设置 → 应用设置 → 纯净模式` 关闭，或安装时选「仍要安装 / 知晓风险继续」。
   - **安装监控 / 禁止安装未知来源应用**：`设置 → 隐私保护 → 特殊权限设置 → 安装未知应用`，给「文件管理」或你用的浏览器打开允许。
   - 若提示「应用未通过小米安全检测」，选择「继续安装」即可（自用 debug 包没有上架检测，属正常）。
4. 安装完成后无需给任何权限即可使用 SAF 主流程。

### 方式 B：ADB 安装（电脑有 adb 时）

```bash
# 手机：设置 → 我的设备 → 全部参数 → 连点「OS 版本」7 次开启开发者选项
#       → 设置 → 更多设置 → 开发者选项 → 打开「USB 调试」和「USB 安装」
adb devices
adb install -r WallpaperDedupe-1.0.0-debug.apk
```
`USB 安装` 在 HyperOS 上可能需要插入 SIM 卡并登录小米账号才能开启；开不了就用方式 A。

### 方式 C：MIUI/HyperOS 的「应用包管理组件」被精简

极少数固件会禁用第三方安装器。可改用：
```bash
adb push WallpaperDedupe-1.0.0-debug.apk /data/local/tmp/
adb shell pm install -r /data/local/tmp/WallpaperDedupe-1.0.0-debug.apk
```

---

## 7. 使用教程

### 第 1 步：选择壁纸目录

打开应用 → 点 **「添加目录」** → 系统文件选择器里进入壁纸所在文件夹 → 点「使用此文件夹 / 允许访问」。

- 可以**重复添加多个目录**（例如 `Pictures/Wallpapers` + `Download/Wallpapers`）。
- 授权会持久化，重启应用/重启手机后仍然有效，除非你点「清空目录」或卸载应用。
- 这一步**不需要给应用任何系统权限**。

HyperOS 上常见壁纸位置（不同版本略有差异）：

| 路径 | 说明 |
| --- | --- |
| `内部存储/Pictures/Wallpapers/` | 主题商店 / 壁纸应用下载常见位置 |
| `内部存储/DCIM/Wallpaper/` | 部分版本 |
| `内部存储/Download/Wallpapers/` | 浏览器、第三方壁纸 App 下载 |
| `内部存储/MIUI/wallpaper/` | 部分 MIUI 版本 |
| `内部存储/Android/data/com.android.thememanager/...` | 主题壁纸缓存，**SAF 通常进不去，见下方说明** |
| `内部存储/Android/data/com.miui.miwallpaper/...` | 锁屏壁纸缓存，同上 |
| `/data/system/users/0/wallpaper*` | 当前系统锁屏/桌面壁纸，需 root，SAF 与 MediaStore 都拿不到 |

> ⚠ **HyperOS / Android 11+ 的硬限制**：系统文档选择器**不允许授权 `Android/data`、`Android/obb` 及其子目录**，这是 AOSP 的沙箱规则，不是本应用的缺陷，任何第三方 App 都绕不过。
>
> 推荐做法：用系统「文件管理」把 `Android/data` 里能复制出来的壁纸**导出到 `Pictures/Wallpapers`**，再用本应用选这个普通目录；或直接点 **「全部图片」** 走 MediaStore 扫描已被系统索引的图片。
> 若某些缓存文件既复制不出、也没被媒体库索引，就只能借助 root / 电脑端备份，本工具不做任何越权尝试。

### 第 2 步：调阈值

拖动滑块，范围 60%~98%，默认 **82%**。

| 阈值 | 适用场景 | 误报风险 |
| --- | --- | --- |
| 95–98% | 只找完全相同 / 仅重新压缩过的图 | 极低 |
| 88–94% | 重复 + 压缩 + 轻微调色 | 低 |
| **80–87%（默认 82%）** | 再加上明显调色、中心裁剪变体 | 中，需人工过一眼 |
| 60–79% | 宽松探索，找"看着像"的图 | 较高，建议只用来复核 |

**建议流程**：先用 82% 跑一遍看分组质量 → 若发现明显漏掉的同图，降到 75% 点「仅重新分组」；若发现把不相干的图凑成一组，升到 90% 再点「仅重新分组」。因为特征已缓存，重新分组**不会重新解码图片**，很快。

### 第 3 步：开始查重

点 **「开始查重」**。进度分三段：目录扫描 → 提取图像特征 → 两两比对分组。2500 张图最坏约 310 万对比较，中端机一般十几秒到一两分钟，期间按钮会变成「停止」，可随时中止。

> 单次最多扫描 2500 张（`ImageScanner.DEFAULT_MAX`）。壁纸特别多的话，建议按目录分批查，或改这个常量后重新在 Actions 构建。

### 第 4 步：查看分组并勾选

- 每张卡片是一组相似壁纸，横向可滑动查看缩略图。
- 标 **「★ 建议保留」** 的是该组里分辨率最高（其次文件最大）的一张，仅供参考。
- 点 **「保留最佳·勾选其余」** 会批量勾选该组除建议保留项外的所有图 —— **这只是修改勾选状态**，不会动文件。
- 也可以自己逐张勾选，甚至把"建议保留"项也勾上（清单里会额外标注提醒）。
- 如果某一组**全部**被勾选，导出时会弹警告，防止你误删到一张不剩。

### 第 5 步：生成待删除清单

点 **「生成待删除清单」** → 确认弹窗 → 选择保存位置（例如 `下载` 目录）→ 系统写入一个 `.txt`。

清单内容示例：

```
壁纸查重 · 待删除清单（人工核对用）
========================================
生成时间   : 2026-11-14 22:31:08
相似度阈值 : 82%
设备适配   : Redmi K60E / HyperOS 2

【重要声明】
本应用不具备删除文件的能力：未申请任何写入/删除权限，代码中不含任何删除调用。
...

【第 1 组】共 3 张 · 平均相似度 93.7%
  建议保留   : sunset_4k.jpg (1440×3200)
  保留项 URI : content://com.android.externalstorage.documents/tree/.../document/...
  1. sunset_compressed.jpg
     content URI      : content://.../document/...
     位置提示         : Pictures/Wallpapers/sunset_compressed.jpg  [docId=...]
     尺寸             : 1080×2400
     大小             : 1.42 MB
     与建议保留项相似度: 95.3%
...
合计：2 组 / 3 个待删除候选，约 4.18 MB 可回收空间（按文件大小估算）
```

同时会：
- 在 `Android/data/com.workbuddy.wallpapergedupe/files/reports/pending_delete_list.txt` 留一份备份（状态栏会显示路径）；
- 弹出预览，可点 **「复制全文」** 把清单放进剪贴板，粘到备忘录 / 发给自己。

### 第 6 步：人工核对并手动删除

拿清单去系统「文件管理」里按文件名 / 位置提示逐项确认，自己决定删不删。
**本应用到此为止，不参与删除。**

> SAF 规范不向第三方提供真实绝对路径，所以清单给的是 `content://` URI + `documentId` + 相对位置提示。这在 HyperOS 上是正常且唯一合规的做法。

---

## 8. 算法原理与已知局限

**特征向量**（单张约 4.2 KB，`Feature.java`）：
`pHash(整图)`、`pHash(归一化灰度)`、`pHash(水平镜像)`、`dHash(整图)`、`8 个裁剪视图 pHash`、`9 个 3×3 分块 dHash`、`1024 维归一化灰度向量`、`20 维 HSV 直方图`、`log(宽高比)`。

**评分**（`SimilarityEngine.java`）：
1. 粗筛：整图哈希 + 8 种裁剪交叉匹配，约 45 次 `Long.bitCount`，低于 `阈值-0.26` 直接淘汰（绝大多数图片对止步于此，这是 O(n²) 仍能跑得动的关键）。
2. 精细：分块最佳匹配、归一化互相关 NCC、宽高比、色彩直方图加权。
3. 若判定为"裁剪变体"，自动降低 NCC 权重、提高结构哈希权重（裁剪后像素不再对齐，硬算 NCC 会误杀）。
4. 相似度 ≥ 阈值的图片对用并查集连边，形成分组。

**已知局限**：
- 完全改变构图的"同一主题不同壁纸"不会被判为重复（这是设计意图，不是 bug）。
- 大面积纯色 / 极简渐变壁纸容易彼此相似，属于 pHash 类算法的固有弱点；这类建议把阈值提到 90% 以上。
- 旋转 90°、镜像以外的复杂几何变换不专门处理。
- HEIC/HEIF 能否解码取决于系统 `BitmapFactory` 支持情况；HyperOS 一般支持自家相机拍的 HEIC，不支持的会被自动跳过（不会崩溃）。
- 缩略图按 32×32 方形拉伸后计算，极端宽高比（如超长全景图）相似度会略降，`aspectLog` 项做了一定补偿。

---

## 9. 隐私与安全声明

1. **无 `INTERNET` 权限** —— 无法联网，图片特征、文件名、路径都不会离开手机。
2. **无写入/删除权限** —— 全工程没有 `DocumentsContract.deleteDocument`、`File.delete`、`ContentResolver.delete` 对图片的调用。
3. **默认不勾选** —— 每次分组结果出来时所有勾选框都是空的，不会替你做决定。
4. **清单只是文本** —— 导出只写你通过系统文件选择器新建的那个 `.txt` 和应用私有目录的备份。
5. 唯一持久化数据是「你授权过的目录 URI 列表」（存在 SharedPreferences），点「清空目录」即可清除。

---

## 10. 二次开发指北

| 想改什么 | 改哪里 |
| --- | --- |
| 扫描上限 | `ImageScanner.DEFAULT_MAX`（当前 2500） |
| 阈值范围 / 默认值 | `activity_main.xml` 的 `SeekBar max=38 progress=22` 与 `MainActivity.currentThreshold()`（当前 `60 + progress`） |
| 评分权重 | `SimilarityEngine.score()` 末尾两组系数组 |
| 裁剪变体种类 | `FeatureExtractor.CROPS` 与 `Feature.cropHashes` 数组长度（两处需同步） |
| 分块粒度 | `FeatureExtractor.tileRect()`、`gridHashes` 长度、`SimilarityEngine.gridSim()` |
| 清单字段 | `ExportHelper.report()` |
| APK 版本号 | `app/build.gradle` 的 `versionCode` / `versionName` |
| 添加 release 构建 | Actions 手动触发时勾选 `include_release`，或改 workflow 默认构建命令 |
| 换成自己的签名 | 在 `app/build.gradle` 增加 `signingConfigs` 并把密钥放进 GitHub Secrets（自用 debug 签名已足够） |

---

## 11. 快速检查清单（编译前自查）

- [ ] `.github/workflows/build.yml` 已随源码一起上传（隐藏目录容易漏）
- [ ] `app/src/main/AndroidManifest.xml`、`res/`、`java/` 目录层级没有被压缩工具拍平
- [ ] 仓库分支是 `main` 或 `master`
- [ ] Actions 页面能看到 `Build Wallpaper Dedupe APK`
- [ ] 构建日志末尾出现 `BUILD SUCCESSFUL`，Artifacts 区有 `WallpaperDedupe-APK`
- [ ] 手机安装时按需关闭纯净模式 / 允许未知来源

如有构建报错，把 Actions 里 `编译 APK` 步骤的完整日志贴出来即可定位。
