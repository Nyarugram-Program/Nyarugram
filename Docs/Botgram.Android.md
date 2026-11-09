# 原 Telegram 页面接入说明

## 2026-11-09 大型 ClientHello 的会话传输

登录及注册页右上角齿轮打开原生“连接设置”页面，使用 Telegram 的页面过渡动画，内含大型 ClientHello 开关。当前设置页及旧个人设置页的调试区均显示“大型 ClientHello”和开启/关闭状态；修改按账号保存，刷新后续请求使用的 TLS 配置并重连实时通道。

登录或注册时选择的“大型 ClientHello”随账号会话保存，后续认证、消息 RPC、媒体传输及实时 WSS 连接使用同一选项，重新启动后仍有效。API 29 以下不启用。旧版本未保存该字段的 HTTPS 会话在 API 29 及以上默认启用，以兼容需要大型握手的部署；重新登录后按本次勾选状态保存。各账号的设置隔离，退出时清除。

填充通过合法 ALPN 项使 ClientHello TLS 记录达到至少 1500 字节，每项协议名最多 127 字节，兼容 Android Conscrypt 在证书校验时读取 SSLParameters 的长度解析。证书链和主机名继续由系统验证。

## 2026-11-08 媒体发送与相册（批次 I）

原聊天页的拍照、相册与文件发送已接入自建后端：客户端把 `upload.saveFilePart` / `upload.saveBigFilePart` 分片发到 `PUT /api/v1/media/{client_file_id}/{part}`，下载 `upload.getFile` 按 offset/limit 取 `GET /api/v1/media/{media_id}`，二者与 `/api/v1/rpc` 使用同一 Bearer 会话但走独立二进制协议，上传和下载各自使用独立线程池，不阻塞聊天 RPC。`messages.sendMedia` / `sendMultiMedia` / `uploadMedia` 映射为明确字段，相册 2–10 项共享一个 `grouped_id`；响应生成 `TL_photo` / `TL_document`（id、mime、大小、尺寸、时长、文件名）供原页面渲染。媒体作为明文内容同样经过 E2EE 准入，加密会话拒绝。

服务端 schema v11 使用 `media_files` / `media_parts`（有界 bytea 分块）/ `media_shares`（按会话授权下载）；未完成或取消的上传对读取入口不可见，维护任务清理 24 小时前的暂存。`messages.getExtendedMedia` 已映射但固定 501 `BOTGRAM_EXTENDED_MEDIA_UNSUPPORTED`，本产品没有付费媒体。下载与媒体复用按仍对本人可见的消息引用授权（schema v12 `media_messages`）：消息被全局删除或本人隐藏后不再提供字节；转发会保留附件与相册分组；照片宽高在上传完成时从图片头解析。真实上传、相册与视频播放尚未在设备上验证。

## 2026-11-08 messages 基础接口批次（26 项）

209 项 messages 请求的第一批 26 项已接入 typed Protobuf（tag 144–169）与 PostgreSQL schema v10：删除（自见/双方）、清除历史（保留或隐藏会话）、收藏夹清除、编辑与可编辑性检查、转发（保留来源或去来源）、输入状态、草稿（保存/全部读取/清空）、消息置顶与全部取消、会话置顶与排序、未读标记与标记列表、内容已读、群在线人数、共同群组、提示条隐藏、举报、浏览次数、私聊已读时间、会话内搜索与搜索计数、通话记录清除边界。原有 `ChatActivity`、会话列表、共享媒体页与 `MessagesController`/`SendMessagesHelper` 未替换。

删除改为标记：`deleted_messages`（仅本人）与 `message_deletions`（会话级），历史、搜索、会话最近消息、未读数和差分重放统一过滤，已删内容不会从其他入口重新出现；清除历史用 `hidden_through` 隐藏会话而不删除成员关系。输入状态事件 6 秒过期，不作为永久历史重放；草稿只对本人同步。媒体类搜索 filter 仍返回 501（媒体属后续批次），群管理员代删与通话记录本地清除尚未实现。

验证：后端 `tests/messages-batch-a.mjs` 在隔离 PostgreSQL 库通过 59 项断言，`Tools/Verify-BotgramProtobufMapping.ps1` 387 项断言覆盖真实 Android TL 往返，`Tools/Verify-BotgramTL.ps1` 44 个响应通过原 TL 编解码，既有 business/e2ee/outbox/idempotent-pts/typed-persistence 等回归通过，APK 构建与 v1+v2 签名校验通过。

