# ARCHITECTURE.md — Velora 2.9.4 真实技术架构

> 基线：**2.9.4 / 36**，当前稳定基线；分支 `main`；tag `v2.9.4`；代码提交 `16797e8`；无 remote。
> 本文只描述**当前代码实际存在**的结构；未接入/已回退的方案会明确标注。

---

## 1. 总览

```text
MainActivity (AppCompatActivity + Compose)
  └─ CruiseScreen
      ├─ CruiseViewModel  ── combine(RoadData, GpsSample, LocationStatus, AppSettings)
      ├─ MapTransitionLayer / MapLayerHost
      │    └─ MapScreen: TextureMapView + Compose overlay
      ├─ MapChromeHost
      │    ├─ MapChrome (顶栏/限速卡/归中/演示/搜索/设置)
      │    ├─ NavSearchScreen (搜索/预览/方案)
      │    └─ NavHud (导航 HUD)
      ├─ NaviGuideHost (百度 guideView，位于地图层下方)
      └─ SettingsHost

AppGraph (direct-only)
  ├─ LocationRepository ── MultiSourceLocationSource (fused/gps/network)
  ├─ SettingsRepository (DataStore + no-backup auth)
  └─ MergedBaiduProvider
       ├─ BaiduCruiseProvider (HTTP 小客车巡航 camera/interval)
       └─ BaiduHorizonProvider (超视距 WS + lamp static/dynamic)
```

没有自研地图引擎。地图底图/道路/POI 由百度 `TextureMapView` 原生渲染；我们只画 Compose UI 和百度 overlay（车标/路线）。

## 2. 模块与包结构

| 路径 | 职责 | 关键类/文件 |
|---|---|---|
| `ui/` | Activity、Compose 屏幕、ViewModel、主题 | `MainActivity`、`CruiseScreen`、`CruiseViewModel`、`SettingsScreen`、`MapScreen` |
| `ui/nav/` | 导航 HUD/搜索/启动门控 | `NavHud`、`NavSearchScreen`、`NavStartGate`、`NavModePolicy` |
| `ui/component/` | 可复用视觉组件 | `SpeedGauge`、`LimitSign`、`LaneGuide`、`CameraCapsule`、`TrafficLightCapsule`、`IntervalAvgCircle`、`MapBackdrop` |
| `provider/` | 数据提供者接口/合并 | `SpeedLimitProvider`、`NoOpProvider`、`MergedBaiduProvider` |
| `provider/baidu/` | 百度 HTTP/超视距/lamp 实现 | `BaiduCruiseProvider`、`BaiduHorizonProvider`、`BaiduMappers`、`LampDynamicClient`、`CruiseRequestPolicy` |
| `data/location/` | 多源定位与可用性 | `MultiSourceLocationSource`、`LocationRepository`、`LocationQuality` |
| `data/nav/` | 导航桥接/启动门控 | `BaiduNavSource`、`MapSceneController`、`GuideStartGate`、`LaneDrawableMapper`、`NavSentinels`、`NavTrace`、`SimNavSource` |
| `data/map/baidu/` | 百度地图初始化/协议/缓存/时间门控 | `BaiduMapSdk`、`BaiduCruiseApi`、`HorizonProtocol`、`HorizonLocStamp`、`MapImageCache`、`CoordUtil` |
| `data/settings/` | DataStore 设置 | `SettingsRepository`、`GatewaySettingsMigration`（仅迁移） |
| `data/search/` | 搜索模型/历史/距离 | `SearchPlace`、`SearchHistoryStore` |
| `data/tts/` | MiMo 语音 | `TtsPlayer` |
| `data/audio/` | 超速提示音 | `BeepPlayer` |
| `domain/model/` | 数据模型 | `RoadData`、`GpsSample`、`TrafficLightInfo`、`IntervalSnapshot`、`NavUiState`、`Lane`、`CameraInfo` |
| `domain/engine/` | 纯逻辑 | `OverspeedDetector`、`SpeedFilter`、`IntervalEngine`、`IntervalTracker`、`TrafficLightResolver`、`LimitTransitionDetector`、`DistanceFormat` |
| `service/` | 前台巡航服务 | `CruiseService` |
| `di/` | 依赖装配 | `AppGraph` |

