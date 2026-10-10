# KMP 切片检查清单

实现计划、抽取工作、脚手架或审查时使用本参考。默认路径是渲染器中立的 KMP。CMP 检查仅在选定共享
Compose 渲染器之后适用；它们不是把每个 Feature 或宿主都转换为 Compose 的指令。

## 变更前

- 范围明确到确切的文件/包、当前调用方、一个边界和一个回滚点。
- 现有行为由测试或书面的对等性清单捕获。
- 目标 owner、source set 和消费者是真实的，而非从目标图示照搬。
- 依赖被分类为 common-ready、contract-needed、renderer-specific、platform-island 或 unknown。
- 已检查实际的 Gradle target 和验证任务名。

## 契约质量

- 公共类型表达领域值，而非 Android/JVM/存储/渲染细节。
- 错误、取消、线程、事务、顺序、序列化和归属语义在跨越边界处显式表达。
- 能力缺失对调用方可见。
- 除非 `expect/actual` 提供具体的静态收益，否则使用普通接口。
- 每个抽象都有真实调用方，并移除可度量的依赖。
- Native 或 IPC 导出比内部 Kotlin API 更小、更稳定。

## 模块与 source-set 图

- app host 负责实现聚合、DI、导航/窗口生命周期和打包。
- Core 不导入 Feature；Feature 不导入另一个 Feature 的实现。
- 共享模块不依赖平台实现或渲染器模块。
- Gradle `api` 暴露是有意为之；否则使用 `implementation`。
- 包共置、Android 模块抽取、KMP 转换和渲染器共享保持为独立变更，除非证据要求合并它们。
- `commonMain` 在被称作共享之前，至少被一个非 Android target 编译。
- 仅 JVM+Android 的共享有一个诚实的 owner，不被错误标记为 common。
- 平台文件位于最窄的相关 source set 中。
- Android 服务、Context/URI/资源和通知留在 Android 侧。
- Rhino/JS 行为留在能力边界之后，直到另一个 target 有兼容且经过测试的实现。

## 渲染器与宿主检查

- 表现层契约不包含 Compose、Material/Miuix、Fluent、WinUI、SwiftUI 或平台资源类型。
- 渲染器拥有自己的资源、无障碍、布局和导航表现。
- 共享 Compose UI 发出语义回调/effect；宿主拥有平台启动器。
- 一个 CMP Screen 至少有两位有意为之的消费者，或有其他有文档记录的产品理由。
- WinUI/iOS 桥接定义了版本控制、生命周期、取消、错误和内存归属。
- sidecar 协议定义了启动、就绪、关闭、崩溃恢复、升级和本地访问行为。

## 门禁

- 受影响的 Android 单元/lint/架构/打包门禁通过。
- Common 测试以及实际 metadata/target 编译任务通过。
- 能力状态区分 compile、contract-test、smoke、package 和 release-ready。
- 适配器测试在有意义处覆盖成功、失败和取消。
- 序列化/数据库变更包含向前/向后兼容或迁移证据。
- 阅读器/规则/服务变更包含对等性证据，并在相关处包含真机性能证据。
- 减少的历史违规在同一变更中同步下调其基线。
- `git diff --check` 通过。

## 脚手架

- 至少两个已接受的手工示例证明某项约定后，才将其生成。
- 提供 dry-run，且默认执行拒绝覆盖。
- 现有 graph/DI 文件被安全地追加修改，而非整体替换。
- 只生成必要的文件；无空层或无端推测的 target。
- 生成器行为有 fixture、snapshot 或编译证据。

## 审查输出

对每个发现给出精确的 file/line 引用、具体影响和最小可信修复：

- P0/P1：行为/数据丢失、不兼容的规则/存储/ABI 语义、target 损坏、生命周期、线程、取消、
  native 归属或打包缺陷。
- P2：非法依赖、渲染器/平台泄漏、虚假能力、状态重复、基线放宽或不必要的抽象。
- P3：约定、命名、文档、graph 或模板漂移。

若未发现问题，说明剩余未验证的宿主、设备、打包、互操作和性能风险。
