# ⚠️ 这里是源码

## 如果你只是想用这个 App，请不要下载这个文件夹

**直接去下 APK：**

```
apk/kanju-5.3.1.apk
```

装到手机上就能用，不需要 JDK、不需要 Android SDK、不需要懂 Java。

**这个 `src/` 文件夹是给想研究、想改、想自己构建的人看的。**
里面是一堆 `.java` 和 `.xml`，你没有编译环境的话，下载了也打不开、更用不了。

---

## 这是什么

「看剧」安卓 App 的完整源码。不依赖 Gradle、不依赖 Android Studio，用 JDK + Android SDK 的几个命令行工具直接编译出 APK。

之所以走手工链，是因为整个 App 只有 **275 KB**（快 3 万行代码 + 一个 hls.js），用 Gradle 构建出来的空壳都比它大。

## 目录结构

```
src/
├── AndroidManifest.xml            清单：权限、组件、SDK 版本
├── assets/
│   ├── index.html                 全部界面（HTML/CSS/JS，单文件，约 117 KB）
│   └── hls.min.js                 播放内核 hls.js（第三方，Apache-2.0）
├── res/
│   ├── mipmap-xxhdpi/ic_launcher.png   桌面图标
│   ├── values/strings.xml         应用名「看剧」
│   └── xml/kx_a11y.xml            无障碍服务配置
└── java/com/dfy/kx/               21 个 Java 文件，见下表
```

## Java 文件职责

### 核心服务

| 文件 | 干什么 |
|---|---|
| `CoreServer.java` | **最核心的一个**（64 KB）。跑在 App 进程里的 HTTP 服务器，监听 `127.0.0.1:8686`。所有接口都在这里：搜索、分类、详情、图片代理、分片代理、投屏中转、下载管理、设置读写 |
| `Sources.java` | 采集源管理。接口调用、返回解析、多源并发聚合、同名片子合并、健康检查（连续失败的源自动标灰） |
| `Cats.java` | 分类归一。各源分类名不一样（「记录片」/「纪录片」、「动画片」/「动漫」），统一映射到 App 的 34 个分类项 |
| `Net.java` | 网络工具。自己封的 HTTP 客户端，带超时、重定向、中断感知（线程被 cancel 时不记成源故障） |
| `Proxy.java` | 分片代理与缓存。m3u8 改写、分片转发（带 Referer 绕防盗链）、磁盘缓存 + 有上限的内存缓存 |
| `DiskCache.java` | 磁盘缓存。目录管理、过期清理、大小统计 |
| `Douban.java` | 豆瓣榜单。8 个榜单的抓取和解析，各榜海报字段名不同（`cover_url` / `cover` / `pic`），做了兼容 |

### 界面与交互

| 文件 | 干什么 |
|---|---|
| `MainActivity.java` | 主界面（39 KB）。WebView 容器、JS 桥接、返回键分级逻辑、权限申请、横屏处理、启动流程 |
| `DomDump.java` | 把 WebView 里的 DOM 导成「可点击元素 + 屏幕坐标」的文本，给自动化测试和无障碍用 |
| `KxAccessibility.java` | 无障碍服务。让外部程序能读到界面元素、点击元素（方便别人接手调试） |

### 投屏

| 文件 | 干什么 |
|---|---|
| `Cast.java` | DLNA 协议实现。SSDP 发现设备、拉设备描述、SOAP 发指令（Play/Pause/Seek/SetVolume/换集）、心跳、时长兜底 |
| `CastCtl.java` | 投屏会话管理。状态机、seek 保护窗口（防止电视回报旧位置把刚设的值盖回去）、自动重发 |
| `CastService.java` | 投屏前台服务。保活用的常驻通知，否则 App 退到后台会被系统掐断 |
| `CastOverlay.java` | 悬浮遥控窗。系统级悬浮窗，在其他应用上面也能控制电视 |

### 下载

| 文件 | 干什么 |
|---|---|
| `DlService.java` | 离线下载前台服务。8 路并发拉分片、进度回写、任务恢复 |
| `DlStore.java` | 下载任务持久化。任务列表存磁盘，App 重启后能接着下 |

### 其他

| 文件 | 干什么 |
|---|---|
| `Device.java` | 设备信息（型号、屏幕、WebView 版本），下载页展示用 |
| `Log.java` | 日志。写文件 + 内存环形缓冲，供 `/api/log` 读取 |

## 一处说明

`assets/` 里原本有个 `loading.jpg`（播放加载页的背景图），已经从源码中移除——那是作者个人的图片，不适合随开源包分发。`index.html` 里已经不再引用它，**不影响构建**。加载页现在是纯色 + 转圈 + 「正在加载…」。

## 怎么编译

见 [BUILD.md](BUILD.md)。

## 许可

MIT-0（只要是人就能用，不用署名，出了事别找我）。

但 `assets/hls.min.js` 是第三方库，Apache-2.0，保留其原始声明。

---

**再说一次：只想用的话，下载 `apk/kanju-5.3.1.apk`，别下这个文件夹。**
