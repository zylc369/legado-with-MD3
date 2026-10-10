---
name: legado-kmp-migration
description: 规划、实现或审查 Legado 的 KMP 优先模块化，覆盖共享的 domain、data 和 presentation 代码，并独立选择 Android、Desktop、Windows 原生及可选的 iOS 渲染器。适用于 Gradle 边界、commonMain 抽取、平台能力、原生或进程桥接、选择性 CMP、目标门禁以及遗留 owner 移除。除非同时改变多平台或 Gradle 边界，否则不要用于仅限 Android 的 View 到 Compose 重写。
---

# Legado KMP 优先迁移

## 目的

将一个已验证的职责推向可复用的 KMP 边界，同时不削弱 Android 行为，也不强迫每个宿主使用 Compose。以共享行为和明确的平台契约作为优化目标，而非追求最大共享 UI 占比。

有效的渲染器包括 Android Compose/View 岛、使用 Material 或 Fluent 的 Compose Desktop、Windows 原生 WinUI 3，以及可选的 SwiftUI/UIKit。CMP 只是一种渲染器技术，而不是架构之根。

行动之前，先阅读仓库 `AGENTS.md`、`docs/dev/kmp-cmp-modernization.md`，以及 `docs/dev/feature-first-structure.md` 的相关部分。

仅在适用时阅读支撑性参考：

- 实施或审查：[references/slice-checklist.zh-cn.md](references/slice-checklist.zh-cn.md)。
- 多渲染器、WinUI 3、SwiftUI/UIKit、原生导出或 IPC：
  [references/renderer-host-strategy.zh-cn.md](references/renderer-host-strategy.zh-cn.md)。

## 选择工作模式

- **架构/规划：** 盘点当前依赖与消费者，然后确定共享边界、渲染器策略、目标、门禁和回滚点。
- **纯 KMP 抽取：** 将稳定的模型、规则、端口、用例、reducer 或 store 移入不依赖 Compose 的 `commonMain`。
- **数据/运行时抽取：** 将 domain 契约与存储、网络、文件和规则引擎分离，同时在契约测试下保留兼容语义。
- **渲染器边界：** 将渲染器中立的 presentation 与 Material/Miuix、Fluent、WinUI 3、SwiftUI/UIKit 或专用阅读器 UI 分离。
- **选择性 CMP：** 仅当真实宿主有意选择相同的渲染器和交互模型时，才共享 Compose Screen/资源集。
- **原生宿主桥接：** 通过 Apple framework、Windows DLL/C ABI 或进程/IPC 边界暴露一个小型、带版本的 API；不要导出内部 Kotlin 对象图。
- **构建逻辑：** 在更大范围推广之前，先用一个有代表性的模块证明目标/约定/门禁变更。
- **审查：** 优先报告发现和未验证的目标；仅在请求修复或方案时才编辑。

## 实施前必须做出的决策

1. **共享什么？**
    - 默认候选是 domain 值、业务规则、repository 端口、用例、序列化模型、状态快照、命令和 reducer。
    - Screen、设计系统组件、导航渲染器、平台 ViewModel owner、资源句柄、生命周期和 OS 集成默认不共享。
    - 纯 presentation API 不得暴露 Compose 注解/类型、Material/Fluent 类型、Android 资源、Koin、Room 或平台 SDK 对象。

2. **谁来渲染它？**
    - 分别命名每个承诺支持的宿主和渲染器；“Desktop”不是渲染器。
    - 不同渲染器可以消费相同的 presentation 契约，同时各自拥有不同的布局、资源、导航和无障碍。
    - 不要跨无关的设计系统发明通用 UI 包装器。仅当其含义真正通用时才共享语义值。

