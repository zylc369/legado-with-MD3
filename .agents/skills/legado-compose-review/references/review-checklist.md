# Legado Compose 审查清单

仅使用与当前审查范围相关的章节。

## 上下文

- 确认规范的 Feature 归属方，以及是否存在某个遗留 `ui/...` 文件属于兼容性归属方。
- 阅读 Screen/Content、ViewModel/状态契约、route/Activity、DI 以及直接使用的 domain/data 契约。
- 仅在需要对齐行为或存在实际调用方时，才阅读旧的 XML/View/adapter。
- 识别所有入口点：MainActivity route、Intent/deep link、notification/widget/service 或 Activity Result。

## 行为与状态

- 是否存在唯一持久的状态归属方，还是 ViewModel、Activity、`remember`、adapter 与 repository 的副本可能分叉？
- 每个状态是否只提升到需要读写它的最低归属方？局部展开、焦点或动画状态无需强制放入 ViewModel；业务状态不得复制进 Compose 局部状态。
- 在需要时，loading、empty、error、selection、query、sorting 以及 dialog/sheet 状态能否在重建后复原？
- 一次性 effect 是否只被消费一次，而不会变成可持久重放的标志位？
- 当 route 没有活跃 collector 时，ViewModel 产生的 effect 是否仍可能被发出？若丢失它会导致导航、结果或持久业务状态不一致，应将其建模/确认为状态，而不是依赖 `SharedFlow` 缓冲。
- 是否处理了成功、失败、取消以及陈旧响应的顺序？
- `LaunchedEffect` 的 key 是否匹配预期生命周期，并避免重复的加载/导航/toast？
- 由 `Flow` 支撑的 Android UI 状态是否使用 `collectAsStateWithLifecycle` 收集，或者存在针对其他 collector 的成文生命周期理由？
- 可变集合/实体暴露的方式是否会破坏所声称的 `@Stable` 契约？
- 在 Strong Skipping 下，不稳定参数是否在每次更新时被重建并按标识比较？在对普通重组判定为缺陷之前，先要求提供编译器/测量证据。
- 在插入/移除/重排时，懒加载 item 是否使用稳定标识，以避免状态附着到错误的行？

## 架构与归属

- Screen/Content 渲染状态并发出语义动作；它不访问 DAO、repository、网络、存储、service 或全局应用状态。
- 新 ViewModel 代码使用 Gateway/Repository/UseCase 边界，不新增 DAO 或旧的全局访问。
- 宿主负责导航、框架 launcher、权限、Context/URI 相关工作以及根生命周期。
- 保留的 Activity 转换兼容性输入/结果，但不成为第二个业务或导航归属方。
- Feature 代码不依赖另一个 Feature 的实现。
- 在规范 Feature 归属方已存在后，新文件不再扩展遗留包。

## 导航与兼容性

- 新的应用内目的地注册在当前 MainActivity 图中，除非有外部入口需求足以支持使用 Activity。
- 现有的 extras、result code、deep link 与调用方保持语义不变。
- 多条入口路径初始化等价状态，并汇聚到同一归属方。
- 嵌套 UI 通过回调/effect 请求导航，而不是直接改动根栈。
- 纯 UI 导航可以直接调用宿主回调。检查快速连点与生命周期状态；在支持的情况下使用如 `dropUnlessResumed` 这类生命周期感知的防护，而不是仅为挪动导航调用而凭空创建一个 ViewModel effect。
- 返回拦截覆盖真实的选中/未保存状态，同时不破坏 predictive back 或宿主结果投递。
- 自定义手势进度 UI 使用 `PredictiveBackHandler`；定制化的 Navigation 3 弹出转场同样需要定义/验证 predictive-pop 行为。

## UI、Insets 与无障碍

- 追踪 `Scaffold` 的 `contentWindowInsets`、inner padding 的消费、嵌套 scaffold/sheet、系统栏与 IME。检查重叠与双重 padding 两种情况。
- 面向用户的字符串保留本地化与回退行为。
- 当其语义适配时使用项目组件/主题；不要机械地套用包装器。
- 触摸目标、焦点顺序、semantics/content description 与键盘行为适配对应控件。
- 自定义指针输入具备可访问的语义动作以及键盘/D-pad 替代方案；当标准 Material/Foundation 交互行为适配时优先使用。
- 列表/图片在相关处保留现有的缓存、加载与无障碍行为。
- 被移除的 XML/binding/menu 资源不再有任何引用。

## 验证与输出

将每项发现关联到证据与最小修复方案。仅推荐能够观察到该风险的检查：

- 针对 Kotlin 接线的编译；
- 针对资源、manifest、XML/binding 与打包的 assemble；
- 针对 reducer/ViewModel/use case 的聚焦单元测试；
- 针对导航兼容性的 route 加保留的 Intent/result 路径检查；
- 针对 Insets、IME、无障碍、predictive back 与渲染性能的设备/模拟器检查。

使用 skill 中的 P0/P1/P2/P3。若没有发现，明确列出未执行或未观察到的内容。
