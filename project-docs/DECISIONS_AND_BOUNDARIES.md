# DECISIONS_AND_BOUNDARIES.md — 已确定决策与开发边界

> 基线：**2.9.4 / 36**（当前稳定基线；main / tag `v2.9.4`；代码提交 `16797e8`）。
> 这里记录**已经明确采用**的决策和**禁止/高风险**边界；曾经尝试但未采用的方案放 `DEVELOPMENT_HISTORY.md`，未决问题放 `OPEN_QUESTIONS.md`。

---

## 1. 产品/架构总决策

### 1.1 不做自研地图

- 地图底图、道路、POI 由百度地图 SDK 9.0.2 渲染；我们只做 Compose 界面层和百度 overlay（车标、路线）。
- `MapBackdrop` 只是无 SDK/无 AK 时的占位；`MapImageCache` 只是静态图降级/过渡。**不要写成“自研地图引擎”。**

### 1.2 App 直连百度，不使用自有网关

- `AppGraph` 的活动路径是 direct-only：`MergedBaiduProvider(BaiduCruiseProvider, BaiduHorizonProvider)`。
- `backend/` 仅历史资产；`useRemote()` 仍存在但不是活动路径；设置页没有网关入口。
- 新增数据能力时，优先在手机直连百度；不要恢复网关作为默认路径，除非重新决策。

### 1.3 导航采用 B 方案（ADD_MAP=false）

- 百度导航只跑算路/引导/回调；我们保留自己的 `TextureMapView` + `NavHud`。
- 不使用百度默认导航 UI；不另开独立导航页（当前基线）。
- 导航中相机归百度引擎；`navActive` 阻止我们的相机写入。

### 1.4 导航中单指拖动：当前架构接受为限制

- `ADD_MAP=false` + 外部 `TextureMapView` 下，导航引擎独占相机/平移；单指拖动无效，双指缩放/旋转可用。
- 这是**能力边界**，不是“某个开关没开”；不要把它列为待修 Bug。
- 2.9.3（`ADD_MAP=true` 最小验证）与 2.10.0（独立 `NavActivity`）都已尝试并**回退/留档**；2.9.4 起接受该边界，不再立项修单指拖动。

### 1.5 不要重建/条件移除 `TextureMapView`

- 2.9.1 曾为清残留在退出后重建 `TextureMapView`，导致共享 GL 上下文被拆、持续白屏。
- 当前规则：**同一个地图视图始终存在**；不要用 `key(...)`/条件分支销毁重建 `AndroidView`。

### 1.6 不要用 `BNMapController.setLevel` 当 map zoom

- `setLevel` 是导航比例尺档位，不是地图 SDK zoom；2.6.3 起的 `setLevel(18f)` 导致“先对后缩”。
- 2.9.2 已移除 start 中的 `setLevel`；只在 onStart 后 1.2s 用地图 SDK `newLatLngZoom(target, 17.5f)`。
- `MapSceneController.applyNaviZoom()` 仍存在但未被调用；不要在没有真机证据时重新接入。

## 2. 数据与安全语义

### 2.1 定位三级，不用“未知”文案

- `LocationQuality`：`NONE` / `APPROXIMATE` / `GOOD`。
- 地图只需 `hasPosition`；限速/车道/相机/红绿灯需 `cruiseData`（GOOD + ≤5s）；速度需 `speed`（GOOD + GPS/FUSED + ≤3s + 精度合格）。
- 不显示“限速未知/定位未知”；用少显示/不显示表达。
- 2.5s hold 只用于防止能力闪断，不用于伪造数据。

### 2.2 无电子眼不标红

- 用户已确认：无电子眼 + 超速 + 有限速 → 只显示限速，不标红。
- `OverspeedDetector` 当前符合；不要改回“无电子眼也标红”。

### 2.3 电子眼 800m

- `CAMERA_ALERT_RANGE_M=800.0`；只在 800m 内显示胶囊/参与超速判定；区间 INSIDE 强制保持。
- 800m 是用户接受的口径；不要无证据改成 500/1000 或按速度自适应。

### 2.4 红绿灯主路线 = static + dynamic（B）

