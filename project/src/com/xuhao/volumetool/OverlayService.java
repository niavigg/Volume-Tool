package com.xuhao.volumetool;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 音量悬浮窗：
 *  · 平时：一个小圆（38dp）显示当前音量数字，半透明，不占地方
 *  · 点一下：立刻展开成小药丸「− 音量 +」并马上调一格（左半 −1 / 右半 +1），按住连发
 *  · 闲置 2.5 秒自动收回成小圆
 *  · 可拖动，位置记住（存的是圆形位置，重开不漂移）
 *  · 任何途径改音量（侧边栏/磁贴/通知/物理键/其他应用），圆和药丸里的数字实时刷新
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）。
 */
public class OverlayService extends Service {

    public static final String PREFS = "volume_tool_prefs";
    public static final String KEY_ENABLED = "overlay_enabled";
    public static final String KEY_OFF_MANUALLY = "overlay_off_manually";
    public static final String KEY_X = "overlay_x";
    public static final String KEY_Y = "overlay_y";

    private static final long IDLE_MS = 2500L;        // 闲置多久收回到圆形
    private static final float IDLE_ALPHA = 0.55f;    // 圆形闲置时的不透明度（别太淡，太淡看着像点不了）

    /** 调试用，抓日志：adb logcat -s VT-Volume:VT-Overlay:VT-Notif:VT-Keep */
    private static final String TAG = "VT-Overlay";
    private static final long HOLD_DELAY_MS = 400L;   // 药丸上按住多久开始连发
    private static final long HOLD_REPEAT_MS = 120L;  // 连发间隔
    private static final int DRAG_SLOP = 10;          // 超过这个位移算拖动
    private static final int CIRCLE_DP = 38;          // 圆形直径
    private static final long REATTACH_DELAY_MS = 700L;   // 被摘掉后隔多久补回来

    /** 主界面用它判断悬浮窗是否正在显示 */
    public static volatile boolean RUNNING = false;
    /** 开/关状态变化的应用内广播，MainActivity 收到后刷新悬浮窗开关 */
    public static final String ACTION_STATE_CHANGED = "com.xuhao.volumetool.ACTION_OVERLAY_STATE";

    private WindowManager wm;
    private View root;
    private LinearLayout pill;
    private TextView tvVol;       // 药丸里的数字
    private TextView tvCircle;    // 圆形里的数字
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable fadeTask;
    private RepeatTask repeatTask;
    private BroadcastReceiver volumeWatcher;

    private float downRawX, downRawY;
    private int startX, startY;
    private boolean moved, repeated;
    private int dir = +1;
    private boolean expanded;      // true = 药丸；false = 圆形
    private int pillW, pillH;      // 最近展开时药丸尺寸，收回时对准中心用
    private int circlePx;          // 圆形直径（px）
    private int screenW, screenH;
    private boolean attached;      // 窗口当前是否贴在屏幕上
    private boolean finishing;     // 是我们自己收摊，别当成"被系统摘了"又补回来
    private Runnable reattachTask;
    private ViewTreeObserver.OnWindowAttachListener attachWatch;

    /**
     * 药丸上的长按连发任务。
     * 必须是 static 嵌套类 + 显式持有 service：捕获外部实例产生的 this$0
     * 会触发 build-tools 34.0.0 自带 R8 8.2.2 的 NPE。
     */
    private static final class RepeatTask implements Runnable {
        private final OverlayService s;
        private final Handler h;

        RepeatTask(OverlayService s, Handler h) { this.s = s; this.h = h; }

        @Override
        public void run() {
            s.repeated = true;
            s.step(s.dir);
            h.postDelayed(this, HOLD_REPEAT_MS);
        }
    }

    /**
     * 音量变化监听（和主界面同一方案）：任何来源（磁贴/通知/侧边栏/物理键）
     * 改了媒体音量都会广播过来，实时刷新悬浮窗数字。
     */
    private static final class VolumeWatcher extends BroadcastReceiver {
        private final OverlayService s;

