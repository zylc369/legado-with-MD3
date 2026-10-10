---
name: legado-compose-review
description: 审查现有 Legado Android Compose 屏幕、路由、ViewModel、契约、对话框、Sheet、导航和兼容宿主，发现具体的行为、生命周期、状态归属、架构、Inset、无障碍和可维护性风险。用于审查或审计请求。除非明确要求修复，否则不要重写代码；实现使用 legado-compose-migration，多平台或 Gradle 边界问题使用 legado-kmp-migration。
---

# Legado Compose 审查

## 目的

在提议重写之前，先找出具体缺陷和有风险的漂移。依据当前仓库行为和约束来评判代码，而不是某种偏好的模板。

阅读 `AGENTS.md`、范围内的文件和
[references/review-checklist.md](references/review-checklist.md)。仅当旧 View 实现仍是调用方或行为基线时，才查阅它。

## 审查工作流

1. 明确确切的审查范围，以及用户是要仅审查还是要修复。仅审查的工作不授权修改。
2. 检查最小的完整行为切片：Screen、状态宿主/契约、路由/宿主、DI、
   相关 repository/use case、资源和兼容入口点。
3. 端到端追踪状态和 effect。在关注风格问题之前，先寻找互相分歧的 owner、被重复执行的一次性工作、生命周期
   错误、丢失的事件、不稳定的 identity，以及错误/取消路径。
4. 通过真实宿主追踪导航/结果、Insets/IME、返回、无障碍和平台 launcher。不要因为存在某个特定 API
   就推断其正确。
5. 先报告发现，附上精确的行引用、具体影响和最小可信的修复方案。
   将保持兼容性的修复与可选的重设计分开。
6. 若未发现问题，就明确说明，并列出重要的未验证运行时/设备路径。

## 严重程度

- **P0/P1：** 崩溃、数据/行为丢失、导航/结果/入口兼容性破坏、不安全的
  生命周期或并发、重复的破坏性 effect、无法访问/被遮挡的关键内容。
- **P2：** 可能分歧的状态归属、UI 中的业务/数据访问、平台泄漏、不正确的
  稳定性契约、反复出现的重组/性能问题，或带来真实维护成本的架构漂移。
- **P3：** 局部的约定、命名、可测试性或清理问题，没有可证明的行为
  或结构性影响。

不要把缺少某个注解、不同的文件拆分或包装器选择提升为 P2，除非你能解释其可观察的或架构上的后果。

## 边界

- 不要要求每个屏幕都有相同数量的文件或抽象。
- 当轻量兼容 Activity 和成熟的 View/平台孤岛有当前调用方且只有一个明确 owner 时，允许其存在。
- 新的 Compose 展示层不得新增 DAO、网络、存储或服务访问；报告既有
  债务，但不要把审查变成未经授权的领域重写。
- 把 `@Stable` 视为本身可能是错的契约，而不是稳定性或性能的证明。
- 现代 Kotlin/Compose 编译器版本默认启用 Strong Skipping，因此
  不稳定的参数不会自动构成性能缺陷。检查 identity 变化、实际
  重组证据和编译器/基准数据。缺少项目要求的注解是 P3，
  除非它导致具体的架构或运行时问题；错误的 `@Stable` 承诺可能是
  P2，因为它会抑制必需的更新。
- Android `Flow` 状态通常应使用 `collectAsStateWithLifecycle` 收集。
  组合作用域的收集器并不会仅仅因为使用了 `LaunchedEffect` 就自动具备生命周期感知能力。
- 按投递语义审查每个源自 ViewModel 的 `SharedFlow` effect。缓冲容量
  在无 UI 收集时并不能使事件变得持久。导航、结果投递、支付、
  破坏性完成以及其他一致性关键的结果通常应归约为状态，
  或使用显式的确认协议；尽力而为的瞬时反馈可以保留为 effect。
- 仅涉及 UI 的行为应停留在最低的 UI owner。由宿主处理的
  直接用户导航回调是有效的，而且往往优于 ViewModel 往返；在可用的 Lifecycle 版本
  支持时，使用诸如 `dropUnlessResumed` 这类生命周期感知机制来防护快速/重复的导航。
- Insets 和 predictive back 需要归属和运行时路径分析；仅有 `Scaffold`、padding API、
  Navigation 3 或 `BackHandler` 并不能证明正确性。
- 当自定义 UI 需要手势进度时，使用 `PredictiveBackHandler`；仅当二元拦截足够时，
  才使用普通的 `BackHandler`。当自定义 Navigation 3 转场时，检查
  `predictivePopTransitionSpec` 以及正常的 pop 行为。
- 仅当项目组件的行为、语义和无障碍适配时，才优先复用。
- 优先使用 Material/Foundation 交互 API，因为它们提供语义、焦点和输入
  行为。自定义手势/组件必须保留 role、状态/动作语义、键盘/D-pad
  访问和足够的触摸目标。

## 当前官方基线

当库行为很重要时，依据仓库版本和当前官方文档加以验证：

- [Strong Skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)
  以及 [stability contracts](https://developer.android.com/develop/ui/compose/lifecycle)。
- [Lifecycle-aware Compose collection](https://developer.android.com/topic/libraries/architecture/lifecycle)。
- [State hoisting](https://developer.android.com/develop/ui/compose/state-hoisting)。
- [UI event delivery](https://developer.android.com/topic/architecture/ui-layer/events)。
- [Material 3 Insets](https://developer.android.com/develop/ui/compose/system/material-insets)
  以及 [Compose Insets](https://developer.android.com/develop/ui/compose/system/insets-ui)。
- [Predictive back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
  以及 [Navigation 3 transitions](https://developer.android.com/guide/navigation/navigation-3/animate-destinations)。
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
  以及 [semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)。

当请求实现时，还要阅读迁移 skill 的项目模式。