- 巡航红绿灯不再使用 `probe_lamp`/鹰眼 service_id/entity_name。
- `LampDynamicClient`：`static_lamp_search` 找灯 → `dynamic_lamp_search` 取多方向/多相位；不依赖鹰眼。
- 倒计时以百度返回的 `timestamp`（数据生成时间）为基准，不得用本地 now 重置。
- 300m 是显示上限，不是保证距离；实际首次出现取决于绑路返回。
- 导航红绿灯是另一条链路（SDK listener），**不得**用巡航链路替代或混为一谈。

### 2.5 区间测速

- `IntervalTracker` 用相机 rawType=5/9 起终点、offset/距离推进；未真机验证。
- 不要因为代码存在就宣布区间已验收；跨 link/终点缺失/60s cooldown 都需真机。

### 2.6 车道

- 巡航车道依赖百度车信 `lane_arrow`；当前账号没有该能力，百度明确为商业收费；用户已决定不再讨论/不采购，**不是代码可绕过**。
- 导航车道用 `LaneDrawableMapper`（drawable 方向），不是结构化车道；推荐以 `_bright` 为准，`_gray` 不推荐。
- 不要把“导航 drawable 车道可用”当成“巡航车道已解决”。

### 2.7 限速对象三分（用户已确认，B2/B3 已按此实现）

- **当前道路限速、前方摄像头限速、区间限速是三个独立对象，不能互相代替。**
- 当前道路限速未知时，**不允许**用前方摄像头限速填充；`RoadData.speedLimit` 只表示道路/link 限速。
- 前方摄像头限速已知时，电子眼提醒区域正常显示该摄像头自己的限速（`cameraSpeedLimit` → `CruiseUiState.cameraLimit` → `CameraCapsule`）。
- 区间平均判定使用区间自己的限速（`intervalSpeedLimit`），不是道路或普通摄像头限速。
- 同一台被选中的相机必须同时提供类型、距离和它的限速；不允许“距离取最近相机、限速取另一台更远相机”。
- 不允许为了保证界面始终有数字而混用数据；未知就隐藏。
- 相关实现：`RoadData.kt`、`BaiduMappers.kt`、`MergedBaiduProvider.kt`、`CruiseViewModel.kt`。状态见 `POST_2.9.2_IMPLEMENTATION_STATUS.md` 的 D01/D02。

### 2.8 导航语音在后台/锁屏继续播报（2026-11-20 用户确认）

- 产品口径：**导航切到后台或手机锁屏后，导航语音仍需继续播报**，不能因为 Activity 停止收集 UI 状态而中断。
- 实现边界：语音触发放在 `BaiduNavSource`（观察自身 `NavUiState`），通过 `NavSpeaker` 调用 `TtsPlayer`；`TtsPlayer` 使用 application context，不依赖 Activity/Compose 生命周期。
- UI 不再持有或触发导航播报；`collectAsStateWithLifecycle` 只服务显示，不作为语音输入。
- 退出导航必须停止当前播报；TTS 开关仍受设置控制。
- 验证要求：锁屏、切后台、开关切换、退出导航四项需真机听感确认；代码存在不等于已验证。

### 2.9 区间缺速度时隐藏平均（Q-24，2026-11-20 确认）

- 区间平均只用“有真实速度样本”的时段累计：`IntervalSnapshot.validSpeedS` 记录有效时间，`speedCoverage = validSpeedS / elapsedS`。
- **覆盖率低于 70%（即缺测超过 30%）时隐藏平均圆盘/平均超速判定**，不把缺测按 0 算出的偏低平均值展示给驾驶员。
- 70% 是当前实现阈值（`IntervalEngine.MIN_SPEED_COVERAGE`）；如果需要更保守/更宽松，只改这个常量并补测试。
- 区间状态、剩余距离、瞬时超速提示不受影响；隐藏的只是“平均”这一项。

### 2.10 旧 `directAk` 迁移（Q-25，2026-11-20 确认）

- **保留用户已保存的巡航 AK**，不因升级自动清空；旧 noBackup `direct_ak` 在启动时一次性迁移到 `EncryptedSharedPreferences`，随后删除旧明文键。
- 在设置 → 数据服务 提供“恢复内置服务端 AK”入口，带确认对话框；执行后删除本机加密值，回退到 `BuildConfig.HORIZON_AK`（若构建也未配置则为空，不发起请求）。
- 该入口只影响服务端/超视距 AK，不改 Android 应用端 AK、MiMo Key、包名或签名；地图 AK 与 TTS Key 各有独立入口。