## 3. 运行时数据流

### 3.1 定位

1. `CruiseService`（前台 location service）或 `CruiseViewModel` 启动 `LocationRepository`。
2. `MultiSourceLocationSource` 注册 `fused`(API31+)/`gps`/`network`，按 tier→精度→新鲜度选最佳；带 sticky 防抖。
3. `LocationRepository` 每 500ms 计算 `LocationStatus`：
   - `hasPosition`：地图可显示（≤120s，非 NONE）；
   - `cruiseData`：只有 GOOD + ≤5s 才可喂道路数据；
   - `speed`：只有 GOOD + GPS/FUSED + ≤3s + 精度合格才可显示速度；
   - 能力掉落后有 2.5s hold，避免仪表闪烁。
4. `CruiseService` 把最新 `GpsSample` 推给 `AppGraph.provider.updateLocation`，并更新通知。

### 3.2 道路数据

`AppGraph` 是 **direct-only**：

- 监听 `SettingsRepository.settings.directAk`；AK 非空 → `MergedBaiduProvider(BaiduCruiseProvider, BaiduHorizonProvider)`；空 → `NoOpProvider`。
- 网关实现已退役，仅保留 `GatewaySettingsMigration`/兼容字段；设置页没有网关入口；backend 仅历史资产。

两个 Provider：

| Provider | 来源 | 主要数据 |
|---|---|---|
| `BaiduHorizonProvider` | 超视距 WebSocket + lamp `static_lamp_search`/`dynamic_lamp_search` | position/link/限速/坡度/曲率/warning、相机、WS 红绿灯、lamp 多方向红绿灯 |
| `BaiduCruiseProvider` | HTTP 小客车巡航 `detection`/`refresh` | `camera_list`、区间相机、`link_info`/限速；`CruiseRequestPolicy` 门控：移动 30s、静止 25m/无结果 30s、302 退避 15min |

`MergedBaiduProvider` 合并规则（`RoadDataMerger.merge`，post-2.9.2 / B3 之后）：

- 先看 `isValid`：**失效源不得贡献** roadId/pathId/限速/相机/区间/时间戳/置信度；
- `speedLimit`：只表示当前道路限速；horizon 有真实道路限速则用，http 兜底（HTTP 当前没有道路限速字段，所以通常是 `null`，**不再用摄像头限速填充**）；
- `cameras`：horizon **含测速相机**时用 horizon，否则用 http（只看列表非空会把 HTTP 测速点整表遮掉）；
- `cameraSpeedLimit`：跟随所选相机源；`intervalSpeedLimit`：跟随所选区间源；
- `lanes`：horizon 有则用 horizon，否则 http；
- `trafficLight`：horizon 优先，http 兜底；
- `interval`：horizon 非 NONE 优先，否则 http；
- `cameraAhead/cameraType/distance`：跟随所选相机源（同一台相机同时提供类型/距离/自身限速）；
- `confidence/timestamp`：仅在有效源之间取较大；`isValid = horizon || http`。

> 这段是 B1–B3 修改后的真实行为；`DECISIONS_AND_BOUNDARIES.md §2.7` 记录了对应的产品语义。

### 3.3 UI 状态

`CruiseViewModel` 组合：

```text
providerFlow.flatMapLatest { observeRoadData() }
+ LocationRepository.latest
+ LocationRepository.status
+ SettingsRepository.settings
→ CruiseUiState
```

关键门控：

