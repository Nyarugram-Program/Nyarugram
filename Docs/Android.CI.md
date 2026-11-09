# Android 自动构建与发布

`.github/workflows/android.yml` 在每次分支提交后运行，也支持从 Actions 页面手动执行。两个独立任务分别构建 `afatStandalone` 稳定版和 `afatDebug` 调试版，均包含四种 ABI。

两个任务成功后，默认分支自动发布 GitHub Release。其他分支可在 Actions 的 Artifacts 中下载产物，保留 30 天。手动运行默认分支也会发布 Release。

Release 标签使用 `v版本号-提交哈希前12位`，例如 `v13.1.12-0123456789ab`。重新运行同一提交会更新该标签下的产物。发布时先创建草稿，上传成功后公开，附带以下文件。

- `Nyarugram-stable.apk`，关闭调试，开启代码及资源压缩。
- `Nyarugram-debug.apk`，开启调试。
- 两份 APK 的 SHA-256 校验文件及包含包名、版本和提交哈希的构建信息。

沿用仓库现有签名配置和包名，两种 APK 使用相同包名，安装会互相覆盖。仓库内现有签名密钥为公开的默认开发密钥，发布产物沿用它。工作流通过内置 `GITHUB_TOKEN` 发布，发布任务需要仓库允许 Actions 使用 `contents: write`，无需另配个人访问令牌。

CI 使用 Ubuntu 24.04、JDK 17、Gradle 8.13、Android SDK 36、Build Tools 36.0.0、NDK 27.2.12479018 和 CMake 3.22.1。原生源码按 Git 子模块固定提交获取，libyuv 使用现有构建脚本相同的 GitHub 镜像，不递归获取嵌套子模块。Signal 0.86.5 与 Protobuf 3.25.8 从 Maven Central 下载并校验固定 SHA-256，缓存命中后也重新校验。

Gradle 的 `botgramDependencyRoot` 属性指定依赖目录，默认继续使用本机的 `D:/BotgramBuild`。Linux CI 将其设为 runner 临时目录。本机新增下载和缓存仍遵循 D 盘约定。

每次修改仓库内容都要递增 `gradle.properties` 中的 `APP_VERSION_NAME` 与 `APP_VERSION_CODE`。CI 校验生成 APK 的包名、版本号、签名有效性以及调试标记后才上传产物。
