/\*

- Copyright (C) 2008 The Android Open Source Project
-
- Licensed under the Apache License, Version 2.0 (the "License");
- you may not use this file except in compliance with the License.
- You may obtain a copy of the License at
-
-      http://www.apache.org/licenses/LICENSE-2.0
-
- Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
  \*/

// 声明包路径，属于 Android 系统服务核心包
package com.android.server;

// 静态导入 Watchdog 内部类的静态方法，用于创建带默认/自定义超时的 Checker
import static com.android.server.Watchdog.HandlerCheckerAndTimeout.withCustomTimeout;
import static com.android.server.Watchdog.HandlerCheckerAndTimeout.withDefaultTimeout;

// 导入 Android 系统相关注解、服务、工具类等依赖
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.IActivityController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hidl.manager.V1_0.IServiceManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceDebugInfo;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.sysprop.WatchdogProperties;
import android.util.Dumpable;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;

// 导入 Android 内部系统工具类
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.ZygoteConnectionConstants;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.StackTracesDumpHelper;
import com.android.server.am.TraceErrorLogger;
import com.android.server.criticalevents.CriticalEventLog;
import com.android.server.wm.SurfaceAnimationThread;

// 导入 Java 基础工具类
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/\*\*

- 看门狗服务核心类，实现 Dumpable 接口支持调试信息输出
- 功能：每分钟调用注册的监视器（Monitor），若监视器未正常响应则终止系统进程，避免系统卡死
  \*/
  public class Watchdog implements Dumpable {
  // 日志标签，用于打印 Watchdog 相关日志
  static final String TAG = "Watchdog";

      /** 调试标志位，默认关闭 */
      public static final boolean DEBUG = false;

      // 调试模式开关，开启后使用调试版默认值
      private static final boolean DB = false;

      // 注意1：不要将此值设置低于30秒，否则需同步调整ZygoteConnection的调用超时时间，避免误触发看门狗
      // 注意2：调试模式下的值已低于ZygoteConnection的等待时间，可能导致第三方应用异常、CTS测试失败
      // 默认超时时间：调试模式10秒，正式模式60秒
      private static final long DEFAULT_TIMEOUT = DB ? 10 * 1000 : 60 * 1000;

      // 预看门狗超时比例（值为4表示预超时时间是完整超时的1/4）
      // 预看门狗仅日志告警，不终止进程；完整超时会触发进程终止
      private static final int PRE_WATCHDOG_TIMEOUT_RATIO = 4;

      // 监视器状态枚举（按延迟程度递增排序）
      static final int COMPLETED = 0; // 已完成响应
      static final int WAITING = 1; // 等待中（未超时）
      static final int WAITED_UNTIL_PRE_WATCHDOG = 2; // 已达预超时时间
      static final int OVERDUE = 3; // 已超时

      // 超时历史记录文件路径，用于跟踪崩溃循环
      private static final String TIMEOUT_HISTORY_FILE = "/data/system/watchdog-timeout-history.txt";
      // 系统属性：崩溃循环计数（记录指定时间内的崩溃次数）
      private static final String PROP_FATAL_LOOP_COUNT = "framework_watchdog.fatal_count";
      // 系统属性：崩溃循环时间窗口（单位：秒）
      private static final String PROP_FATAL_LOOP_WINDOWS_SECS = "framework_watchdog.fatal_window.second";

      // 需要抓取堆栈信息的原生进程列表（系统核心服务进程）
      public static final String[] NATIVE_STACKS_OF_INTEREST = new String[] {
          "/system/bin/audioserver", // 音频服务进程
          "/system/bin/cameraserver", // 相机服务进程
          "/system/bin/drmserver", // DRM数字版权管理服务进程
          "/system/bin/keystore2", // 密钥存储服务进程
          "/system/bin/mediadrmserver", // 媒体DRM服务进程
          "/system/bin/mediaserver", // 媒体服务进程
          "/system/bin/netd", // 网络守护进程
          "/system/bin/sdcard", // SD卡管理进程
          "/system/bin/servicemanager", // 服务管理器进程
          "/system/bin/surfaceflinger", // Surface合成进程
          "/system/bin/vold", // 存储卷管理进程
          "media.extractor", // 媒体提取器进程（实际路径：system/bin/mediaextractor）
          "media.metrics", // 媒体指标收集进程（实际路径：system/bin/mediametrics）
          "media.codec", // 硬件媒体编解码服务进程
          "media.swcodec", // 软件媒体编解码进程
          "media.transcoding", // 媒体转码服务进程
          "com.android.bluetooth", // 蓝牙服务进程
          "/apex/com.android.art/bin/artd", // ART虚拟机守护进程
          "/apex/com.android.os.statsd/bin/statsd", // 统计数据收集进程
      };

      // 需要监控的HAL（硬件抽象层）接口列表
      public static final List<String> HAL_INTERFACES_OF_INTEREST = Arrays.asList(
              "android.hardware.audio@4.0::IDevicesFactory", // 音频设备工厂接口
              "android.hardware.audio@5.0::IDevicesFactory", // 音频设备工厂接口（v5.0）
              "android.hardware.audio@6.0::IDevicesFactory", // 音频设备工厂接口（v6.0）
              "android.hardware.audio@7.0::IDevicesFactory", // 音频设备工厂接口（v7.0）
              "android.hardware.biometrics.face@1.0::IBiometricsFace", // 面部生物识别接口
              "android.hardware.biometrics.fingerprint@2.1::IBiometricsFingerprint", // 指纹生物识别接口
              "android.hardware.bluetooth@1.0::IBluetoothHci", // 蓝牙HCI接口
              "android.hardware.camera.provider@2.4::ICameraProvider", // 相机提供者接口
              "android.hardware.gnss@1.0::IGnss", // GNSS定位接口
              "android.hardware.graphics.allocator@2.0::IAllocator", // 图形内存分配器接口
              "android.hardware.graphics.allocator@4.0::IAllocator", // 图形内存分配器接口（v4.0）
              "android.hardware.graphics.composer@2.1::IComposer", // 图形合成器接口
              "android.hardware.health@2.0::IHealth", // 设备健康状态接口
              "android.hardware.light@2.0::ILight", // 灯光控制接口
              "android.hardware.media.c2@1.0::IComponentStore", // 媒体组件存储接口
              "android.hardware.media.omx@1.0::IOmx", // OMX媒体编解码接口
              "android.hardware.media.omx@1.0::IOmxStore", // OMX组件存储接口
              "android.hardware.neuralnetworks@1.0::IDevice", // 神经网络设备接口
              "android.hardware.power@1.0::IPower", // 电源管理接口
              "android.hardware.power.stats@1.0::IPowerStats", // 电源统计接口
              "android.hardware.sensors@1.0::ISensors", // 传感器接口（v1.0）
              "android.hardware.sensors@2.0::ISensors", // 传感器接口（v2.0）
              "android.hardware.sensors@2.1::ISensors", // 传感器接口（v2.1）
              "android.hardware.vr@1.0::IVr", // VR模式接口
              "android.system.suspend@1.0::ISystemSuspend" // 系统休眠接口
      );

      // 需要监控的AIDL接口前缀列表（匹配服务名称前缀）
      public static final String[] AIDL_INTERFACE_PREFIXES_OF_INTEREST = new String[] {
              "android.hardware.audio.core.IModule/", // 音频核心模块接口
              "android.hardware.audio.core.IConfig/", // 音频核心配置接口
              "android.hardware.audio.effect.IFactory/", // 音频效果工厂接口
              "android.hardware.biometrics.face.IFace/", // 面部生物识别接口
              "android.hardware.biometrics.fingerprint.IFingerprint/", // 指纹生物识别接口
              "android.hardware.bluetooth.IBluetoothHci/", // 蓝牙HCI接口
              "android.hardware.camera.provider.ICameraProvider/", // 相机提供者接口
              "android.hardware.drm.IDrmFactory/", // DRM工厂接口
              "android.hardware.gnss.IGnss/", // GNSS定位接口
              "android.hardware.graphics.allocator.IAllocator/", // 图形内存分配器接口
              "android.hardware.graphics.composer3.IComposer/", // 图形合成器接口（v3）
              "android.hardware.health.IHealth/", // 设备健康状态接口
              "android.hardware.input.processor.IInputProcessor/", // 输入处理器接口
              "android.hardware.light.ILights/", // 灯光控制接口
              "android.hardware.neuralnetworks.IDevice/", // 神经网络设备接口
              "android.hardware.power.IPower/", // 电源管理接口
              "android.hardware.power.stats.IPowerStats/", // 电源统计接口
              "android.hardware.sensors.ISensors/", // 传感器接口
              "android.hardware.vibrator.IVibrator/", // 振动器接口
              "android.hardware.vibrator.IVibratorManager/", // 振动器管理接口
              "android.hardware.wifi.hostapd.IHostapd/", // WiFi热点管理接口
              "android.hardware.wifi.IWifi/", // WiFi核心接口
              "android.hardware.wifi.supplicant.ISupplicant/", // WiFi认证接口
              "android.system.suspend.ISystemSuspend/", // 系统休眠接口
      };

      // Watchdog单例实例（全局唯一）
      private static Watchdog sWatchdog;

      // 看门狗核心工作线程（循环监控各线程/监视器状态）
      private final Thread mThread;

      // 全局锁对象，用于同步临界资源访问
      private final Object mLock = new Object();

      /* 存储HandlerChecker及对应超时配置的列表（监控各核心线程） */
      private final ArrayList<HandlerCheckerAndTimeout> mHandlerCheckers = new ArrayList<>();
      // 专门用于监控Monitor的Checker（运行在独立线程）
      private final HandlerChecker mMonitorChecker;
      // ActivityManagerService实例引用（用于日志上报、系统重启）
      private ActivityManagerService mActivity;
      // 活动控制器（用于通知系统无响应状态）
      private IActivityController mController;
      // 是否允许重启系统进程（默认允许）
      private boolean mAllowRestart = true;
      // 看门狗超时时间（初始为默认值，后续可通过系统设置更新）
      private volatile long mWatchdogTimeoutMillis = DEFAULT_TIMEOUT;
      // 需要抓取堆栈的Java进程PID列表
      private final List<Integer> mInterestingJavaPids = new ArrayList<>();
      // 跟踪错误日志记录器（用于生成错误ID、关联日志）
      private final TraceErrorLogger mTraceErrorLogger;

      /**
       * 内部静态类：封装HandlerChecker与自定义超时时间的关联关系
       * 支持两种超时模式：默认超时（使用全局配置）、自定义超时（单独指定）
       */
      static final class HandlerCheckerAndTimeout {
          // 被封装的HandlerChecker实例（实际执行监控逻辑）
          private final HandlerChecker mHandler;
          // 自定义超时时间（Optional.empty()表示使用默认超时）
          private final Optional<Long> mCustomTimeoutMillis;

          // 私有构造方法：通过静态工厂方法创建实例
          private HandlerCheckerAndTimeout(HandlerChecker checker, Optional<Long> timeoutMillis) {
              this.mHandler = checker;
              this.mCustomTimeoutMillis = timeoutMillis;
          }

          // 获取封装的HandlerChecker实例
          HandlerChecker checker() {
              return mHandler;
          }

          /** 返回自定义超时时间（Optional类型，为空则使用默认） */
          Optional<Long> customTimeoutMillis() {
              return mCustomTimeoutMillis;
          }

          /**
           * 静态工厂方法：创建使用默认超时的HandlerChecker包装类
           * 超时时间使用系统全局配置（可通过设置修改）
           */
          static HandlerCheckerAndTimeout withDefaultTimeout(HandlerChecker checker) {
              return new HandlerCheckerAndTimeout(checker, Optional.empty());
          }

          /**
           * 静态工厂方法：创建使用自定义超时的HandlerChecker包装类
           * 超时时间优先级：自定义 > 全局默认
           */
          static HandlerCheckerAndTimeout withCustomTimeout(
                  HandlerChecker checker, long timeoutMillis) {
              return new HandlerCheckerAndTimeout(checker, Optional.of(timeoutMillis));
          }
      }

      /**
       * 内部公共类：线程状态检查器，实现Runnable接口
       * 功能：监控指定Handler对应的线程状态，执行注册的Monitor逻辑，判断是否超时
       */
      public static class HandlerChecker implements Runnable {
          // 待监控的Handler（关联目标线程的Looper）
          private final Handler mHandler;
          // 线程名称（用于日志打印和状态描述）
          private final String mName;
          // 已注册的Monitor列表（需定期执行monitor()方法）
          private final ArrayList<Monitor> mMonitors = new ArrayList<Monitor>();
          // 待添加的Monitor队列（避免并发修改mMonitors）
          private final ArrayList<Monitor> mMonitorQueue = new ArrayList<Monitor>();
          // 当前检查的最大超时时间（毫秒）
          private long mWaitMaxMillis;
          // 标记本次检查是否完成（true：已完成，false：执行中/超时）
          private boolean mCompleted;
          // 当前正在执行的Monitor实例（用于超时后定位阻塞点）
          private Monitor mCurrentMonitor;
          // 本次检查的开始时间（毫秒，系统运行时间）
          private long mStartTimeMillis;
          // 暂停计数（多次暂停需对应多次恢复）
          private int mPauseCount;
          // 暂停结束时间（毫秒，系统运行时间，用于定时恢复检查）
          private long mPauseEndTimeMillis;
          // 时钟工具（用于获取当前时间，支持调试替换）
          private Clock mClock;
          // 同步锁对象（与Watchdog全局锁一致，保证线程安全）
          private Object mLock;

          // 构造方法：初始化HandlerChecker核心参数（指定时钟工具）
          HandlerChecker(Handler handler, String name, Object lock, Clock clock) {
              mHandler = handler;
              mName = name;
              mLock = lock;
              mCompleted = true; // 初始状态为已完成
              mClock = clock;
          }

          // 构造方法：使用默认系统时钟（SystemClock.uptimeClock()）
          HandlerChecker(Handler handler, String name, Object lock) {
              this(handler, name, lock, SystemClock.uptimeClock());
          }

          // 线程安全添加Monitor：先加入队列，后续统一同步到mMonitors
          void addMonitorLocked(Monitor monitor) {
              // 避免在Handler执行检查时修改mMonitors，先加入队列
              mMonitorQueue.add(monitor);
          }

          /**
           * 线程安全调度检查任务：将Runnable提交到目标Handler的消息队列
           * @param handlerCheckerTimeoutMillis 本次检查的超时时间（毫秒）
           */
          public void scheduleCheckLocked(long handlerCheckerTimeoutMillis) {
              mWaitMaxMillis = handlerCheckerTimeoutMillis;

              // 若当前无检查任务且有待添加的Monitor，同步队列到Monitor列表
              if (mCompleted && !mMonitorQueue.isEmpty()) {
                  mMonitors.addAll(mMonitorQueue);
                  mMonitorQueue.clear();
              }

              // 获取当前系统运行时间（不含休眠时间，避免休眠导致误判）
              long nowMillis = mClock.millis();
              // 判断是否处于暂停状态（暂停计数>0 或 暂停未到期）
              boolean isPaused = mPauseCount > 0 || mPauseEndTimeMillis > nowMillis;
              // 无需调度的场景：无Monitor且线程正在轮询 或 处于暂停状态
              if ((mMonitors.size() == 0 && isHandlerPolling()) || isPaused) {
                  mCompleted = true;
                  return;
              }
              // 已有检查任务在执行，无需重复调度
              if (!mCompleted) {
                  return;
              }

              // 重置检查状态，提交任务到目标线程的消息队列头部（优先执行）
              mCompleted = false;
              mCurrentMonitor = null;
              mStartTimeMillis = nowMillis;
              mPauseEndTimeMillis = 0;
              mHandler.postAtFrontOfQueue(this);
          }

          // 判断目标线程的消息队列是否正在轮询（无阻塞则返回true）
          boolean isHandlerPolling() {
              return mHandler.getLooper().getQueue().isPolling();
          }

          /**
           * 线程安全获取当前检查完成状态
           * @return 状态码（COMPLETED/WAITING/WAITED_UNTIL_PRE_WATCHDOG/OVERDUE）
           */
          public int getCompletionStateLocked() {
              if (mCompleted) {
                  return COMPLETED;
              } else {
                  // 计算已等待时间（当前时间 - 开始时间）
                  long latency = mClock.millis() - mStartTimeMillis;
                  if (latency < mWaitMaxMillis / PRE_WATCHDOG_TIMEOUT_RATIO) {
                      return WAITING; // 未达预超时
                  } else if (latency < mWaitMaxMillis) {
                      return WAITED_UNTIL_PRE_WATCHDOG; // 已达预超时，未达完整超时
                  }
              }
              return OVERDUE; // 已超时
          }

          // 获取当前监控的线程实例
          public Thread getThread() {
              return mHandler.getLooper().getThread();
          }

          // 获取监控的线程名称
          public String getName() {
              return mName;
          }

          // 线程安全描述阻塞状态（用于日志输出，定位阻塞线程/Monitor）
          String describeBlockedStateLocked() {
              final String prefix;
              if (mCurrentMonitor == null) {
                  prefix = "Blocked in handler"; // 阻塞在Handler线程本身
              } else {
                  prefix = "Blocked in monitor " + mCurrentMonitor.getClass().getName(); // 阻塞在指定Monitor
              }
              // 计算阻塞时间（秒）
              long latencySeconds = (mClock.millis() - mStartTimeMillis) / 1000;
              return prefix + " on " + mName + " (" + getThread().getName() + ")"
                  + " for " + latencySeconds + "s";
          }

          // Runnable核心逻辑：执行所有注册的Monitor的monitor()方法
          @Override
          public void run() {
              // 执行期间mMonitors不会变化（仅当mCompleted为true时才同步队列）
              final int size = mMonitors.size();
              for (int i = 0 ; i < size ; i++) {
                  synchronized (mLock) {
                      mCurrentMonitor = mMonitors.get(i); // 标记当前执行的Monitor
                  }
                  mCurrentMonitor.monitor(); // 执行Monitor的监控逻辑（需快速返回，避免阻塞）
              }

              // 所有Monitor执行完成，重置状态
              synchronized (mLock) {
                  mCompleted = true;
                  mCurrentMonitor = null;
              }
          }

          /**
           * 定时暂停检查：指定时间后自动恢复
           * @param pauseMillis 暂停时长（毫秒）
           * @param reason 暂停原因（用于日志）
           */
          public void pauseForLocked(int pauseMillis, String reason) {
              mPauseEndTimeMillis = mClock.millis() + pauseMillis;
              // 标记为已完成，避免预超时后Watchdog误判
              mCompleted = true;
              Slog.i(TAG, "Pausing of HandlerChecker: " + mName + " for reason: "
                      + reason + ". Pause end time: " + mPauseEndTimeMillis);
          }

          /** 暂停检查（需手动调用resumeLocked恢复） */
          public void pauseLocked(String reason) {
              mPauseCount++; // 暂停计数+1
              mCompleted = true; // 标记为已完成，避免误判
              Slog.i(TAG, "Pausing HandlerChecker: " + mName + " for reason: "
                      + reason + ". Pause count: " + mPauseCount);
          }

          /** 恢复检查（与pauseLocked成对调用） */
          public void resumeLocked(String reason) {
              if (mPauseCount > 0) {
                  mPauseCount--; // 暂停计数-1
                  Slog.i(TAG, "Resuming HandlerChecker: " + mName + " for reason: "
                          + reason + ". Pause count: " + mPauseCount);
              } else {
                  Slog.wtf(TAG, "Already resumed HandlerChecker: " + mName); // 重复恢复，打印严重日志
              }
          }

          // 重写toString：返回当前Checker监控的线程名称
          @Override
          public String toString() {
              return "CheckerHandler for " + mName;
          }
      }

      /**
       * 内部广播接收器：监听系统重启广播（ACTION_REBOOT）
       * 收到广播后触发系统重启（仅处理nowait=1的广播）
       */
      final class RebootRequestReceiver extends BroadcastReceiver {
          @Override
          public void onReceive(Context c, Intent intent) {
              // 若广播携带nowait=1参数，立即重启系统
              if (intent.getIntExtra("nowait", 0) != 0) {
                  rebootSystem("Received ACTION_REBOOT broadcast");
                  return;
              }
              // 不支持的广播类型，打印警告日志
              Slog.w(TAG, "Unsupported ACTION_REBOOT broadcast: " + intent);
          }
      }

      /**
       * 内部静态类：Binder线程监视器，实现Monitor接口
       * 功能：检查Binder线程是否可用，确保IPC通信正常
       * 原理：调用Binder.blockUntilThreadAvailable()，阻塞直到有空闲Binder线程
       */
      private static final class BinderThreadMonitor implements Watchdog.Monitor {
          @Override
          public void monitor() {
              Binder.blockUntilThreadAvailable();
          }
      }

      /**
       * 监控器接口：需被监控的组件需实现此接口
       * monitor()方法需快速执行（避免阻塞），用于检查组件状态是否正常
       */
      public interface Monitor {
          void monitor();
      }

      /**
       * 获取Watchdog单例实例（懒加载，首次调用时创建）
       * @return 全局唯一的Watchdog实例
       */
      public static Watchdog getInstance() {
          if (sWatchdog == null) {
              sWatchdog = new Watchdog();
          }
          return sWatchdog;
      }

      // 私有构造方法：初始化Watchdog核心资源（单例模式禁止外部创建）
      private Watchdog() {
          // 创建看门狗工作线程，线程名称为"watchdog"，执行run()方法
          mThread = new Thread(this::run, "watchdog");

          // 初始化监控核心线程的HandlerChecker
          // 1. 创建独立的Monitor监控线程（优先级默认，允许IO操作）
          ServiceThread t = new ServiceThread("watchdog.monitor",
                  android.os.Process.THREAD_PRIORITY_DEFAULT, true /*allowIo*/);
          t.start();
          // 创建MonitorChecker，关联Monitor线程的Handler
          mMonitorChecker = new HandlerChecker(new Handler(t.getLooper()), "monitor thread", mLock);
          // 将MonitorChecker添加到列表（使用默认超时）
          mHandlerCheckers.add(withDefaultTimeout(mMonitorChecker));

          // 2. 添加前台线程监控（FgThread）
          mHandlerCheckers.add(
                  withDefaultTimeout(
                          new HandlerChecker(FgThread.getHandler(), "foreground thread", mLock)));
          // 3. 添加主线程监控（Looper.getMainLooper()，系统核心线程）
          mHandlerCheckers.add(
                  withDefaultTimeout(
                          new HandlerChecker(
                                  new Handler(Looper.getMainLooper()), "main thread", mLock)));
          // 4. 添加共享UI线程监控（UiThread）
          mHandlerCheckers.add(
                  withDefaultTimeout(new HandlerChecker(UiThread.getHandler(), "ui thread", mLock)));
          // 5. 添加IO线程监控（IoThread）
          mHandlerCheckers.add(
                  withDefaultTimeout(new HandlerChecker(IoThread.getHandler(), "i/o thread", mLock)));
          // 6. 添加显示线程监控（DisplayThread）
          mHandlerCheckers.add(
                  withDefaultTimeout(
                          new HandlerChecker(DisplayThread.getHandler(), "display thread", mLock)));
          // 7. 添加动画线程监控（AnimationThread）
          mHandlerCheckers.add(
                  withDefaultTimeout(
                          new HandlerChecker(
                                  AnimationThread.getHandler(), "animation thread", mLock)));
          // 8. 添加Surface动画线程监控（SurfaceAnimationThread）
          mHandlerCheckers.add(
                  withDefaultTimeout(
                          new HandlerChecker(
                                  SurfaceAnimationThread.getHandler(),
                                  "surface animation thread",
                                  mLock)));
          // 初始化Binder线程监视器（添加到MonitorChecker）
          addMonitor(new BinderThreadMonitor());

          // 添加当前进程（system_server）到需监控的Java进程列表
          mInterestingJavaPids.add(Process.myPid());

          // 断言：正式版/用户调试版中，默认超时需大于Zygote的包装进程超时（避免误杀）
          assert DB || Build.IS_USERDEBUG ||
                  DEFAULT_TIMEOUT > ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS;

          // 初始化错误跟踪日志器
          mTraceErrorLogger = new TraceErrorLogger();
      }

      /**
       * 启动看门狗服务（由SystemServer调用）
       * 功能：启动看门狗工作线程，开始监控逻辑
       */
      public void start() {
          mThread.start();
      }

      /**
       * 初始化Watchdog（由SystemServer调用）
       * 功能：关联ActivityManagerService，注册重启广播接收器
       * @param context 系统上下文
       * @param activity ActivityManagerService实例
       */
      public void init(Context context, ActivityManagerService activity) {
          mActivity = activity;
          // 注册广播接收器：监听ACTION_REBOOT，需要REBOOT权限
          context.registerReceiver(new RebootRequestReceiver(),
                  new IntentFilter(Intent.ACTION_REBOOT),
                  android.Manifest.permission.REBOOT, null);
      }

      /**
       * 内部静态类：系统设置观察者（ContentObserver）
       * 功能：监听全局设置中看门狗超时时间的变化（Settings.Global.WATCHDOG_TIMEOUT_MILLIS）
       */
      private static class SettingsObserver extends ContentObserver {
          // 监控的设置项URI（看门狗超时时间）
          private final Uri mUri = Settings.Global.getUriFor(Settings.Global.WATCHDOG_TIMEOUT_MILLIS);
          // 系统上下文
          private final Context mContext;
          // Watchdog实例引用
          private final Watchdog mWatchdog;

          // 构造方法：初始化观察者，立即触发一次设置读取
          SettingsObserver(Context context, Watchdog watchdog) {
              super(BackgroundThread.getHandler());
              mContext = context;
              mWatchdog = watchdog;
              onChange(); // 首次读取当前设置值
          }

          // 当设置项变化时触发（带URI和用户ID参数）
          @Override
          public void onChange(boolean selfChange, Uri uri, int userId) {
              if (mUri.equals(uri)) {
                  onChange(); // 仅处理看门狗超时时间的变化
              }
          }

          // 读取设置值并更新看门狗超时时间
          public void onChange() {
              try {
                  // 从系统设置中读取超时时间（默认使用DEFAULT_TIMEOUT）
                  mWatchdog.updateWatchdogTimeout(Settings.Global.getLong(
                          mContext.getContentResolver(),
                          Settings.Global.WATCHDOG_TIMEOUT_MILLIS, DEFAULT_TIMEOUT));
              } catch (RuntimeException e) {
                  // 读取设置失败，打印错误日志
                  Slog.e(TAG, "Exception while reading settings " + e.getMessage(), e);
              }
          }
      }

      /**
       * 注册系统设置观察者（由SystemServer调用，需在设置服务初始化后）
       * 功能：监听看门狗超时时间的设置变化
       * @param context 系统上下文
       */
      public void registerSettingsObserver(Context context) {
          context.getContentResolver().registerContentObserver(
                  Settings.Global.getUriFor(Settings.Global.WATCHDOG_TIMEOUT_MILLIS),
                  false, // 不监听子URI
                  new SettingsObserver(context, this),
                  UserHandle.USER_SYSTEM); // 仅监听系统用户的设置
      }

      /**
       * 更新看门狗超时时间（线程安全）
       * @param timeoutMillis 新的超时时间（毫秒）
       */
      void updateWatchdogTimeout(long timeoutMillis) {
          // 正式版中，超时时间不能小于等于Zygote的包装进程超时（避免误杀）
          if (!DB && !Build.IS_USERDEBUG
                  && timeoutMillis <= ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS) {
              timeoutMillis = ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS + 1;
          }
          // 更新全局超时时间（volatile修饰，保证多线程可见性）
          mWatchdogTimeoutMillis = timeoutMillis;
          Slog.i(TAG, "Watchdog timeout updated to " + mWatchdogTimeoutMillis + " millis");
      }

      /**
       * 判断Java进程是否为需要监控的进程
       * @param processName 进程名称
       * @return true：需要监控（媒体存储、电话进程），false：不需要
       */
      private static boolean isInterestingJavaProcess(String processName) {
          return processName.equals(StorageManagerService.sMediaStoreAuthorityProcessName)
                  || processName.equals("com.android.phone");
      }

      /**
       * 通知看门狗Java进程启动（由ActivityManagerService调用）
       * 功能：将需要监控的Java进程PID添加到列表，超时后抓取堆栈
       * @param processName 进程名称
       * @param pid 进程PID
       */
      public void processStarted(String processName, int pid) {
          if (isInterestingJavaProcess(processName)) {
              Slog.i(TAG, "Interesting Java process " + processName + " started. Pid " + pid);
              synchronized (mLock) {
                  mInterestingJavaPids.add(pid);
              }
          }
      }

      /**
       * 通知看门狗Java进程终止（由ActivityManagerService调用）
       * 功能：将终止的进程PID从监控列表移除
       * @param processName 进程名称
       * @param pid 进程PID
       */
      public void processDied(String processName, int pid) {
          if (isInterestingJavaProcess(processName)) {
              Slog.i(TAG, "Interesting Java process " + processName + " died. Pid " + pid);
              synchronized (mLock) {
                  mInterestingJavaPids.remove(Integer.valueOf(pid));
              }
          }
      }

      /**
       * 设置活动控制器（用于通知系统无响应状态）
       * @param controller IActivityController实例
       */
      public void setActivityController(IActivityController controller) {
          synchronized (mLock) {
              mController = controller;
          }
      }

      /**
       * 设置是否允许重启系统进程
       * @param allowRestart true：允许（默认），false：禁止
       */
      public void setAllowRestart(boolean allowRestart) {
          synchronized (mLock) {
              mAllowRestart = allowRestart;
          }
      }

      /**
       * 添加Monitor到监控列表（线程安全）
       * @param monitor 实现Monitor接口的监控器
       */
      public void addMonitor(Monitor monitor) {
          synchronized (mLock) {
              mMonitorChecker.addMonitorLocked(monitor);
          }
      }

      /**
       * 添加线程到监控列表（使用默认超时，线程安全）
       * @param thread 待监控的Handler（关联目标线程Looper）
       */
      public void addThread(Handler thread) {
          synchronized (mLock) {
              final String name = thread.getLooper().getThread().getName();
              mHandlerCheckers.add(withDefaultTimeout(new HandlerChecker(thread, name, mLock)));
          }
      }

      /**
       * 添加线程到监控列表（使用自定义超时，线程安全）
       * @param thread 待监控的Handler（关联目标线程Looper）
       * @param timeoutMillis 自定义超时时间（毫秒）
       */
      public void addThread(Handler thread, long timeoutMillis) {
          synchronized (mLock) {
              final String name = thread.getLooper().getThread().getName();
              mHandlerCheckers.add(
                      withCustomTimeout(new HandlerChecker(thread, name, mLock), timeoutMillis));
          }
      }

      /**
       * 暂停当前线程的监控（定时恢复，线程安全）
       * 场景：当前线程执行耗时操作，避免看门狗误判超时
       * @param pauseMillis 暂停时长（毫秒）
       * @param reason 暂停原因（用于日志）
       */
      public void pauseWatchingCurrentThreadFor(int pauseMillis, String reason) {
          synchronized (mLock) {
              // 遍历所有Checker，找到当前线程对应的Checker并暂停
              for (HandlerCheckerAndTimeout hc : mHandlerCheckers) {
                  HandlerChecker checker = hc.checker();
                  if (Thread.currentThread().equals(checker.getThread())) {
                      checker.pauseForLocked(pauseMillis, reason);
                  }
              }
          }
      }

      /**
       * 暂停Monitor线程的监控（定时恢复，线程安全）
       * 场景：Monitor执行耗时操作，避免看门狗误判超时
       * @param pauseMillis 暂停时长（毫秒）
       * @param reason 暂停原因（用于日志）
       */
      public void pauseWatchingMonitorsFor(int pauseMillis, String reason) {
          mMonitorChecker.pauseForLocked(pauseMillis, reason);
      }

      /**
       * 暂停当前线程的监控（手动恢复，线程安全）
       * 注意：需与resumeWatchingCurrentThread成对调用，支持嵌套暂停
       * @param reason 暂停原因（用于日志）
       */
      public void pauseWatchingCurrentThread(String reason) {
          synchronized (mLock) {
              // 遍历所有Checker，找到当前线程对应的Checker并暂停
              for (HandlerCheckerAndTimeout hc : mHandlerCheckers) {
                  HandlerChecker checker = hc.checker();
                  if (Thread.currentThread().equals(checker.getThread())) {
                      checker.pauseLocked(reason);
                  }
              }
          }
      }

      /**
       * 恢复当前线程的监控（与pauseWatchingCurrentThread成对调用，线程安全）
       * @param reason 恢复原因（用于日志）
       */
      public void resumeWatchingCurrentThread(String reason) {
          synchronized (mLock) {
              // 遍历所有Checker，找到当前线程对应的Checker并恢复
              for (HandlerCheckerAndTimeout hc : mHandlerCheckers) {
                  HandlerChecker checker = hc.checker();
                  if (Thread.currentThread().equals(checker.getThread())) {
                      checker.resumeLocked(reason);
                  }
              }
          }
      }

      /**
       * 重启系统（线程安全）
       * @param reason 重启原因（用于日志）
       */
      void rebootSystem(String reason) {
          Slog.i(TAG, "Rebooting system because: " + reason);
          // 获取电源管理服务（IPowerManager）
          IPowerManager pms = (IPowerManager)ServiceManager.getService(Context.POWER_SERVICE);
          try {
              // 调用电源管理服务重启系统（false：不等待，reason：重启原因，false：不是用户请求）
              pms.reboot(false, reason, false);
          } catch (RemoteException ex) {
              // 远程调用失败（服务不可用），忽略异常
          }
      }

      /**
       * 线程安全评估所有Checker的完成状态（取最严重的状态）
       * @return 状态码（COMPLETED/WAITING/WAITED_UNTIL_PRE_WATCHDOG/OVERDUE）
       */
      private int evaluateCheckerCompletionLocked() {
          int state = COMPLETED;
          // 遍历所有Checker，取状态最严重的值（OVERDUE > WAITED_UNTIL_PRE_WATCHDOG > WAITING > COMPLETED）
          for (int i=0; i<mHandlerCheckers.size(); i++) {
              HandlerChecker hc = mHandlerCheckers.get(i).checker();
              state = Math.max(state, hc.getCompletionStateLocked());
          }
          return state;
      }

      /**
       * 线程安全获取指定状态的Checker列表
       * @param completionState 目标状态（如OVERDUE）
       * @return 符合状态的Checker列表
       */
      private ArrayList<HandlerChecker> getCheckersWithStateLocked(int completionState) {
          ArrayList<HandlerChecker> checkers = new ArrayList<HandlerChecker>();
          for (int i=0; i<mHandlerCheckers.size(); i++) {
              HandlerChecker hc = mHandlerCheckers.get(i).checker();
              if (hc.getCompletionStateLocked() == completionState) {
                  checkers.add(hc);
              }
          }
          return checkers;
      }

      /**
       * 线程安全描述Checker阻塞状态（拼接所有阻塞Checker的信息）
       * @param checkers 阻塞的Checker列表
       * @return 阻塞状态描述字符串（用于日志/崩溃报告）
       */
      private String describeCheckersLocked(List<HandlerChecker> checkers) {
          StringBuilder builder = new StringBuilder(128);
          for (int i=0; i<checkers.size(); i++) {
              if (builder.length() > 0) {
                  builder.append(", "); // 多个Checker用逗号分隔
              }
              builder.append(checkers.get(i).describeBlockedStateLocked());
          }
          return builder.toString();
      }

      /**
       * 添加需要监控的HAL进程PID到集合（线程安全）
       * 功能：通过HIDL服务管理器获取HAL服务对应的PID
       * @param pids 存储PID的集合（输出参数）
       */
      private static void addInterestingHidlPids(HashSet<Integer> pids) {
          try {
              // 获取HIDL服务管理器实例
              IServiceManager serviceManager = IServiceManager.getService();
              // 获取所有HIDL服务的调试信息
              ArrayList<IServiceManager.InstanceDebugInfo> dump =
                      serviceManager.debugDump();
              for (IServiceManager.InstanceDebugInfo info : dump) {
                  if (info.pid == IServiceManager.PidConstant.NO_PID) {
                      continue; // 无PID的服务忽略
                  }
                  // 仅添加在HAL_INTERFACES_OF_INTEREST列表中的服务PID
                  if (!HAL_INTERFACES_OF_INTEREST.contains(info.interfaceName)) {
                      continue;
                  }
                  pids.add(info.pid);
              }
          } catch (RemoteException e) {
              Log.w(TAG, e); // 远程调用失败，打印警告日志
          }
      }

      /**
       * 添加需要监控的AIDL进程PID到集合（线程安全）
       * 功能：通过ServiceManager获取AIDL服务对应的PID
       * @param pids 存储PID的集合（输出参数）
       */
      private static void addInterestingAidlPids(HashSet<Integer> pids) {
          // 获取所有AIDL服务的调试信息
          ServiceDebugInfo[] infos = ServiceManager.getServiceDebugInfo();
          if (infos == null) return;

          for (ServiceDebugInfo info : infos) {
              // 匹配AIDL接口前缀，符合则添加PID
              for (String prefix : AIDL_INTERFACE_PREFIXES_OF_INTEREST) {
                  if (info.name.startsWith(prefix)) {
                      pids.add(info.debugPid);
                  }
              }
          }
      }

      /**
       * 获取需要监控的原生进程PID列表（线程安全）
       * 功能：整合AIDL/HAL/NATIVE_STACKS_OF_INTEREST中的进程PID
       * @return 去重后的PID列表
       */
      static ArrayList<Integer> getInterestingNativePids() {
          HashSet<Integer> pids = new HashSet<>();
          addInterestingAidlPids(pids); // 添加AIDL服务进程PID
          addInterestingHidlPids(pids); // 添加HAL服务进程PID

          // 添加NATIVE_STACKS_OF_INTEREST中的进程PID
          int[] nativePids = Process.getPidsForCommands(NATIVE_STACKS_OF_INTEREST);
          if (nativePids != null) {
              for (int i : nativePids) {
                  pids.add(i);
              }
          }

          return new ArrayList<Integer>(pids);
      }

      /**
       * 看门狗核心工作逻辑（运行在独立线程）
       * 循环流程：调度Checker检查 → 等待超时 → 评估状态 → 处理超时（日志/重启）
       */
      private void run() {
          boolean waitedHalf = false; // 标记是否已触发预超时

          while (true) { // 无限循环，持续监控
              List<HandlerChecker> blockedCheckers = Collections.emptyList(); // 阻塞的Checker列表
              String subject = ""; // 阻塞状态描述
              boolean allowRestart = true; // 是否允许重启
              int debuggerWasConnected = 0; // 调试器连接标记（0：未连接，1：曾连接，2：已连接）
              boolean doWaitedPreDump = false; // 是否为预超时（仅日志，不重启）
              // 缓存当前超时时间（避免循环中被修改，保证一致性）
              final long watchdogTimeoutMillis = mWatchdogTimeoutMillis;
              // 预超时检查间隔（完整超时时间 / 比例）
              final long checkIntervalMillis = watchdogTimeoutMillis / PRE_WATCHDOG_TIMEOUT_RATIO;
              final ArrayList<Integer> pids; // 需要抓取堆栈的进程PID列表

              synchronized (mLock) {
                  long timeout = checkIntervalMillis;
                  // 调度所有Checker执行检查（使用各自的超时配置）
                  for (int i=0; i<mHandlerCheckers.size(); i++) {
                      HandlerCheckerAndTimeout hc = mHandlerCheckers.get(i);
                      // 计算当前Checker的超时时间（自定义 > 全局默认 × 硬件超时倍数）
                      hc.checker().scheduleCheckLocked(hc.customTimeoutMillis()
                              .orElse(watchdogTimeoutMillis * Build.HW_TIMEOUT_MULTIPLIER));
                  }

                  // 调试器连接标记递减（用于延迟处理调试场景）
                  if (debuggerWasConnected > 0) {
                      debuggerWasConnected--;
                  }

                  // 等待checkIntervalMillis时间（期间可能被唤醒）
                  // 使用SystemClock.uptimeMillis()：不含休眠时间，避免休眠导致误判
                  long start = SystemClock.uptimeMillis();
                  while (timeout > 0) {
                      // 检查调试器是否连接，更新标记
                      if (Debug.isDebuggerConnected()) {
                          debuggerWasConnected = 2;
                      }
                      try {
                          mLock.wait(timeout); // 等待超时或被notify
                      } catch (InterruptedException e) {
                          Log.wtf(TAG, e); // 中断异常，打印严重日志
                      }
                      // 再次检查调试器连接状态
                      if (Debug.isDebuggerConnected()) {
                          debuggerWasConnected = 2;
                      }
                      // 更新剩余等待时间
                      timeout = checkIntervalMillis - (SystemClock.uptimeMillis() - start);
                  }

                  // 评估所有Checker的状态
                  final int waitState = evaluateCheckerCompletionLocked();
                  if (waitState == COMPLETED) {
                      // 所有Checker均完成，重置预超时标记，继续下一轮循环
                      waitedHalf = false;
                      continue;
                  } else if (waitState == WAITING) {
                      // 部分Checker仍在等待（未超时），继续下一轮循环
                      continue;
                  } else if (waitState == WAITED_UNTIL_PRE_WATCHDOG) {
                      // 已达预超时时间（未达完整超时）
                      if (!waitedHalf) {
                          Slog.i(TAG, "WAITED_UNTIL_PRE_WATCHDOG");
                          waitedHalf = true;
                          // 获取预超时的Checker列表，准备日志
                          blockedCheckers = getCheckersWithStateLocked(WAITED_UNTIL_PRE_WATCHDOG);
                          subject = describeCheckersLocked(blockedCheckers);
                          pids = new ArrayList<>(mInterestingJavaPids);
                          doWaitedPreDump = true; // 标记为预超时（仅日志）
                      } else {
                          continue; // 已处理过预超时，继续等待完整超时
                      }
                  } else {
                      // 已达完整超时（OVERDUE），准备终止进程
                      blockedCheckers = getCheckersWithStateLocked(OVERDUE);
                      subject = describeCheckersLocked(blockedCheckers);
                      allowRestart = mAllowRestart; // 是否允许重启
                      pids = new ArrayList<>(mInterestingJavaPids);
                  }
              } // END synchronized (mLock)

              // 记录看门狗日志（预超时/完整超时）
              logWatchog(doWaitedPreDump, subject, pids);

              // 预超时仅日志，不终止进程，继续循环
              if (doWaitedPreDump) {
                  continue;
              }

              // 完整超时：尝试通知ActivityController（系统无响应）
              IActivityController controller;
              synchronized (mLock) {
                  controller = mController;
              }
              if (controller != null) {
                  Slog.i(TAG, "Reporting stuck state to activity controller");
                  try {
                      // 禁用服务dump（避免阻塞）
                      Binder.setDumpDisabled("Service dumps disabled due to hung system process.");
                      // 通知控制器：系统无响应（返回值>=0：继续等待，-1：终止进程）
                      int res = controller.systemNotResponding(subject);
                      if (res >= 0) {
                          Slog.i(TAG, "Activity controller requested to coninue to wait");
                          waitedHalf = false;
                          continue; // 控制器要求继续等待，重置标记并循环
                      }
                  } catch (RemoteException e) {
                      // 远程调用失败，忽略异常
                  }
              }

              // 检查是否允许终止进程（调试器连接/禁止重启时不终止）
              if (Debug.isDebuggerConnected()) {
                  debuggerWasConnected = 2;
              }
              if (debuggerWasConnected >= 2) {
                  Slog.w(TAG, "Debugger connected: Watchdog is *not* killing the system process");
              } else if (debuggerWasConnected > 0) {
                  Slog.w(TAG, "Debugger was connected: Watchdog is *not* killing the system process");
              } else if (!allowRestart) {
                  Slog.w(TAG, "Restart not allowed: Watchdog is *not* killing the system process");
              } else {
                  // 满足终止条件：打印日志，终止system_server进程
                  Slog.w(TAG, "*** WATCHDOG KILLING SYSTEM PROCESS: " + subject);
                  WatchdogDiagnostics.diagnoseCheckers(blockedCheckers); // 诊断阻塞Checker
                  Slog.w(TAG, "*** GOODBYE!");
                  // 非用户版且检测到崩溃循环，且未忽略崩溃计数：触发崩溃循环中断
                  if (!Build.IS_USER && isCrashLoopFound()
                          && !WatchdogProperties.should_ignore_fatal_count().orElse(false)) {
                      breakCrashLoop();
                  }
                  Process.killProcess(Process.myPid()); // 终止当前进程（system_server）
                  System.exit(10); // 退出程序（状态码10：看门狗触发）
              }

              waitedHalf = false; // 重置预超时标记
          }
      }

      /**
       * 记录看门狗日志（预超时/完整超时）
       * 功能：抓取堆栈、CPU状态、关键事件日志，写入Dropbox
       * @param preWatchdog 是否为预超时（true：预超时，false：完整超时）
       * @param subject 阻塞状态描述
       * @param pids 需要抓取堆栈的进程PID列表
       */
      private void logWatchog(boolean preWatchdog, String subject, ArrayList<Integer> pids) {
          // 获取关键事件日志（在预超时日志前抓取，避免日志污染）
          String criticalEvents =
                  CriticalEventLog.getInstance().logLinesForSystemServerTraceFile();
          // 生成错误ID（用于关联日志）
          final UUID errorId = mTraceErrorLogger.generateErrorId();
          if (mTraceErrorLogger.isAddErrorIdEnabled()) {
              // 将错误ID、进程信息添加到跟踪日志
              mTraceErrorLogger.addProcessInfoAndErrorIdToTrace("system_server", Process.myPid(),
                      errorId);
              mTraceErrorLogger.addSubjectToTrace(subject, errorId);
          }

          // 定义Dropbox日志标签（预超时/完整超时区分）
          final String dropboxTag;
          if (preWatchdog) {
              dropboxTag = "pre_watchdog";
              CriticalEventLog.getInstance().logHalfWatchdog(subject); // 记录预超时事件
              FrameworkStatsLog.write(FrameworkStatsLog.SYSTEM_SERVER_PRE_WATCHDOG_OCCURRED); // 写入统计日志
          } else {
              dropboxTag = "watchdog";
              CriticalEventLog.getInstance().logWatchdog(subject, errorId); // 记录完整超时事件
              EventLog.writeEvent(EventLogTags.WATCHDOG, subject); // 写入系统事件日志
              // 写入统计日志（触发Perfetto跟踪）
              FrameworkStatsLog.write(FrameworkStatsLog.SYSTEM_SERVER_WATCHDOG_OCCURRED, subject);
          }

          // 构建日志头部（根据开关决定是否添加）
          final LinkedHashMap headersMap =
                  com.android.server.am.Flags.enableDropboxWatchdogHeaders()
                  ? new LinkedHashMap<>(Collections.singletonMap("Watchdog-Type", dropboxTag)) : null;
          long anrTime = SystemClock.uptimeMillis(); // 记录超时发生时间
          StringBuilder report = new StringBuilder();
          report.append(ResourcePressureUtil.currentPsiState()); // 添加系统资源压力状态（PSI）
          ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(false); // 创建CPU跟踪器
          StringWriter tracesFileException = new StringWriter(); // 捕获堆栈抓取异常
          // 抓取进程堆栈信息（Java/Native）
          final File stack = StackTracesDumpHelper.dumpStackTraces(
                  pids, processCpuTracker, new SparseBooleanArray(),
                  CompletableFuture.completedFuture(getInterestingNativePids()),
                  tracesFileException, subject, criticalEvents, headersMap,
                  Runnable::run, /* latencyTracker= */null);
          // 等待5秒，确保堆栈日志写入完成（系统已阻塞，多等几秒不影响）
          SystemClock.sleep(5000);
          processCpuTracker.update(); // 更新CPU状态
          // 添加CPU当前状态到报告（最近10秒）
          report.append(processCpuTracker.printCurrentState(anrTime, 10));
          // 添加堆栈抓取异常信息（若有）
          report.append(tracesFileException.getBuffer());

          if (!preWatchdog) {
              // 完整超时：触发内核SysRq，抓取阻塞线程和CPU回溯（写入内核日志）
              doSysRq('w'); // 显示所有阻塞线程
              doSysRq('m'); // 显示内存状态
              doSysRq('l'); // 显示所有CPU回溯
          }

          // 异步写入Dropbox（避免阻塞看门狗线程）
          Thread dropboxThread = new Thread("watchdogWriteToDropbox") {
                  public void run() {
                      // 若ActivityManagerService已初始化，将日志添加到Dropbox
                      if (mActivity != null) {
                          mActivity.addErrorToDropBox(
                                  dropboxTag, null, "system_server", null, null, null,
                                  null, report.toString(), stack, null, null, null,
                                  errorId, null);
                      }
                  }
              };
          dropboxThread.start();
          try {
              dropboxThread.join(2000); // 等待2秒，确保日志写入（超时则放弃）
          } catch (InterruptedException ignored) { }
      }

      /**
       * 触发内核SysRq命令（用于调试，抓取系统状态）
       * @param c SysRq命令字符（如'w'：显示阻塞线程）
       */
      private void doSysRq(char c) {
          try {
              // 写入SysRq触发文件（/proc/sysrq-trigger）
              FileWriter sysrq_trigger = new FileWriter("/proc/sysrq-trigger");
              sysrq_trigger.write(c);
              sysrq_trigger.close();
          } catch (IOException e) {
              Slog.w(TAG, "Failed to write to /proc/sysrq-trigger", e); // 写入失败，打印警告
          }
      }

      /** 重置超时历史记录（清空历史文件） */
      private void resetTimeoutHistory() {
          writeTimeoutHistory(new ArrayList<String>());
      }

      /**
       * 写入超时历史记录到文件
       * @param crashHistory 崩溃历史列表（存储每次崩溃的时间戳字符串）
       */
      private void writeTimeoutHistory(Iterable<String> crashHistory) {
          String data = String.join(",", crashHistory); // 拼接为逗号分隔字符串

          try (FileWriter writer = new FileWriter(TIMEOUT_HISTORY_FILE)) {
              // 写入Zygote启动时间（用于区分不同启动周期）
              writer.write(SystemProperties.get("ro.boottime.zygote"));
              writer.write(":");
              writer.write(data); // 写入崩溃历史
          } catch (IOException e) {
              Slog.e(TAG, "Failed to write file " + TIMEOUT_HISTORY_FILE, e); // 写入失败，打印错误
          }
      }

      /**
       * 读取超时历史记录（从文件中读取）
       * @return 崩溃时间戳数组（空数组表示无历史或读取失败）
       */
      private String[] readTimeoutHistory() {
          final String[] emptyStringArray = {};

          try (BufferedReader reader = new BufferedReader(new FileReader(TIMEOUT_HISTORY_FILE))) {
              String line = reader.readLine();
              if (line == null) {
                  return emptyStringArray; // 文件为空，返回空数组
              }

              String[] data = line.trim().split(":");
              String boottime = data.length >= 1 ? data[0] : ""; // Zygote启动时间
              String history = data.length >= 2 ? data[1] : ""; // 崩溃历史
              // 验证启动时间一致且历史非空，返回拆分后的时间戳数组
              if (SystemProperties.get("ro.boottime.zygote").equals(boottime) && !history.isEmpty()) {
                  return history.split(",");
              } else {
                  return emptyStringArray;
              }
          } catch (FileNotFoundException e) {
              return emptyStringArray; // 文件不存在，返回空数组
          } catch (IOException e) {
              Slog.e(TAG, "Failed to read file " + TIMEOUT_HISTORY_FILE, e); // 读取失败，打印错误
              return emptyStringArray;
          }
      }

      /**
       * 检查是否有活跃的USB连接（用于崩溃循环判断）
       * @return true：有活跃USB连接，false：无
       */
      private boolean hasActiveUsbConnection() {
          try {
              // 读取USB状态文件（/sys/class/android_usb/android0/state）
              final String state = FileUtils.readTextFile(
                      new File("/sys/class/android_usb/android0/state"),
                      128 /*max*/, null /*ellipsis*/).trim();
              if ("CONFIGURED".equals(state)) {
                  return true; // 状态为CONFIGURED表示有活跃连接
              }
          } catch (IOException e) {
              Slog.w(TAG, "Failed to determine if device was on USB", e); // 读取失败，打印警告
          }
          return false;
      }

      /**
       * 检查是否存在崩溃循环（指定时间内多次超时崩溃）
       * @return true：存在崩溃循环，false：不存在
       */
      private boolean isCrashLoopFound() {
          // 从系统属性读取崩溃循环配置（计数、时间窗口）
          int fatalCount = WatchdogProperties.fatal_count().orElse(0);
          long fatalWindowMs = TimeUnit.SECONDS.toMillis(
                  WatchdogProperties.fatal_window_seconds().orElse(0));
          // 配置不完整（计数或时间窗口为0），返回false
          if (fatalCount == 0 || fatalWindowMs == 0) {
              if (fatalCount != fatalWindowMs) {
                  // 配置不一致（一个为0，一个非0），打印警告
                  Slog.w(TAG, String.format("sysprops '%s' and '%s' should be set or unset together",
                              PROP_FATAL_LOOP_COUNT, PROP_FATAL_LOOP_WINDOWS_SECS));
              }
              return false;
          }

          // 构建新的崩溃历史：保留最近（fatalCount-1）条，添加当前时间戳
          long nowMs = SystemClock.elapsedRealtime(); // 系统启动到现在的时间（含休眠）
          String[] rawCrashHistory = readTimeoutHistory(); // 读取历史崩溃时间戳
          ArrayList<String> crashHistory = new ArrayList<String>(Arrays.asList(Arrays.copyOfRange(
                          rawCrashHistory,
                          Math.max(0, rawCrashHistory.length - fatalCount - 1),
                          rawCrashHistory.length)));
          crashHistory.add(String.valueOf(nowMs)); // 添加当前时间戳
          writeTimeoutHistory(crashHistory); // 写入更新后的历史

          // 有活跃USB连接时，不判定为崩溃循环（避免调试场景误判）
          if (hasActiveUsbConnection()) {
              return false;
          }

          // 解析第一条崩溃时间戳
          long firstCrashMs;
          try {
              firstCrashMs = Long.parseLong(crashHistory.get(0));
          } catch (NumberFormatException t) {
              Slog.w(TAG, "Failed to parseLong " + crashHistory.get(0), t); // 解析失败，打印警告
              resetTimeoutHistory(); // 重置历史记录
              return false;
          }
          // 判定条件：崩溃次数>=配置计数，且首次崩溃到当前时间<=时间窗口
          return crashHistory.size() >= fatalCount && nowMs - firstCrashMs < fatalWindowMs;
      }

      /** 中断崩溃循环（触发内核崩溃，用于调试） */
      private void breakCrashLoop() {
          try (FileWriter kmsg = new FileWriter("/dev/kmsg_debug", /* append= */ true)) {
              // 写入崩溃循环中断日志到内核调试日志
              kmsg.append("Fatal reset to escape the system_server crashing loop\n");
          } catch (IOException e) {
              Slog.w(TAG, "Failed to append to kmsg", e); // 写入失败，打印警告
          }
          doSysRq('c'); // 触发内核崩溃（SysRq命令'c'）
      }

      /**
       * 实现Dumpable接口：输出调试信息（adb shell dump 命令触发）
       * @param pw 打印流（输出目标）
       * @param args 命令参数（未使用）
       */
      @Override
      public void dump(@NonNull PrintWriter pw, @Nullable String[] args) {
          pw.print("WatchdogTimeoutMillis=");
          pw.println(mWatchdogTimeoutMillis); // 输出当前看门狗超时时间
      }

  }