3. **非 Kotlin 宿主如何消费它？**
    - JVM Compose 宿主可以直接依赖 KMP/JVM 模块。
    - SwiftUI/UIKit 需要一个刻意导出的 Apple framework 门面。
    - WinUI 3 需要一个带有窄 C ABI 的 Kotlin/Native `mingwX64` DLL，或一个单独打包、通过带版本的本地 IPC 协议访问的 JVM 进程。
    - 将有内容的 Kotlin 类型保持为内部。在桥接处定义 DTO/状态快照、命令、错误、取消、回调线程、分配所有权和释放。

4. **有哪些证据支撑该主张？**
    - 区分元数据/编译、契约测试、宿主冒烟、打包和可发布级别的证据。
    - Android 加 JVM 编译并不能证明 iOS 或 Windows 原生就绪。
    - 在模块、目标、任务或宿主于本仓库中实际存在之前，不要将其记录为当前状态。

## 工作流程

1. 限定一个切片：确切的文件、调用方、行为、预期 owner/source set、宿主影响和回滚点。
2. 将依赖分类为 `common-ready`、`contract-needed`、`renderer-specific`、`platform-island` 或 `unknown`。import 只是第一遍；编译每一个所声称的目标。
3. 在迁移行为之前建立刻画测试或契约测试。保留存储、脚本 ABI、序列化、错误、取消、顺序和线程语义。
4. 选择最小的接缝。优先使用接口加构造函数注入；仅在真正的平台原语或已记录、注入无法提供边界的情况下使用 `expect/actual`。
5. 以增量方式实现，迁移一组有界的调用方，然后在无调用方残留时删除旧 owner。不要为了 import 兼容性保留内部 `Help/Utils/Base/Provider` 门面。
6. 运行真实的仓库门禁和目标任务。绝不要从目标设计推断任务支持。
7. 报告行为证据、依赖/基线增量、能力变更、确切命令、回滚路径以及未验证的宿主/设备。

## 边界规则

- 纯 KMP 的 domain/presentation 不依赖 Compose、AndroidX ViewModel、Room 实体/DAO、Koin、平台资源、`File`/URI、JVM-only 库或渲染器类型。
- 渲染器模块可以依赖 presentation；presentation 绝不依赖渲染器。
- 每个宿主拥有自己的 composition root、根导航/窗口生命周期、平台效果、实现选择和打包。
- 不支持的能力要显式表达；成功的 no-op 不是实现。
- 仍然禁止 Feature 到 Feature 的实现依赖。仅为真实的 Gradle 消费者创建 `api/impl`；按职责拆分渲染器，而不是使用通用的分层名称。
- 不要在同一个切片中同时变更存储、网络、DI、导航和 UI 技术。
- 历史基线只能下降。新的 source set 从零开始，不能成为抬高基线的理由。

## 最低验证要求

- 始终保留受影响的 Android G0 门禁。
- 纯 KMP：common 测试加每一个所声称目标的编译。
- 数据/运行时：与存储、序列化、取消和错误风险相称的 adapter 契约和兼容性检查。
- CMP 渲染器：目标编译加渲染后的 UI/语义冒烟及运行时依赖对齐。
- WinUI 3 DLL：原生链接、生成 API 审查、消费者冒烟、内存/释放/错误/线程测试以及包加载。
- WinUI 3 IPC：协议兼容性、启动/关闭/重连、本地访问策略、取消以及安装器冒烟。
- iOS 原生 UI：framework 导出加 Swift 消费者的编译/观察/生命周期冒烟。
- 始终运行 `git diff --check`；跨模块移动后使用干净重建。

## 审查输出

按影响程度列出发现，并附上确切的 file/line 证据和最小可信的修复方案：

- P0/P1：行为或数据丢失、ABI/存储不兼容、目标损坏、生命周期/线程/取消缺陷，或打包/运行时失败。
- P2：渲染器泄漏进 presentation、非法依赖、虚假能力、未测试的桥接、状态重复、基线放宽，或不必要的抽象。
- P3：约定、命名、文档、图或模板漂移。

如果没有发现，请说明哪些宿主、包、设备、渲染器、互操作和性能路径仍未验证。