- `roadUsable = status.cruiseData && !navActive`；
- 限速/相机/车道/红绿灯只在 `roadUsable` 时展示；
- `cameraAhead = status.cruiseData && road.cameraAhead && distance ≤ 800m`（区间 INSIDE 强制保持）；
- `LimitSign`/超速判定用 `road.speedLimit`；`CameraCapsule` 用 `state.cameraLimit`（来自 `road.cameraSpeedLimit`）；区间平均用 `road.intervalSpeedLimit`；
- `OverSpeedDetector.classify(speed, limit, cameraAhead, interval.phase, threshold)`；
- 进入 SEVERE 且 soundEnabled 且非导航 → `BeepPlayer`。

### 3.4 地图

`MapScreen.kt`：

- `MapLayer` 对外组件；`LiveBaiduMap` 持有 `TextureMapView`（`BaiduMapSdk.available` 时）或静态/占位降级；
- 主页角落与展开地图是**同一个 View**：`CruiseScreen.MapTransitionLayer` 用 `scaleX/scaleY + graphicsLayer` 做 620ms 过渡，不重建；
- `HOME_MAP_ZOOM=18f`；`detailMapZoom(homeScale)=18+log2(0.38)≈16.60`；横屏 `homeScale=1f`；
- 相机：
  - 巡航/浏览/搜索预览：`BaiduMap.setMapStatus`；
  - 导航中：`navActive` 阻止我们的相机写入；`LaunchedEffect(navActive)` 在交接时取消动画，并在 1.2s 后写一次 `newLatLngZoom(target, 17.5f)`，3.7s 打日志；
  - 归中：`recenterKey` + `recenterZoomReset`；按钮复位默认 zoom，收起地图保持当前 zoom；
- 跟随：`follow` 只在 GOOD 时传 true；死区 `max(20m, accuracy×0.8)`；
- 路线 fit：`newLatLngBounds(bounds, fitW, fitH)`，`fitW/H = view 尺寸 - 28dp×2`；记录 `applied` + zoom；
- 搜索预览：`fitRoute`/`preview` 分支；预览结束后回中心。
- `MapChrome`：收起、演示、搜索、设置、红绿灯胶囊、底部速度/限速卡、归中按钮。
- 降级：无 AK/SDK 不可用 → `MapBackdrop` 占位；静态图 `MapImageCache` 仅用于过渡/降级，不代表自研地图。

### 3.5 导航

`BaiduNavSource`（`ADD_MAP=false`）：

1. `plan()`：把 WGS84 起点转 bd09，百度路线规划；收集多方案 `routeTabs`/`routePaths`。
2. `startGuide()`：
   - 检查 activity/naviInited/routeReady；
   - `ADD_MAP=false`、`IS_REALNAVI=!simulate`、`IS_SUPPORT_FULL_SCREEN=false`；
   - `MapSceneController.enterNaviScene()` + `applyNaviDayNight()`；
   - `setStartByFullView(false)`；
   - `applyNaviDisplaySettings(true)`（仅保留地图原生红绿灯；`onlyMain` 已因 JNI 缺失移除）；
   - `manager.onCreate(activity, config)` → guideView；
   - `NavTrace.arm()` 多次抢回 crash handler；
   - guideView 可见、放在 `NaviGuideHost`（地图下方）；
   - 等待布局后 `onStart/onResume`；成功后 `pinNaviView()`（关闭自动回缩/自动缩放）。
3. 回调：`IBNaviListener`/`TrafficLightListener` 全部包 `guarded()`，异常只记 `[CALLBACK]`，避免 SDK 线程杀进程。
4. HUD：`NavHud` 读 `NavUiState`，展示转向卡/车道/速度/ETA/总览/退出；导航红绿灯用 `NavTrafficLight` 驱动 `TrafficLightCapsule`。
5. 退出：`stop()` 主线程化、重入保护；`pre-destroy` 清场景 → `onPause/onStop/onDestroy(false)` → 移除 guideView → `applyNaviDisplaySettings(false)` → `post-destroy` 清场景 + `sceneRevision++` → 600ms verify。
6. 日夜：`MapSceneController` 用 `BNMapController.setDayNightMode(NAV_DAY/NAV_NIGHT)`；设置 `day/night/system`，默认 day；`onDayNightChanged` 时按用户选择纠正。

