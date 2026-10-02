# Volume Tool 构建存档（BUILD_STATUS）

## 产物
| 文件 | 版本 | 说明 |
|---|---|---|
| `dist\VolumeTool-v1.0.apk` | versionCode 17 / 1.0 | **当前版本（真机验证通过）**：开源首发编号。内容与内部 v2.5 一致，修首页加减按钮「点一下跳两格」；`versionCode` 保持 17（>16）以保证旧版本能覆盖升级，不降级 |
| `dist\VolumeTool-v2.5.apk` | versionCode 16 / 2.5 | 内部版本：修首页加减按钮「点一下跳两格」（触摸 + 点击双触发），已归档到 `archive/` |
| `dist\VolumeTool-v2.4.apk` | versionCode 15 / 2.4 | 首页彻底重做成 底部导航三页（音量 / 设置 / 关于），参考 SmsForwarder 的卡片风格 |
| `dist\VolumeTool-v2.3.apk` | versionCode 14 / 2.3 | 修好「音量写入被 ColorOS 静默吞掉」——逐级降级 + 写后校验 |
| `dist\VolumeTool-v2.2.apk` | versionCode 13 / 2.2 | 修悬浮窗离开主界面后点不动（拆掉抖动重建）；通知刷新加轮询兜底 |
| `dist\VolumeTool-v2.1.apk` | versionCode 12 / 2.1 | 悬浮窗退到后台自动补回来；通知按钮重排让「切换」不展开也能用；首页两按钮照 btnOverlay 写法 |
| `dist\VolumeTool-v2.0.apk` | versionCode 11 / 2.0 | 只要媒体/铃声两路；状态栏新增「媒体/铃声」切换磁贴 |
| `dist\VolumeTool-v1.9.apk` | versionCode 10 / 1.9 | 媒体/铃声/闹钟全局切换，一处选定处处同步；修「音量−」不响应 |
| `dist\VolumeTool-v1.6.apk` | versionCode 7 / 1.6 | 通知数字实时刷新（step/setMuted 内直接 post） |
| `dist\VolumeTool-v1.5.apk` | versionCode 6 / 1.5 | 后台保活 + 开机自启 + 防杀 + 电池白名单 |
| `dist\VolumeTool-v1.4.apk` | versionCode 5 / 1.4 | 悬浮窗全局化（磁贴开关 + 授权自动开） |
| `dist\VolumeTool-v1.3.apk` | versionCode 4 / 1.3 | 悬浮窗收成小圆 + 数字实时刷新 |
| `dist\VolumeTool-v1.2.apk` | versionCode 3 / 1.2 | 悬浮窗（药丸版）+ 每次进入索要权限 |
| `dist\VolumeTool-v1.1.apk` | versionCode 2 / 1.1 | 两个独立磁贴 + 首页两个大按钮 |
| `dist\VolumeTool-v1.0.apk` | versionCode 1 / 1.0 | 初版（单磁贴），留档 |
| `dist\volume.keystore` | — | 签名密钥：alias `volumetool`，store/key pass `volumetool123` |

十四版同一张证书，SHA-256 `623e39c25e10533ca99f5c4553c517bf5195f51b370025fb3f48a5bf5d41adbd` → 新版可直接覆盖安装旧版。
`build.ps1` 已按用户要求删除（构建由 agent 直接跑命令行管线）。

## v1.9 新增（调哪一路：媒体 / 铃声，全端同步）
### 一个真相来源
`Streams`（SharedPreferences `volume_tool_prefs` → `current_stream`）是**唯一**的「当前调哪一路」。
`VolumeHelper` 里所有读写音量都过 `Streams.streamType()`，所以没有任何地方能自己偷偷调媒体。
切换时发应用内广播 `com.xuhao.volumetool.STREAM_CHANGED`（`setPackage` 限定本应用），四处同时刷新：

