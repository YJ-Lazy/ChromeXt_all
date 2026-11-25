# ScriptHub · Android 浏览器脚本增强

[![Android CI](https://github.com/YJ-Lazy/ChromeXt_all/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/YJ-Lazy/ChromeXt_all/actions/workflows/android.yml)
![LSPosed](https://img.shields.io/badge/Hook-LSPosed-blue)

基于 [ChromeXt](https://github.com/JingMatrix/ChromeXt) 开发的 Android 浏览器用户脚本增强模块，支持夸克 UC/U4 内核及 Chromium 浏览器，并提供独立的脚本管理 APP。

**使用 LSPosed，模块 APP 无需单独授予 Root 权限。Root 仅用于可选的增强日志读取。**

[下载 APK（Actions）](https://github.com/YJ-Lazy/ChromeXt_all/actions/workflows/android.yml) · [问题反馈](https://github.com/YJ-Lazy/ChromeXt_all/issues) · [上游英文文档](README.upstream.md)

## 主要功能

- 用户脚本安装、匹配与执行，沿用 ChromeXt 的 GM API。
- 夸克专用悬浮菜单、原生脚本管理及 JavaScript 调试入口。
- Chromium 浏览器菜单集成及 Eruda 网页调试（视内核兼容性而定）。
- APP 内统一查看、搜索脚本及源码，按浏览器分配和启停。
- APP 内通过脚本链接导入，支持 Greasy Fork、ScriptCat、GitHub；普通网站可跳转搜索适用脚本。
- 跨浏览器脚本源码同步，各浏览器的 GM 数据独立保存。
- 自动发现浏览器，按内核特征尝试适配。
- 独立模块日志与可选 Root 增强诊断，支持复制、分享及删除本地日志。
- 原生管理界面，支持系统深色模式。

## 浏览器兼容性

| 浏览器 | 当前适配状态 |
| --- | --- |
| 夸克 10.15.0.1125 | Android 16 真机已验证基础注入、脚本安装及菜单 |
| Chrome 158.0.8127.92 | Android 16 真机已验证模块运行、脚本安装、配置通信及独立日志 |
| Brave、Kiwi、Vivaldi 等 | 继承 Chromium 适配，具体版本待验证 |
| Edge、三星浏览器等 | 尝试 Chromium 动态适配 |
| Via 等 | 尝试通用 WebView 适配 |
| 小米、360 浏览器 | 保留原有专用适配路径 |
| UC、QQ 浏览器 | 待验证 |
| Firefox | 暂无 Gecko 专用适配 |

不限制为单一浏览器版本。开放作用域不代表所有浏览器都已兼容；不同版本和内核的实际功能以测试结果为准。上述真机验证不代表全部 GM API、所有脚本或调试功能均已通过测试。

## 安装与使用

1. 确保设备已安装并启用 LSPosed。
2. 前往 [Android 构建](https://github.com/YJ-Lazy/ChromeXt_all/actions/workflows/android.yml)，选择成功的构建并下载 APK 产物（可能需要登录 GitHub）。优先使用可安装的已签名 APK；未签名 APK 需自行签名。
3. 安装模块，在 LSPosed 中启用并勾选需要使用的浏览器。
4. 强制停止目标浏览器，再重新打开。
5. 通过 浏览器菜单或夸克悬浮入口安装用户脚本。
6. 打开 ScriptHub APP 管理脚本及浏览器配置，重新加载网页使配置生效。

无需为系统或 System UI 启用模块。本项目不承诺支持免 Root 框架或内嵌 APK 方案。

## APP 导入脚本

打开 APP → 导入脚本，输入 HTTPS 脚本链接或脚本详情页，解析后查看元数据、权限及源码，确认并分配目标浏览器。输入普通网站可跳转 Greasy Fork 搜索适用脚本。当前下载上限为 180 KB，暂不支持覆盖更新已有脚本。

## 脚本备份与恢复

APP 内选择导出脚本，支持多选及全选；单个导出为 `.user.js`，多个导出为 ScriptHub JSON 备份。导入脚本支持网址及本地文件多选，恢复时跳过重复或无效脚本，默认不启用。备份仅包含源码，不包含 GM 数据或浏览器分配设置。单次备份上限 16 MiB，单脚本导入上限 180 KB。

## 跨浏览器脚本管理

在浏览器 A 安装脚本并加载网页，使脚本同步到 APP；在 APP 中选择该脚本，分配给浏览器 B 并启用。浏览器 B 下次页面注入时尝试导入并执行匹配的脚本。

当前同步为基础实现：单次源码传输约 200 KB；启停在下次注入时生效；源码版本冲突、统一编辑删除和浏览器菜单状态同步仍待完善。

## 日志与适配反馈

在 APP 内打开 **日志**：

| 操作 | 说明 |
| --- | --- |
| 刷新 | 读取模块独立保存的日志，不申请 Root |
| Root 增强读取 | 可选；合并模块日志、Logcat 与 LSPosed 原始日志，需要 Root 授权 |
| 一键复制适配日志 | 复制设备版本、模块版本及当前显示的日志 |
| 分享日志 | 分享当前诊断内容 |
| 删除日志 | 删除模块自己的日志文件，不清除系统或 LSPosed 原始日志 |

独立日志通信已收到 Chrome 真机成功反馈；拒绝 Root 后的完整回归及 Root 增强读取仍需进一步确认。若独立日志为空，请检查作用域、重启浏览器，必要时使用 Root 增强读取或从 LSPosed 管理器导出日志。

提交 [Issue](https://github.com/YJ-Lazy/ChromeXt_all/issues) 时请附上浏览器名称及版本、Android 和 LSPosed 版本、复现步骤及适配日志。分享前请移除敏感网址和个人信息。

## 开发与构建

使用 JDK 21：

```bash
./gradlew app:assembleDebug
./gradlew app:assembleRelease
```

Windows 使用 `gradlew.bat`。构建及产物以 [GitHub Actions](https://github.com/YJ-Lazy/ChromeXt_all/actions) 为准。

当前脚本运行引擎仍为 ChromeXt；Violentmonkey 上游构建验证属于迁移研究，并非已完成 Android 运行时移植。

## 开源致谢

- [ChromeXt](https://github.com/JingMatrix/ChromeXt)
- [Violentmonkey](https://github.com/violentmonkey/violentmonkey)
- [Eruda](https://github.com/liriliri/eruda)
- [LSPosed](https://github.com/LSPosed/LSPosed)

[项目许可证](LICENSE) · [Violentmonkey MIT 许可证](third_party/violentmonkey/LICENSE) · [原版 README](README.upstream.md)

原版文档中的安装及兼容性说明不代表本项目的支持承诺。