**当前导航显示边界（2.9.4）**：

- `setIsOnlyDisplayMainRoute` 已移除：`carnavi:9.0.2` 的 .so 未实现 JNI，真机 `UnsatisfiedLinkError`；导航中候选路线不作为可修项。
- `setMapTrafficLightEnable(true)` 仍保留；原生灯数据/渲染不作承诺，HUD 灯是独立链。
- 退出后的 `MapSceneController.restoreCruiseScene` 已清路线/气泡/POI/交通；底图 POI 与单指拖动仍为 SDK 边界（MAP-01），不要重建 TextureMapView。

### 3.6 搜索

`NavSearchScreen`：

- `runSearch()` 并行发起：
  - `searchNearby`（当前点 5km、距离升序、20/页）；
  - `requestSuggestion`（`citylimit(false)`，全国）；
  - 都不足时 `searchInCity` 兜底；
- 按 uid 合并去重（uid 空用 name@city），上限 30；本地结果噪声时全国结果置顶；2.5s 超时；
- `seq` 令牌丢弃过期回调；
- 选中地点后写入 `SearchHistoryStore`（SharedPreferences，最多 10 条）。

### 3.7 设置与语音

`SettingsRepository`：

- DataStore `slm_settings`：threshold/sound/tts/keepScreenOn/dayNightMode/debugLog；
- `SecretStore`（EncryptedSharedPreferences）：服务端 AK/SK、MiMo Key、Android 应用端 AK；旧 `gateway_auth.preferences_pb`（noBackup）只在启动时迁移 `direct_ak` 后删除；
- `directAk` 默认 `BuildConfig.HORIZON_AK`（从 gitignored `local.properties` 注入）；设置 → 数据服务可加密覆盖；地图 AK 独立走 `BuildConfig.BAIDU_MAP_AK`/Manifest 并由 `SDKInitializer.setApiKey` 注入。
- `TtsPlayer` 直连 `https://api.xiaomimimo.com/v1/chat/completions`，Key 由 `SecretStore` 提供（回退 `BuildConfig.MIMO_API_KEY`）；空 Key 不请求；无网关。

### 3.8 诊断

- `NavTrace`：`nav-trace.log`；`[APP]` 版本/设备；`[GUIDE]`、`[HEARTBEAT]`、`[SCENE]`、`[CAM]`、`[FIT]`、`[SEARCH]`、`[NAVI]`、`[CALLBACK]`；`arm()` 在 SDK 覆盖 handler 后抢回。
- `NavDiagActivity`（debug）：导出 `nav-trace.log`/`crash.log`/`horizon-diag.log` + `ApplicationExitInfo`。
- `HorizonDiagActivity`（debug）：超视距/lamp 诊断。
- `JsonlDebugLogger`：可选的 JSONL 调试日志（设置 `debugLog`）。

### 3.9 零百性能测试（独立功能）

- 入口仅设置 → 性能测试 → `AccelActivity`；不进入 `CruiseScreen` 的布局/状态流。
- `AccelViewModel` 只读收集 `LocationRepository.latest`；不发百度请求、不新建定位。
- `feature/accel/core`：旧项目的 `ZeroHundredSession`、`PerformanceCalculator`、`TestMode` 等纯 Java 核心；`AccelSpeedFilter` 复用旧 SpeedEngine 的短时预测/修正。
- `TestStore` 保存到本机 `velora_accel.db`（tests/samples/segments）；UI 只显示 0–60、0–100、历史最佳。
- UI 强制浅色 `LightSlmColors`，复用 296dp/270° 圆弧几何与白色胶囊/卡片风格；不显示深色赛车仪表盘。

## 4. 外部依赖与职责