### 2.11 退役网关清理（Q-26，2026-11-20 确认）

- 删除确认无用的网关实现与测试：`GatewayApi`、`GatewayDtos`、`RemoteProvider` 及其 22 条测试。
- **保留** `GatewaySettingsMigration`（清理旧 `ak` 字段、迁移旧 mode）与 `AppSettings` 中的兼容字段；迁移属于升级兼容，不是活动数据路径。
- 无 AK 时的占位改为 `NoOpProvider`，不再保留“网关可恢复”的假路径。

### 2.12 导航播报节点（Q-27，2026-11-20 确认）

- 转向播报使用**固定距离节点**：1000 / 500 / 300 / 100 / 50 米；同一路口、同一节点只播一次。
- 节点变化、道路名或转向类型变化时才产生新播报；到达、偏航仍立即播报。
- 不再按原始距离每次变化重复播报；大于 1000m 时暂不播转向距离。
- 播报仍由 `BaiduNavSource` 在后台驱动（§2.8），不受 Activity 生命周期影响。

### 2.13 缺精度定位不得判 GOOD（Q-28，2026-11-20 确认）

- `hasAccuracy=false` / `accuracy<=0` 时，定位等级**一律 APPROXIMATE**：可以显示地图，但不显示限速、相机、红绿灯、车道，也不显示速度。
- 不再用“非 NETWORK 默认 30m”把未知精度当成 GOOD；NETWORK 源维持 APPROXIMATE。
- 该规则同时约束 UI 门控与 Provider 数据喂入（`LocationQuality` 是唯一来源）。

### 2.14 搜索排序（Q-21 / VA-O04，2026-11-20 确认）

- 采用**相关度分层 + 层内距离优先**：
  - T0 名称完全等于查询；
  - T1 名称包含完整查询；
  - T2 查询包含名称（较短但仍相关）；
  - T3 类别/模糊匹配（如“健身房”→“XX健身俱乐部”）。
- 同一层内按距离从近到远，未知距离（-1）排在该层最后；结果上限 30。
- 跨城精确目的地仍排最前；附近类别结果不再被更远的同类结果压后。
- 该策略取代原“本地是否回答查询”的 `remoteFirst` 启发式。

### 2.15 版本与发布节奏（2026-11-22 更新）

- 当前稳定基线：**2.9.4（versionCode 36）**，tag `v2.9.4`；2.9.3(35) 为上一封版。
- **2.10.0 跳过**：后续版本不得使用 2.10.0。
- `dist/Velora-preview-debug.apk` 已是 2.9.4/36 封版；SHA-256 `e0b34d1e72998a4dbeb6b201199bd514026d5e3c942d423935991de55d165ee6`。
- 开发暂告一段落；未验证项不得写成已解决，后续只在实车道路数据齐备后重启道路项。

### 2.16 开源凭据与高级服务降级（2026-11-26）

- 仓库和 Git 历史不得包含真实 AK/SK、MiMo Key、签名密码或个人路径；`local.properties`、`*.jks`、`*.keystore`、`dist/*.apk`、日志/JSONL 均 gitignored。
- 两套 AK 严格分离：Android 应用端 AK（地图/导航/搜索，绑定包名 + SHA1）与服务端 AK（超视距/HTTP/lamp，高级服务需百度工单）。不得互相替代。
- 运行时凭据存入 `EncryptedSharedPreferences`；设置页只脱敏显示，日志统一经 `SecretRedaction`，不得新写明文。
- 服务端 AK 为空时不创建 Horizon/HTTP/lamp 请求；鉴权失败/到期进入长退避并在设置页温和提示，不伪造限速、电子眼或红绿灯数据。
- Git 历史清理必须另行确认；当前只做工作区脱敏，不重写历史、不 force-push。

### 2.17 零百性能测试（2026-11-27）

