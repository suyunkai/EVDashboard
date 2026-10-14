# 仪表工坊 0.1.0 验证记录

日期：2026-10-14～15。源码、主机测试、Android VM 和实车验收分别记录，不能相互替代。下列新增记录优先于后面的早期测试记录。

## 10 月 15 日完整链路测试

- QA5：`dist/instrument-workshop-0.1.0-qa5.apk`，SHA-256 `6ff586bfb82d510ce4dee989e0147a04193811b381c94d068d3ad7acaefd7b61`。0.1.0 / code 1，debug 签名；v2 签名和 ZIP 16 KiB 对齐检查通过。VM 用户 10 与实车用户 11 已安装、启动。
- JVM 48 项全部通过，包含 5 项真实执行 writer shell 的模拟命令测试、6 项音效事务失败恢复测试、同内容模式双向限制；Lint 0 错误、24 警告。
- Android VM 6 项中 5 项通过：完整 1 GiB 合成镜像生成/复核/损坏拒绝，root 只读流到应用文件，PCM WAV 规范化及损坏/超长拒绝。MP3 MediaCodec 测试超时失败；VM 日志显示 C2 缓冲分配/ION 不可用。不能把该失败隐藏为通过，也不能据此断言实车 MP3 解码失败或成功。
- 修复并实车复测完整导出：root 直接写 `/data/media` 曾导致应用侧 FUSE 长度为 0；改为 root 只读、应用自己写文件。第一次失败记录保留且无效，不当作备份。
- 00:05～00:08，应用完整导出当前 B 槽（`cp_rootfs_b -> /dev/block/sdd4`，1 GiB），前后分区摘要与导出文件一致。记录 ID `974dcc69-f001-4511-af0a-33eefc49fdac`；已通过界面分类为“已修改镜像”，因为当前车此前已开启 SSH，不能冒称原厂镜像。
- 00:11～00:13，应用由该导出生成 SSH 候选，记录 ID `58d0368d-baaf-4fbc-b7d7-74db627c313b`；00:15～00:16 通过界面重新读取复核。当前 SSH 已开启、root 已为空密码，因此重建为字节完全相同的镜像；独立 `cmp` 也通过。
- 候选、有效导出、写前当前 B 分区 SHA-256 均为 `f1e80679d4d4685d9fc8ac7b8d58281e5a067095fdc2da8335509ffde3e9141b`。
- 完整 1 GiB 导出已拉回本机 `Downloads/CP/workshop-qa-20261014/source/complete.img`；39 个传输块逐个核对、gzip 完整性及解压后的全量摘要通过。本机 EROFS 全读检查通过，与此前已启动验收的 SSH 镜像逐树比较 PASS：28,002 路径、0 差异；全文件字节比较也通过。应用候选与该完整回传文件摘要完全一致，不是只查看镜像头。
- 应用自身 SSH 指纹确认、空密码 root 连接及状态查询通过；AVAS 与仪表音效服务均 active，无已有音效覆盖。这尚不是音效修改或声学验收。
- 用户明确允许受控同内容重写测试。QA5 新选项默认关闭、不持久化，需四项风险/操作确认外再二次确认；仅放行与当前分区完全一致的候选，保留全部其他检查。
- 远程坐标触控仍不能可靠驱动实车 UI；TAB/ENTER 可用，逐项核对焦点后执行。不改变车辆输入过滤或无障碍配置。
- QA5 首次刷写请求发现实车后台启动缺陷：候选暂存成功，但启动会话结束后没有 worker 进程、`pid`、`stage`、`write.started` 或写入锁。应用保持待核验，未自动重写。独立全量 B 槽摘要仍未变化。确认没有 worker 后停止应用，将 pending 清单改名归档为 `unstarted-56974253-b93c-4dea-8ed3-66c76f09147b.json`；任务目录、候选及原备份全部保留，没有伪造 PASS/FAIL 结果。
- QA6 修复启动握手：启动 shell 在 fork 前忽略 HUP，并等待 worker 自己写入 pid/result 后才返回；10 秒无确认则明确失败并保留任务，不继续假定已启动。VM 上通过本机 ADB 发起普通文件 worker、关闭会话后等待并读取结果，1 项专项测试通过。JVM 增至 50 项全部通过，Lint 仍 0 错误、24 警告。
- QA6 APK SHA-256：`c868056ce01eb38790bfa3bdea2924cef64b1509ef6a68aca607832fda6683b3`；v2 签名与 ZIP 16 KiB 对齐检查通过。

### 应用实车写入与回读（QA6）