| 表面 | 有没有自己的选择 | 表现 |
|---|---|---|
| 常驻通知 | 有（第 4 个按钮 `→铃声`） | 标题变「铃声 5/7」，按钮轮流显示下一个目标 |
| 首页 | 有（媒体 / 铃声 两个按钮） | 选中的蓝底白字加 ✓，未选中浅灰底深灰字 |
| 状态栏「媒体/铃声」磁贴 | **有**（v2.0 起，点一下就换） | 调铃声时点亮，副标题写「铃声 5/7」 |
| 状态栏 音量± 磁贴 | **没有自己的选择，纯跟随** | 副标题变「铃声 5/7」，由 `KeepAliveService.pokeTiles()` 强制重画 |
| 悬浮窗 | **没有自己的选择，纯跟随** | 收到 STREAM_CHANGED 立刻重读并 `wake()` 一下 |

### 「音量 −」为什么之前按了没反应
`adjustStreamVolume(stream, ADJUST_LOWER, ...)` 在部分 ROM 上会被吞掉。
改成**先读当前值、算目标值、再 `setStreamVolume` 精确写**。± 走同一段代码，不存在某个方向失灵；
顺带：若当前是静音状态先自动解除静音（否则调了也听不出变化）；ROM 禁用 `setStreamVolume` 时兜底退回 `adjustStreamVolume`。
另外补了 `MODIFY_AUDIO_SETTINGS`（normal 级，不弹权限框）—— 少数 ROM 会校验它。

## v2.3 改动（真机调试定案）
开发机直连真机（OPPO PEGM00，ColorOS 11 / Android 11，设备号 dffdcc40）逐项实测，抓 logcat 定位到**真正的根因**：

- **这台机器上 `AudioManager.setStreamVolume()` 会被系统静默吞掉**：调用不报错、返回正常，
  但 `getStreamVolume()` 读回来值根本没变。v1.9 修「音量−」时改成了 setStreamVolume 精确定位，
  在这台 OPPO 上等于把加减两条路全堵死 —— 这才是「通知栏/悬浮窗怎么调都没反应」的真因。
- **修法（VolumeHelper.step）**：不再赌某一种写法，改成**逐级降级 + 写后校验**：
  1. `setStreamVolume(目标值)` → 写完立刻读回来，变了才算数
  2. 不行换 `adjustStreamVolume(ADJUST_RAISE / ADJUST_LOWER)`（实测本机走这条，日志 how=adjust）
  3. 还不行换 `setStreamVolume + FLAG_SHOW_UI`
  4. 最后 `adjustStreamVolume + FLAG_SHOW_UI`
  每级都以「读回来的值真的变了」为准；`setMuted` / `setVolume` 同样处理。
  任何 ROM 都能命中有效路径。
- `VolumeReceiver` 显式 `exported=true`（无 intent-filter 时默认不导出；只影响 adb 调试，
  通知按钮本来就不受影响；action 是自定义串，无安全风险）。
- 代码里保留 `Log.d("VT-*")` 诊断日志：出问题直接
  `adb logcat -s VT-Volume VT-Overlay VT-Notif VT-Recv VT-Keep`。

### 真机验证记录（OPPO PEGM00 / ColorOS 11，全部通过）
- 桌面与系统设置界面点击悬浮窗：均生效（13→14，how=adjust）——「离开主界面点不动」已修
- 通知按钮 音量+ / 音量− / 切换 / 静音：全部生效且标题实时刷新（12→13→14→切铃声→切回）
- 静音 ↔ 取消静音：正常（「媒体（已静音）」↔「媒体 14/16」）
- 首页截图确认：「媒体（当前）」「铃声」按钮文字正常无乱码，悬浮窗小圆正常
- 音量− 与 + 同样有效（降级链自动选本机有效路径）

## v1.0（开源首发，2026-10-02）

仓库：<https://github.com/niavigg/Volume-Tool>

- **版本编号重置**：对外叫 v1.0（开源首发），`versionCode` 保持 **17**（> 内部的 16），
  这样手机上已装的 2.5 能正常覆盖升级，不会因为 versionName 回退而装不上。
  `strings.xml` 的 `about_repo` 与 README 里的仓库地址已换成真实地址。
- **目录整理**：历史 APK（v1.0~v2.4 等 16 个）与构建 stub 移到仓库外的 `archive/`；
  签名密钥 `volume.keystore` 放在仓库外，由持有人自行保管；
  `project/gen/`（`aapt2` 生成的 `R.java`）移出仓库并加进 `.gitignore`。
