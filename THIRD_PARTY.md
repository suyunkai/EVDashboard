# 第三方组件与来源

## erofs-utils

- `vendor/erofs-audit`：1.9.1，提交 `d3fbccba6e409c100843563b416c47cd82c87ca0`，用于只读校验、树遍历及元数据导出。
- `vendor/erofs-legacy`：1.8.10，提交 `51b5939b5f783221310d25146e6a2019ba8129b6`，用于兼容已观察到的 EROFS 时间戳/特性结构的重建。
- 上游：[kernel.org erofs-utils](https://git.kernel.org/pub/scm/linux/kernel/git/xiang/erofs-utils.git/)。
- 许可按各文件 SPDX；大部分 `lib`/`include` 是 GPL-2.0-or-later OR Apache-2.0，工具通常 GPL-2.0-or-later。完整原文在各目录 `COPYING` 和 `LICENSES/`。
- 本项目兼容性补丁：legacy `mkfs/main.c` 初始化 `feature_compat` 仅为 `EROFS_FEATURE_COMPAT_SB_CHKSUM`，不引入旧镜像未使用的时间戳语义；命令行固定 `legacy-compress,^xattr-name-filter`，保持 UUID、构建时间和 inode 元数据。
- 源码归档调整：两个 vendor 目录的 `.gitignore` 放行随发行版提供的构建配置脚本，以及真正的 `include/erofs/config.h`、`lib/config.c` 源码；不跟踪本机生成的顶层 `config.h`、构建缓存或可执行产物。
- `native/image_tree.c`、`fip_pack.c`、`fip_verify.c` 来自本用户先前授权的本地 CP 镜像验证工具；`image_tool.c` 在此基础上独立实现应用所需的最小变更。不是 Custom Profiles 反编译代码。

分发这些 GPL 工具二进制时必须同时履行对应源码及许可证义务。本项目保留对应源代码和构建脚本；不把闭源第三方应用的反编译产物纳入工程。

## JSch

`com.github.mwiede:jsch:0.2.20`，用于标准 SSH。来自 [mwiede/jsch](https://github.com/mwiede/jsch/tree/jsch-0.2.20)。Revised BSD；其内含 JZlib 使用 Revised BSD，jBCrypt 使用 ISC。许可证随 APK 的 `licenses/` 资产提供。

## 构建与测试依赖

Gradle Wrapper / Android Gradle Plugin / Kotlin 运行库、AndroidX Test、JUnit 及仅用于 JVM 单测的 JSON 组件沿用各自许可证。测试 APK 与测试合成 EROFS 不进入正式应用的运行资产。构建缓存中的主机可执行文件不属于需要发布的应用资产。

## 只读驻车判断依据

采用 Android 官方定义的 `GEAR_SELECTION`、`PERF_VEHICLE_SPEED` 和 `GEAR_PARK`，不使用车型名称或推测的私有属性。
参考：[VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds)、[VehicleGear](https://developer.android.com/reference/android/car/VehicleGear)。
使用读取接口，不向 VHAL 注入或写入值。供应商固件接口不可用时，自动音效恢复拒绝执行。