2026-11-08 两轮复核修复：转发与普通发送共用同一套 E2EE 准入（禁止明文的会话拒绝 409，单方反向方向仍允许明文）；已提交转发按持久化请求身份（源会话、源消息 ID、silent、drop_author）识别，源消息后续编辑、删除或清除历史后重试仍返回原消息与原 pts，身份不一致或同一 random_id 换目标会话返回 409；浏览次数只统计并返回请求 peer 会话内本人可见的消息，未计数成员用错误 peer 递增不再产生查看者记录；编辑、可编辑性、转发、已读时间都校验消息属于请求会话；多会话删除与内容已读返回实际事件数作为 `pts_count`。旧转发记录仅按不可变的作者与 tick 级创建时间判定来源唯一性并回填（正文与实体可编辑，不参与判定），同时要求源会话、源消息 ID、silent、drop_author 一致；证据不足、选项不一致或同作者同时间多条消息返回明确冲突并保留原记录（旧版去来源转发无作者与时间，重试固定 409）。双方删除不再受调用者自见隐藏影响。十一项修复均以回退旧行为的方式验证回归有效，批次 A 回归 90 项断言。真机页面、双机同步与后台送达未验证；未部署后端。详见 `Docs/artifacts/messages-batch-a-delivery.md` 与矩阵 `Docs/artifacts/messages-api-matrix.md`。

## 2026-11-07 单方开启与方向性消息保护

资料页的“端到端加密”改为显示本人开启状态。一方开启立即生效，接收方无须 PIN 即可看到“此信息开启了端到端加密”，聊天中通过原居中服务消息提示“对方开启了端到端加密”；反向普通消息保持正常。此阶段没有接收方密钥，旧消息永久保持占位，之后双方开启也只使新消息可读。双方开启的新消息沿用 Signal 会话；关闭只影响本人之后发出的消息，另一方的设置保留。管理列表、发送限制、草稿与锁图标按本人开启方向判断，消息与状态仍通过原差分和服务消息同步。单方正文仅以发送者本机密钥密封保存和投递密文，不以服务端明文隐藏实现。操作语义版本为 2，需要匹配新版自部署后端；具体状态与密钥边界见 `Docs/Botgram-E2EE.md`。

## 2026-11-07 E2EE 第二轮提交与历史恢复修复

移除异步 dirty 快照及锁定时的旧状态写回；消息采用加密 redo 日志，将候选 vault、历史与发送原信封纳入可恢复提交。失败后锁定，解锁先恢复事务再显示或发送。历史修复崩溃残尾，迁移/压缩先生成完整文件再替换，并按真实旧版信封哈希键兼容读取。保留第一方等待对方期间的确切身份确认，发送前核验实际会话身份。消息解密、历史 I/O 和发送提交移到后台，结果通过代际和显示版本检查后刷新原页面。实际构建与测试见 `Docs/artifacts/e2ee-delivery.md`；未部署后端，不将宿主检查等同于真机验收。

本项目采用用户自部署方式：后端、数据库、TLS 代理及通话所需的 TURN/STUN 均由部署者配置和维护。项目不提供公共服务器；客户端登录时填写自己的服务地址。本文本机测试记录仅适用于对应开发环境。

## 2026-11-07 端到端加密复核修复

本轮修正首期实现中的九项问题：Keystore 加密改由平台生成 IV 并回读保存；vault 文件升级为 PIN 封装整体置于 Keystore 外层封装的嵌套结构（`vault.bin` v2），仅持有文件+正确 PIN 或仅有设备密钥都不能恢复 `vaultKey`；KDF 改为标准 PBKDF2-HMAC-SHA256（API 26+ 用 JCE，API 23–25 用经 RFC 向量验证的兼容实现），旧 v1 文件只在 PIN 与设备层都验证通过后原子迁移；普通文字发送只对绑定账号的一对一用户聊天执行 E2EE 检查，群聊、收藏夹、Secret Chat 与未绑定账号不拦截；PIN/创建任务按账号身份、路径、vault 与代际校验，后台锁定/切号/退出后的迟到结果不安装密钥；发送与接收改为“候选 vault → 加密历史 `fsync` → 原子替换 vault → 提交内存”才返回成功，失败不消耗棘轮；会话复用校验已验证身份与设备 generation，身份确认绑定确认时展示的确切密钥与 generation；接收解密先验证外层路由/设备/epoch 再在候选状态上解密校验，失败无副作用；显示历史改为持久化追加式加密记录（偏移索引+256 条内存 LRU），不再有 5000 条唯一副本上限。交付验证与未验证边界见 `Docs/Botgram-E2EE.md` 与 `Docs/artifacts/e2ee-delivery.md`。