- **入库清单**：54 个文件，源码 14 个类 + 资源 + README/LICENSE/build.sh/BUILD_STATUS/截图。
  已核对：无密钥、无 APK、无个人路径（`BUILD_STATUS.md` 里的用户目录已改为通用描述）。
- **发布说明**：`docs/RELEASE-v1.0.md`，可直接复制作为 GitHub Release 的正文。

## v2.5 改动（修首页加减按钮点一下跳两格）

- **根因：同一个按钮上同时挂了 `OnTouchListener` 和 `OnClickListener`，两边各调一次 `VolumeHelper.step()`。**
  `bindHoldButton()` 里 ACTION_DOWN 调一次（这是为了长按连发），`OnClickListener` 又调一次；
  而 OnTouchListener 返回 `false` 表示「我不吃这个事件」，View 会继续走 `onTouchEvent` → `performClick()`，
  于是手指一次点击 = 音量走两格。
  （返回 false 是为了保留按钮的按压态和水波纹，不能改成 true。）
- **改法**：加 `consumed[0]` 标记。ACTION_DOWN 里置位并调一格，onClick 看到标记就跳过并复位；
  ACTION_CANCEL 时复位（被取消的事件不会产生 click）。长按连发逻辑没动。
- **真机验证（OPPO PEGM00）**：单击 + 连点三次 → 10→11→12→13，每次正好一格；
  长按 1.5 秒 → 13→16（连发正常）。
- 顺带给 `build.sh` 加了密钥缺失的醒目警告：换签名会导致手机上的旧版本无法覆盖升级
  （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），不能悄悄生成新密钥了事。

## v2.4 改动（首页重做 + 开源整理）
- **主界面推翻重做**：参考 SmsForwarder 的 Material 卡片风格，底部导航三页（音量 / 设置 / 关于）。
  不用 androidx / ViewPager / Fragment，就是三个 ScrollView 叠一起切 visibility，
  tab 用 LinearLayout + `setSelected` 驱动 selector 换色。全项目依旧零依赖，只靠 android.jar 编译。
  - 音量页：大号读数卡（数字 + 进度条 + 当前路名）、媒体/铃声分段按钮（selected + seg_bg selector）、
    两个大加减按钮（长按连发）、悬浮窗开关卡（Switch）
  - 设置页：权限三行（红/绿点 + 去授权）、保活状态行、快捷操作说明、使用提示
  - 关于页：版本 + 机型信息、功能特性、实现方式、开源信息
- **悬浮窗状态广播**：`OverlayService` 新增 `ACTION_STATE_CHANGED`，
  onCreate/onDestroy 时发应用内广播，首页 Switch 实时跟上（之前靠延迟刷新，时机不稳）。
- **开源整理**：新增 README.md（含截图 docs/screenshots/）、LICENSE（MIT）、.gitignore、
  build.sh 一键构建（自动拷 ASCII 临时目录避中文路径坑，密钥不存在时自动 keytool 生成）。
  strings.xml 清掉了旧首页的废弃文案。
- **UI 同步教训**：分段按钮高亮靠 `setSelected` + selector，refreshVolumeUi 里必须一并刷；
  服务异步拉起后 UI 不会自己知道，要么广播要么延迟刷。

## v2.2 改动（修自己上版埋的雷）
- **悬浮窗离开主界面就点不动 = v2.1 那个「3 秒自检」干的。**
  自检用 `root.isAttachedToWindow()` 判断窗口在不在，但退出 App 后 ROM 会让这个值短暂变 false，
  代码就 `removeViewImmediate` + 重新 inflate 一个全新悬浮窗 —— 等于每 3 秒拆一次建一次，
  手指按下去窗口刚好被换掉，触摸事件整个丢掉。
  **改法**：删掉定时轮询，改用 `ViewTreeObserver.OnWindowAttachListener` 只听系统回调，
  真被摘了才延迟 700ms 补回（`AttachWatch` / `ReattachTask`），**平时一次都不重建**。
  另加 `finishing` 标志，避免我们自己 `onDestroy` 摘窗口时又被当成"被系统摘了"补回来。