- 00:31:56 通过应用界面重新完成四项条件、默认关闭的测试选项和第二次风险确认；未跳过备份或镜像审查。
- 任务 `b277eb1c-0b30-4e02-a8a0-f06f673da177`，目标仅当前 `cp:b / /dev/block/sdd4`。原有效导出及生成候选均保留，没有重新生成一份多余备份。
- 00:33:09 后台 writer 进入 PREFLIGHT；00:33:34 进入 WRITING；日志确认实际写入 1,073,741,824 字节，用时 10.018 秒。
- 00:33:43 进入 FULL_READBACK；全量回读 1,073,741,824 字节用时 23.922 秒，摘要及 `cmp` 通过；另一槽、`cp_metadata` 和 `cp_metadata_bak` 前后摘要一致。
- 00:34:24 应用取得 `PASS / COMPLETE`，保存 `WRITE_AND_READBACK_VERIFIED` 收据，移除 pending 和写入锁。没有自动重启或切槽。
- 这是本 APK 的真实同内容写入验收，不是仅模拟执行；没有功能变更。启动验收仍单独记录，不能由写入 PASS 代替。
- 写入后另外从外部 ADB 独立重算 live B 与完整 readback 摘要，仍均为 `f1e80679…e9141b`；独立比较非目标摘要一致，pending 与写入锁均不存在。已安装 `base.apk` 摘要与 QA6 APK 一致。

### 重启后只读验收通过（当前 B 槽，同内容重写）

用户另行授权写入成功后重启，优先尝试仪表。读取 CP 的 `/sbin/reboot`、systemd reboot 单元后，确认存在正常系统重启入口，但未证明其硬件影响范围仅限 CP。00:35:45 向 CP 发出一次带旧 boot ID 检查的 `systemctl --no-block reboot`；没有 `--force`，没有切槽或再次写入。随后 SSH 断开、AP 远程 shell 暂无响应。等待现场屏幕状态和远程协助恢复，不能仅凭断线判定启动失败或成功，也不重复发送重启命令。

重启前 AP boot ID：`a4829b79-25b7-4f79-b83e-bab1f244af04`；CP boot ID：`f241efef-1139-4cd4-91a4-7ca8208e4d0a`。重启前 CP 的 `data-mount@sv_blk13.service`、`ecarx-systemd-networkd-wait-online.service`、`logrotate.service` 已为 failed，后续不得误记成这次重启新出现的问题。

用户随后确认仪表和车机均正常恢复，并说明自己重启了。5560 恢复后进行了独立只读核验；这不能用于证明此前 CP 重启命令仅影响 CP，或单独归因 AP 重启的原因。

- AP boot ID 已变为 `5de8307e-79de-48c3-93ae-d6391c88e4c0`，CP boot ID 已变为 `28f8dbf5-a2b2-454a-a82d-d9eb456eee39`，两者均确实重新启动，不是只看到屏幕亮起。
- Android 用户仍为 11，`sys.boot_completed=1`，CP 仍为 `cp:b`、`/dev/block/sdd4`，没有回退至 A 槽。
- 重启后全量 B 槽 SHA-256 仍为 `f1e80679d4d4685d9fc8ac7b8d58281e5a067095fdc2da8335509ffde3e9141b`；A 槽为 `66dc35579794cdf3d83923774e02f55ddc6d17a88d77f4733293a77d6b4d6270`；两份 metadata 均为 `4b63bc12c2047f26eae8df8a6c035a905768d15a16632be210fc997b0e3aef4c`，均与写前/回读时一致。
- SSH 空密码 root 登录成功，`dropbear.socket`、`app_1.8.service`、`cluster-cmw-audioplay.service` 均 active；socket 自启动链接和 enable preset 均在。没有手动启动这些服务。
- 没有 pending 写入任务或 writer 锁；成功收据仍保留。上面三项已有 failed 服务没有新增变化；不据此宣称系统所有服务无故障。
- SSH 主机指纹重启后变化，首次严格校验正确拒绝。完整已校验镜像中的 `dropbearkey.service` 明确生成 `/run/dropbear` 密钥，运行时 `/run` 为 tmpfs。核对同车 ADB、车内固定 `eth0.12` 路由后，以单独的本机 postboot known-hosts 记录新公钥做本次只读验收，保留旧记录；没有关闭全局校验或更改 APK 信任设置。
- 该固件密钥轮换意味着应用旧指纹会要求用户重新确认，开机自动音效恢复不能视为已通过。AVAS 的实际声音及临时覆盖/恢复仍需另外验收。

## 早期验证记录（10 月 14 日）

