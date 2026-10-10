# Legado Android Compose 项目模式

在实现页面时阅读本参考。由于仓库正处于迁移过程中，请对照当前检出代码确认每一个引用的 API。

## 归属与放置

- Android 应用模块：`:app`。
- 规范的新 Feature 包：`io.legado.app.feature.<name>`。
- `ui/...` 是遗留/迁移区域。在某个 Feature 建立其规范包之后，只保留明确的兼容性归属者。
- `MainActivity` 拥有新的应用内 Navigation 3 目的地和根图。
- 保留的 Activity 负责转换稳定的外部/遗留 Intent 输入与结果；它不是第二个展示或导航归属者。
- 应用级 Koin 聚合保留在宿主中。使用仓库现有的 `viewModelOf` 或参数化 `viewModel` 约定，而不是新增第二种 DI 模式。

一个 Feature 可以包含 Contract、ViewModel、Route、Screen、组件、对话框、底部表单和展示模型，但只创建其行为所需的文件。目录形态不是验收标准。

## 状态与副作用

对于行为复杂的新页面，仓库期望：

- `@Stable` 的面向 Compose 的 `UiState` 和 UI item 模型；
- Compose 渲染边界处的不可变集合；
- 私有 `MutableStateFlow`，对外暴露为只读 `StateFlow`；
- 当确实需要尽力而为的瞬时副作用时，使用私有
  `MutableSharedFlow(extraBufferCapacity = 16)`，对外暴露为只读 `SharedFlow`；
- 单一 `onIntent` 分发器处理用户动作；
- 宿主动作表达为副作用或回调。

`@Stable` 是一种断言，而非魔法优化。每个公开属性都必须保持稳定并且其变化能被 Compose 观察到。仅在包装器具有正确的相等性和变更语义时才包装不稳定数据。在发明性能抽象之前，先使用度量/编译器报告。

将作为事实来源的业务状态保留在 ViewModel 中。局部 `remember`/`rememberSaveable` 适用于
UI 可供性以及可恢复的草稿/ID，而不是仓库状态的第二份副本。避免 UI 到 ViewModel 的反馈循环；在产出状态的 reducer 或 flow 中恢复一致性。

按投递语义对宿主动作分类：

- 用户点击的唯一含义是 UI 导航时，可以直接调用宿主回调；在可用的 Lifecycle 版本支持时使用生命周期感知的快速点击防护，例如 `dropUnlessResumed`。
- 必须能在缺失收集器的情况下存活的 ViewModel 结果，应变为带确认机制或另一种显式持久协议的状态。
- 有意为之的尽力而为的 Snackbar/toast/触觉反馈可以使用 Feature 副作用流。记录其丢失行为，而不是假设 `extraBufferCapacity` 就解决了问题。

## 宿主边界

除非项目已有经过测试的抽象，否则将以下内容保留在 route/host：

- 导航和结果投递；
- 权限和 Activity Result 启动器；
- 文件/文档选择器；
- Android 框架对话框和服务；
- 依赖 Context 的剪贴板、URI 和外部应用操作。

可复用的 Screen 函数接收状态和语义回调。它们不知道 Activity、binding、DAO、应用单例或根回退栈。

## 列表、生命周期与重组

- 当 item 身份在插入、移除或重排后仍然保留时使用稳定 key；当异构复用重要时使用 `contentType`。
- 使用 `collectAsStateWithLifecycle` 收集 Android route 状态，除非刻意且有文档说明地使用其他生命周期。对副作用收集器应用相同的投递分析；组合生命周期和宿主 `RESUMED` 状态不可互换。
- 按 `LaunchedEffect` 所代表的生命周期为其设置 key。对不应重启的副作用所捕获的会变化的回调，使用 `rememberUpdatedState`。
- 在组合中派生廉价的展示值；仅当其成本/生命周期值得时才记忆化或搬移工作。
- 优先使用面向 Compose 的不可变集合，同时让临时计算和数据层 API 保持其自然的集合类型。
- 在现代 Kotlin/Compose 编译器版本上，Strong Skipping 默认启用。不稳定参数按身份比较，因此避免不必要的实例抖振，但不要仅凭直觉添加包装器或 `@Stable`。仓库要求在 UI 状态/类型上使用该注解；这些类型必须真正满足其相等性和可观察变更契约。

## Insets 与返回

追踪每一层拥有哪个 inset。对于 Material 3 `Scaffold`，检查其配置的
`contentWindowInsets`，并验证内容正确地应用/消费所提供的 padding。底部表单、对话框、IME 和嵌套 scaffold 可能需要单独处理。需要视觉/手工证据；仅仅存在 `Scaffold`、`safeDrawing` 或 padding 修饰符并不能证明任何东西。

Navigation 3 集成并不能免除验证自定义返回拦截、选择模式、未保存更改和保留的 Activity 入口点的需要。将返回动作路由到同一归属者，由它决定是否允许离开。当手势进度驱动 UI 时使用 `PredictiveBackHandler`；
在自定义 `NavDisplay` 转场时，还要提供/测试预测性弹出行为。

## 自适应布局与无障碍

由于此应用以 API 37 为目标，大屏方向、宽高比和可调整大小限制不能用作兼容性回退。将当前应用窗口视为在旋转、折叠/展开、分屏和桌面窗口化下都是动态的。

- 对新的或大幅迁移的目的地，测试紧凑和扩展宽度。
- 使用当前窗口度量/窗口尺寸类来做布局决策；不要基于物理设备类别分支或假设竖屏。
- 使用合适的局部可保存状态或 `SavedStateHandle` 在重建时保留重要的输入/草稿/选择状态，具体取决于状态归属者和大小。
- 不要无限制拉伸手机布局。仅当 Feature 受益时才采用列表-详情/支持性窗格或自适应导航；避免仅为满足清单而添加库。
- 优先使用标准交互组件/修饰符。自定义指针输入需要语义动作、焦点/键盘访问和可用的触摸目标。

## 架构边界

新的 UI 和 ViewModel 不新增 DAO、`appDb`、网络客户端或旧偏好访问。使用现有的
Gateway/Repository/UseCase 契约，或添加带有真实调用方的最小真实边界。

对于严格仅为 UI 的迁移，可以保留现有的展示层违规以避免将架构重写与 UI 重写合并。冻结而非复制它，为其建立文档，在边界被更正之前不要将该页面描述为已完全现代化。

## 验证选择

- 仅 Kotlin：`:app:compileAppDebugKotlin`。
- 资源/清单/XML/生成的绑定/打包：`:app:assembleAppDebug`。
- 变更的状态转换：视情况使用带成功/失败/取消用例的聚焦单元测试。
- 导航或兼容性：演练 MainActivity route 以及每个保留的 Intent/result 入口。
- Insets、IME、无障碍和预测性返回：在风险需要时提供设备/模拟器证据。
- 新增/大幅变更的目的地：代表性的紧凑与扩展窗口检查，加上旋转/重建；当布局分支有意义时添加有针对性的自适应 UI 测试。

使用 `AGENTS.md` 获取规范化的完整验证集和精确的包装命令。

## 当前官方参考

- [State hoisting](https://developer.android.com/develop/ui/compose/state-hoisting)
- [Lifecycle-aware Compose collection](https://developer.android.com/topic/libraries/architecture/lifecycle)
- [UI event delivery](https://developer.android.com/topic/architecture/ui-layer/events)
- [Strong Skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)
- [Compose side effects](https://developer.android.com/develop/ui/compose/side-effects)
- [Material Insets](https://developer.android.com/develop/ui/compose/system/material-insets)
- [Predictive back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
- [Adaptive layouts for resizable apps](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