- **悬浮窗窗口 flags 补全**：除 `FLAG_NOT_FOCUSABLE` 外加 `NOT_TOUCH_MODAL`
  （只吃自己那一小块区域的触摸，区域外穿透给下面的应用；少了它部分 ROM 会把小窗口
  当成全屏拦截层，别的应用反而点不动）、`WATCH_OUTSIDE_TOUCH`、`LAYOUT_NO_LIMITS`（允许贴边）、
  `HARDWARE_ACCELERATED`。闲置透明度 0.35 → 0.55（太淡看着像点不了）。
- **通知不实时刷新**：加了三层保险。
  1. `VolumeNotifier.post()` 合并连续调用 + **内容签名比对**，跟上次一模一样就不 `notify()`
     （无意义的重复 notify 会被部分 ROM 直接丢弃，看起来就是"不刷新"）；
  2. `setOnlyAlertOnce(true)`，内容变了就更新但不当新通知反复提醒；
  3. `KeepAliveService` 轮询兜底：每 3 秒直接读一次当前音量，跟上次记录不同就 `force()` 重贴。
     这样即使 ROM 不发 `VOLUME_CHANGED_ACTION`、或者广播到不了后台服务，最迟 3 秒也会刷。
  切「媒体/铃声」走 `force()`，保证标题文字一定换。

## v2.1 改动
### 1. 悬浮窗退出主界面就没了 —— 加自恢复
- `OverlayService$KeepTask`：每 3 秒自检窗口是否还贴着（`getParent()/isAttachedToWindow()`），被摘掉就重新 `addOverlay()`。
  这是针对「主界面一退悬浮窗就消失」的直接解药（部分 ROM 会在应用进后台时摘掉 overlay window）。
- `KeepAliveService$Watchdog`：每 5 秒检查 `OverlayService.shouldBeOn()` 且 `RUNNING==false` → 重新 `startService`。
  另外 `KeepAliveService.onCreate()` 也会恢复一次——进程被杀后被 START_STICKY 拉回来时，悬浮窗会跟着回来。
- 仍然救不回来的情况：MIUI / ColorOS 的「**后台弹出界面** / 后台显示悬浮窗」没开。这是 ROM 的限制，
  首页 hint 里加了提示让用户自己去系统设置开。

### 2. 首页按钮照抄「悬浮窗开关」按钮的写法
用户指明：`btnOverlay` 是正常的，那两个是坏的。所以彻底放弃"自己画选中态"的做法，
去掉 `setBackgroundTintList` / `setTextColor` / `setTypeface` / `"✓ " + text` 这些花活，
**只做 `setText(R.string.xxx)`**：选中显示「媒体（当前）」，未选中显示「媒体」，跟 btnOverlay 一模一样。
教训：不要碰系统 Button 的背景与字体，标记选中态用文字最稳。

### 3. 通知栏按钮重排
通知**折叠态只显示前 3 个 action**（这是 SystemUI 的行为，与机型无关，第 4 个要展开才看得到）——
上一版把「切换」排在第 4 位，所以用户在通知栏根本摸不到切换。现在重排为：
`音量+` → `音量−` → `切换（→铃声）` → `静音`，切换进入最前三个，不展开就能用。

## v2.0 改动
- **砍掉闹钟**：`Streams` 只剩 `MEDIA` / `RING` 两路（`COUNT = 2`），`next()` 变成媒体 ⇄ 铃声来回切。
  字符串、首页按钮、注释里的「闹钟」全部清掉（只保留保活那个 AlarmManager 闹钟，跟音量无关）。
- **状态栏也能切**：新增第 4 个快捷设置磁贴 `StreamTileService`（图标 `res/drawable/ic_stream.xml`，标签「媒体/铃声」），
  副标题写「铃声 5/7」；调铃声时磁贴点亮（`STATE_ACTIVE`），调媒体时暗下来；点一下就换。
  **用户要手动把它拖进快捷栏**：下拉快捷设置 → 编辑。`KeepAliveService.pokeTiles()` 里已把它加进去，别处一切它立刻重画。