## 2026-11-06 一对一聊天端到端加密（首期）

在普通用户资料页新增“端到端加密”行（联系人和非联系人均显示，不受添加联系人按钮条件影响），在“设置 → 隐私和安全 → 密码锁”旁新增“对聊天进行端到端加密”，并新增原生风格加密会话管理列表；空列表显示锁与钥匙图标和“暂无端到端加密聊天”。四位数 PIN 使用 `PasscodeActivity` 的独立模式，与应用密码锁隔离；PIN 只解锁本机密钥库，消息使用 libsignal `0.86.5` 的 X3DH/PQXDH + Double Ratchet 会话。

服务端新增公开预密钥目录、逐会话 `pending/active/closing/suspended/closed` 状态机、密文信封投递、明文拒绝和身份密钥变化暂停；不保存 PIN、私钥或消息明文。加密区间只支持一对一文字；附件、通话、群聊、转发、编辑、表情实体、远程链接预览和多设备/换机恢复不支持或明确阻止。系统通知固定显示“收到一条加密消息”；锁定/未解锁时聊天与列表只显示占位文案；加密聊天设置 `FLAG_SECURE`。

客户端本地 SQLite 只保存密文信封；解密后的显示文本放在以 PIN 解锁的 vault key 加密的持久化 `display.bin` 历史（追加式认证记录，内存仅保留有界 LRU），不在数据库、草稿、搜索、发送队列或日志中保存明文。vault 采用 PIN+Keystore 嵌套封装，PIN 退避持久化，冷启动、退后台和切换账号会递增操作代际并清除内存密钥。PostgreSQL/relational 后端路径对 E2EE RPC 明确返回 501，本机正式文件存储路径完整支持；该限制不属于静默降级。

本轮已完成客户端编译、E2EE 核心宿主回归 73 项（含 v2 封装负向、PBKDF2 向量、v1 迁移、提交失败回滚、身份确认策略、发送 applicability、操作代际、6000 条历史重开）、后端 `tests/e2ee.mjs` 隔离回归、既有 smoke/gid/m49/rpc-boundaries 回归和 APK 构建/签名检查；未连接真机，PIN 页面视觉、双机握手与收发、重启后历史、锁定通知、后台锁定、真实 Keystore 与 ROM 差异未验证。设计与安全边界详见 `Docs/Botgram-E2EE.md`，交付哈希与命令见 `artifacts/build-info.json` 与 `Docs/artifacts/e2ee-delivery.md`。

## 2026-11-06 特别关心状态与来电通知修复

后续补修覆盖冷启动和同一联系人重叠操作：缓存尚未载入时也记录删除/屏蔽操作及本地变更，拒绝操作前发起的迟到磁盘恢复；同一联系人按 token 集合跟踪全部未决操作，任一失败只结束自身抑制，任一成功的已确认抑制不被其他失败解除。持久化保存服务端基础 users 快照，不把未决操作过滤后的展示列表写入缓存。状态专项宿主回归为 134 项；本次交付与真机验证边界见 `Docs/artifacts/special-care-overlap-fix-delivery.md`。

普通来电在 Android 13+ 的 pre-notification 阶段不再用普通 `notify()` 发布 `Notification.CallStyle`。CallStyle 仅在真实前台服务通过 `startForeground` 发布，或本次通知确实携带 full-screen intent 时使用；其余普通、特别关心但全屏权限不足或单次全屏声明已消耗的通知使用保留来电人、语音/视频、接听、拒接和点击打开的普通模板。群来电保持普通 3 级并移除 CallStyle。发布失败会记录异常类型并降级或清理，不在未发布通知时继续响铃。未修改 3/4 级频道默认值、用户频道设置或正在通话的前台通知。

特别关心的删除/屏蔽抑制改为按操作绑定：请求未决时不写盘，失败只撤销该次抑制并回滚本地 contact/blocked 状态，成功后才持久化；重启后未决抑制不会永久压制。磁盘缓存改为单次原子恢复，迟到或旧 revision 快照被拒绝时不改变任何状态。特别关心入口保持联系人资料页右上角菜单及联系人管理列表，聊天页展开菜单没有该入口；资料页右上角按钮保持原三点菜单图标，不切换为星标。

