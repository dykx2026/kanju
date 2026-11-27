# 怎么编译

不用 Gradle，不用 Android Studio。四个命令行工具串起来就行。

## 一、需要装的东西

| 项 | 版本 | 说明 |
|---|---|---|
| **JDK** | **17**（必须） | `d8.bat` 跑在 Java 17 上。JDK 8/11 会报错，JDK 21 我没测过 |
| **Android SDK Build-Tools** | 34.x | 只要 4 个可执行文件：`aapt2`、`d8.bat`、`zipalign`、`apksigner.bat` |
| **android.jar** | **API 34** | 编译期的 Android 类库，`platforms/android-34/android.jar` |

Build-Tools 和 android.jar 都能用 sdkmanager 单独下，不必装整个 SDK：

```
sdkmanager "build-tools;34.0.0" "platforms;android-34"
```

## 二、环境变量

改 `build.bat` 开头这几行，指向你自己的路径：

```bat
set JDK=D:\Apps\_apkbuild\jdk-17.0.13+11
set BT=D:\Apps\_apkbuild\android-14
set ANDROID_JAR=D:\Apps\_apkbuild\android-34\android.jar
```

`BT` 指的是含 `aapt2.exe` / `d8.bat` / `zipalign.exe` / `apksigner.bat` 的那个目录。

## 三、签名密钥

自己生成一个，别用我的：

```bat
keytool -genkeypair -v -keystore my.keystore -alias mykey ^
  -keyalg RSA -keysize 2048 -validity 10000 ^
  -storepass 你的密码 -keypass 你的密码 ^
  -dname "CN=Someone, OU=Dev, O=Home, L=City, S=State, C=CN"
```

然后把 `build.bat` 里 `apksigner` 那行的路径和密码改掉。

## 四、八步构建

`build.bat` 干的就是这八件事，可以照着手敲：

```
[1/8] aapt2 compile
      aapt2 compile --dir src\res -o out\res.zip
      把 res/ 下的资源编译成扁平包

[2/8] aapt2 link
      aapt2 link -o out\unsigned.apk -I android.jar
        --manifest src\AndroidManifest.xml
        -R out\res.zip
        -A src\assets
        --min-sdk-version 24 --target-sdk-version 34
        --auto-add-overlay
      生成「只有资源和清单、没有代码」的 APK
      -A 是把 assets/ 原样塞进去的关键

[3/8] javac
      javac -source 8 -target 8 -encoding UTF-8 -nowarn
        -bootclasspath android.jar -cp android.jar
        -d out\classes @srcs.txt
      ★ 必须 -source 8 -target 8：用 Java 11+ 的字节码特性，安卓上会崩
      ★ 必须 -bootclasspath 指 android.jar：否则 javac 会去用 JDK 自带的
        java.* 类，编出来的东西在手机上找不到方法

[4/8] jar
      jar cf out\classes.jar -C out\classes .

[5/8] d8
      d8.bat --lib android.jar --min-api 24 --release
        --output out\dex out\classes.jar
      .class 转 .dex（安卓的字节码格式）

[6/8] 塞 dex 进 APK
      python adddex.py out\unsigned.apk out\dex\classes.dex out\withdex.apk
      ★ 这一步 Gradle 会自动做，手工链得自己来：
        aapt2 生成的 APK 里没有 classes.dex，得解开、加进去、重新打包
        adddex.py 就是干这个的（约 40 行，用 zipfile 操作）

[7/8] zipalign
      zipalign.exe -f -p 4 out\withdex.apk out\aligned.apk
      -p 4 是按 4 页对齐，安卓 15+ 要求 16KB 页对齐时更严格，
      4 是向下兼容的安全值

[8/8] apksigner
      apksigner.bat sign --ks my.keystore --ks-pass pass:xxx ^
        --key-pass pass:xxx --out kanju.apk out\aligned.apk
      apksigner.bat verify kanju.apk
```

跑完输出 `BUILD_OK` 就成了。

## 五、几个坑

**1. `aapt2 link` 不打包 assets 的话，装上去是白屏**

必须带 `-A src\assets`。漏了的话 APK 装得上、App 起得来、但 WebView 加载不到 `index.html`，一片纯白，logcat 里只有一句 `ERR_FILE_NOT_FOUND`。

**2. `javac` 不加 `-bootclasspath` 会在真机上 `NoSuchMethodError`**

看起来能编译、能装、能启动，一调到某个方法就崩。因为 javac 用了 JDK 自带的 `java.*` 实现，而安卓的 `libcore` 里没有那些方法。

**3. `d8` 不加 `--lib android.jar` 会报「缺类」**

尤其是用了 `java.util.function`、`java.time` 这些，d8 需要知道这些类在目标平台存不存在。

**4. 改了 Java 代码一定要重新跑 [3/8]~[6/8]**

只跑 [1/8][2/8] 的话，改的代码不会进 APK，你会以为是改动没生效。

**5. 改 `assets/index.html` 只需要重跑 [1/8][2/8] 加上后面几步**

HTML 算资源，不用重新 javac。不过 `build.bat` 是全量跑的，几秒的事，别折腾增量。

**6. 中文字符串乱码**

`build.bat` 第二行必须 `chcp 65001`，并且 `javac` 加 `-encoding UTF-8`。少一个，界面上的中文全是问号。

## 六、构建出来多大

```
APK        275 KB
dex        ~240 KB
hls.min.js  414 KB（压缩后约 120 KB）
index.html  117 KB（压缩后约 30 KB）
```

体积能这么小，是因为没有 AndroidX、没有 Kotlin 运行时、没有图片资源、没有多语言。所有界面都是那一个 `index.html`。

## 七、想改界面不想碰 Java

改 `assets/index.html` 就行。里面是纯 HTML/CSS/JS，顶部有配色变量：

```js
var C = {
  bg:'#171A21', card:'#1B2838', accent:'#66C0F4',
  text:'#C7D5E0', title:'#FFFFFF', sub:'#8F98A0'
};
```

改完重跑构建。不用懂 Java。

## 八、想做成「零源版」

`Sources.java` 第 49~61 行是 7 个内置源的 `ALL.add(...)`，全部删掉或注释掉，同时把 `BUILTIN` 改成 `0`。

这就是仓库里那个发布版 APK 的做法——包本身不带源，用户自己往设置里加。

## 九、调试

App 里跑着一个 HTTP 服务器（`127.0.0.1:8686`），所有功能都有对应接口，可以直接用浏览器或 curl 调：

```
http://127.0.0.1:8686/api/stat          运行状态（源健康、缓存、投屏、下载）
http://127.0.0.1:8686/api/search?kw=xxx  搜索
http://127.0.0.1:8686/api/cats           分类列表
http://127.0.0.1:8686/api/list?cat=2&pg=1 分类内容
```

配合 `adb forward tcp:8686 tcp:8686` 就能在电脑上调。

要看 WebView 里的状态，用 `chrome://inspect`（需要 App 里开了调试开关），或者用 `DomDump.java` 把 DOM 导出来。