- **修首页按钮的「乱」**：之前用 `setBackgroundColor(实心蓝 / 透明)` 标选中，会把 Button 默认的形状和水波纹整个盖掉，
  视觉上就是一堆色块。改成 `setBackgroundTintList` 着色（保留原形状）：未选中浅灰底深灰字，选中蓝底白字 + ✓ 前缀。
  原文存在 `streamBase[]` 里，避免 ✓ 前缀反复叠加；按钮补了 `singleLine` 和 `minHeight 48dp`。

## v1.5 新增（后台保活 + 开机自启 + 防杀）
### 保活三板斧（Android 上真能防杀的就这几样）
1. **常驻前台服务 `KeepAliveService`**：借用音量通知做前台通知（不额外多一条），START_STICKY 被系统回收后自动重建。这是防杀的根——前台服务是 Android 唯一合法的不被杀手段。
2. **开机自启**：`BootReceiver` 收到 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → 拉起 KeepAliveService + 恢复通知 + 恢复悬浮窗（若之前开着）。配合用户在系统设置里给的自启动/关联启动权限，开机就活。
3. **被划掉自动回来**：`KeepAliveService.onTaskRemoved()` → AlarmManager 2 秒后触发 `KeepAliveReceiver` → 重新拉起 KeepAliveService + 恢复悬浮窗。用非精确闹钟（`set`），不需要 SCHEDULE_EXACT_ALARM 权限；FGS 启动靠 SYSTEM_ALERT_WINDOW 豁免（Android 14 FGS 限制豁免项之一）。

### 电池优化白名单（防 Doze 杀后台）
- 新增权限 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- 主页权限状态行加了电池白名单项：缺了就红字提示「⚠ 未加入电池优化白名单 —— 点此加入（防止后台被杀）」，点了跳系统对话框一键加入
- `ensurePermissions` / `grantFirstMissing` 优先级：悬浮窗 → 通知 → 电池白名单，每次进应用都检查

### 主页改动
- 新增 `tvKeepAlive` 状态行：运行中绿字「后台保活：✓ 运行中（开机自启；从最近任务划掉也会自动回来）」；停了红字可点拉起
- 权限状态行加电池白名单项
- hint 更新：提到三个磁贴、开机自启、划掉自动回来

### 架构调整
- `OverlayService` 不再是前台服务（去掉 startForeground），变成纯视图管理服务；前台保活统一归 `KeepAliveService`
- 所有 `startForegroundService(OverlayService)` 调用点改成 `startService`（避免 5 秒内没调 startForeground 导致 ANR）
- `VolumeReceiver`（通知按钮）顺手拉 KeepAliveService，点通知 = 应用活着
- `KeepAliveService` 自己也注册了 VOLUME_CHANGED_ACTION 监听，任何来源改音量都刷通知数字（不再依赖悬浮窗或主界面在线）

## v1.4 新增（悬浮窗全局化，不再绑死主界面）
- **新增「悬浮窗」磁贴（`OverlayTileService`）**：任何页面下拉快捷设置，点一下直接开/关悬浮窗，
  配图标 `ic_overlay`、标签「悬浮窗」，副标题显示「悬浮窗：开/关」。这就是"在别的页面随手用"的入口。
- **授权后自动开启**：`MainActivity.autoStartOverlayIfReady()` 在 `onResume` 里判断——悬浮窗权限已授权 +
  服务没在跑 + 用户没手动关过（`KEY_OFF_MANUALLY`）→ 自动 `startForegroundService` 把悬浮窗开起来。
  所以授权完第一次，悬浮窗就直接全局可用，不用专门切回主界面点开关。
- **手动关过就尊重**：主界面按钮 / 磁贴关掉时会写 `KEY_OFF_MANUALLY=true`，此后不会在 `onResume` 里
  又自动开回来；再想开，点磁贴或主界面按钮（会清掉该标记）。
- 澄清：悬浮窗窗口本身一直是系统级 `TYPE_APPLICATION_OVERLAY`（全局任意页面显示）+ 前台服务 +
  开机自启；之前"限死"的是**开关入口只在主界面**，这版补上了磁贴入口 + 自动开启。

