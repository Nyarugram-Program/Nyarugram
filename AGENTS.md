# Botgram 开发指引

本文件适用于客户端仓库；后端已移至同级 `../Botgram.Backend`，其开发要求见 `../Botgram.Backend/AGENTS.md`。用户在当前任务中的明确要求优先于本文。根据任务范围执行，不因本文重新索取已有授权，也不把普通代码或文档修改自动扩大为部署任务。

## 项目目标与结构

Botgram 是保留 Telegram Android 原页面、接入自建后端的客户端。后端应适配本仓库的 App 模型；允许为接入修改客户端底层通信及必要页面，不要另建一套聊天应用或自建服务入口。

- `TMessagesProj/`：原 Telegram Android 主模块。
- `TMessagesProj/src/main/java/org/telegram/botgram/`：认证、会话、TL HTTP 传输、GID、日志等接入代码。
- `TMessagesProj_AppStandalone/`：当前交付的 standalone APK。
- `../Botgram.Backend/`：同级独立 ASP.NET Core 后端仓库与集成测试。
- `Shared/botgram-m49.json` 与 `Shared/botgram-m49.manifest.json`：客户端自带的固定 M49 快照及版本/校验值；后端在自身仓库保存独立副本，构建互不读取对方目录。
- `Tools/`：构建、TL 验证及 Windows 部署脚本。
- `artifacts/`：交付产物，不能代替源代码。

开始相关修改前阅读 `Docs/Botgram.Android.md`、`Docs/Botgram.GID.md`、`Docs/Botgram.Chinese.md`；涉及服务运行时再阅读 `../Botgram.Backend/DEPLOYMENT.md`。以当前源代码和配置为准，不硬编码历史文档中的进程号、版本目录或局域网 IP。

## 客户端约束

- 保留原会话列表、聊天、个人资料及通话页面和控制器。新增必要 UI 时复用 Telegram 的 `BaseFragment` / `UniversalFragment`、列表、输入框、主题及弹窗组件。
- 不重新引入独立 Botgram Activity 或替代聊天页。登录成功继续使用原 `LoginActivity` 的认证完成流程。
- 当前通信是带 Bearer 认证的 TL 对象 HTTP RPC，不是 MTProto；不要声称换数据中心 IP 即可接入。
- 已绑定但过期的账号不得回落到 Telegram 原生网络；退出、重新认证及切换账号时隔离旧请求和回调。
- Bearer 令牌使用 Android Keystore 加密保存。网络、文件和反查定位等阻塞操作放在后台，UI 更新回到主线程；处理销毁、取消及迟到回调。
- TL JSON 的 `$type` 是完整 Java 类名，字段保持原 TL 名称；修改反射编码或混淆规则时验证真实 TL 二进制序列化。
- 未实现的原生 `phone.*`、附件、后台推送等功能不能宣称可用；独立 REST WebRTC 信令不代表原 Telegram 通话已接通。

## 当前产品约定

- 项目要求用户自行部署后端及数据库、TLS 代理、TURN/STUN 等配套服务，不提供公共服务器或托管账号服务。文档不得包含实际公网服务地址，不指定默认公共连接目标；地址示例使用私网地址，协议身份域名由部署者显式配置。开源依赖、数据来源和技术资料链接不属于服务入口。

- Settings 与旧版个人设置页恢复 Nyarugram Premium：默认未激活，点击开通并收到自身后端确认后免费激活；不要走 Telegram 支付。Money、Telegram Business、Help 和 Devices 仍隐藏，同时考虑搜索和深链接入口。
- Debug 区块保留“保存日志”“查看日志”及按账号保存的“大型 ClientHello”开启/关闭设置，版本号隐藏菜单仅保留两个日志入口，复用 Telegram UI。登录和注册页通过右上角齿轮平滑进入连接设置。日志导出使用系统文件选择器，预览最多读取末尾 256 KiB。
- 简体中文随 APK 离线打包。英文文案放在 `res/values/strings.xml`；自定义中文同时维护 `Tools/Import-BotgramChinese.ps1` 的本地翻译字典，再运行脚本生成 `values-zh/strings.xml`。不要只修改会被重新生成覆盖的中文文件。
- 用户可见名称为 Gram ID，显示格式为 `+区域代码 八位数字`，例如 `+030 12345678`；三位区域代码保留前导零，后八位不可全零。API、wire、内部类和资源键仍可用 GID，不重命名协议字段。
- 所有账号的发送礼物暂时禁用，点击使用原生弹窗提示“暂无可用的礼物”；在构造联网与礼物支付前拦截，不禁用普通订阅、充值、抽奖或共享礼物外观组件。Gram 收入只移除 Fragment 充值，保留收益、统计及提现。
- M49 使用地理大区、次区域和中间区域，手动选择共 29 项，不包含 World，也不包含国家代码。字母简称是应用展示简称，不是官方 M49 字母编码。
- 注册时请求近似定位，将反查的国家代码交给后端映射最具体区域；定位失败用 World `001`。不发送或存储经纬度，不用 SIM 或 IP 静默替代定位。
- GID 分配及碰撞校验由后端负责；不因修改 GID 更换账号 UUID、TL ID 或聊天关联。TL `phone` 字段仅承载兼容用的十一位 GID，不能将其拨号或写入系统电话簿。
- M49 编译和启动不自动访问网络。主动更新官方数据后，同步各仓库自带副本的快照版本和 `dataSha256`，运行 `Tools/Verify-BotgramM49.ps1`；不要重新引入客户端与后端之间的数据文件构建依赖。

