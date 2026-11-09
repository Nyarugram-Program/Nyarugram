param([string]$Source = 'D:\BotgramBuild\downloads\telegram-zh-hans-export.xml')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$english = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal)
foreach ($relative in @('TMessagesProj/src/main/res/values/strings.xml', 'TMessagesProj_AppStandalone/src/main/res/values/strings.xml')) {
    $document = [xml][IO.File]::ReadAllText((Join-Path $workspace $relative))
    foreach ($entry in $document.resources.string) { $english[$entry.name] = $entry.InnerText }
}
$translation = [xml][IO.File]::ReadAllText($Source)
$output = New-Object System.Xml.XmlDocument
[void]$output.AppendChild($output.CreateXmlDeclaration('1.0', 'utf-8', $null))
[void]$output.AppendChild($output.CreateComment(' Simplified Chinese from https://translations.telegram.org/zh-hans/android/export ; see Docs/Botgram.Chinese.md. '))
$root = $output.CreateElement('resources')
[void]$output.AppendChild($root)
$seen = New-Object 'System.Collections.Generic.Dictionary[string,bool]' ([StringComparer]::Ordinal)
$skipped = @()
function Get-Placeholders([string]$value) {
    $matches = [regex]::Matches(($value -replace '%%', ''), '%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])')
    $position = 0
    $tokens = foreach ($match in $matches) {
        $position++
        $index = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { "$position" }
        "$index`:$($match.Groups[2].Value)"
    }
    return (($tokens | Sort-Object -Unique) -join ',')
}
foreach ($entry in $translation.resources.string) {
    $name = $entry.name
    if (!$english.ContainsKey($name)) { continue }
    if ($seen.ContainsKey($name)) { throw "Duplicate translation: $name" }
    $seen[$name] = $true
    if ((Get-Placeholders $entry.InnerText) -cne (Get-Placeholders $english[$name])) {
        $skipped += $name
        continue
    }
    [void]$root.AppendChild($output.ImportNode($entry, $true))
}
$localTranslations = [ordered]@{
    BotgramSpecialCareMessagesChannel = '特别关心消息'
    BotgramSpecialCareCallsChannel = '特别关心来电'
    BotgramSpecialCareNotificationLabel = '特别关心'
    BotgramSpecialCareIneligible = '特别关心仅支持已添加的个人联系人，不支持自己、机器人、已删除用户、已屏蔽用户或非联系人。'
    BotgramSpecialCareTitle = '特别关心'
    BotgramSpecialCareAdd = '设为特别关心'
    BotgramSpecialCareRemove = '取消特别关心'
    BotgramSpecialCareConfirmAdd = '将这位联系人设为特别关心？仅支持已添加的个人联系人。'
    BotgramSpecialCareConfirmRemove = '取消对这位联系人的特别关心？'
    BotgramSpecialCareSaved = '特别关心已更新。'
    BotgramSpecialCareFailed = '特别关心未更新，请检查网络连接和联系人状态后重试。'
    BotgramSpecialCareInfo = '点击联系人可取消特别关心。删除或屏蔽联系人会清除特别关心，重新添加或解除屏蔽不会自动恢复。其他设备的变更将通过定期查询同步。'
    BotgramSpecialCareChannelSettings = '消息声音与震动'
    BotgramE2eeTitle = '端到端加密'
    BotgramE2eeOn = '启用'
    BotgramE2eeOff = '关闭'
    BotgramE2eeWaiting = '等待对方'
    BotgramE2eeConfirm = '确认'
    BotgramE2eeCancel = '取消'
    BotgramE2eePinSetupTitle = '端到端加密 PIN'
    BotgramE2eePinConfirmTitle = '确认 PIN'
    BotgramE2eePinVerifyTitle = '输入 PIN'
    BotgramE2eePinSetupInfo = '此账号的所有端到端加密聊天共用此 PIN。PIN 无法找回；丢失后将无法解锁本机加密聊天。'
    BotgramE2eePinConfirmInfo = '请再次输入相同的四位数字。'
    BotgramE2eePinVerifyInfo = '请输入此账号的四位数字 PIN。'
    BotgramE2eePinMismatch = '两次输入的 PIN 不一致，请重试。'
    BotgramE2eePinWrong = 'PIN 错误。'
    BotgramE2eePinRetry = '尝试次数过多，请在 %1$d 秒后重试。'
    BotgramE2eePinForgot = '我忘记 PIN，并要删除本机密钥'
    BotgramE2eeLockedMessage = '加密消息。解锁 PIN 后查看。'
    BotgramE2eeUndecryptableMessage = '此加密消息无法在本机解密。'
    BotgramE2eePendingInfo = '对方邀请你启用端到端加密。双方确认后消息才会受到保护。'
    BotgramE2eeWaitingPeer = '等待对方确认。'
    BotgramE2eeSafetyCode = '安全码'
    BotgramE2eeSafetyCodeInfo = '请与对方核对此安全码。一致时聊天受到保护。'
    BotgramE2eeTurnOff = '关闭端到端加密'
    BotgramE2eeTurnOffConfirm = '关闭'
    BotgramE2eeTurnOffInfo = '只关闭你发送消息的加密。若对方仍开启，其发来的新消息将永久显示占位提示；已有加密历史仍需要 PIN 解锁。'
    BotgramE2eeIdentityChanged = '对方安全码已变化。双方确认前暂停发送。'
    BotgramE2eeConfirmIdentity = '确认新的安全码'
    BotgramE2eeSendLocked = '请先解锁端到端加密再发送消息。'
    BotgramE2eeSendNotReady = '加密会话尚未就绪，请稍后重试。'
    BotgramE2eeUnsupportedPeer = '对方客户端暂不支持端到端加密。'
    BotgramE2eeTextOnly = '加密聊天首期仅支持文字消息。'
    BotgramE2eeEmptyList = '暂无端到端加密聊天'
    BotgramE2eeChangePin = '更改 PIN'
    BotgramE2eeResetKeys = '删除本机加密密钥'
    BotgramE2eeResetInfo = '这会永久删除本机密钥及解密加密历史的能力，不会删除对方持有的消息，且无法撤销。'
    BotgramE2eeResetConfirm = '删除密钥'
    BotgramE2eeSystemInvited = '已发送端到端加密邀请'
    BotgramE2eeSystemActivated = '端到端加密已启用'
    BotgramE2eeSystemDeclined = '端到端加密邀请已拒绝'
    BotgramE2eeSystemCancelled = '端到端加密邀请已取消'
    BotgramE2eeSystemCloseRequested = '已请求关闭端到端加密'
    BotgramE2eeSystemClosed = '端到端加密已关闭'
    BotgramE2eeSystemIdentityChanged = '安全码已变化，双方确认前暂停发送'
    BotgramE2eeSystemReactivated = '端到端加密已重新启用'
    BotgramE2eePrivacyTitle = '对聊天进行端到端加密'
    BotgramE2eeStatusActive = '已启用'
    BotgramE2eeStatusPending = '待确认'
    BotgramE2eeStatusSuspended = '需确认身份'
    BotgramE2eeStatusClosing = '关闭中'
    BotgramE2eeNotification = '收到一条加密消息'
    BotgramE2eeFailed = '端到端加密操作失败，请检查网络连接后重试。'
    BotgramE2eePeerUnsupportedShort = '联系人暂不支持'
    BotgramE2eeIdentityConfirmInfo = '双方确认新的安全码后才会恢复加密发送。'
    BotgramE2eeInviteInfo = '开启后立即保护你发出的消息。对方未开启时只能看到占位提示，此阶段的消息以后也永远无法解密。对方发给你的普通消息仍可正常阅读。'
    BotgramE2eeUnilateralMessage = '此信息开启了端到端加密'
    BotgramE2eeSystemSelfEnabled = '你开启了端到端加密'
    BotgramE2eeSystemPeerEnabled = '对方开启了端到端加密'
    BotgramE2eeSystemSelfDisabled = '你关闭了端到端加密'
    BotgramE2eeSystemPeerDisabled = '对方关闭了端到端加密'
    BotgramE2eeSelfOnlyInfo = '你已开启加密，对方尚未开启。对方只能看到你发送消息的占位提示，此阶段的旧消息永远无法解密，即使对方之后也开启。对方发给你的普通消息仍可正常阅读。'
    BotgramE2eePeerOnlyInfo = '对方已开启加密。之前收到的加密消息将永久保持占位，无法解密。你开启后，只有之后的新消息双方可读；未开启时，你发出的消息仍按普通消息发送。'
    BotgramE2eeConfirmEnable = '启用'
    BotgramE2eeResetDone = '本机加密密钥已删除。'
    BotgramE2eeDeviceKeyInvalid = '设备加密密钥不可用。请删除本机加密密钥并重新设置 PIN。'
    BotgramE2eeCommitFailed = '加密消息未能安全保存，因此未发送。'
    BotgramE2eeVaultCorrupt = '本机加密文件无法读取。若持续出现，请重置本机加密密钥。'
    BotgramE2eeStorageFailed = '本机加密文件写入失败，请检查存储空间后重试。'
    BotgramAuthenticatorIntro = '使用验证器应用保护账号。在新设备上登录时，需要输入验证器生成的验证码。'
    BotgramAuthenticatorManage = '管理验证器'
    BotgramAuthenticatorEnrollInfo = '使用验证器扫描下方二维码，或复制密钥手动添加。输入六位验证码，完成绑定。'
    BotgramAuthenticatorSixDigitCode = '六位验证器验证码'
    BotgramAuthenticatorQr = '验证器绑定二维码'
    BotgramAuthenticatorCopyKey = '复制密钥'
    BotgramRecoveryCodesShow = '恢复码'
    BotgramRecoveryCodesSaveTxt = '保存 TXT'
    BotgramRecoveryCodesSaved = '恢复码已保存。'
    BotgramRecoveryCodesSaveFailed = '文件保存失败，请重试并选择可写入的位置。'
    BotgramRecoveryCodesRemaining = '剩余未使用的恢复码 — %1$d'
    BotgramAuthenticator = '验证器应用'
    BotgramAuthenticatorCode = '验证器验证码或恢复码'
    BotgramAuthenticatorInfo = '支持 Google Authenticator、Microsoft Authenticator、Aegis 等 TOTP 验证器。扫描二维码或手动输入密钥，再填写六位验证码确认绑定。离开前请妥善保存恢复码。'
    BotgramAuthenticatorEnabled = '验证器保护已开启。新登录需要账号密码，以及验证器验证码或一次性恢复码。'
    BotgramAuthenticatorEnable = '绑定验证器'
    BotgramAuthenticatorConfirm = '确认验证码并绑定'
    BotgramAuthenticatorDisable = '关闭验证器保护'
    BotgramAuthenticatorDisableInfo = '请输入新的验证器验证码或未使用过的恢复码，以关闭两步验证。'
    BotgramAuthenticatorInvalid = '验证码错误、已过期或已使用。请检查设备时间，或使用未使用过的恢复码。'
    BotgramAuthenticatorWait = '尝试次数过多，请一分钟后重试。'
    BotgramAuthenticatorFailed = '验证器操作未完成，请检查连接后重试。'
    BotgramRecoveryCodesInfo = '下方是 10 个八位数字恢复码。验证器不可用时，在登录页面输入其中一个完整恢复码。每个恢复码使用后即销毁。离开此页面前，请复制或保存到安全位置。'
    BotgramRecoveryCodesRegenerate = '生成新的恢复码'
    BotgramRecoveryCodesReplaceInfo = '此操作会使旧恢复码全部失效。请输入新的验证器验证码或未使用过的恢复码以继续。'
    BotgramRecoveryEmailUnavailable = '服务器尚未配置邮件找回服务。可跳过此步骤，设置不含邮件找回的两步验证密码。'
    BotgramSignIn = '登录 Botgram'
    BotgramSignUp = '创建账号'
    BotgramSignInInfo = '请输入用户名和密码以继续。'
    BotgramSignUpInfo = '请设置用户名、显示名称和密码。'
    BotgramServer = '服务器地址'
    BotgramPort = '端口'
    BotgramInvalidPort = '请输入 1 到 65535 之间的端口号，或留空使用 8521。'
    BotgramDisplayName = '显示名称'
    BotgramCreateAccount = '创建账号'
    BotgramHaveAccount = '已有账号？登录'
    BotgramInvalidCredentials = '用户名或密码错误。'
    BotgramUsernameTaken = '该用户名已被使用。'
    BotgramAuthenticationFailed = '无法登录，请检查填写的信息后重试。'
    BotgramInvalidServer = '请输入不含路径的 HTTP 或 HTTPS 主机地址，端口请单独填写。'
    BotgramConnectionFailed = '无法连接或保存登录状态，请重试。'
    BotgramLoginRequestFailed = '请求服务器失败（%1$s），请提供此错误代码。'
    BotgramLoginResponseFailed = '处理登录响应失败（%1$s），请提供此错误代码。'
    BotgramLoginSessionFailed = '安全保存登录状态失败（%1$s），请提供此错误代码。'
    BotgramConnectionSettings = '连接设置'
    BotgramEnabled = '开启'
    BotgramDisabled = '关闭'
    BotgramLargeClientHello = '大型 ClientHello'
    BotgramLargeClientHelloUnsupported = '大型 ClientHello 需要 Android 10 或更高版本。'
    BotgramLargeClientHelloHttpsRequired = '大型 ClientHello 仅适用于 HTTPS 连接。'
    BotgramContactGidInfo = '请输入联系人的 Gram ID，例如 +030 12345678。'
    BotgramContactGidInvalid = '请输入有效的三位 M49 区域代码和八位数字，后八位不可为 00000000。'
    BotgramContactGidNotFound = '未找到使用该 Gram ID 的账号。'
    BotgramContactSaveFailed = '联系人未保存，请检查网络连接后重试。'
    BotgramContactNotesUnavailable = 'Gram ID 账号暂不支持联系人备注。'
    BotgramContactAddSelf = '不能把自己添加为联系人。'
    BotgramGid = 'Gram ID'
    BotgramGidCopied = '已复制 Gram ID'
    BotgramChangeGid = '修改 Gram ID'
    BotgramGeographicRegion = '地理区域'
    BotgramSelectRegion = '请选择 World 以外的地理区域。'
    BotgramGidInputInfo = '区域代码后填写八位数字，不可为 00000000。提交时会校验是否被占用。'
    BotgramGidInvalid = '请输入八位数字，且不可为 00000000。'
    BotgramGidTaken = '该 Gram ID 已被使用，请更换号码。'
    BotgramGidHelp = 'Gram ID 用于代替手机号码。请选择联合国 M49 地理区域和八位数字。修改 Gram ID 后，账号和聊天记录仍然保留。'
    BotgramKeepGid = '保留 %1$s'
    BotgramLocatingRegion = '正在确认地理区域…若无法定位，将使用 World（+001）。'
    BotgramAccountInfo = 'Gram ID、用户名与简介'
    TelegramPremium = 'Nyarugram Premium'
    BotgramPremiumInfo = '当前账号可免费终身使用，开通状态由 Nyarugram 服务器确认。当前可用权益如上所示。'
    BotgramPremiumSubtitle = '专属会员标识与更多应用图标，免费终身使用。'
    BotgramPremiumLifetime = '终身'
    BotgramPremiumLifetimePrice = '0$/终身'
    BotgramPremiumSubscribeLifetime = '以终身 0.00$ 的价格订阅'
    BotgramPremiumBadgeInfo = '开通后，账号会在支持的个人资料和聊天界面中显示 Premium 会员标识。'
    BotgramPremiumIconsInfo = '在设置 → 聊天设置中，选择 Nyarugram 内置的更多应用图标。需要 Android 系统版本及桌面启动器支持更换应用图标。'
    BotgramPremiumActivate = '免费开通'
    BotgramPremiumActive = '已开通 Nyarugram Premium'
    BotgramPremiumFailed = '无法确认 Premium 状态，请重试。没有发生任何付款。'
    BotgramNoGifts = '暂无可用的礼物'
    BotgramWallpaperUnsupported = '目前仅支持纯色和渐变壁纸，暂不支持此壁纸选项。'
    BotgramWallpaperFailed = '壁纸未能保存，已保留原壁纸，请重试。'
    BotgramCallOverlayTitle = '在其他应用上层显示来电'
    BotgramMissedCallNotification = '未接来电'
    BotgramCallOverlayMessage = '为了在 Nyarugram 处于后台或屏幕锁定时直接把来电界面显示在最前面，请允许 Nyarugram 显示在其他应用上层。'
    BotgramSaveLogs = '保存日志'
    BotgramViewLogs = '查看日志'
    BotgramLogsSaved = '日志已保存到所选位置。'
    BotgramNoLogs = '暂无日志记录。'
    BotgramLogsPreviewInfo = '预览最多显示末尾 256 KB，使用“保存日志”可导出完整文件。'
}
foreach ($name in $localTranslations.Keys) {
    foreach ($existing in @($root.SelectNodes("string[@name='$name']"))) {
        [void]$root.RemoveChild($existing)
    }
    $entry = $output.CreateElement('string')
    $entry.SetAttribute('name', $name)
    $entry.InnerText = $localTranslations[$name]
    [void]$root.AppendChild($entry)
}
$destination = Join-Path $workspace 'TMessagesProj/src/main/res/values-zh/strings.xml'
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
$settings = New-Object System.Xml.XmlWriterSettings
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$settings.Indent = $true
$writer = [Xml.XmlWriter]::Create($destination, $settings)
try { $output.Save($writer) } finally { $writer.Dispose() }
Write-Output "Imported $($root.ChildNodes.Count) Chinese strings. Incompatible placeholders use English fallback: $($skipped -join ', ')"