本轮仅修改客户端，未部署或重启 18524 测试后端。宿主回归、Android 编译和 APK 签名/包信息已通过；adb 无设备，真机来电矩阵、失败回滚流程和 ROM 全屏行为尚未验证。详细结果见 `Docs/artifacts/special-care-delivery.md` 与 `artifacts/build-info.json`。

## 2026-11-06 固定手机测试后端端口

本机统一测试地址为 `http://192.168.2.200:18524`，固定端口 **18524**，不再使用旧测试入口 18521。测试部署位于 `D:\BotgramBuild\native-calls-test`，实际版本读取其 `deployment.json`；从当前后端源码重新发布后升级，支持特别关心 typed RPC 与通话信令。RPC/事件使用 Protobuf-only，注册登录等 REST 仍为 JSON。

保留原 18521 测试实例的账号、会话、聊天和消息；此前占用 18524 的独立旧测试库已停用并单独备份，不合并数据。手机原来保存的 18521 地址需改为 18524；原服务器地址参与本地认证缓存隔离，不保证旧客户端会话自动跨端口恢复，必要时用原账号重新登录。正式 8521 不变。维护和恢复要求见 `../Botgram.Backend/DEPLOYMENT.md` 的“本机手机测试后端：固定端口 18524”。

## 2026-11-05 联系人添加修复

原 `ContactAddActivity` 每次提交都会带上 `textWithEntities` 备注对象，即使输入框为空；Protobuf 映射因此把整个 `contacts.addContact` 当作带备注请求拒绝，页面却已在请求前写入 `contact=true` 和别名，形成假成功。现在空备注对象与完全未填写备注等价，只有确实存在文本或实体时才视为备注；当前未实现的备注在 Gram ID 账号的联系人页面和新建联系人表单中不再显示，不再出现“可以输入、保存时返回 501”的不一致。

绑定账号的添加在收到后端确认前不修改共享用户、联系人列表、备注和页面偏好；失败时保留输入并显示原 Telegram 弹窗，成功后才按响应中的用户与联系人状态刷新 `MessagesController`、`ContactsController`、本地存储、资料页和聊天添加栏。请求进行中禁用完成按钮以防重复提交，页面销毁或账号切换后的迟到回调被丢弃。系统通讯录写入仍对绑定账号禁用；未绑定 Botgram 的原 Telegram 账号保留原有流程。

后端 `contacts.addContact` 继续按请求返回目标用户和 `contact=true`，LegacyOnly JSON 存储与 PostgreSQL 路径均通过同名联系人去重，别名只对添加者生效。当前实现返回明确的 `BOTGRAM_CONTACT_NOTES_UNSUPPORTED`、`BOTGRAM_CONTACT_PHONE_EXCEPTION_UNSUPPORTED` 与 `CONTACT_ADD_SELF`，不静默丢弃请求字段。

验证入口为 `Tools/Verify-BotgramProtobufMapping.ps1`（真实 Android TL 对象、空备注归一化、非空备注拒绝、flags 校验）、后端 `--presence-contacts-protobuf-only` 与 `--presence-contacts-postgres`、`tests/contacts.mjs` 及 `Tools/Verify-BotgramTL.ps1 -FixturePath ../Botgram.Backend/obj/contact-tl-fixtures.json`。真机安装与从原页面实际添加的顺序验证尚未执行，不能用上述宿主测试替代。

## 2026-11-05 未接来电通知

自建后端的一对一来电结束且未接听时，在独立的未接来电通知频道显示提醒，语音标题为“未接来电”，视频复用“未接视频来电”。点击进入原聊天页，通知自动消失；不创建本地伪造聊天消息，不把未接通知当作仍在响铃的通话。

Protobuf 终态映射保留来电方、被叫方、发起时间和接听/接通元数据，作为本地通知判定使用，不改变原 `TL_phoneCallDiscarded` 二进制格式。主叫、已接听（包括其他设备接听）、已接通和忙线终态不产生未接提醒；本机主动拒接持久化抑制。按账号、服务器、用户及呼叫 ID 去重，重连、差分重放和用户划掉通知后不会重复提醒，去重记录有界保存。退出账号清除该账号的可见未接通知，保留原系统通知授权和频道设置。系统未授予通知权限时不能显示通知。

