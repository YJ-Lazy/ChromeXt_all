# ChromeXt · Android 浏览器脚本增强

[![Android CI](https://github.com/YJ-Lazy/ChromeXt_kuake/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/YJ-Lazy/ChromeXt_kuake/actions/workflows/android.yml)
[![Violentmonkey validation](https://github.com/YJ-Lazy/ChromeXt_kuake/actions/workflows/violentmonkey.yml/badge.svg?branch=master)](https://github.com/YJ-Lazy/ChromeXt_kuake/actions/workflows/violentmonkey.yml)

基于 [ChromeXt](https://github.com/JingMatrix/ChromeXt) 的 Android 浏览器增强模块，重点适配夸克浏览器，并逐步扩展至其他 Chromium、WebView 浏览器。

**使用 LSPosed 即可，模块 APP 本身无需 Root 授权。** 当前不提供免 Root 框架或 APK 内嵌适配保证。

[下载构建](https://github.com/YJ-Lazy/ChromeXt_kuake/actions) · [问题反馈](https://github.com/YJ-Lazy/ChromeXt_kuake/issues) · [原版文档（英文）](README.upstream.md)

## 功能

- 用户脚本安装、匹配及执行，沿用 ChromeXt 的 GM API 实现。
- 夸克 UC/U4 内核专用适配及浏览器内悬浮入口。
- APP 内统一脚本清单、源码查看、搜索、按浏览器分配及启停。
- 跨浏览器脚本源码同步，各浏览器的 GM 存储独立。
- 自动发现浏览器，按运行时内核特征尝试适配，不再仅依赖固定 Chromium 包名。
- Eruda 调试工具及原有浏览器菜单能力（依赖具体内核）。
- 独立诊断日志：记录适配过程、异常堆栈及同步状态，支持刷新、复制、分享和删除。
- 原生卡片式管理界面，支持系统深色模式。

以上为当前源码实现范围，并非所有浏览器均已通过真机验证。

## 安装与使用

1. 在已安装 LSPosed 的设备上，从本仓库 [GitHub Actions](https://github.com/YJ-Lazy/ChromeXt_kuake/actions/workflows/android.yml) 选择成功的构建，下载并解压 APK 产物。GitHub 可能要求登录。
2. 安装 APK（测试可使用 Debug APK；未签名 Release APK 需自行签名）。
3. 在 LSPosed 中启用 ChromeXt，并勾选需要增强的浏览器作用域。
4. 强制停止并重新打开浏览器，访问网页。
5. 通过浏览器内菜单或夸克悬浮入口安装用户脚本（如 `.user.js` 链接）。
6. 打开 ChromeXt APP，在脚本页或浏览器页管理脚本分配。刷新网页后应用配置。

不需要为 Android 系统、System UI 或所有 WebView 宿主应用启用模块。无需向模块 APP 单独授予 Root 权限。

## 浏览器兼容性

| 浏览器 | 适配情况 |
| --- | --- |
| 夸克 | UC/U4 专用适配；已有基础注入验证，最新版本待回归 |
| Chrome、Brave、Kiwi、Vivaldi 等 | 继承上游 Chromium 适配，具体版本待验证 |
| Edge、三星浏览器等 | 尝试运行时 Chromium 检测 |
| Via 等 | 尝试通用 WebView 适配 |
| 小米浏览器、360 浏览器 | 保留原有专用路径 |
| UC、QQ 浏览器等 | 待验证或补充专用内核适配 |
| Firefox | Gecko 内核尚无专用适配 |

LSPosed 作用域开放不等于内核兼容。浏览器必须具备可用的注入路径；APP 自动发现和跨进程同步还依赖系统浏览器识别及包可见性。

## 跨浏览器脚本管理

在浏览器 A 安装脚本并加载网页后，模块尝试将脚本源码同步到 APP。随后可在 APP 中为浏览器 B 启用该脚本；浏览器 B 下次注入时尝试导入并执行匹配脚本。

- 启停配置在下次页面注入时生效，不会立即终止已经运行的脚本。
- 当前为基础同步实现，单次源码传输约 200 KB 上限。
- 同名脚本版本冲突、源码更新传播、统一删除及 APP 内完整安装/编辑仍待完善。
- 浏览器菜单与 APP 的启停状态尚未完全统一。

## 适配反馈与日志

如果浏览器无法使用：

1. 检查 LSPosed 模块和目标浏览器作用域是否启用。
2. 强制停止浏览器后重新打开，复现问题。
3. 进入 ChromeXt APP → 日志 → 刷新 → 一键复制适配日志。
4. 在 [Issues](https://github.com/YJ-Lazy/ChromeXt_kuake/issues) 提供浏览器名称、版本、Android 版本、LSPosed 版本、复现步骤及日志。

模块通过跨进程接口将日志保存到 APP 私有文件，不再通过 `su` 读取系统日志。删除日志仅删除模块自己的日志文件，不影响其他模块或系统日志。若模块未加载或通信失败，请补充 LSPosed 管理器中的相关日志。提交前请检查并移除个人信息及敏感网址。

## 开发状态

- [x] 夸克专用注入适配与悬浮入口
- [x] APP 脚本及浏览器管理界面
- [x] 基础跨浏览器脚本分配
- [x] 动态浏览器检测
- [x] 独立日志存储及删除（源码已实现，待验证）
- [ ] 完整脚本安装、编辑、删除及版本同步
- [ ] 常见浏览器真机兼容性回归
- [ ] 无 APP Root 授权场景回归
- [ ] Violentmonkey 完整 Android 运行时移植

目前实际脚本运行仍使用 ChromeXt 的实现。仓库已引入 Violentmonkey 上游构建验证流程，但这不代表其完整运行时已移植。

## 构建

使用 JDK 21，在项目根目录执行：

```bash
./gradlew app:assembleDebug
./gradlew app:assembleRelease
```

Windows 使用 `gradlew.bat`。构建状态及产物以本仓库 Actions 为准。

## 致谢与许可证

- [JingMatrix/ChromeXt](https://github.com/JingMatrix/ChromeXt)：原始项目及用户脚本实现。
- [Violentmonkey](https://github.com/violentmonkey/violentmonkey)：迁移研究及上游验证。
- [Eruda](https://github.com/liriliri/eruda)：网页调试工具。
- [LSPosed](https://github.com/LSPosed/LSPosed)：Hook 框架。

保留项目 [LICENSE](LICENSE) 与 [Violentmonkey MIT 许可证](third_party/violentmonkey/LICENSE)。原版功能、API 及开发说明见 [README.upstream.md](README.upstream.md)；其中的上游安装方式及兼容性描述不代表本分支的支持承诺。