- Java 单元测试 28 项：全部通过，包含不可降级原始分类、损坏导出不得声明有效原始备份。
- Android Lint：无错误；保留关于中文单语言、Car 反射兼容性、arm64 单 ABI、文件管理权限和固定依赖版本等警告，没有全局隐藏。
- 原生主机合成文件测试：SSH-only、password-only、组合、幂等复核、xattr/权限/时间戳保留、未知额外文件修改拒绝、锁定账号拒绝、截断拒绝、1 GiB FIP 封装及不透明尾部保持全部通过。
- 对此前已保留的真实原始 B 镜像和 SSH 候选执行只读逐树比较，通过；没有在这一步写入车辆。
- APK 的 v2 签名、ZIP 对齐检查通过；原生 ELF LOAD 对齐为 16 KiB。
- 本地 NewCar E245 VM：先校验配置引用镜像摘要、离线 QCOW2 完整性，再启动 `NewCar_E245_N5` / `emulator-5580`。核验 `ro.kernel.qemu=1`、版本 `263632`，当前 Android 用户为 10。
- VM 安装、启动、两个页面切换与大屏布局已实际截图检查。应用没有以 E245 为兼容开关。
- VM Android 仪器测试 2 项通过；最终测试包复测 125 秒：合成完整 1 GiB 文件的检测、SSH+密码修改、本机重建、独立树/容器复核、重新读取、源不变及损坏后拒绝；本机 ADB UID 0 及普通应用文件在 root 命名空间的摘要验证。
- VM 无 `/proc/boot_slot` 时正确拒绝 CP 槽位识别，不猜测 Android 槽位。
- 实车 `127.0.0.1:5560`：安装成功、实际用户 11、应用启动成功，音效首页完成截图检查。只读 shell 已识别 `cp:b`、`cp_rootfs_b -> /dev/block/sdd4`；这不是应用自身导出链路验收。
- 实车远程 ADB 曾出现间歇性 shell/截图停滞；23:14 重连后 shell、安装及文件截图读取恢复。普通坐标触控事件进入系统队列，但未使页面切换，原因尚未确定；23:28 使用逐项核对焦点的 TAB/ENTER 成功进入仪表盘工坊，截图与 Activity 层级共同确认。
- 23:29 已通过应用的「检查权限并识别当前 CP 槽位」按钮完成实车只读识别：应用自行连接本机 ADB，确认 UID 0、`cp:b`、`/dev/block/sdd4`、1 GiB；任务正常结束。没有改变无障碍服务或系统输入配置。完整导出、生成与刷写仍未执行，没有更改音效。
- 最终 APK SHA-256：`01c1c0804e4f9169f0ed495249e4cfcd3bf5e3234f0dc8da6f379476606482d1`，debug 签名，版本 0.1.0 / code 1。已在 VM 安装复测。实车更新传输曾遇到 EOF；恢复连接后验证车端暂存 APK 完整，再本地执行安装成功。重新读取已安装的 `base.apk` 摘要与本机完全一致，启动成功（896 ms）。
- 对应源码包从全新临时目录解压、重新构建主机原生工具并跑合成测试，通过；排除车辆镜像、个人 SDK 配置、构建产物及截图。

## 待验证 / 不作保证

- 应用完整导出、生成、真实同内容写入、回读及当前 B 槽重启后的只读验收已按上文通过；跨 OTA、A 槽、外部镜像导入后的真实写入和真实写入中断恢复不可由这一次结果推断。
- AVAS、锁车提示音以及临时通用提示音覆盖的实际播放、恢复和故障回滚需要驻车现场验收。服务 active 不等于声学验收通过。
- 开机音效恢复默认关闭；只读驻车检查及固件生命周期差异尚需实车验证。未知状态拒绝。
- 不支持任意镜像格式、未知 Dropbear 策略、跨固件版本替换、锁定账号解锁或不同结构的 CP 分区。
- 校验不能消除硬件、断电、并发 OTA、启动验签等风险。保留另一槽也不保证可恢复。

## 复核入口

首次 Git 归档前，已从暂存区导出干净快照，补齐上游忽略规则排除的构建输入（包括 `include/erofs/config.h`、`lib/config.c` 与 Autotools 配置脚本），在不使用工作目录缓存的情况下重新完成 Android arm64/APK 构建、50 项 JVM 测试以及主机原生合成测试。均通过。仅跟踪源码、构建输入和文字验证记录；车辆镜像、APK、实车截图、SDK 配置及凭据不纳入 Git。上游生成文件和许可证原文的既有空白格式保留，自有代码空白检查通过。

- JVM：`./gradlew :app:testDebugUnitTest`
- 本地原生：先 `bash tools/build-host.sh`，再 `python3 tools/test-native.py`；使用 `build/host/` 内与源码对应的主机工具，可选两个只读原车样本路径。
- Android：构建测试 APK 后，确认 emulator 身份，显式 `adb -s emulator-5580` 安装到查询到的当前用户，再执行 `am instrument --user <实际用户> -w -e class com.kooo.evdashboard.LocalImagePipelineTest com.kooo.evdashboard.test/androidx.test.runner.AndroidJUnitRunner`。
- 原生合成样本由 `tools/test-native.py` 生成，不含原厂资源或车辆凭据。