        VolumeWatcher(OverlayService s) { this.s = s; }

        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent != null && Streams.ACTION_CHANGED.equals(intent.getAction())) {
                s.onStreamChanged();     // 换了调哪一路
            } else {
                s.onVolumeChanged();     // 音量本身变了
            }
        }
    }

    /**
     * 监听"悬浮窗被系统摘掉"这件事本身。
     *
     * 之前用定时轮询 + isAttachedToWindow() 判断，结果退出主界面后 ROM 会让这个值
     * 短暂变 false，代码就每 3 秒把悬浮窗 remove 掉重新 inflate 一次 —— 用户手指按下去
     * 窗口刚好被换掉，触摸事件整个消失，表现为"离开主界面就点不动"。
     * 现在改成只听系统回调：真被摘了才补，平时一次都不重建。
     */
    private static final class AttachWatch
            implements ViewTreeObserver.OnWindowAttachListener {
        private final OverlayService s;

        AttachWatch(OverlayService s) { this.s = s; }

        @Override public void onWindowAttached() { s.attached = true; }

        @Override
        public void onWindowDetached() {
            s.attached = false;
            if (s.finishing) return;                 // 是我们自己关的，别补
            if (shouldBeOn(s)) {
                s.handler.removeCallbacks(s.reattachTask);
                s.handler.postDelayed(s.reattachTask, REATTACH_DELAY_MS);
            } else {
                s.stopSelf();
            }
        }
    }

    /** 被摘掉之后的补回动作（static 嵌套类 + 显式宿主，躲开 R8 8.2.2 的 this$0 NPE） */
    private static final class ReattachTask implements Runnable {
        private final OverlayService s;

        ReattachTask(OverlayService s) { this.s = s; }

        @Override public void run() { s.reattach(); }
    }

    /** 用户设置里是不是"该显示悬浮窗" */
    public static boolean shouldBeOn(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        fadeTask = this::fadeToIdle;
        repeatTask = new RepeatTask(this, handler);
        reattachTask = new ReattachTask(this);
        attachWatch = new AttachWatch(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        circlePx = (int) (CIRCLE_DP * dm.density);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        if (!addOverlay()) {
            RUNNING = false;
            stopSelf();
            return;
        }
        RUNNING = true;
        registerVolumeWatcher();
        broadcastState();
        // 前台保活归 KeepAliveService 管，这里只管悬浮窗本体
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wake();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        finishing = true;              // 先立牌子，免得摘窗口的回调又把它补回来
        RUNNING = false;
        handler.removeCallbacksAndMessages(null);
        if (volumeWatcher != null) {
            try { unregisterReceiver(volumeWatcher); } catch (Exception ignored) {}
        }
        savePos();
        if (root != null && wm != null) {
            try {
                if (attachWatch != null) {
                    root.getViewTreeObserver().removeOnWindowAttachListener(attachWatch);
                }
            } catch (Throwable ignored) {}
            try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
        }
        root = null;
        attached = false;
        // 悬浮窗关掉后，把常驻通知补回来（音量按钮还在通知里）
        try { VolumeNotifier.post(this); } catch (Exception ignored) {}
        broadcastState();
        super.onDestroy();
    }

    /** 把开/关状态广播给同进程的界面（首页悬浮窗开关靠它跟上节奏） */
    private void broadcastState() {
        try {
            sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
        } catch (Exception ignored) {}
    }

    /**
     * 补回悬浮窗：只在"确实被系统摘掉了"的时候才走（由 AttachWatch 触发）。
     * 平时一次都不会执行，所以不会打断用户正在进行的触摸。
     */
    private void reattach() {
        if (finishing) return;
        if (!shouldBeOn(this)) { stopSelf(); return; }
        if (attached && root != null && root.getParent() != null) return;   // 已经在了，别重复贴
        if (!Settings.canDrawOverlays(this)) { RUNNING = false; stopSelf(); return; }

        // 旧的那个可能已经被系统摘了，也可能还挂在 WM 上；两种都要安全处理
        if (root != null) {
            try {
                root.getViewTreeObserver().removeOnWindowAttachListener(attachWatch);
            } catch (Throwable ignored) {}
            try { wm.removeViewImmediate(root); } catch (Throwable ignored) {}
            root = null;
            attached = false;
        }
        try {
            if (!addOverlay()) {
                RUNNING = false;
                stopSelf();
            }
        } catch (Throwable t) {
            RUNNING = false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** @return 是否成功把悬浮窗加上（没权限时为 false） */
    private boolean addOverlay() {
        if (!Settings.canDrawOverlays(this)) return false;

        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        root = LayoutInflater.from(this).inflate(R.layout.overlay_volume, null);
        pill = root.findViewById(R.id.ovPill);
        tvVol = root.findViewById(R.id.ovVol);
        tvCircle = root.findViewById(R.id.ovCircle);
        expanded = false;   // 默认从圆形开始

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE：不抢焦点，后面的应用照常能操作；
                // NOT_TOUCH_MODAL：只吃自己这一小块区域的触摸，区域外事件穿透给下面的应用
                // （少了它，部分 ROM 会把整个窗口当成全屏拦截层，别的应用反而点不动）；
                // WATCH_OUTSIDE_TOUCH：能感知区域外的按下；
                // LAYOUT_NO_LIMITS：允许贴到屏幕边缘不被强行拉回来。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt(KEY_X, 60);
        lp.y = sp.getInt(KEY_Y, 400);

        root.setOnTouchListener(this::onTouch);
        try {
            root.getViewTreeObserver().addOnWindowAttachListener(attachWatch);
        } catch (Throwable ignored) {}
        try {
            wm.addView(root, lp);
            attached = true;
        } catch (Exception e) {
            root = null;
            attached = false;
            return false;
        }
        showVolume();
        wake();
        return true;
    }

    /** 拖动和点按都在这一个监听里处理：左半 −，右半 + */
    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                moved = false;
                repeated = false;
                dir = (e.getX() < v.getWidth() / 2f) ? -1 : +1;
                android.util.Log.d(TAG, "down x=" + e.getX() + "/" + v.getWidth()
                        + " dir=" + dir + " expanded=" + expanded);
                wake();
                handler.removeCallbacks(repeatTask);
                if (expanded) {
                    handler.postDelayed(repeatTask, HOLD_DELAY_MS);
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (e.getRawX() - downRawX);
                int dy = (int) (e.getRawY() - downRawY);
                if (!moved && (Math.abs(dx) > DRAG_SLOP || Math.abs(dy) > DRAG_SLOP)) {
                    moved = true;
                    handler.removeCallbacks(repeatTask);
                }
                if (moved) {
                    lp.x = startX + dx;
                    lp.y = startY + dy;
                    clampPos();
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeatTask);
                android.util.Log.d(TAG, "up moved=" + moved + " repeated=" + repeated
                        + " expanded=" + expanded + " dir=" + dir);
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !moved) {
                    if (expanded) {
                        if (!repeated) step(dir);
                    } else {
                        // 圆形点一下：展开成药丸，并且立刻调一格（点左 −1 / 点右 +1）
                        setMode(true);
                        step(dir);
                    }
                }
                if (moved) savePos();
                scheduleIdle();
                return true;

            default:
                return false;
        }
    }

    // ==================== 圆形 ⇄ 药丸 ====================

    /** 切换形态，切换时保持视觉中心不动（药丸以圆心为中心展开） */
    private void setMode(boolean wantExpanded) {
        if (expanded == wantExpanded || root == null) return;
        expanded = wantExpanded;

        if (wantExpanded) {
            tvCircle.setVisibility(View.GONE);
            pill.setVisibility(View.VISIBLE);
            root.post(() -> {
                try {
                    pillW = pill.getWidth();
                    pillH = pill.getHeight();
                    if (pillW > 0) {
                        lp.x -= (pillW - circlePx) / 2;
                        lp.y -= (pillH - circlePx) / 2;
                        clampPos();
                        wm.updateViewLayout(root, lp);
                    }
                } catch (Exception ignored) {}
            });
        } else {
            if (pillW > 0) {
                lp.x += (pillW - circlePx) / 2;
                lp.y += (pillH - circlePx) / 2;
            }
            clampPos();
            pill.setVisibility(View.GONE);
            tvCircle.setVisibility(View.VISIBLE);
            try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
            savePos();   // 记住的是圆形位置，这样重开不漂移
        }
        showVolume();
    }

    /** 别让悬浮窗跑出屏幕 */
    private void clampPos() {
        int w = (expanded && pillW > 0) ? pillW : circlePx;
        int h = (expanded && pillH > 0) ? pillH : circlePx;
        lp.x = Math.max(0, Math.min(lp.x, screenW - w));
        lp.y = Math.max(0, Math.min(lp.y, screenH - h));
    }

    // ==================== 音量 ====================

    private void step(int d) {
        VolumeHelper.step(this, d, false);   // 悬浮窗里不弹系统音量条，数字自己显示
        showVolume();
    }

    private void showVolume() {
        String v = String.valueOf(VolumeHelper.getVolume(this));
        if (tvVol != null) tvVol.setText(v);
        if (tvCircle != null) tvCircle.setText(v);
    }

    /**
     * 换了「调哪一路」（媒体/铃声）：悬浮窗没有自己的选择，永远跟着全局走，
     * 这里只负责立刻把数字刷成新那一路的，并醒一下让用户看得见。
     */
    private void onStreamChanged() {
        showVolume();
        wake();
    }

    /** 任何来源的音量变化都会走到这里：数字实时刷新，顺带把常驻通知也刷了 */
    private void onVolumeChanged() {
        showVolume();
        try { VolumeNotifier.post(this); } catch (Exception ignored) {}
    }

    private void registerVolumeWatcher() {
        volumeWatcher = new VolumeWatcher(this);
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(Streams.ACTION_CHANGED);   // 悬浮窗不自选，跟着全局那一路走
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeWatcher, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volumeWatcher, f);
        }
    }

    // ==================== 闲置/透明 ====================

    /** 一碰就恢复不透明，并重新开始计闲置时间 */
    private void wake() {
        if (root == null) return;
        root.animate().alpha(1f).setDuration(150).start();
        showVolume();
        scheduleIdle();
    }

    private void scheduleIdle() {
        handler.removeCallbacks(fadeTask);
        handler.postDelayed(fadeTask, IDLE_MS);
    }

    private void fadeToIdle() {
        setMode(false);   // 闲置先收回成圆形
        if (root != null) root.animate().alpha(IDLE_ALPHA).setDuration(400).start();
    }

    private void savePos() {
        if (lp == null) return;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_X, lp.x)
                .putInt(KEY_Y, lp.y)
                .apply();
    }
}