## 构建与依赖

**新下载的 Gradle、SDK/NDK、源码依赖、包、工具及其缓存全部放在 D 盘。** 默认根目录为 `D:\BotgramBuild`。可以使用电脑上已有的 JDK，不要把新依赖下载到 C 盘或工作区。保留源码依赖的固定版本与校验信息。

原生依赖目录可能是指向 D 盘的 junction。编辑或清理前确认目标，不递归删除联接指向的依赖，也不要把它们当作普通空子模块重建。

从仓库根目录构建：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:JAVA_TOOL_OPTIONS = '-Djava.net.preferIPv4Stack=true'
./Tools/Build-Botgram.ps1
```

此 JDK 路径适用于当前电脑；其他环境先确认已安装路径。脚本使用 D 盘 Gradle 8.13，任务为 `:TMessagesProj_AppStandalone:assembleAfatDebug`，产物为 `TMessagesProj_AppStandalone/build/outputs/apk/afat/debug/Nyarugram-debug.apk`。需要准备依赖时使用 `Tools/Prepare-BotgramBuild.ps1`，不要对已有环境反复重新下载。

- 版本从 `gradle.properties` 读取。每次修改本仓库内容（包括代码、资源、脚本和文档）都必须同步更新 `APP_VERSION_NAME` 与严格递增的 `APP_VERSION_CODE`。默认递增版本名称末位修订号，用户指定版本时按其要求执行。APK 交付前必须检查最终产物的包名、版本名称和版本代码，并同步更新交付记录。
- 当前包名为 `me.nyarugram.messenger`，可与 Telegram 共存；不要未经任务要求更换包名或签名。
- 完成所有源代码修改后再构建，避免在编译过程中修改源文件。若发生这种情况，重新构建后再验证交付物。
- 遇到 Gradle 端口绑定失败先检查运行环境并做有限重试，不随意终止其他应用、关闭防火墙或修改系统网络设置。

## 验证与交付

按修改范围选择验证：文档修改检查内容与路径即可；客户端代码需编译相关交付变体；后端、协议或共享数据修改按后端指引执行相关回归。不为低影响文案修改新增镜像实现的测试。

后端测试生成样本且 Android Java 类编译完成后，从根目录验证原 TL 编码：

```powershell
./Tools/Verify-BotgramTL.ps1
./Tools/Verify-BotgramTL.ps1 -FixturePath ../Botgram.Backend/obj/gid-tl-fixtures.json
```

安装后的应用显示名称为 `Nyarugram`。交付 APK 时复制最终产物到 `artifacts/android/Nyarugram-debug.apk`，使用 D 盘 Android 构建工具验证签名、包名与版本，并检查本次新增资源。同步更新 `artifacts/build-info.json` 的实际哈希和验证信息，不填写未经执行的结果。

构建、后端集成测试和宿主 TL 验证不能代替真机测试；明确说明定位权限、页面操作、安装或端到端通信中尚未验证的部分。保持已有用户修改，避免无关格式化、依赖升级与生成文件改动。

## 本机服务与数据

已部署服务位于 `D:\Botgram\Backend`。读取 `deployment.json` 和 `process.json` 获取当前版本和监听地址。发布、升级和数据迁移遵循后端目录的部署要求；不要向正式数据库创建测试账号，也不要删除数据来解决启动问题。后台进程使用隐藏窗口启动，不提交或输出用户数据库、令牌、密码、私钥及真实服务凭据。