- 零百只从 **设置 → 性能测试** 进入独立 `AccelActivity`；巡航主页/地图/导航不得新增入口、按钮或浮层。
- 测试只读订阅现有 `LocationRepository`，不新建定位会话、不发百度请求、不消耗配额。
- 旧零百核心状态机/计算/记录放在 `feature/accel/core`，不复用旧项目的深色仪表盘 UI。
- UI 强制使用 Velora 浅色体系；成绩只认 GNSS，加速度计辅助默认关闭；没有真实测试前不宣称精度。
- 记录保存在本机独立 SQLite `velora_accel.db`；旧独立 App 的数据库不跨包迁移。

## 3. 导航显示开关（当前代码事实）

- 2.9.2 代码曾在 `startGuide` 调用：
  - `setIsOnlyDisplayMainRoute(true)`；
  - `setMapTrafficLightEnable(true)`。
- **2.9.4 已移除 `setIsOnlyDisplayMainRoute` 调用**：`carnavi:9.0.2` 的 .so 缺 JNI，真机抛 `UnsatisfiedLinkError`；MAP-04 关闭为 SDK 硬边界。
- `setMapTrafficLightEnable(true)` 仍保留；原生灯是否交付/渲染不作为承诺，HUD 灯是独立链。
- 不要再根据 2.9.2 日志断言“未启用”，也不要重新接入 onlyMain。

## 4. 导航崩溃与依赖

- 2.4.1 根因：导航引导页 `RGBottomSheet` 依赖 `rx.subjects.BehaviorSubject`，`carnavi:9.0.2` 未带 RxJava 1.x；缺失导致 `NoClassDefFoundError`，SDK handler 吞栈后 `System.exit(10)`。
- 决策：**保留 `io.reactivex:rxjava:1.3.8` 和 `NaviSdkDependencyTest`**；不要以“依赖精简”为由移除。
- `NavTrace.arm()`/`guarded()` 是取证/防回调杀进程措施，不是根因修复；保留。
- 旧假设（`add_map=false` 地图归属、SUCCESS/TO_NAVI 时序、双地图销毁）已被 2.4.1 证据推翻；不要重新当作根因。

## 5. 地图场景/日夜

- 百度地图 SDK 与导航 SDK 共用进程级 native 引擎；`add_map=false` 不改变引擎归属。
- 进入导航：`MapSceneController.enterNaviScene()`；退出：`restoreCruiseScene("pre-destroy"/"post-destroy"/"verify")`。
- 退出清理必须无条件执行（即使 `guideManager` 已 null），否则路线/图钉/配色/比例尺会残留。
- 导航日夜：`day/night/system`，默认 `day`；这是**工程默认**，用户尚未最终裁决；巡航底图恒浅色也是当前实现选择。
- `setDayNightMode`/`setMap2DLook`/`setLayerMode`/`setCarLogoVisible` 属 `comapi` 层，只在 9.0.2 验证；升级 SDK 必须重新核对。

## 6. 身份/名称/图标/地图视觉

- 当前 `strings.xml` 仍是 `Velora`；2.9.2 没有应用名/图标改动纳入基线。
- 名称、图标、地图视觉都是后续产品事项；不要在技术任务中擅自改。
- 任何名称/图标落库不得改 `applicationId`、`namespace`、签名证书/SHA1；否则百度 AK 失效。
- 个性化地图只做过调研，未改样式；`.sty` 不影响主页静态图，公开使用需审核/不能沿用标准审图号。

## 7. 密钥与分发

- `local.properties` / `backend/.env` 均 gitignored；用户授权预填，但**严禁进 git/分发包**。
- APK 的 BuildConfig 内含 `HORIZON_AK`、`MIMO_API_KEY`；不能外发。
- 地图 AK 绑定包名 + SHA1；不要改包名/签名。debug/release 签名变化需回百度控制台更新 SHA1。

## 8. 工程与文档边界

- 当前分支 `main`；2.9.2 已提交（基线 commit `bbdc2a6`）；tag `v2.9.2`；无 remote。
- 改代码前先确认工作区；2.9.2 已是 `main` 基线（commit `bbdc2a6` / tag `v2.9.2`），不要 reset/覆盖。
- 2.9.2 开发日志不是实现证明；遇到日志/代码冲突，以代码为准并在 OPEN_QUESTIONS 记录。
- 本项目文档以 `project-docs/` 为准；根目录旧文档是 2.3.9 前后的历史快照，不能直接当 2.9.2 现状。
- 本轮文档任务只改文档，不改业务代码、配置、依赖或图标。

---