## v1.3 新增
### 1. 悬浮窗形态：平时小圆，用时药丸
- **平时**：38dp 小圆（`ovCircle`），只显示当前音量数字，35% 不透明度（数字仍可读），几乎不占地方
- **点一下**：展开成药丸「− 音量 +」（`ovPill`）**并立刻调一格**（点圆左半 −1 / 右半 +1），按住 400ms 起每 120ms 连发
- **闲置 2.5 秒**：自动收回成小圆
- 药丸比 v1.2 缩小：高 44→38dp，± 文字 22→18sp，数字 15→13sp，内边距 14→10dp
- 形态切换**保持视觉中心不动**（药丸以圆心展开，收起时对回圆心）；`setMode()` 里 `root.post()` 等布局完成后再取药丸尺寸做位移
- **防漂移**：收起成圆时 `savePos()` 存的是圆形位置，服务重启从圆形开始 → 位置不漂移
- `clampPos()` 用 `DisplayMetrics` 把窗口限制在屏幕内
- 布局改 `FrameLayout` 双子视图（圆 VISIBLE / 药丸 GONE），`WRAP_CONTENT` 窗口随内容收缩——药丸收起后不会留"看不见的死区"挡住下面的应用

### 2. 悬浮窗数字实时刷新
- `OverlayService$VolumeWatcher`（static 嵌套类，BroadcastReceiver）监听
  `"android.media.VOLUME_CHANGED_ACTION"`（和首页同一个隐藏广播），**任何来源**改媒体音量
  （侧边栏/磁贴/通知栏/物理键/其他应用）→ 圆和药丸里的数字立刻刷新
- API 33+ 用 `RECEIVER_NOT_EXPORTED` 注册（与 MainActivity 同款写法）；`onDestroy` 注销
- 顺带把常驻通知的数字也刷了（`onVolumeChanged` → `VolumeNotifier.post`）
- 磁贴点按也补了 `VolumeNotifier.post(this)`（`VolumeTileBase.onClick`），磁贴调完音量通知数字也同步

## v1.2 的内容（仍然有效）
- **音量悬浮窗**（`OverlayService`）：`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`，可拖动，
  前台服务（`specialUse` 类型）复用常驻通知（`VolumeNotifier.NOTIF_ID`），不多出一条通知；
  开机/更新后若之前是开着的自动恢复（`BootReceiver`）；首页按钮开关
- **每次进入应用都索要权限**：`onResume()` 检查悬浮窗 + 通知权限，缺哪个弹哪个（可分别"去授权"）；
  悬浮窗权限必须跳设置页（`ACTION_MANAGE_OVERLAY_PERMISSION`），返回后 `onResume` 再查；
  首页常驻一行状态：缺权限红字可点，齐了绿字 `✓`
- 清单新增 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`；
  `OverlayService` 声明 `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`

## v1.1 的内容（仍然有效）
- **快捷设置磁贴拆开**：`VolumeUpTileService`（音量 +）、`VolumeDownTileService`（音量 −），
  公共逻辑在抽象基类 `VolumeTileBase`；标签固定不混淆，副标题「当前 x/y」，静音/0 变灰；
  `setSubtitle` 是 API 29+ 加了版本判断
- 首页两大按钮调音量（等权重填满，长按连发）+ 媒体/铃声 二选一行
- 通知栏的 音量+ / 音量− / 静音 / 切换 一直是四个独立按钮（v1.9 起）

## 构建管线（agent 直接跑，全部在纯 ASCII 临时目录、相对路径）
```
aapt2 compile --dir res -o res.zip
aapt2 link -o base.apk -I android.jar --manifest AndroidManifest.xml \
           --min-sdk-version 26 --target-sdk-version 34 --java gen res.zip
