# ChromeXt 独立 Zygisk 实验版

本分支只通过 Zygisk 启动；不提供 Xposed 模块入口，也不依赖 LSPosed APK/服务。master 继续构建原 LSP 版。内部回调类沿用现有 Hook API 的形状，仅供 ChromeXt 自身使用，不是通用 Xposed 框架。

- 保留现有 ScriptHub、脚本数据库、浏览器适配、悬浮菜单、开发者工具、脚本同步及资源；运行效果须真机测试。
- 原生模块限制到明确列出的浏览器主进程，不注入 system_server、渲染/GPU/媒体子进程。首轮优先验证夸克 Android 16。
- 首轮产物为 ARM64，Android 8.1+ 加载器（各 Android 版本未验证）；16KB ELF 对齐。
- 同一 applicationId 保留管理器数据及 Provider 地址，LSP 与 Zygisk APK 是替换关系，不能同时安装。测试 debug APK 的签名与正式 APK 可能不同，请备份脚本再切换，不能覆盖时不要直接清除数据。
- ZIP 内已包含同一批构建的管理器 APK；在已启动 Android 的 Root 管理器中刷入 ZIP 时自动覆盖安装（用户 0）。若刷入时 Package Manager 不可用，首次正常启动后自动补装一次。开启 Zygisk 后重启，不需要另外下载 APK。
- 安装使用 Android Package Manager 的签名及版本校验，不卸载旧 APP、不清除数据、不强制降级。签名不一致或安装失败会明确提示；首次启动补装失败时记录到模块目录的 manager-install.log，禁用该模块以免继续使用不匹配的管理器。修正签名或备份迁移后重新刷入。不要对同一浏览器同时启用其他 ChromeXt 注入入口。卸载/禁用 Zygisk 模块并重启即可停止注入；不会自动删除管理器和脚本。
- 初次测试查看 logcat -s ChromeXt-Zygisk CXDiagnostics。应用内诊断页仍保留，Native 启动失败时需看 logcat。
- 本分支不是正式发布；CI 验证产物结构不代表真机 Hook 成功。若 manager APK 与 ZIP 不匹配，请重新刷入同一批产物。CI 直接使用 Gradle 默认 debug 签名，不配置额外签名密钥或 Secrets。

第三方依赖锁定在 fetch-deps.sh：LSPlant (LGPL-3.0), Dobby, xDL 和 Zygisk API sample。源代码及许可证见对应上游仓库；保留 ChromeXt GPL-3.0。此分支的 Zygisk 模块不替换系统文件、不更改网页安全策略。