## 9. 2026-11-19 用户真机结论后的产品决策

### 9.1 进入“冻结修补、只做报告”阶段

- 用户明确：**2.9.2 导航显示类问题不再修补，只负责报告。**
- **适用范围**：§9.2–§9.6 列出的退出残留、默认比例尺、导航红绿灯、只显主路线、第二次导航卡顿、搜索排序、车信车道、个性化地图。
- **不适用范围**：四轮审查形成的 59 条 VA。用户已于 2026-11-19 起另行下达 B1–B3 开发指令，并已提交；B4 继续进行。
- 因此：
  - 不再为退出残留、默认比例尺、导航红绿灯、只显主路线、搜索排序等问题改代码；
  - 文档只记录现状、证据和后续可选方案；
  - 不要以“顺手修一下”为由改动导航/搜索/地图代码。

### 9.2 导航现状：失败项保留，不假装修好

- 退出导航残留：**仍存在**（2026-11-19 真机）。
- 导航默认比例尺：**仍不对，太大，无法显示车道级**。
- 导航红绿灯：**仍不行**；地图没有原生红绿灯。
- 只显主路线：**仍不行**，备选路线仍显示。
- 同一 App 会话内第二次导航：**严重卡顿，重启 App 才恢复**（NAV-09）；疑似 native 引擎/路线/GL 资源释放不彻底，未定位。
- HUD 尺寸：**基本合适**，用户接受，不再调整。
- 用户表示导航怎么优化都做不好、**有点想放弃**；这不是正式“删除导航/回退 A”的决议，但意味着：
  - 不要主动承诺导航优化排期；
  - 如未来重启，先重新立项/重新验收，不要在当前架构上继续打补丁。

### 9.3 导航地图原生红绿灯：接受缺失

- 用户认可“导航地图没有原生红绿灯”的当前结果，不再要求修复。
- 不要把“地图原生灯态缺失”写成待修 Bug；HUD 红绿灯是否可用是另一条链路，当前也未达成，但同样不再修补。

### 9.4 搜索：跨城通过，排序/精准度是新问题

- 跨城搜索：**用户确认可以**。
- 搜索排序：**已真机确认有问题**，暂不修：
  - 搜“健身房”，1km 内结果排到几百公里外的同名类型之后；
  - 搜“充电”，2.3km 结果排在 1.1km 前。
- 产品口径（仅记录，不是已实现）：对“充电/健身房”这类目的地类型高度相关、范围很广的关键词，**距离应优先于泛相关度**；跨城关键词仍要保留全国结果。
- 未来修复前必须先定排序策略（本地距离优先/全国相关度兜底/混合权重），不要直接改 `NavSearchScreen`。

### 9.5 车信：商业收费，关闭讨论

- 百度明确车信为商业收费/授权能力；用户决定**不再讨论、不采购**。
- 巡航车道继续缺失；不要写“等待技术修复”或“已修复”。
- 导航 drawable 车道仍可用，但不等于巡航车道。

### 9.6 个性化地图：未排期，当前样式被接受

- 用户认可当前地图样式；个性化地图**未排期**。
- 不要开始地图重绘/`.sty` 实验；`docs/BAIDU_PERSONALIZED_MAP_RESEARCH.md` 只作未来参考。
- 若未来重启，先解决“主页静态图不跟随 `.sty`”和审图号/审核问题。

### 9.7 文档边界

- 以上是**产品状态/决策记录**，不代表代码已改。
- 2.9.2 已提交为 `main` 基线（`bbdc2a6` / tag `v2.9.2`）；旧文档归档到 `docs/legacy/2026-11-19/`；2.10.0 实验源码归档到 `<workspace>/archive/Velora-2.10.0-navactivity/`（实验性、已弃用，不用于开发）；零百加速旧项目归档到 `<workspace>/archive/SpeedMeter-accel-zero100/`（已过期、目前不需要）。

## 10. 16 KB 页大小兼容（2026-11-21）

