# ChromeXt Zygisk · WebUI 独立版

仅刷入 ARM64 ZIP，无须安装 ChromeXt APK；在 Root 管理器模块列表打开 WebUI。支持浏览器注入范围、脚本库、导入/编辑/删除、按浏览器分配与启停、备份导出、HTTPS 链接更新及日志读取。默认不注入，不默认启用导入脚本。保存后需强制停止目标浏览器再启动；模块在进程启动时加载只读配置快照，不开放新的 Root IPC 服务。浏览器本地 GM 存储保留，原有浏览器内菜单、脚本引擎与 Eruda 保留源码。

旧管理器内的脚本请先导出 ScriptHub JSON 备份，再在 WebUI 导入。不会自动卸载旧 APP 或清除其数据。浏览器本地脚本：启用注入后重启浏览器、打开网页，再在 WebUI「脚本」点击「同步浏览器本地脚本」并保存。仅回读 ChromeXt 私有脚本源码快照，保留已有脚本的设置、同名冲突不覆盖、删除标记不恢复。不回读 GM 数据；浏览器本地删除/修改不会自动覆盖 WebUI。链接获取受 WebUI CORS 限制；文件选择、下载和剪贴板能力取决于管理器 WebUI，提供源码粘贴、文本复制替代。日志来自限定 tag 的 logcat，清空显示不会清除系统日志。资源编译中间件仅作为模块内置 resources.bin，绝不安装。

支持 KernelSU 系列 WebUI 宿主；其他兼容宿主待验证。Android 8.1+ ARM64，首轮优先 Android 16。独立 Zygisk + LSPlant，不依赖 LSPosed，master 的 LSP API 102 不受影响。此构建尚需真机验证资源加载、脚本运行与 UI。

第三方依赖锁定在 fetch-deps.sh：LSPlant (LGPL-3.0), Dobby, xDL 和 Zygisk API sample。ChromeXt GPL-3.0。

日志页「注入自检」仅允许已保存注入范围的浏览器，先启动限定 tag 的 logcat，再强制停止并冷启动，采集约 10 秒，按主进程 PID 汇总各注入阶段。浏览器打开后返回 WebUI，可读取上次保存在模块私有文件的自检报告。不会清除系统日志、浏览器数据或修改 SELinux。启动阶段通过不等于网页脚本已执行。

若 WebUI 的 ActivityManager 命令失败，日志页可开始手动采集，通过系统应用信息强行停止浏览器并从桌面重开，访问网页后返回结束采集。通过新主进程 PID 验证冷启动，并从起点读取保留的 logcat；日志可能轮转。未执行冷启动时不再输出误导性的阶段结果。