javac --release 11 -encoding UTF-8 -classpath android.jar -d cls <src/**/*.java + gen/**/*.java>
jar cf app.jar -C cls .
d8 --lib android.jar --min-api 26 --output dex app.jar      # dex 目录须预先存在；输入不接受目录
jar uf pkg.apk classes.dex                                   # dex 必须在 jar 根
zipalign -f 4 pkg.apk aligned.apk
apksigner sign --ks volume.keystore --ks-pass pass:volumetool123 --key-pass pass:volumetool123 --out out.apk aligned.apk
apksigner verify --print-certs out.apk
```
keystore 要先拷进 ASCII 目录再用相对路径签名（apksigner 走 java.exe，中文路径参数会被 ANSI 转码破坏）。

## 必须记住的坑
1. **aapt2 link 的资源 zip 要当"位置参数"**，不能写 `-R res.zip`：`-R` 是 overlay 语义，会报
   `resource string/xxx does not override an existing resource`。
   旧会话因此绕远路搞了个"框架属性 stub + `--auto-add-overlay`"，其实完全不需要（`stub-ascii\` 是废的）。
2. **d8/R8 8.2.2 对含 `this$0` 合成字段的嵌套类会 NPE**
   （`Cannot invoke "String.length()" because "<parameter1>" is null`）——与中文路径无关、与是否匿名无关：
   - 静态上下文匿名类（无 this$0）→ 通过；实例匿名类 / **命名**实例内部类（有 this$0）→ 崩
   - **规矩：内部类一律写成 `static` 嵌套类 + 构造函数显式传宿主**（现在的 `MainActivity$RepeatStep`、
     `MainActivity$RefreshReceiver`、`OverlayService$RepeatTask`、`OverlayService$VolumeWatcher` 都是这样）。
     lambda / 方法引用安全（编译成 invokedynamic，不产生带 this$0 的 class 文件），所以监听器都写 lambda。
2. **`new Notification.Builder(...)...addAction(...)` 别忘了最后那个 `.build()`**：少写它，javac 报的是
   少写它 javac 会报「不兼容的类型: Builder无法转换为Notification」，而且符号指向最后一个 `addAction`，很容易误判。
3. **从 jar 直接起 apksigner 的主类是 `com.android.apksigner.ApkSignerTool`**（不是带 `.tools.` 的那个包）。

## 工具链
- JDK 25：`C:\Program Files\Microsoft\jdk-25.0.2.10-hotspot`
- SDK：任意 Android SDK（含 `platforms/android-34/android.jar` 与 `build-tools/34.0.0`），
  路径通过 `ANDROID_SDK` 环境变量传给 `build.sh`，不必放进仓库
- 源码：`project\src\`（13 个类）、`project\res\`、`project\AndroidManifest.xml`

## 命令行版管线（不用 .bat，Git Bash 下更稳）
```
SDK=.../volume-tool/sdk; BT=$SDK/build-tools/34.0.0; JDK=".../jdk-25.0.2.10-hotspot/bin"
aapt2.exe  compile --dir res -o res.zip
aapt2.exe  link -o base.apk -I $SDK/platforms/android-34/android.jar \
           --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 34 --java gen res.zip
javac.exe  --release 11 -encoding UTF-8 -classpath $SDK/platforms/android-34/android.jar -d cls <src+gen>
jar.exe    cf app.jar -C cls .
java.exe   -cp $BT/lib/d8.jar com.android.tools.r8.D8 --lib $SDK/.../android.jar --min-api 26 --output dex app.jar
jar.exe    uf base.apk classes.dex          # 必须在 dex 目录下执行，classes.dex 要在 jar 根
zipalign.exe -f 4 pkg.apk aligned.apk
java.exe   -cp $BT/lib/apksigner.jar com.android.apksigner.ApkSignerTool sign --ks volume.keystore \
           --ks-pass pass:volumetool123 --key-pass pass:volumetool123 --out signed.apk aligned.apk
```
（全程在纯 ASCII 临时目录 `%TEMP%\vt19` 里跑，避免中文路径把参数转坏。）

## 安装使用
- `adb install -r dist\VolumeTool-v1.3.apk`（或拷到手机直接装，需允许未知来源；覆盖安装后悬浮窗开关记得重新开）
- 打开应用会**自动弹权限请求**；首页红字也能点着去授权
- 首页点「开启音量悬浮窗」→ 屏幕出现小圆（显示当前音量）
  - 点小圆 → 展开成药丸并立刻调一格（左 − 右 +），按住连发；2.5 秒不用自动收回成小圆
  - 可拖动，位置记住；侧边栏/磁贴/通知栏调音量，数字实时刷
- 下拉快捷设置 → 编辑，把「音量 +」「音量 −」两个磁贴拖进快捷栏
- 常驻通知里也有 音量+ / 音量− / 静音 三个按钮