专项宿主验证入口为 `Tools/Verify-BotgramMissedCalls.ps1`，要求先编译 Android Debug 类。它验证实际 Protobuf 终态映射、未接判定及持久化历史重载/裁剪；不能替代真机通知点击、锁屏、后台送达和多设备操作验收。

## 2026-11-05 局域网 HTTP 与事件连接

显式填写私有 IPv4 HTTP 地址时，客户端自动使用 HTTP RPC + WS 事件提示，无需额外开关，Debug 与正式构建使用相同私网判断。范围为 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`；必须填写实际服务器 IP，CIDR 不是可连接地址。判断不做 DNS 查询，域名、公网 HTTP、回环和 IPv6 地址不因这项规则启用明文 WS。HTTPS 地址继续使用系统证书校验和主机名校验的 WSS，不自动降级。

有效且已激活的账号在应用退到后台后保留事件连接和差分唤醒，账号切换不再停掉其他已登录账号的来电接收；账号退出、会话失效仍关闭并隔离旧连接与回调。事件仅提示立即拉取差分，不将后台接收视为前台在线或自动已读；原定时轮询保留作补偿。后台常驻、系统回收和深度休眠的送达能力仍受平台约束。HTTP/WS 是用户显式选择的明文局域网传输，凭据与内容不具备 TLS 保密性。

当前单进程后端的 WebSocket 仍每 500 毫秒检查事件，本次未实现提交后立即唤醒服务端连接，不能把手机 WS 已连接等同于端到端延迟验收。

## 2026-11-05 验证器界面与恢复码修订

13.1.4 / 72409 的两步验证入口直接使用原 `TwoStepVerificationSetupActivity.TYPE_INTRO`，保留原布局、`tsv_setup_intro` 锁动画和底部按钮。设置、搜索和深链接均进入此介绍页，点击后单独打开二维码、可复制密钥及验证码输入页面。已绑定账号进入验证器管理。

新恢复码为 10 个互不重复的八位数字字符串，保留前导零。登录和管理接口事务性消费对应摘要，同一个码最多成功使用一次。新摘要使用由受保护 TOTP 密钥派生的 HMAC-SHA256 校验，兼容既有 SHA-256 格式恢复码。绑定确认与重新生成后使用原生弹窗展示，提供复制和通过系统文件选择器保存 UTF-8 TXT。离开页面后清理客户端内存中的恢复码。

本次完成后端 HTTP 专项 182 条、PostgreSQL 292 条回归以及 APK 编译与签名检查。真机扫码、视觉布局、复制和文件选择器导出尚未复测。更完整结果记录在 `artifacts/build-info.json` 的 `totpUiRecoveryVerification`，其他隐私功能缺口沿用下一节的说明。

## 2026-11-05 隐私与验证器交付

13.1.3 / 72399 的 standalone Debug APK 已完成编译、v1/v2 签名及包信息检查。隐私设置中的两步验证、搜索及 2sv 深链接使用 Telegram 原生组件构成的 TOTP 页面，支持二维码或密钥绑定、验证码确认、登录校验、关闭和重置一次性恢复码。邮件找回服务尚未配置。自动删除页面加入 156×156、90 帧的原 Hello 鸭子离线素材，来源和格式转换记录见 `Tools/assets/auto-delete-hello.json`。

隐私规则、全局设置、黑名单、账号与默认消息 TTL 参数、会话查询与撤销、联系人删除、网站授权空列表、付款信息清理及通行密钥空列表已接入 typed RPC。黑名单、非联系人 Premium 发信限制、Gram ID 可见性与发现、群邀请及 REST 通话权限检查已覆盖 LegacyOnly 和 PostgreSQL 路径。

尚未完成自动删除消息执行、现有聊天定时器应用、账号闲置清理、通行密钥注册与登录、Passport 安全值、付费消息，以及全局归档和隐藏已读等设置的完整行为联动。TTL 目前只有参数读写，不能视为已生效的删除功能。历史会话缺少设备和创建时间时返回空值。尚未执行真机安装、验证器扫码和页面流畅度复测。

实际结果保存在 `artifacts/build-info.json` 的 `privacySecurityVerification`。手机测试后端 18524 已停机备份并切换到本次 Release 发布物，既有账号、聊天、消息、TL 身份及有效会话已核对保留。

保留原 `LaunchActivity`、`MainTabsActivity`、`DialogsActivity`、`ChatActivity`、消息气泡、群聊创建及资料页面。此前的独立 Botgram Activity、聊天界面、消息缓存和 WebRTC 界面已移除。业务兼容工作集中在后端，而不是另建客户端聊天模型。

## 协议边界

原页面继续消费 TL 类。绑定账号的 RPC 通过 `BotgramProtobufRequestMapper` / `BotgramProtobufResponseMapper` 显式映射到版本化 typed Protobuf；旧会话可保留原 TL JSON pin，但收到旧入口关闭错误不会自动重试写入。P7 联调应新登录，不恢复原生网络回退。认证、能力协商、Gram ID 和 Premium 的 REST 仍使用 JSON；P7 不是禁用所有 JSON。

采用经过 Bearer 认证的 HTTP(S) TL 对象 RPC，**没有实现 MTProto 加密、数据中心和握手协议**，因此未修改的官方 Telegram 客户端不能直接连接。客户端仍需以下接入改动：

- `LoginActivity`：用户名／密码认证，登录成功进入原 `onAuthSuccess` 和页面导航。
- `BotgramLoginView`：作为原 `LoginActivity` 内的 `SlideView` 提供用户名／密码认证，复用原登录页的布局、继续按钮、加载动画、描边输入框及错误弹窗；注册时显示名称输入框。
- `BotgramSession`：按 Telegram 账号槽位隔离令牌，并用 Android Keystore AES-GCM 加密保存。
- `BotgramTransport`：会话固定协议、typed RPC 映射、HTTP 请求、取消绑定、错误回调、前后台事件提示与差分补偿轮询，以及独立的壁纸 revision 同步。
- `ConnectionsManager`：绑定自建后端的账号走上述传输；未绑定账号保留原行为。绑定账号即使会话失效也不回落到 Telegram 传输。
- `ApplicationLoader`：重启后恢复绑定账号的差分轮询。收到的响应仍由原 MessagesController／MessagesStorage 处理。

HTTP 绑定账号不向原生 MTProto 层恢复用户 ID，也不响应原生层缺少 MTProto 密钥触发的登出通知；账号仅因主动登出或后端令牌失效退出。清除后台后启动仍使用本地账号配置和 Keystore 加密保存的有效 Bearer 令牌，不需要再次认证。

HTTP 绑定账号在登录和启动时关闭手机通讯录同步，不创建 Android 通讯录同步账号、不自动读取或写入系统通讯录，也不因缺少同步权限显示提醒或联系人标签感叹号。应用内的联系人列表仍通过后端加载。

Android Java 编译后运行 `Tools/Verify-BotgramAuth.ps1`，验证真实 `ConnectionsManager` 原生登出回调对 HTTP 绑定账号、排队后的绑定变化及未绑定原生账号的隔离行为。此宿主测试不代替真机清除后台、重开及登录界面操作验证。

## 服务端当前支持范围

| 原 App TL API | 状态 |
|---|---|
| 用户资料、按完整用户名查找、解析用户名 | 已实现 |
| 按 GID 查询和添加联系人、私有备注姓名、联系人列表 | 已实现；GID 通过 TL phone 兼容字段传输，不写入系统电话簿 |
| 会话列表、会话详情、历史记录分页／锚点 | 已实现 |
| 单聊文字发送、random_id 去重、已读、同会话回复、常见富文本实体、静音发送 | 已实现 |
| 收藏夹中的基础自发文字与历史 | 已实现；高级收藏夹分类／搜索接口尚未实现 |
| 创建基础群聊、群资料、群成员读取、群文字 | 已实现 |
| updates.getState／getDifference 与差分切片 | 已实现，pts 从 1 开始，避免首条消息被初始化跳过 |
| 附件、语音消息、跨会话引用、定时消息、自定义表情实体、编辑撤回、频道、机器人等 | 尚未实现；返回明确的 501 兼容错误 |
| 原生 phone.*／群通话 API | 尚未实现；原通话页面未替换，也没有接入旧的 WebRTC REST 接口 |

保留页面不等于所有 Telegram 功能已有后端实现。当前重点为原页面的基础文字聊天兼容。服务端对尚不支持的请求返回 `BOTGRAM_RPC_UNSUPPORTED:<方法>`，不伪造操作成功。独立 WebRTC REST 信令仍留在后端供后续工作使用，不能据此宣称原 Telegram 语音通话已经适配。

## 运行与构建

新登录表单的服务器地址与端口初始留空，服务器地址由用户填写。省略协议时使用 HTTPS；端口留空且地址本身未带端口时使用 8521，地址自带端口时使用该端口。公网 RPC 与 WSS 共用 TLS 8521，仅调试构建允许显式 HTTP 本地开发地址，非调试构建拒绝 HTTP。登录成功后，使用原搜索页面按完整用户名找到其他用户，打开原聊天页面。

前台 WSS 通过 Authorization Bearer 连接 `/api/ws`，仅将事件作为唤醒原 `getDifference` 的提示，事件游标不当作 Telegram pts。后台或切换账号时关闭旧连接，断线采用 1–30 秒退避重连；原差分轮询保留。HTTPS/WSS 均使用系统证书信任和主机名验证，不接受自签名绕过，不跟随携带凭据的重定向。WSS 握手 401 使对应会话退出；TLS 失败不降级 HTTP。尚未实现后台推送。

登录和注册表单把服务器地址与右侧可选端口分开输入。粘贴带端口的完整 HTTP(S) 地址时自动拆分，保存会话使用完整地址，已有账号从已保存的连接配置恢复。升级后已保存的旧 5080 端口迁移至 8521，其他自定义端口保留。登录/注册切换采用淡出、轻微滑动和淡入动画，保留表单内容。`Tools/Verify-BotgramServerAddress.ps1` 验证空端口、地址自带端口、手动指定端口、端口边界、IPv6 与旧地址迁移。

所有新下载的构建依赖放在 **D:\BotgramBuild**：

- `downloads`：下载归档和官方校验文件。
- `tools/gradle-8.13`、`gradle`：Gradle 程序与缓存。
- `android-sdk`：新增 NDK、CMake、构建工具；现有平台和工具通过目录联接读取。
- `native`：仓库固定版本源码依赖，工作区子模块位置通过目录联接读取。
- `nuget`、`dotnet`、`tmp`：.NET 包、CLI 状态和构建临时目录。

```powershell
./Tools/Prepare-BotgramBuild.ps1
./Tools/Build-Botgram.ps1
```

默认构建目标为 `:TMessagesProj_AppStandalone:assembleAfatDebug`，输出文件名为 `Nyarugram-debug.apk`，交付路径为 `artifacts/android/Nyarugram-debug.apk`。运行 `./Tools/Build-Botgram.ps1 -Variant Standalone` 构建非调试、R8 混淆及资源压缩版本，原始输出为 `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`，交付路径为 `artifacts/android/Nyarugram-standalone.apk`。两者安装后的显示名称均为 `Nyarugram`（所有系统语言一致），沿用仓库的开发签名配置，不作为私有生产签名的正式发布包。后端集成测试：

```powershell
cd ../Botgram.Backend
dotnet build
node tests/smoke.mjs
node tests/tl-schema.mjs
```

Android Java 编译完成后可运行 `./Tools/Verify-BotgramTL.ps1`，使用刚编译的原 TL 类对测试响应做二进制往返检查。宿主机环境替身仅提供 Android 文本工具及测试环境标记，不会打包到 App。

## 历史交付验证结果（不适用于下述未构建的新改动）

- `assembleAfatDebug` 完整通过，包含 arm64-v8a、armeabi-v7a、x86、x86_64；APK 位于 `artifacts/android/Nyarugram-debug.apk`（约 115 MB），安装显示名称已通过 APK 元数据检查为 `Nyarugram`。
- `assembleAfatStandalone` 完整通过；非调试 APK 约 68 MiB，确认未标记 debuggable，该历史产物的 R8 检查覆盖 HTTPS 地址处理、HTTP 拒绝逻辑及 WSS 路径，不作为当前默认服务器配置说明。
- 两个 APK v1／v2 签名校验通过；版本 `13.1.2`（72389），包名 `me.nyarugram.messenger`，最低 Android API 23、目标 API 36，使用仓库开发签名。
- 后端 Release 发布成功，交付包为 `artifacts/Botgram.Backend.zip`。
- 后端集成回归及 56 个响应类型与源代码匹配检查通过；本次宿主检查完成 29 个基础响应、2 个 GID 响应和 26 个联系人响应的原 TL 二进制往返，以及 34 项地址检查和 6 项账号隔离断言。
- 未执行手机／模拟器安装和端到端收发测试。原生通话、附件及其余未支持 API 的限制仍然存在。

后端集成测试包含 TL 身份稳定性、单聊、原响应类型、群聊、差分、去重与权限隔离。Android 编译和设备验证的实际结果以本次交付说明为准，不应把服务端通过测试理解为手机收发或语音已经验证。

## 设置与日志

- Settings 和旧版个人设置页不再显示 Devices，设置搜索中也移除了对应入口。
- Debug 区块以及版本号隐藏菜单只提供“保存日志”和“查看日志”。
- 保存日志通过系统文件选择器导出 ZIP；包含日志目录中的 `.txt`、`.log` 文件，不包含数据库或堆转储。导出前等待 Java 日志队列写入并刷新，按每个文件当时的长度读取。
- 查看日志复用 Telegram 的 `UniversalFragment` 列表和 `AlertDialog` 文本弹窗，显示文件名、大小，并可选择复制文本。每次最多读取末尾 256 KiB；完整内容可通过保存日志导出。
- 新增文案包含英文与内置简体中文；没有日志时显示空状态，文件访问失败时显示错误提示。

## 当前客户端增量与验收边界

- Settings 与旧版个人设置恢复 **Nyarugram Premium**。默认未激活，只有 `POST /api/me/premium/activate` 返回真实 `active:true` 才解锁；状态查询为 `GET /api/me/premium`，启动查询失败会重试。不进入 Telegram 支付，不伪造余额。PB `UserSummary.nyarugram_premium` tag11 保留 absent/false/true；旧 PB 和 Gram ID 响应缺字段只保留同一认证会话已确认的状态，不继承其他账号/过期会话。用新 TL user 对象触发原 UserConfig 权益通知。
- Gram ID 编辑保留原区域选择器及八位号码输入。原整数布局重载把号码框宽度设为零，现明确使用浮点 weight；后台保存验证号码与稳定用户 ID，显示名统一为 Gram ID，wire `gid` / TL `phone` 不改名。
- Gram 收入页移除两处 Fragment 充值及余额不足弹窗中的 Fragment 充值入口；收益、统计、提现不移除。所有账号的发送礼物入口显示“暂无可用的礼物”，并在构造加载、拍卖、转送及礼物型 Premium/Stars/转售付款前设置防线；普通订阅、充值、抽奖和礼物外观组件不因此禁用。原生请求按账号会话及 token/GUID 取消隔离，礼物型商店交易确认也明确拒绝，既有未完成的礼物付款不会被伪装成已完成。
- 主题列表的文件解析和预览图解码移至后台，结果按 view 绑定 generation 校验；不强制每次打开聊天设置重新请求远程主题，不关闭动画。尚无设备帧耗时测量，不能把原因定位等同于性能验收。
- 壁纸首期仅纯色/渐变 NoFile：全局列表/收藏/安装与聊天个人覆盖、双人会话 `for_both`、`revert` 使用 typed RPC。预览、清除和聊天设置等待服务端返回后才改本地；不依赖伪造服务消息，不改变 pts。文件/slug/上传/图案资源/非空表情主题、消息 ID 复用及全部收藏重置尚不支持，明确失败。群及收藏夹不支持 `for_both`。
- revision 不是 pts：登录、重连、前台恢复与定期查询读取 GetWallpapers，变化时逐个补拉缓存 full-info，以便其他请求插队；全部成功且未被新变更或提示打断后才应用并推进本地 watermark，失败保留旧缓存。旧 full-info 缺 wallpaper state 不当作清除。基本群 `TL_chatFull` 无壁纸线字段，另用原 ChatThemeController 壁纸缓存持久化 NoFile，按账号槽位与本人 TL ID 隔离，不改原 TL 构造器格式。

**当前不是可交付版本。** 新 schema/mapper/UI 源码已修改，但正式 protoc 再生成、Premium/壁纸宿主回归、Android 类型编译、最终 APK/签名/版本/资源检查仍被构建工具路径的实际沙箱拒绝阻塞。旧生成 Java 不包含新增字段，不能直接构建交付。`Tools\Verify-BotgramPremium.ps1`、`Tools\Verify-BotgramWallpaper.ps1` 与使用编译后真实 TL 类型的 `Tools\Verify-BotgramGifts.ps1` 为待执行检查；已有 Java 语法解析及礼物分类替身测试不等于 Android 类型编译或运行时验证。Android Studio、模拟器与真机运行测试按用户要求暂停（NOT RUN）。历史 APK 与后端测试记录不作为本轮结果，未安装、未变更生产服务或默认 base URL。
