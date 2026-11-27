# ChromeXt 独立 Zygisk 实验版

本分支只通过 Zygisk 启动；不提供 Xposed 模块入口，也不依赖 LSPosed APK/服务。master 继续构建原 LSP 版。内部回调类沿用现有 Hook API 的形状，仅供 ChromeXt 自身使用，不是通用 Xposed 框架。

- 保留现有 ScriptHub、脚本数据库、浏览器适配、悬浮菜单、开发者工具、脚本同步及资源；运行效果须真机测试。
- 原生模块限制到明确列出的浏览器主进程，不注入 system_server、渲染/GPU/媒体子进程。首轮优先验证夸克 Android 16。
- 首轮产物为 ARM64，Android 8.1+ 加载器（各 Android 版本未验证）；16KB ELF 对齐。
- 同一 applicationId 保留管理器数据及 Provider 地址，LSP 与 Zygisk APK 是替换关系，不能同时安装。测试 debug APK 的签名与正式 APK 可能不同，请备份脚本再切换，不能覆盖时不要直接清除数据。
- 安装匹配的管理器 APK，再安装 ZIP，开启 Zygisk 并重启。不要对同一浏览器同时启用其他 ChromeXt 注入入口。卸载/禁用 Zygisk 模块并重启即可停止注入。
- 初次测试查看 logcat -s ChromeXt-Zygisk CXDiagnostics。应用内诊断页仍保留，Native 启动失败时需看 logcat。
- 本分支不是正式发布；CI 验证产物结构不代表真机 Hook 成功。若 manager APK 与 ZIP 不匹配，请重新安装同一批产物。

第三方依赖锁定在 fetch-deps.sh：LSPlant (LGPL-3.0), Dobby, xDL 和 Zygisk API sample。源代码及许可证见对应上游仓库；保留 ChromeXt GPL-3.0。此分支的 Zygisk 模块不替换系统文件、不更改网页安全策略。
