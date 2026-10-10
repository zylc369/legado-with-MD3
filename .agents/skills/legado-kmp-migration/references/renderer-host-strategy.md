# 渲染器与宿主策略

当一个 Feature 可能使用多个 UI 技术栈，或非 Kotlin 宿主需要消费共享 Kotlin 行为时，阅读本参考。

## 区分三个决策

1. **共享行为：** domain、repositories、use cases、state snapshots、commands 和 reducers。
2. **宿主集成：** Gradle/JVM、Apple framework、Windows DLL/C ABI，或 process/IPC。
3. **渲染器：** Android Material/Miuix、Compose Material/Fluent、WinUI 3、SwiftUI/UIKit，或
   专门的平台孤岛。

不要从一个决策推断另一个决策。KMP core 并不要求 CMP，一个宿主上的 Compose 也
不要求每个宿主共享同一个 Screen。

## 渲染器无关的表现层

优先使用不可变 state snapshots、稳定 ID、commands/intents、effects、领域值和显式的
capability/error states。将 Composable lambdas、`Modifier`、Material/Fluent 类型、
`StringResource`、icons/painters、navigation UI 对象、Android Context、Swift/WinRT 对象、Room
实体和 DI 容器排除在边界之外。

`@Stable` 属于面向 Compose 的 contract 或 adapter。如果 WinUI 3 或 SwiftUI 也要消费
state，则让纯 state 保持不含渲染器注解，并在 Compose 边界处适配它。

## WinUI 3 的选择

WinUI 3 不能像 Kotlin 宿主那样消费 Kotlin/JVM Gradle 模块。需显式选择桥接方式。

### Kotlin/Native DLL 与 C ABI

当进程内调用和单一打包进程很重要，且所需的依赖闭包支持该 target 时，使用 `mingwX64`
共享库。

- 导出一个小的 facade，而非 repositories、Flow、sealed hierarchies 或泛型 Kotlin API。
- 使用带显式 create/dispose 的不透明句柄。
- 为每个返回的字符串或 buffer 定义所有权。
- 将异步工作映射为 request IDs 加 callbacks/polling；定义 callback thread 与取消。
- 对 ABI 进行版本管理，并在 CI 中编译 native 消费者。
- 不要将 JVM-only 的 Room、Rhino 或 jsoup 依赖强行放入 DLL；为这些能力拆分一个
  native-compatible core，或改用 IPC。

### JVM sidecar 与 IPC

当复用比进程内集成更重要时，将现有的 JVM 数据/运行时栈保留在单独打包的进程中，并暴露一个
带版本的本地协议。

- 定义协议 DTO 和版本协商。
- 定义启动、就绪、关闭、崩溃恢复和升级行为。
- 限制本地访问；不要无意中暴露未认证的网络服务。
- 传播取消和结构化错误。
- 将两个进程作为一个产品打包并测试。

在选定产品范围的桥接方式之前，先使用一个有界的读/写 Feature 比较冷启动、调用延迟、状态映射、
数据库与运行时复用、内存所有权、崩溃隔离、安装器复杂度和测试易用性。

## iOS native 渲染器

导出一个刻意保持精简的 Apple framework facade。SwiftUI 可以观察一个经过测试的共享 state host，
或拥有一个调用共享 repositories/use cases 的 `ObservableObject`。用 Swift 消费者测试验证 Swift
名称、optionality、collections、async/Flow 桥接、生命周期、取消和内存所有权。

## 选择性 CMP

只有当真实宿主有意共享其视觉与交互模型时，才共享一个 CMP Screen。
资源和设计系统组件属于该渲染器。如果 Desktop 使用 WinUI 3、iOS
使用 SwiftUI，那么即使 Android Compose 的代码在技术上可移植，它也可以继续作为 Android 渲染器。
