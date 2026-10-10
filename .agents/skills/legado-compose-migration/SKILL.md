---
name: legado-compose-migration
description: 使用 Jetpack Compose 创建或迁移 Legado Android 屏幕，同时保留行为并遵循仓库的 Feature 归属、UDF、导航、DI、inset 和兼容性边界。用于 XML/View/RecyclerView/DialogFragment 迁移和新的 Android Compose 目的地。仅做审查时请改用 legado-compose-review，当变更跨越多平台或 Gradle 边界时请改用 legado-kmp-migration。
---

# Legado Compose 迁移

## 目的

交付一个可用的 Android UI 界面，同时不把 View 时代的归属引入新的 Compose 代码。
对于迁移，在清理前先保留可观察行为。对于新屏幕，使用仓库当前的
Feature-first 和 UDF 约定。

编辑之前，请阅读 `AGENTS.md`、`docs/dev/feature-first-structure.md` 的相关部分、
当前实现，以及一两个行使相同行为的邻近示例。阅读
[references/project-patterns.md](references/project-patterns.md) 了解仓库特定的归属放置、
状态和宿主细节；示例是证据，而不是可以盲目复制的模板。

## 确定形态

- **新目的地：** 优先选择 `MainActivity` 的 Navigation 3 目的地。不要在没有外部入口或平台
  生命周期理由的情况下创建独立 Activity。
- **迁移目的地：** 仅当现有调用方需要稳定的 Android Intent extras/results 或框架契约时，
  才将 Activity 保留为薄兼容宿主。
- **部分迁移：** 当替换成熟的 View 渲染或平台集成会引入与请求无关的行为风险时，将其保留为
  显式的孤岛。
- **多平台边界：** 不要只把它当作 Android 屏幕迁移，同时也要使用
  `legado-kmp-migration`。

## 工作流

1. 界定界面范围和成功标准。记录必须保留的输入、导航/结果、空/加载/错误
   状态、动作、对话框/底部弹层、持久化、返回行为和外部副作用。
2. 阅读最小的完整切片：UI、状态所有者、宿主/路由、DI、资源、数据/用例
   调用，
   以及作为行为参考所需的任何旧实现。
3. 选择读取和写入每个值的最低状态所有者。将持久性业务/渲染状态
   放入 `UiState`；当没有更高层的所有者需要时，将纯本地的焦点、展开、动画和草稿状态留在 UI 中。将
   Feature/业务动作经由 Feature intent 类型处理。仅请求宿主导航的直接
   用户动作可以使用宿主回调。对话框/底部弹层可见性
   属于 UI 状态；Android 框架对话框、权限、文件选择器和启动留在宿主中。
4. 让 Screen/Content 不直接访问 repository、DAO、网络、存储和服务。新的
   呈现
   代码使用 Repository/Gateway/UseCase 边界。仅当任务明确限定为 UI 时才保留现有违规，
   不要扩散它，并记录遗留债务。
5. 在宿主边界处接线导航、DI 和兼容行为。保持公共 extras、结果
   码和深链接语义稳定，除非用户请求 API 变更。
6. 仅在引用和兼容调用方都
   消失后才删除 XML、适配器、绑定和资源。
7. 按风险比例进行验证，并同时报告已通过的证据和未验证的手动/设备路径。

## 项目约束

- 新归属位于 `io.legado.app.feature.<name>` 下；遗留的 `ui/...` 是迁移来源，
  而不是第二份实现的目的地。
- 按 `AGENTS.md` 的要求，对 Feature/业务动作使用 ViewModel 持有的只读 `StateFlow`
  和单个 `onIntent` 分发器。当 Feature 有尽力而为的瞬时副作用时，暴露
  项目的 `SharedFlow(extraBufferCapacity = 16)` 形态。在没有 UI 收集时，缓冲容量并不能让发射
  变得持久：导航/结果/破坏性完成以及其他
  一致性关键的 ViewModel 结果必须归约为状态，或使用显式的确认
  协议，而不是依赖副作用流。
- 仅在对齐注解契约时才用 `@Stable` 标注面向 Compose 的 `UiState` 和 UI item 模型：
  公共可变属性和不可观察的变更会使其不正确。
  在渲染边界使用不可变集合；不要机械地更改 repository/数据集合。
- 在 Android 上，使用 `collectAsStateWithLifecycle` 收集 `Flow` UI 状态，除非有文档化的
  生命周期要求需要其他方式。`LaunchedEffect` 收集器的作用域是
  组合，而非自动绑定到 `STARTED`/`RESUMED`。长期存在的副作用必须使用合适的
  key，并对不应重启它们的变化值使用 `rememberUpdatedState`。
- 当项目主题和组件的行为合适时复用它们。仅凭包装器的存在并不足以
  成为使用它的理由，当语义、无障碍或宿主需求不同时尤甚。
- Insets 是一项归属决策。检查 `Scaffold` 的 `contentWindowInsets`、内容
  是否消费 `innerPadding`、嵌套 scaffold/sheet 和 IME 行为。不要假设仅使用
  `Scaffold` 就能证明 edge-to-edge 正确性，也不要重复应用系统栏 padding。
- 保留 predictive-back 行为。仅对二元拦截使用普通的 `BackHandler`；当自定义 UI 需要
  手势进度时使用 `PredictiveBackHandler`。如果自定义了 Navigation 3 过渡，
  请同时定义并验证 predictive-pop 行为以及正常的 pop 过渡。
- 应用的目标为 API 37，在该版本中，方向、宽高比和可调整大小限制不再
  在大屏上保护布局。新的或大幅迁移的目的地必须容忍
  紧凑、中等和扩展的可调整大小窗口、旋转和重建。基于
  当前应用窗口而非物理设备假设来做布局决策；除非屏幕行为从中受益，否则不要添加自适应库或
  多窗格布局。
- 优先使用 Material/Foundation 交互，以获得其语义、焦点和键盘行为。自定义
  控件和手势必须提供合适的 role/state/action 语义、键盘/D-pad 访问
  和足够的触摸目标。
- 将本地化的面向用户文本保留在资源中，并保留回退行为。

## 验证

- 仅涉及 Kotlin 的呈现/宿主变更：使用仓库针对当前 shell 的
  Gradle wrapper 语法运行 `:app:compileAppDebugKotlin`。
- 资源、manifest、XML/绑定删除或打包变更：运行 `:app:assembleAppDebug`。
- 状态/业务变更：运行针对性的 ViewModel/用例测试，或添加一个特征化接缝。
- 导航/Intent/结果/insets/返回：手动或通过 instrumentation 遍历编译无法证明的每条保留入口
  路径。
- 新的或大幅更改的布局：遍历有代表性的紧凑和扩展可调整大小
  窗口、旋转/重建和 IME 交互，而不是只验证一台竖屏手机。
- 始终运行 `git diff --check`。

使用此检出中的真实任务名；不要根据本 skill 臆造任务。