- 现状：APK 的 zip 条目已 16 KB 对齐；6 个 arm64 原生库中 5 个 `PT_LOAD p_align=0x4000`，唯一不达标的是百度导航库 `libapp_BaiduNaviApplib.so`（`0x1000`，4 KB）。其余“未对齐”提示是 Android 16 兼容模式对话框的汇总展示。
- **临时策略**：`compileSdk 36` + `AndroidManifest` 的 `android:pageSizeCompat="enabled"`，让 Android 16 以兼容模式运行并抑制启动警告对话框。targetSdk 仍为 34，不引入 Android 16 行为变更。
- **不是真正修复**：兼容模式仍有性能/稳定性代价；真正的 16 KB 支持需要百度提供 `libapp_BaiduNaviApplib.so` 的 16 KB 对齐版本。百度导航 SDK 更新日志提到 v7.2.5“导航 so 适配 16k”，但当前 Maven `BaiduMapSDK_Map-Navi:9.0.2` 内该库仍是 4 KB，不能据此宣称已适配。
- **版本对比（2026-11-21）**：2.9.2 与 2.9.3 的 `libapp_BaiduNaviApplib.so` SHA-256 完全相同（`f5505ead…`），6 个 arm64 原生库哈希全部一致，zip 方式/`extractNativeLibs`/依赖版本（`carnavi:9.0.2`）也相同。该 4 KB 库不是 2.9.3 引入；警告差异只能来自运行环境/安装历史，不能归因于 2.9.3 的 native 变更。
- 校验工具：`tools/check_16kb_alignment.py <apk>`；当前会明确报告该库不达标，升级百度 SDK 后应立即重跑。
- **2026-11-21 真机验证**：SM-S9210 实际 `PAGE_SIZE=4096`；非调试包（`debuggable=false`）全新安装不再弹框，`pageSizeCompat` 生效；调试包仍显示“可调试应用”开发者警告，属系统行为，不是属性失效。
- 验证要求：真正的 16 KB 页设备上安装并启动，确认加载/导航/地图 native 功能正常；当前设备不能验证这一点。
- **2026-11-21 用户接受版本对比结论，专项调查结束**；不替换百度 SDK，保留兼容模式，等官方 16 KB 版本。


## 11. 2.9.3 / 2.9.4 最终边界决策（2026-11-22）

### 11.1 退出导航后的底图 POI / 单指拖动（MAP-01）

- `BaiduMapSDK_Map-Navi:9.0.2` + `ADD_MAP=false` 共享引擎下，`setNaviMapMode(NORMAL)`、`handleMapThemeAndScene(1)`、`resetMapOverlays()`、`setMapPoiScene(true)` 均返回成功，但底图 POI 与单指拖动不恢复。
- 决策：**接受为 SDK 硬边界**；按总报告 §12.2 **不重建 TextureMapView**，不再加延迟/重复清理。
- 证据：`nav-diag-20261122-145202.txt` 三次 restore 均 `mapModeNormal=true themeScene=true overlays=true`；退出后触摸仅 `scrollWas=true`。

### 11.2 导航中只显主路线（MAP-04）

- `BNRouteGuider.setIsOnlyDisplayMainRoute` 的 Java 方法存在，但 `carnavi:9.0.2` 的 .so 未实现 JNI，调用抛 `UnsatisfiedLinkError`。
- 决策：**移除调用，接受现状**；不再把备选路线作为待修 Bug。

### 11.3 HTTP 巡航静止节流（`CruiseRequestPolicy`）

- 首次请求立即；移动 ≥5 km/h 最多 30s 一次；静止且已有有效结果位移 <25m 不发；静止无有效结果最多 30s 重试；`status=302` 暂停 15 分钟。
- 目的：避免开发挂机/原地待机耗尽百度日配额；不改变道路数据语义。

### 11.4 Horizon 真实采样时间（`HorizonLocStamp`）

- `loc.ts` 使用 `GpsSample.timestampMs`，不再用发送时刻覆盖；envelope `ts` 仍为发送时刻。
- 缺失/超过 60s/未来超过 5s 的 fix 直接拒绝，不发送旧点。
- 残余：百度服务端对时钟偏移的假设未证实；未开网络对时设备的 `elapsedRealtimeNanos` 漂移列为后续可选观察项，不用在线测试验证。

### 11.5 16 KB 与版本

- 2.9.4 仍含 4 KB 对齐的 `libapp_BaiduNaviApplib.so`；保留 `pageSizeCompat`，等百度 SDK 更新。
- 2.9.4 为当前稳定基线；后续版本号不得使用 2.10.0。