| 依赖 | 用途 | 约束 |
|---|---|---|
| `BaiduMapSDK_Map-Navi:9.0.2` | 地图渲染 + 导航引导 | 自带地图 SDK；不要重复加 `BaiduMapSDK_Map` |
| `BaiduMapSDK_Search:9.0.0` | POI/建议搜索 | 需地图 AK |
| `rxjava:1.3.8` | 导航引导页 `RGBottomSheet` 的 `BehaviorSubject` | **不能移除**；缺失会 `NoClassDefFoundError` + `System.exit(10)` |
| `okhttp:4.12` | HTTP/WS/lamp/TTS | — |
| Compose BOM 2024.06.00 | UI | — |
| DataStore 1.1.1 | 设置 | — |
| Lottie 3.6.0 | 动画资产（如使用） | — |

## 5. 状态所有权（关键）

- **地图相机**：巡航/浏览归我们；导航中归百度引擎；`navActive` 是唯一门控。
- **地图场景**：共享 native 引擎；`MapSceneController` 负责导航场景进入/退出、日夜、图层模式。
- **道路数据**：Provider 产出 `RoadData`；`MergedBaiduProvider` 合并；`CruiseViewModel` 按定位质量门控后给 UI。
- **定位**：`LocationRepository` 是唯一状态源；UI 不直接读 `LocationManager`。
- **导航**：`BaiduNavSource` 是唯一桥；`NavUiState` 给 HUD；巡航胶囊在 `navActive` 时被抑制。
- **设置**：`SettingsRepository` 唯一；`AppGraph` 只监听 `directAk` 切换 Provider。

## 6. 工程/构建

- `applicationId`/`namespace` 固定 `com.zouhu.speedlimit`；改包名或签名会让百度 AK 失效。
- `minSdk 26`、`targetSdk 34`、`compileSdk 36`；`arm64-v8a` 单 ABI；debug/release 均 `minifyEnabled false`。
- `build.ps1` → `dist/Velora-preview-debug.apk`；`test.ps1` → `testDebugUnitTest`。
- 当前稳定基线：`2.9.4/36`（tag `v2.9.4`，代码 `16797e8`）；无 remote。
- 备份：旧 `backups/` 已清理；历史 2.9.2 以 tag `v2.9.2` 为准，当前以 `v2.9.4` 为准。

## 7. 架构边界（不要误判为 Bug）

1. **导航中单指拖动无效**：`ADD_MAP=false` + 外部 `TextureMapView` 下，导航引擎独占相机/平移；双指缩放/旋转仍可用。独立 NavActivity 是另一项工程，2.9.3/2.10.0 已回退/留档；2.9.4 接受。
2. **不要重建/条件移除 `TextureMapView`**：2.9.1 因此白屏（共享 GL 上下文被拆）；当前始终保留同一 View。
3. **不要用 `BNMapController.setLevel` 当 map zoom**：量纲不同；当前只在 onStart 后 1.2s 用地图 SDK `newLatLngZoom(17.5)`。
4. **不要用 `setMapTrafficLightEnable` 的有无来判断 SDK 是否提供数据**：当前代码已开启，但数据交付/渲染未验证。
5. **巡航车道不等于导航车道**：巡航依赖车信 `lane_arrow`（未授权）；导航用 drawable mapper。
6. **共享引擎**：导航退出必须清图层/场景；否则巡航地图会继承 3D/夜间/路线残留。
7. **架构描述不等于验收**：2.9.4 只保证代码结构与正常路径；MAP-01（退出后 POI/单指拖动）与 MAP-04（onlyMain JNI）已确认为 SDK 边界。
8. **启动门控**：`GuideStartGate` 要求宿主 RESUMED；`NavStartGate` 管 320ms token；`NavModePolicy` 保证真实/演示互斥。
9. **长时重复导航性能未解决**：短时二次导航冒烟正常，但同进程长时间连续导航仍可能卡顿（O02）；未定位、未修。
