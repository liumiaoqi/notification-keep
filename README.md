# 通知留存（NK）

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
![](https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square)
![](https://img.shields.io/github/v/release/liumiaoqi/notification-keep?style=flat-square)

把 Android 通知留存成**可以离线核对**的证据文件。

系统在通知被读过之后就会清掉它，截图又只能证明"我屏幕上曾经出现过这么一张图"。
这个工具做的是另一件事：把通知按时间记进本机数据库，用哈希链串起来，导出一份别人可以独立复核的文件。

## 它能做什么

- **通知留存**：持续记录本机收到的通知（来源应用、标题、正文、时间、撤回事件），写入本机数据库。
- **完整性自检**：每条记录算一个 SHA-256 指纹，并把前一条的指纹掺进来；改动任意一条，后续全部对不上。
- **断档显式化**：区分"确定没记录到"和"不确定"，把未知时段标出来，不写成无事发生。
- **清空留痕**：清空记录时留下时间、条数与当时的链尾，动作本身可查。
- **证据包导出**：导出数据文件与压缩包，包内含只依赖 Python 标准库的离线核对器。
- **离线核对**：接收方不需要联网、不需要安装本软件，一条命令逐项核对条数、链、签名与出处证明。
- **使用前认证**：打开、导出、清空三处都要求完成一次系统身份验证；签名密钥由设备安全硬件背书。
- **零网络权限**：不申请网络权限，数据在物理上无法由本软件外传。

## 它不做什么

这些边界写在软件里，也写在这里：

- **不证明通知内容本身真实**。内容由发送方与第三方应用提供，别人也能伪造一条通知。
- **时间取自设备时钟**。它能说明记录之间的先后与间隔，但单独说明不了对应外界的哪一刻；要固定时间需要一次外部锚定（例如导出后发一份到自己邮箱，用邮件服务商的收件时间作锚）。
- **不能阻止有设备操作权的人清空**。它只能保证清空会留痕。
- **不替代司法鉴定**。它提供的是可供第三方复核的数据结构，是否采信由使用场景决定。

## 安装

到 [Releases](https://github.com/liumiaoqi/notification-keep/releases) 下载 APK。

装好后需要在系统设置里授予**通知使用权**（不同厂商的路径可能叫"特殊应用权限"或"通知使用权"），否则收不到通知。建议同时设置锁屏密码或指纹，"使用前认证"那一层依赖它。

要求 Android 12（API 31）及以上。

## 构建

需要 JDK 25、Android SDK（compileSdk 37）。

```bash
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 权限

| 权限 | 为什么需要 |
| --- | --- |
| 通知使用权（特殊权限） | 读取通知内容，否则无从留存 |
| 设备凭据 / 生物识别 | "使用前认证"；软件不接触也不保存凭据本身 |
| 网络 | **不申请** |
| 存储 | **不申请**（导出走系统分享面板与文件选择器） |

## 隐私

记录只存在于本机应用私有目录。导出的文件由你自己决定发给谁；发送动作走系统分享面板，由你选定的应用完成传输。
崩溃与无响应日志是独立文件，同样只在本机，不参与哈希链，也不进证据包。

## 许可

[Apache License 2.0](LICENSE)。第三方组件与许可见 [NOTICE](NOTICE)。

## 支持

这个工具如果对你有用，可以 [请我喝杯奶茶](https://afdian.com/a/liumiaoqi)。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
