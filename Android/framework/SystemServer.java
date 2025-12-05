/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 声明包路径，属于Android系统服务核心包
package com.android.server;

// 静态导入相关标志位、权限、常量等
import static android.media.tv.flags.Flags.mediaQualityFw;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.IServiceManager.DUMP_FLAG_PROTO;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.myPid;
import static android.system.OsConstants.O_CLOEXEC;
import static android.system.OsConstants.O_RDONLY;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.hardware.input.Flags.inputManagerLifecycleSupport;
import static com.android.server.utils.TimingsTraceAndSlog.SYSTEM_SERVER_TIMING_TAG;
import static com.android.tradeinmode.flags.Flags.enableTradeInMode;

// 导入Android系统核心注解、服务、工具类等
import android.annotation.NonNull;
import android.annotation.StringRes;
import android.app.ActivityThread;
import android.app.AppCompatCallbacks;
import android.app.ApplicationErrorReport;
import android.app.INotificationManager;
import android.app.SystemServiceRegistry;
import android.app.admin.DevicePolicySafetyChecker;
import android.app.appfunctions.AppFunctionManagerConfiguration;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.crashrecovery.flags.Flags;
import android.credentials.CredentialManager;
import android.database.sqlite.SQLiteCompatibilityWalFlags;
import android.database.sqlite.SQLiteGlobal;
import android.graphics.GraphicsStatsService;
import android.graphics.Typeface;
import android.hardware.display.DisplayManagerInternal;
import android.net.ConnectivityManager;
import android.net.ConnectivityModuleConnector;
import android.net.NetworkStackClient;
import android.os.ArtModuleServiceManager;
import android.os.BaseBundle;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.IBinderCallback;
import android.os.IIncidentManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.server.ServerProtoEnums;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.tracing.perfetto.InitArguments;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Dumpable;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.contentcapture.ContentCaptureManager;

// 导入Android内部系统工具类、服务类
import com.android.i18n.timezone.ZoneInfoDb;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.ApplicationSharedMemory;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.RuntimeInit;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.pm.RoSystemFeatures;
import com.android.internal.policy.AttributeCache;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.ProtoLogConfigurationServiceImpl;
import com.android.internal.protolog.WmProtoLogGroups;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockSettingsInternal;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accounts.AccountManagerService;
import com.android.server.adb.AdbService;
import com.android.server.alarm.AlarmManagerService;
import com.android.server.am.ActivityManagerService;
import com.android.server.ambientcontext.AmbientContextManagerService;
import com.android.server.app.GameManagerService;
import com.android.server.appbinding.AppBindingService;
import com.android.server.appfunctions.AppFunctionManagerService;
import com.android.server.apphibernation.AppHibernationService;
import com.android.server.appop.AppOpMigrationHelper;
import com.android.server.appop.AppOpMigrationHelperImpl;
import com.android.server.appprediction.AppPredictionManagerService;
import com.android.server.appwidget.AppWidgetService;
import com.android.server.art.ArtModuleServiceInitializer;
import com.android.server.art.DexUseManagerLocal;
import com.android.server.attention.AttentionManagerService;
import com.android.server.audio.AudioService;
import com.android.server.autofill.AutofillManagerService;
import com.android.server.backup.BackupManagerService;
import com.android.server.biometrics.AuthService;
import com.android.server.biometrics.BiometricService;
import com.android.server.biometrics.sensors.face.FaceService;
import com.android.server.biometrics.sensors.fingerprint.FingerprintService;
import com.android.server.biometrics.sensors.iris.IrisService;
import com.android.server.blob.BlobStoreManagerService;
import com.android.server.broadcastradio.BroadcastRadioService;
import com.android.server.camera.CameraServiceProxy;
import com.android.server.clipboard.ClipboardService;
import com.android.server.companion.CompanionDeviceManagerService;
import com.android.server.companion.virtual.VirtualDeviceManagerService;
import com.android.server.compat.PlatformCompat;
import com.android.server.compat.PlatformCompatNative;
import com.android.server.compat.overrides.AppCompatOverridesService;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.PacProxyService;
import com.android.server.content.ContentService;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.contentcapture.ContentCaptureManagerService;
import com.android.server.contentsuggestions.ContentSuggestionsManagerService;
import com.android.server.contextualsearch.ContextualSearchManagerService;
import com.android.server.coverage.CoverageService;
import com.android.server.cpu.CpuMonitorService;
import com.android.server.crashrecovery.CrashRecoveryAdaptor;
import com.android.server.credentials.CredentialManagerService;
import com.android.server.criticalevents.CriticalEventLog;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.devicestate.DeviceStateManagerService;
import com.android.server.display.DisplayManagerService;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.dreams.DreamManagerService;
import com.android.server.emergency.EmergencyAffordanceService;
import com.android.server.flags.FeatureFlagsService;
import com.android.server.gpu.GpuService;
import com.android.server.grammaticalinflection.GrammaticalInflectionService;
import com.android.server.graphics.fonts.FontManagerService;
import com.android.server.hdmi.HdmiControlService;
import com.android.server.incident.IncidentCompanionService;
import com.android.server.input.InputManagerService;
import com.android.server.inputmethod.InputMethodManagerService;
import com.android.server.integrity.AppIntegrityManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.lights.LightsService;
import com.android.server.locales.LocaleManagerService;
import com.android.server.location.LocationManagerService;
import com.android.server.location.altitude.AltitudeService;
import com.android.server.locksettings.LockSettingsService;
import com.android.server.logcat.LogcatManagerService;
import com.android.server.media.MediaResourceMonitorService;
import com.android.server.media.MediaRouterService;
import com.android.server.media.MediaSessionService;
import com.android.server.media.metrics.MediaMetricsManagerService;
import com.android.server.media.projection.MediaProjectionManagerService;
import com.android.server.media.quality.MediaQualityService;
import com.android.server.midi.MidiService;
import com.android.server.musicrecognition.MusicRecognitionManagerService;
import com.android.server.net.NetworkManagementService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.watchlist.NetworkWatchlistService;
import com.android.server.notification.NotificationManagerService;
import com.android.server.oemlock.OemLockService;
import com.android.server.om.OverlayManagerService;
import com.android.server.os.BugreportManagerService;
import com.android.server.os.DeviceIdentifiersPolicyService;
import com.android.server.os.NativeTombstoneManagerService;
import com.android.server.os.SchedulingPolicyService;
import com.android.server.os.instrumentation.DynamicInstrumentationManagerService;
import com.android.server.pdb.PersistentDataBlockService;
import com.android.server.people.PeopleService;
import com.android.server.permission.access.AccessCheckingService;
import com.android.server.pinner.PinnerService;
import com.android.server.pm.ApexManager;
import com.android.server.pm.ApexSystemServiceInfo;
import com.android.server.pm.BackgroundInstallControlService;
import com.android.server.pm.CrossProfileAppsService;
import com.android.server.pm.DataLoaderManagerService;
import com.android.server.pm.DexOptHelper;
import com.android.server.pm.DynamicCodeLoggingService;
import com.android.server.pm.Installer;
import com.android.server.pm.LauncherAppsService;
import com.android.server.pm.OtaDexoptService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.ShortcutService;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.dex.OdsignStatsLogger;
import com.android.server.pm.permission.PermissionMigrationHelper;
import com.android.server.pm.permission.PermissionMigrationHelperImpl;
import com.android.server.pm.verify.domain.DomainVerificationService;
import com.android.server.policy.AppOpsPolicy;
import com.android.server.policy.PermissionPolicyService;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.role.RoleServicePlatformHelperImpl;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.power.ThermalManagerService;
import com.android.server.power.hint.HintManagerService;
import com.android.server.powerstats.PowerStatsService;
import com.android.server.print.PrintManagerService;
import com.android.server.profcollect.ProfcollectForwardingService;
import com.android.server.recoverysystem.RecoverySystemService;
import com.android.server.resources.ResourcesManagerService;
import com.android.server.restrictions.RestrictionsManagerService;
import com.android.server.role.RoleServicePlatformHelper;
import com.android.server.rollback.RollbackManagerService;
import com.android.server.rotationresolver.RotationResolverManagerService;
import com.android.server.search.SearchManagerService;
import com.android.server.searchui.SearchUiManagerService;
import com.android.server.security.AttestationVerificationManagerService;
import com.android.server.security.FileIntegrityService;
import com.android.server.security.KeyAttestationApplicationIdProviderService;
import com.android.server.security.KeyChainSystemService;
import com.android.server.security.advancedprotection.AdvancedProtectionService;
import com.android.server.security.authenticationpolicy.AuthenticationPolicyService;
import com.android.server.security.authenticationpolicy.SecureLockDeviceService;
import com.android.server.security.intrusiondetection.IntrusionDetectionService;
import com.android.server.security.rkp.RemoteProvisioningService;
import com.android.server.selinux.SelinuxAuditLogsService;
import com.android.server.sensorprivacy.SensorPrivacyService;
import com.android.server.sensors.SensorService;
import com.android.server.signedconfig.SignedConfigService;
import com.android.server.slice.SliceManagerService;
import com.android.server.smartspace.SmartspaceManagerService;
import com.android.server.soundtrigger.SoundTriggerService;
import com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareService;
import com.android.server.speech.SpeechRecognitionManagerService;
import com.android.server.stats.bootstrap.StatsBootstrapAtomService;
import com.android.server.stats.pull.StatsPullAtomService;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.storage.DeviceStorageMonitorService;
import com.android.server.supervision.SupervisionService;
import com.android.server.systemcaptions.SystemCaptionsManagerService;
import com.android.server.telecom.TelecomLoaderService;
import com.android.server.testharness.TestHarnessModeService;
import com.android.server.textclassifier.TextClassificationManagerService;
import com.android.server.textservices.TextServicesManagerService;
import com.android.server.texttospeech.TextToSpeechManagerService;
import com.android.server.timedetector.GnssTimeUpdateService;
import com.android.server.timedetector.NetworkTimeUpdateService;
import com.android.server.timedetector.TimeDetectorService;
import com.android.server.timezonedetector.TimeZoneDetectorService;
import com.android.server.timezonedetector.location.LocationTimeZoneManagerService;
import com.android.server.tracing.TracingServiceProxy;
import com.android.server.translation.TranslationManagerService;
import com.android.server.trust.TrustManagerService;
import com.android.server.tv.TvInputManagerService;
import com.android.server.tv.TvRemoteService;
import com.android.server.tv.interactive.TvInteractiveAppManagerService;
import com.android.server.tv.tunerresourcemanager.TunerResourceManagerService;
import com.android.server.twilight.TwilightService;
import com.android.server.uri.UriGrantsManagerService;
import com.android.server.usage.StorageStatsService;
import com.android.server.usage.UsageStatsService;
import com.android.server.usb.UsbService;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.vcn.VcnLocation;
import com.android.server.vibrator.VibratorManagerService;
import com.android.server.voiceinteraction.VoiceInteractionManagerService;
import com.android.server.vr.VrManagerService;
import com.android.server.wallpaper.WallpaperManagerService;
import com.android.server.wallpapereffectsgeneration.WallpaperEffectsGenerationManagerService;
import com.android.server.wearable.WearableSensingManagerService;
import com.android.server.webkit.WebViewUpdateService;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerGlobalLock;
import com.android.server.wm.WindowManagerService;

// 导入Dalvik虚拟机相关类
import dalvik.system.VMRuntime;

// 导入Java基础工具类
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

/**
 * SystemServer是Android系统服务的入口类，由Zygote进程孵化
 * 核心功能：启动并管理所有系统服务（AMS、WMS、PMS等），是系统运行的核心枢纽
 */
public final class SystemServer implements Dumpable {

    // 日志标签，用于打印SystemServer相关日志
    private static final String TAG = "SystemServer";

    // 消息调度慢日志阈值（100毫秒）
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    // 消息投递慢日志阈值（200毫秒）
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;

    /*
     * 以下为SYSTEMSERVERCLASSPATH中非services.jar包的服务实现类名
     * 主要对应ARC、Wear、IoT等特定设备的系统服务
     */
    private static final String ARC_PERSISTENT_DATA_BLOCK_SERVICE_CLASS =
            "com.android.server.arc.persistent_data_block.ArcPersistentDataBlockService";
    private static final String ARC_SYSTEM_HEALTH_SERVICE =
            "com.android.server.arc.health.ArcSystemHealthService";
    private static final String LOWPAN_SERVICE_CLASS =
            "com.android.server.lowpan.LowpanService";
    private static final String THERMAL_OBSERVER_CLASS =
            "com.android.clockwork.ThermalObserver";
    private static final String WEAR_CONNECTIVITY_SERVICE_CLASS =
            "com.android.clockwork.connectivity.WearConnectivityService";
    private static final String WEAR_POWER_SERVICE_CLASS =
            "com.android.clockwork.power.WearPowerService";
    private static final String HEALTH_SERVICE_CLASS =
            "com.android.clockwork.healthservices.HealthService";
    private static final String SYSTEM_STATE_DISPLAY_SERVICE_CLASS =
            "com.android.clockwork.systemstatedisplay.SystemStateDisplayService";
    private static final String WEAR_DISPLAYOFFLOAD_SERVICE_CLASS =
            "com.android.clockwork.displayoffload.DisplayOffloadService";
    private static final String WEAR_MODE_SERVICE_CLASS =
            "com.android.clockwork.modes.ModeManagerService";
    private static final String WEAR_DISPLAY_SERVICE_CLASS =
            "com.android.clockwork.display.WearDisplayService";
    private static final String WEAR_DEBUG_SERVICE_CLASS =
            "com.android.clockwork.debug.WearDebugService";
    private static final String WEAR_TIME_SERVICE_CLASS =
            "com.android.clockwork.time.WearTimeService";
    private static final String WEAR_SETTINGS_SERVICE_CLASS =
            "com.android.clockwork.settings.WearSettingsService";
    private static final String WEAR_GESTURE_SERVICE_CLASS =
            "com.android.clockwork.gesture.WearGestureService";
    private static final String WRIST_ORIENTATION_SERVICE_CLASS =
            "com.android.clockwork.wristorientation.WristOrientationService";
    private static final String IOT_SERVICE_CLASS =
            "com.android.things.server.IoTSystemService";
    private static final String CAR_SERVICE_HELPER_SERVICE_CLASS =
            "com.android.internal.car.CarServiceHelperService";

    /*
     * 以下为PRODUCT_APEX_SYSTEM_SERVER_JARS中的服务实现类名
     * 对应Apex模块中的系统服务（如AppSearch、HealthConnect等）
     */
    private static final String APPSEARCH_MODULE_LIFECYCLE_CLASS =
            "com.android.server.appsearch.AppSearchModule$Lifecycle";
    private static final String ISOLATED_COMPILATION_SERVICE_CLASS =
            "com.android.server.compos.IsolatedCompilationService";
    private static final String MEDIA_COMMUNICATION_SERVICE_CLASS =
            "com.android.server.media.MediaCommunicationService";
    private static final String HEALTHCONNECT_MANAGER_SERVICE_CLASS =
            "com.android.server.healthconnect.HealthConnectManagerService";
    private static final String ROLE_SERVICE_CLASS = "com.android.role.RoleService";
    private static final String ENHANCED_CONFIRMATION_SERVICE_CLASS =
            "com.android.ecm.EnhancedConfirmationService";
    private static final String SAFETY_CENTER_SERVICE_CLASS =
            "com.android.safetycenter.SafetyCenterService";
    private static final String SDK_SANDBOX_MANAGER_SERVICE_CLASS =
            "com.android.server.sdksandbox.SdkSandboxManagerService$Lifecycle";
    private static final String AD_SERVICES_MANAGER_SERVICE_CLASS =
            "com.android.server.adservices.AdServicesManagerService$Lifecycle";
    private static final String ON_DEVICE_INTELLIGENCE_MANAGER_SERVICE_CLASS =
            "com.android.server.ondeviceintelligence.OnDeviceIntelligenceManagerService";
    private static final String ON_DEVICE_PERSONALIZATION_SYSTEM_SERVICE_CLASS =
            "com.android.server.ondevicepersonalization."
                    + "OnDevicePersonalizationSystemService$Lifecycle";
    private static final String UPDATABLE_DEVICE_CONFIG_SERVICE_CLASS =
            "com.android.server.deviceconfig.DeviceConfigInit$Lifecycle";


    /*
     * 以下为STANDALONE_SYSTEMSERVER_JARS中的服务实现类名及对应Jar包路径
     * 对应独立Jar包中的系统服务（如WiFi、蓝牙、统计服务等）
     */
    private static final String STATS_COMPANION_APEX_PATH =
            "/apex/com.android.os.statsd/javalib/service-statsd.jar";
    private static final String STATS_COMPANION_LIFECYCLE_CLASS =
            "com.android.server.stats.StatsCompanion$Lifecycle";
    private static final String SCHEDULING_APEX_PATH =
            "/apex/com.android.scheduling/javalib/service-scheduling.jar";
    private static final String REBOOT_READINESS_LIFECYCLE_CLASS =
            "com.android.server.scheduling.RebootReadinessManagerService$Lifecycle";
    private static final String WIFI_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.wifi/javalib/service-wifi.jar";
    private static final String WIFI_SERVICE_CLASS =
            "com.android.server.wifi.WifiService";
    private static final String WIFI_SCANNING_SERVICE_CLASS =
            "com.android.server.wifi.scanner.WifiScanningService";
    private static final String WIFI_RTT_SERVICE_CLASS =
            "com.android.server.wifi.rtt.RttService";
    private static final String WIFI_AWARE_SERVICE_CLASS =
            "com.android.server.wifi.aware.WifiAwareService";
    private static final String WIFI_P2P_SERVICE_CLASS =
            "com.android.server.wifi.p2p.WifiP2pService";
    private static final String WIFI_USD_SERVICE_CLASS =
            "com.android.server.wifi.usd.UsdService";
    private static final String CONNECTIVITY_SERVICE_APEX_PATH =
            "/apex/com.android.tethering/javalib/service-connectivity.jar";
    private static final String CONNECTIVITY_SERVICE_INITIALIZER_CLASS =
            "com.android.server.ConnectivityServiceInitializer";
    private static final String CONNECTIVITY_SERVICE_INITIALIZER_B_CLASS =
            "com.android.server.ConnectivityServiceInitializerB";
    private static final String NETWORK_STATS_SERVICE_INITIALIZER_CLASS =
            "com.android.server.NetworkStatsServiceInitializer";
    private static final String UWB_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.uwb/javalib/service-uwb.jar";
    private static final String UWB_SERVICE_CLASS = "com.android.server.uwb.UwbService";
    private static final String BLUETOOTH_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.bt/javalib/service-bluetooth.jar";
    private static final String BLUETOOTH_SERVICE_CLASS =
            "com.android.server.bluetooth.BluetoothService";
    private static final String DEVICE_LOCK_SERVICE_CLASS =
            "com.android.server.devicelock.DeviceLockService";
    private static final String DEVICE_LOCK_APEX_PATH =
            "/apex/com.android.devicelock/javalib/service-devicelock.jar";
    private static final String PROFILING_SERVICE_LIFECYCLE_CLASS =
            "android.os.profiling.ProfilingService$Lifecycle";
    private static final String PROFILING_SERVICE_JAR_PATH =
            "/apex/com.android.profiling/javalib/service-profiling.jar";

    private static final String RANGING_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.uwb/javalib/service-ranging.jar";
    private static final String RANGING_SERVICE_CLASS = "com.android.server.ranging.RangingService";

    // Tethering连接器接口类名
    private static final String TETHERING_CONNECTOR_CLASS = "android.net.ITetheringConnector";

    // FRP（Factory Reset Protection）持久化数据块系统属性
    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";

    // 系统升级相关文件路径
    private static final String UNCRYPT_PACKAGE_FILE = "/cache/recovery/uncrypt_file";
    private static final String BLOCK_MAP_FILE = "/cache/recovery/block.map";

    // SystemServer进程的最大Binder线程数（高于系统默认值）
    private static final int sMaxBinderThreads = 31;

    /**
     * 系统上下文使用的默认主题
     * 用于系统提供的对话框（如关机对话框）及其他视觉内容的样式
     */
    private static final int DEFAULT_SYSTEM_THEME =
            com.android.internal.R.style.Theme_DeviceDefault_System;

    // 工厂测试模式（0：正常模式，1：低级别工厂测试，2：高级别工厂测试）
    private final int mFactoryTestMode;
    // 性能分析快照定时器（用于定期抓取性能数据）
    private Timer mProfilerSnapshotTimer;

    // 系统级Context（全局唯一，用于系统服务获取资源、启动服务等）
    private Context mSystemContext;
    // 系统服务管理器（负责系统服务的启动、注册、销毁）
    private SystemServiceManager mSystemServiceManager;

    // 核心系统服务引用（用于服务间依赖调用，后续需逐步通过依赖注入优化）
    private PowerManagerService mPowerManagerService;
    private ActivityManagerService mActivityManagerService;
    private WindowManagerGlobalLock mWindowManagerGlobalLock;
    private WebViewUpdateService mWebViewUpdateService;
    private DisplayManagerService mDisplayManagerService;
    private PackageManagerService mPackageManagerService;
    private PackageManager mPackageManager;
    private ContentResolver mContentResolver;
    private EntropyMixer mEntropyMixer;
    private DataLoaderManagerService mDataLoaderManagerService;
    // Incremental服务句柄（用于原生Incremental服务交互）
    private long mIncrementalServiceHandle = 0;

    // 是否为首次启动（系统首次开机或恢复出厂设置后）
    private boolean mFirstBoot;
    // SystemServer启动次数（用于判断是否为运行时重启）
    private final int mStartCount;
    // 是否为运行时重启（非整机重启，仅SystemServer进程重启）
    private final boolean mRuntimeRestart;
    // SystemServer启动时的系统已运行时间（含休眠）
    private final long mRuntimeStartElapsedTime;
    // SystemServer启动时的系统开机时间（不含休眠）
    private final long mRuntimeStartUptime;

    // 原生服务启动相关常量（用于Trace跟踪）
    private static final String START_HIDL_SERVICES = "StartHidlServices";
    private static final String START_SENSOR_MANAGER_SERVICE = "StartISensorManagerService";
    private static final String START_BLOB_STORE_SERVICE = "startBlobStoreManagerService";

    // 记录SystemServer启动信息的系统属性
    private static final String SYSPROP_START_COUNT = "sys.system_server.start_count";
    private static final String SYSPROP_START_ELAPSED = "sys.system_server.start_elapsed";
    private static final String SYSPROP_START_UPTIME = "sys.system_server.start_uptime";

    // Zygote预加载任务（用于WebView等组件的预加载）
    private Future<?> mZygotePreload;

    // SystemServer调试信息 Dump器（用于adb shell dumpsys系统服务状态）
    private final SystemServerDumper mDumper = new SystemServerDumper();

    /**
     * 待写入Dropbox的严重错误（WTF）队列
     * 存储系统启动早期发生的严重错误，待系统服务就绪后统一上报
     */
    private static LinkedList<Pair<String, ApplicationErrorReport.CrashInfo>> sPendingWtfs;

    /**
     * 启动IStats服务（原生服务，用于系统统计数据收集）
     * 阻塞调用，可能耗时较长
     */
    private static native void startIStatsService();

    /**
     * 启动ISensorManager服务（原生服务，用于传感器管理）
     * 阻塞调用，可能耗时较长
     */
    private static native void startISensorManagerService();

    /**
     * 启动memtrack代理服务（原生服务，用于内存跟踪）
     */
    private static native void startMemtrackProxyService();

    /**
     * 启动所有运行在SystemServer内部的HIDL服务
     * 可能耗时较长，用于硬件抽象层服务初始化
     */
    private static native void startHidlServices();

    /**
     * 标记当前进程的堆为可分析状态（仅调试版本有效）
     */
    private static native void initZygoteChildHeapProfiling();

    // 文件描述符泄漏监控相关系统属性
    private static final String SYSPROP_FDTRACK_ENABLE_THRESHOLD =
            "persist.sys.debug.fdtrack_enable_threshold";
    private static final String SYSPROP_FDTRACK_ABORT_THRESHOLD =
            "persist.sys.debug.fdtrack_abort_threshold";
    private static final String SYSPROP_FDTRACK_INTERVAL =
            "persist.sys.debug.fdtrack_interval";

    /**
     * 获取当前进程的最大文件描述符（FD）数量
     * @return 最大FD数量，获取失败返回Integer.MAX_VALUE
     */
    private static int getMaxFd() {
        FileDescriptor fd = null;
        try {
            // 打开/dev/null文件，获取其FD值作为最大FD参考
            fd = Os.open("/dev/null", O_RDONLY | O_CLOEXEC, 0);
            return fd.getInt$();
        } catch (ErrnoException ex) {
            Slog.e("System", "Failed to get maximum fd: " + ex);
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd); // 关闭FD，避免泄漏
                } catch (ErrnoException ex) {
                    // 关闭失败表示系统异常，抛出运行时异常
                    throw new RuntimeException(ex);
                }
            }
        }

        return Integer.MAX_VALUE;
    }

    /**
     * 触发文件描述符泄漏监控的终止操作（原生方法）
     */
    private static native void fdtrackAbort();

    // 堆转储文件存储路径及最大保留数量
    private static final File HEAP_DUMP_PATH = new File("/data/system/heapdump/");
    private static final int MAX_HEAP_DUMPS = 2;

    /**
     * 转储SystemServer的堆信息（hprof格式）
     * 出于隐私考虑，不会自动纳入bugreport，需用户手动提取
     */
    private static void dumpHprof() {
        // hprof文件较大，限制保留数量，避免占满磁盘
        TreeSet<File> existingTombstones = new TreeSet<>();
        for (File file : HEAP_DUMP_PATH.listFiles()) {
            if (!file.isFile()) {
                continue;
            }
            if (!file.getName().startsWith("fdtrack-")) {
                continue;
            }
            existingTombstones.add(file);
        }
        if (existingTombstones.size() >= MAX_HEAP_DUMPS) {
            // 保留最新的MAX_HEAP_DUMPS-1个文件，删除旧文件
            for (int i = 0; i < MAX_HEAP_DUMPS - 1; ++i) {
                existingTombstones.pollLast();
            }
            for (File file : existingTombstones) {
                if (!file.delete()) {
                    Slog.w("System", "Failed to clean up hprof " + file);
                }
            }
        }

        try {
            // 生成带时间戳的hprof文件名
            String date = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
            String filename = "/data/system/heapdump/fdtrack-" + date + ".hprof";
            Debug.dumpHprofData(filename); // 转储堆信息到文件
        } catch (IOException ex) {
            Slog.e("System", "Failed to dump fdtrack hprof", ex);
        }
    }

    /**
     * 启动文件描述符（FD）泄漏监控线程
     * 仅调试版本生效，定期检查FD数量，超过阈值时触发告警或终止
     */
    private static void spawnFdLeakCheckThread() {
        // 从系统属性读取监控阈值和检查间隔
        final int enableThreshold = SystemProperties.getInt(SYSPROP_FDTRACK_ENABLE_THRESHOLD, 1600);
        final int abortThreshold = SystemProperties.getInt(SYSPROP_FDTRACK_ABORT_THRESHOLD, 3000);
        final int checkInterval = SystemProperties.getInt(SYSPROP_FDTRACK_INTERVAL, 120);

        // 启动监控线程
        new Thread(() -> {
            boolean enabled = false; // FD跟踪是否已启用
            long nextWrite = 0; // 下次写入统计日志的时间

            while (true) {
                int maxFd = getMaxFd();
                if (maxFd > enableThreshold) {
                    // FD数量超过启用阈值，触发GC清理可能泄漏的FD
                    System.gc();
                    System.runFinalization();
                    maxFd = getMaxFd(); // 重新获取FD数量
                }

                if (maxFd > enableThreshold && !enabled) {
                    // 启用FD跟踪，记录统计日志
                    Slog.i("System", "fdtrack enable threshold reached, enabling");
                    FrameworkStatsLog.write(FrameworkStatsLog.FDTRACK_EVENT_OCCURRED,
                            FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__ENABLED,
                            maxFd);

                    System.loadLibrary("fdtrack"); // 加载FD跟踪原生库
                    enabled = true;
                } else if (maxFd > abortThreshold) {
                    // FD数量超过终止阈值，转储堆信息并终止进程
                    Slog.i("System", "fdtrack abort threshold reached, dumping and aborting");
                    FrameworkStatsLog.write(FrameworkStatsLog.FDTRACK_EVENT_OCCURRED,
                            FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__ABORTING,
                            maxFd);

                    dumpHprof(); // 转储堆信息
                    fdtrackAbort(); // 终止进程
                } else {
                    // 定期写入统计日志（每小时一次）
                    long now = SystemClock.elapsedRealtime();
                    if (now > nextWrite) {
                        nextWrite = now + 60 * 60 * 1000;
                        FrameworkStatsLog.write(FrameworkStatsLog.FDTRACK_EVENT_OCCURRED,
                                enabled ? FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__ENABLED
                                        : FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__DISABLED,
                                maxFd);
                    }
                }

                try {
                    Thread.sleep(checkInterval * 1000); // 按间隔休眠
                } catch (InterruptedException ex) {
                    continue; // 被中断后继续循环
                }
            }
        }).start();
    }

    /**
     * 启动原生Incremental服务，返回服务句柄
     * Incremental服务用于增量安装、资源按需加载等功能
     */
    private static native long startIncrementalService();

    /**
     * 通知Incremental服务系统已就绪
     * @param incrementalServiceHandle Incremental服务句柄
     */
    private static native void setIncrementalServiceSystemReady(long incrementalServiceHandle);

    /**
     * SystemServer的主入口方法（由Zygote进程调用）
     * 启动SystemServer进程并执行核心逻辑
     */
    public static void main(String[] args) {
        new SystemServer().run();
    }

    /**
     * 构造方法：初始化SystemServer核心参数
     * 私有访问权限，确保仅通过main方法创建实例
     */
    public SystemServer() {
        // 检查工厂测试模式（从系统属性读取）
        mFactoryTestMode = FactoryTest.getMode();

        // 记录SystemServer启动信息
        mStartCount = SystemProperties.getInt(SYSPROP_START_COUNT, 0) + 1;
        mRuntimeStartElapsedTime = SystemClock.elapsedRealtime();
        mRuntimeStartUptime = SystemClock.uptimeMillis();
        // 设置进程启动时间（用于系统统计）
        Process.setStartTimes(mRuntimeStartElapsedTime, mRuntimeStartUptime,
                mRuntimeStartElapsedTime, mRuntimeStartUptime);

        // 判断是否为运行时重启（启动次数>1表示非首次启动）
        mRuntimeRestart = mStartCount > 1;
    }

    /**
     * 实现Dumpable接口：返回当前Dumpable组件名称
     */
    @Override
    public String getDumpableName() {
        return SystemServer.class.getSimpleName();
    }

    /**
     * 实现Dumpable接口：输出SystemServer的调试信息
     * 支持adb shell dumpsys system_server命令触发
     */
    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.printf("Runtime restart: %b\n", mRuntimeRestart); // 是否为运行时重启
        pw.printf("Start count: %d\n", mStartCount); // 启动次数
        pw.print("Runtime start-up time: ");
        TimeUtils.formatDuration(mRuntimeStartUptime, pw); pw.println(); // 启动时开机时间
        pw.print("Runtime start-elapsed time: ");
        TimeUtils.formatDuration(mRuntimeStartElapsedTime, pw); pw.println(); // 启动时已运行时间
    }

    /**
     * 内部类：SystemServer调试信息Dump器
     * 提供adb shell dumpsys system_server_dumper命令的支持
     * 可列出所有系统服务、Dump指定服务状态
     */
    private final class SystemServerDumper extends Binder {

        // 存储Dumpable组件的映射（键：组件名称，值：Dumpable实例）
        @GuardedBy("mDumpables")
        private final ArrayMap<String, Dumpable> mDumpables = new ArrayMap<>(4);

        /**
         * 重写Binder的dump方法：处理调试信息输出请求
         */
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            final boolean hasArgs = args != null && args.length > 0;

            synchronized (mDumpables) {
                if (hasArgs && "--list".equals(args[0])) {
                    // 列出所有可Dump的组件名称
                    final int dumpablesSize = mDumpables.size();
                    for (int i = 0; i < dumpablesSize; i++) {
                        pw.println(mDumpables.keyAt(i));
                    }
                    return;
                }

                if (hasArgs && "--name".equals(args[0])) {
                    // Dump指定名称的组件状态
                    if (args.length < 2) {
                        pw.println("Must pass at least one argument to --name");
                        return;
                    }
                    final String name = args[1];
                    final Dumpable dumpable = mDumpables.get(name);
                    if (dumpable == null) {
                        pw.printf("No dumpable named %s\n", name);
                        return;
                    }

                    try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ")) {
                        // 截取参数（去除--name和组件名称）
                        final String[] actualArgs = Arrays.copyOfRange(args, 2, args.length);
                        dumpable.dump(ipw, actualArgs); // 输出组件调试信息
                    }
                    return;
                }

                // Dump所有可Dump组件的状态
                final int dumpablesSize = mDumpables.size();
                try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ")) {
                    for (int i = 0; i < dumpablesSize; i++) {
                        final Dumpable dumpable = mDumpables.valueAt(i);
                        ipw.printf("%s:\n", dumpable.getDumpableName());
                        ipw.increaseIndent(); // 增加缩进
                        dumpable.dump(ipw, args);
                        ipw.decreaseIndent(); // 减少缩进
                        ipw.println();
                    }
                }
            }
        }

        /**
         * 添加Dumpable组件到映射表
         * @param dumpable 可Dump的组件实例
         */
        private void addDumpable(@NonNull Dumpable dumpable) {
            synchronized (mDumpables) {
                mDumpables.put(dumpable.getDumpableName(), dumpable);
            }
        }
    }

    /**
     * SystemServer核心运行逻辑
     * 流程：初始化环境 → 启动系统服务 → 进入消息循环
     */
    private void run() {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(); // 用于性能跟踪
        try {
            // 初始化Perfetto生产者共享内存缓冲区（仅当启用大缓冲区标志时）
            if (android.tracing.Flags.systemServerLargePerfettoShmemBuffer()) {
                android.tracing.perfetto.Producer.init(new InitArguments(
                        InitArguments.PERFETTO_BACKEND_SYSTEM, 4 * 1024));
            }

            t.traceBegin("InitBeforeStartServices"); // 开始跟踪"服务启动前初始化"阶段

            // 将SystemServer启动信息写入系统属性
            SystemProperties.set(SYSPROP_START_COUNT, String.valueOf(mStartCount));
            SystemProperties.set(SYSPROP_START_ELAPSED, String.valueOf(mRuntimeStartElapsedTime));
            SystemProperties.set(SYSPROP_START_UPTIME, String.valueOf(mRuntimeStartUptime));

            // 写入SystemServer启动事件到EventLog
            EventLog.writeEvent(EventLogTags.SYSTEM_SERVER_START,
                    mStartCount, mRuntimeStartUptime, mRuntimeStartElapsedTime);

            // 初始化系统时区设置（若未设置或无效）
            SystemTimeZone.initializeTimeZoneSettingsIfRequired();

            // 兼容旧版系统属性：将persist.sys.language等属性迁移到persist.sys.locale
            if (!SystemProperties.get("persist.sys.language").isEmpty()) {
                final String languageTag = Locale.getDefault().toLanguageTag();

                SystemProperties.set("persist.sys.locale", languageTag);
                SystemProperties.set("persist.sys.language", "");
                SystemProperties.set("persist.sys.country", "");
                SystemProperties.set("persist.sys.localevar", "");
            }

            // SystemServer进程不允许执行非单向Binder调用（避免阻塞）
            Binder.setWarnOnBlocking(true);
            // SystemServer加载的PackageItemInfo强制使用安全标签
            PackageItemInfo.forceSafeLabels();

            // SQLite默认同步模式设置为FULL（确保数据一致性）
            SQLiteGlobal.sDefaultSyncMode = SQLiteGlobal.SYNC_MODE_FULL;

            // 禁用SQLite兼容性WAL标志，直到设置提供器初始化完成
            SQLiteCompatibilityWalFlags.init(null);

            // 打印系统启动日志
            Slog.i(TAG, "Entered the Android system server!");
            final long uptimeMillis = SystemClock.elapsedRealtime();
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN, uptimeMillis);
            if (!mRuntimeRestart) {
                // 记录系统启动时间统计（SYSTEM_SERVER_INIT_START事件）
                FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                        FrameworkStatsLog
                                .BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SYSTEM_SERVER_INIT_START,
                        uptimeMillis);
            }

            // 同步运行时库版本到系统属性（应对OTA后运行时切换场景）
            SystemProperties.set("persist.sys.dalvik.vm.lib.2", VMRuntime.getRuntime().vmLibrary());

            // 清除虚拟机堆增长限制（SystemServer需要更多内存）
            VMRuntime.getRuntime().clearGrowthLimit();

            // 确保系统指纹已生成（部分设备依赖运行时生成）
            Build.ensureFingerprintProperty();

            // SystemServer访问Environment路径必须显式指定用户
            Environment.setUserRequired(true);

            // SystemServer接收的Bundle自动"脱敏"，避免BadParcelableException
            BaseBundle.setShouldDefuse(true);

            // SystemServer中Parcel序列化异常时包含堆栈跟踪
            Parcel.setStackTraceParceling(true);

            // Binder调用进入SystemServer时强制使用前台优先级
            BinderInternal.disableBackgroundScheduling(true);

            // 增加SystemServer的Binder线程数（默认31个）
            BinderInternal.setMaxThreads(sMaxBinderThreads);

            // 准备主线程Looper（SystemServer进程的主线程）
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);
            Looper.prepareMainLooper();
            // 设置主线程Looper的慢日志阈值
            Looper.getMainLooper().setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);

            // 启用系统服务未找到时的WTF日志
            SystemServiceRegistry.sEnableServiceNotFoundWtf = true;

            // 启动SystemServer初始化线程池（用于并行执行初始化任务）
            SystemServerInitThreadPool tp = SystemServerInitThreadPool.start();
            mDumper.addDumpable(tp); // 将线程池添加到Dumpable列表

            // 提前初始化SystemConfig（耗时操作，并行执行）
            if (android.server.Flags.earlySystemConfigInit()) {
                startSystemConfigInit(t);
            }

            // 加载android_servers原生库（包含系统服务的原生实现）
            System.loadLibrary("android_servers");

            // 初始化Zygote子进程堆分析（仅调试版本有效）
            initZygoteChildHeapProfiling();

            // 调试版本：启动FD泄漏监控线程
            if (Build.IS_DEBUGGABLE) {
                spawnFdLeakCheckThread();
            }

            // 处理上一次未完成的关机请求（若存在，可能不返回）
            performPendingShutdown();

            // 初始化系统上下文（创建SystemServer的Context实例）
            createSystemContext();

            // 初始化主线程模块（Mainline模块相关）
            ActivityThread.initializeMainlineModules();

            // 注册system_server_dumper服务到ServiceManager
            ServiceManager.addService("system_server_dumper", mDumper);
            mDumper.addDumpable(this); // 将SystemServer自身添加到Dumpable列表

            // 创建系统服务管理器（负责系统服务的生命周期管理）
            mSystemServiceManager = new SystemServiceManager(mSystemContext);
            mSystemServiceManager.setStartInfo(mRuntimeRestart,
                    mRuntimeStartElapsedTime, mRuntimeStartUptime);
            mDumper.addDumpable(mSystemServiceManager); // 将服务管理器添加到Dumpable列表

            // 注册系统服务管理器到本地服务（供内部服务调用）
            LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);

            // 延迟加载预安装系统字体映射（仅当未启用优化启动字体加载时）
            if (!com.android.text.flags.Flags.useOptimizedBoottimeFontLoading()
                    && Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
                Slog.i(TAG, "Loading pre-installed system font map.");
                Typeface.loadPreinstalledSystemFontMap();
            }

            // 调试版本：根据系统属性加载JVMTI代理
            if (Build.IS_DEBUGGABLE) {
                String jvmtiAgent = SystemProperties.get("persist.sys.dalvik.jvmtiagent");
                if (!jvmtiAgent.isEmpty()) {
                    int equalIndex = jvmtiAgent.indexOf('=');
                    String libraryPath = jvmtiAgent.substring(0, equalIndex);
                    String parameterList =
                            jvmtiAgent.substring(equalIndex + 1, jvmtiAgent.length());
                    // 加载JVMTI代理
                    try {
                        Debug.attachJvmtiAgent(libraryPath, parameterList, null);
                    } catch (Exception e) {
                        Slog.e("System", "*************************************************");
                        Slog.e("System", "********** Failed to load jvmti plugin: " + jvmtiAgent);
                    }
                }
            }
        } finally {
            t.traceEnd(); // 结束"服务启动前初始化"阶段跟踪
        }

        // 设置默认的应用WTF处理器（处理系统早期严重错误）
        RuntimeInit.setDefaultApplicationWtfHandler(SystemServer::handleEarlySystemWtf);

        // 初始化应用共享内存区域（系统服务可能依赖）
        ApplicationSharedMemory instance = ApplicationSharedMemory.create();
        ApplicationSharedMemory.setInstance(instance);

        // 启动系统服务（引导服务、核心服务、其他服务、Apex服务）
        try {
            t.traceBegin("StartServices"); // 开始跟踪"启动服务"阶段
            startBootstrapServices(t); // 启动引导服务（AMS、WMS、PMS等核心依赖服务）
            startCoreServices(t);      // 启动核心服务（电池、电量统计等基础服务）
            startOtherServices(t);     // 启动其他服务（通知、蓝牙、WiFi等扩展服务）
            startApexServices(t);      // 启动Apex模块中的系统服务
            // 所有服务启动后更新看门狗超时时间（使用系统设置的值）
            updateWatchdogTimeout(t);
            // 记录SystemServer启动完成事件到关键事件日志
            CriticalEventLog.getInstance().logSystemServerStarted();
        } catch (Throwable ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting system services", ex);
            throw ex;
        } finally {
            t.traceEnd(); // 结束"启动服务"阶段跟踪
        }

        // 初始化虚拟机默认StrictMode配置（null表示使用系统默认）
        StrictMode.initVmDefaults(null);

        // 非运行时重启且非首次启动/系统升级：检查SystemServer启动耗时
        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            final long uptimeMillis = SystemClock.elapsedRealtime();
            // 记录SYSTEM_SERVER_READY事件到启动时间统计
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SYSTEM_SERVER_READY,
                    uptimeMillis);
            final long maxUptimeMillis = 60 * 1000; // 最大允许启动耗时（60秒）
            if (uptimeMillis > maxUptimeMillis) {
                Slog.wtf(SYSTEM_SERVER_TIMING_TAG,
                        "SystemServer init took too long. uptimeMillis=" + uptimeMillis);
            }
        }

        // 启动系统服务后设置Binder事务回调（检测冻结的Binder事务）
        Binder.setTransactionCallback(new IBinderCallback() {
            @Override
            public void onTransactionError(int pid, int code, int flags, int err) {
                mActivityManagerService.frozenBinderTransactionDetected(pid, code, flags, err);
            }
        });

        // 注册系统服务GC后内存指标上报回调
        if (android.app.Flags.reportPostgcMemoryMetrics() &&
                com.android.libcore.readonly.Flags.postCleanupApis()) {
            VMRuntime.addPostCleanupCallback(new Runnable() {
                @Override public void run() {
                    MetricsLoggerWrapper.logPostGcMemorySnapshot();
                }
            });
        }

        // 启动主线程消息循环（阻塞，直到Looper退出）
        Looper.loop();
        throw new RuntimeException("Main thread loop unexpectedly exited");
    }

    /**
     * 验证时区ID是否有效
     * @param timezoneProperty 时区ID字符串
     * @return true：有效，false：无效
     */
    private static boolean isValidTimeZoneId(String timezoneProperty) {
        return timezoneProperty != null
                && !timezoneProperty.isEmpty()
                && ZoneInfoDb.getInstance().hasTimeZone(timezoneProperty);
    }

    /**
     * 判断是否为首次启动或系统升级
     * @return true：首次启动或系统升级，false：否则
     */
    private boolean isFirstBootOrUpgrade() {
        return mPackageManagerService.isFirstBoot() || mPackageManagerService.isDeviceUpgrading();
    }

    /**
     * 报告严重错误（WTF）并打印日志
     * @param msg 错误描述信息
     * @param e 异常实例
     */
    private void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Slog.wtf(TAG, "BOOT FAILURE " + msg, e);
    }

    /**
     * 处理上一次未完成的关机请求
     * 若系统属性中存在未处理的关机动作，执行重启或关机
     */
    private void performPendingShutdown() {
        final String shutdownAction = SystemProperties.get(
                ShutdownThread.SHUTDOWN_ACTION_PROPERTY, "");
        if (shutdownAction != null && shutdownAction.length() > 0) {
            boolean reboot = (shutdownAction.charAt(0) == '1'); // 是否为重启（'1'表示重启）

            final String reason;
            if (shutdownAction.length() > 1) {
                reason = shutdownAction.substring(1, shutdownAction.length()); // 关机/重启原因
            } else {
                reason = null;
            }

            // 若为升级重启到Recovery，确保uncrypt操作已完成（避免升级失败）
            if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
                File packageFile = new File(UNCRYPT_PACKAGE_FILE);
                if (packageFile.exists()) {
                    String filename = null;
                    try {
                        filename = FileUtils.readTextFile(packageFile, 0, null); // 读取uncrypt包文件路径
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading uncrypt package file", e);
                    }

                    if (filename != null && filename.startsWith("/data")) {
                        if (!new File(BLOCK_MAP_FILE).exists()) {
                            // 未找到block.map文件，uncrypt可能失败，取消重启
                            Slog.e(TAG, "Can't find block map file, uncrypt failed or " +
                                    "unexpected runtime restart?");
                            return;
                        }
                    }
                }
            }
            // 创建关机/重启任务（必须在有UI的Looper线程执行）
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    ShutdownThread.rebootOrShutdown(null, reboot, reason);
                }
            };

            // 将任务发送到UiThread的消息队列（支持UI显示）
            Message msg = Message.obtain(UiThread.getHandler(), runnable);
            msg.setAsynchronous(true); // 异步消息，优先执行
            UiThread.getHandler().sendMessage(msg);

        }
    }

    /**
     * 启动SystemConfig初始化（并行执行，耗时操作）
     * @param t 性能跟踪对象
     */
    private void startSystemConfigInit(TimingsTraceAndSlog t) {
        Slog.i(TAG, "Reading configuration...");
        final String tagSystemConfig = "ReadingSystemConfig";
        t.traceBegin(tagSystemConfig);
        // 提交SystemConfig初始化任务到线程池
        SystemServerInitThreadPool.submit(SystemConfig::getInstance, tagSystemConfig);
        t.traceEnd();
    }

    /**
     * 创建系统上下文（SystemContext）
     * 系统服务通过此Context获取资源、启动服务等
     */
    private void createSystemContext() {
        // 创建SystemServer的ActivityThread实例（系统进程的ActivityThread）
        ActivityThread activityThread = ActivityThread.systemMain();
        mSystemContext = activityThread.getSystemContext(); // 获取系统级Context
        mSystemContext.setTheme(DEFAULT_SYSTEM_THEME); // 设置默认主题

        // 获取系统UI上下文并设置主题
        final Context systemUiContext = activityThread.getSystemUiContext();
        systemUiContext.setTheme(DEFAULT_SYSTEM_THEME);
        Trace.registerWithPerfetto(); // 注册Perfetto跟踪
    }

    /**
     * 启动引导服务（ Bootstrap Services）
     * 这些服务相互依赖，是系统启动的基础，必须在一个方法中初始化
     * 其他非依赖服务应在startCoreServices或startOtherServices中启动
     */
    private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startBootstrapServices"); // 开始跟踪"启动引导服务"阶段

        // 初始化ArtModuleServiceManager（需在DexUseManagerLocal之前）
        t.traceBegin("ArtModuleServiceInitializer");
        ArtModuleServiceInitializer.setArtModuleServiceManager(new ArtModuleServiceManager());
        t.traceEnd();

        // 尽早启动看门狗服务（Watchdog），避免启动早期死锁无法检测
        t.traceBegin("StartWatchdog");
        final Watchdog watchdog = Watchdog.getInstance();
        watchdog.start();
        mDumper.addDumpable(watchdog); // 将看门狗添加到Dumpable列表
        t.traceEnd();

        // 未启用早期SystemConfig初始化时，在这里启动（兼容旧逻辑）
        if (!android.server.Flags.earlySystemConfigInit()) {
            startSystemConfigInit(t);
        }

        // 启动ProtoLog配置服务（客户端Proto日志功能）
        if (android.tracing.Flags.clientSideProtoLogging()) {
            t.traceBegin("StartProtoLogConfigurationService");
            ServiceManager.addService(
                    Context.PROTOLOG_CONFIGURATION_SERVICE, new ProtoLogConfigurationServiceImpl());
            t.traceEnd();
        }

        // 初始化ProtoLog（注册窗口管理相关日志组）
        t.traceBegin("InitializeProtoLog");
        ProtoLog.init(WmProtoLogGroups.values());
        t.traceEnd();

        // 启动平台兼容性服务（PlatformCompat）
        // AMS、PMS等服务依赖此服务
        t.traceBegin("PlatformCompat");
        PlatformCompat platformCompat = new PlatformCompat(mSystemContext);
        ServiceManager.addService(Context.PLATFORM_COMPAT_SERVICE, platformCompat);
        ServiceManager.addService(Context.PLATFORM_COMPAT_NATIVE_SERVICE,
                new PlatformCompatNative(platformCompat));
        AppCompatCallbacks.install(new long[0], new long[0]); // 安装AppCompat回调
        t.traceEnd();

        // 启动文件完整性服务（FileIntegrityService）
        // 用于验证文件完整性，需在应用启动前启动
        t.traceBegin("StartFileIntegrityService");
        mSystemServiceManager.startService(FileIntegrityService.class);
        t.traceEnd();

        // 启动Installer服务（应用安装相关服务）
        // 等待Installer启动完成，确保/data/user等目录已创建
        t.traceBegin("StartInstaller");
        Installer installer = mSystemServiceManager.startService(Installer.class);
        t.traceEnd();

        // 启动设备标识符策略服务（DeviceIdentifiersPolicyService）
        // 需在AMS之前启动（应用可能需要访问设备标识符）
        t.traceBegin("DeviceIdentifiersPolicyService");
        mSystemServiceManager.startService(DeviceIdentifiersPolicyService.class);
        t.traceEnd();

        // 启动功能标志服务（FeatureFlagsService）
        // 用于读取运行时标志覆盖，同步进程间标志状态
        t.traceBegin("StartFeatureFlagsService");
        mSystemServiceManager.startService(FeatureFlagsService.class);
        t.traceEnd();

        // 启动URI授权管理服务（UriGrantsManagerService）
        t.traceBegin("UriGrantsManagerService");
        mSystemServiceManager.startService(UriGrantsManagerService.Lifecycle.class);
        t.traceEnd();

        // 启动电源统计服务（PowerStatsService）
        // 跟踪电源相关数据（如各硬件模块耗电）
        t.traceBegin("StartPowerStatsService");
        mSystemServiceManager.startService(PowerStatsService.class);
        t.traceEnd();

        // 启动IStats服务（原生统计服务）
        t.traceBegin("StartIStatsService");
        startIStatsService();
        t.traceEnd();

        // 启动MemtrackProxy服务（内存跟踪代理服务）
        // 需在AMS之前启动，避免早期内存跟踪调用失败
        t.traceBegin("MemtrackProxyService");
        startMemtrackProxyService();
        t.traceEnd();

        // 启动权限访问检查服务（AccessCheckingService）
        // 提供权限和AppOp的新实现
        t.traceBegin("StartAccessCheckingService");
        LocalServices.addService(PermissionMigrationHelper.class,
                new PermissionMigrationHelperImpl());
        LocalServices.addService(AppOpMigrationHelper.class,
                new AppOpMigrationHelperImpl());
        mSystemServiceManager.startService(AccessCheckingService.class);
        t.traceEnd();

        // 启动ActivityTaskManagerService（ATMS）和ActivityManagerService（AMS）
        // AMS是系统核心服务，管理Activity、进程、内存等
        t.traceBegin("StartActivityManager");
        ActivityTaskManagerService atm = mSystemServiceManager.startService(
                ActivityTaskManagerService.Lifecycle.class).getService();
        mActivityManagerService = ActivityManagerService.Lifecycle.startService(
                mSystemServiceManager, atm);
        mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
        mActivityManagerService.setInstaller(installer);
        mWindowManagerGlobalLock = atm.getGlobalLock(); // 获取窗口管理全局锁
        t.traceEnd();

        // 启动数据加载管理服务（DataLoaderManagerService）
        // 需在PMS之前启动（应用安装可能依赖数据加载）
        t.traceBegin("StartDataLoaderManagerService");
        mDataLoaderManagerService = mSystemServiceManager.startService(
                DataLoaderManagerService.class);
        t.traceEnd();

        // 启动Incremental服务（原生增量服务）
        // 需在PMS之前启动（应用增量安装依赖）
        t.traceBegin("StartIncrementalService");
        mIncrementalServiceHandle = startIncrementalService();
        t.traceEnd();

        // 启动电源管理服务（PowerManagerService，PMS）
        // 其他服务依赖电源管理功能，需早期启动
        // 原生守护进程可能监听此服务注册，需立即响应Binder调用
        t.traceBegin("StartPowerManager");
        mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);
        t.traceEnd();

        // 启动热管理服务（ThermalManagerService）
        t.traceBegin("StartThermalManager");
        mSystemServiceManager.startService(ThermalManagerService.class);
        t.traceEnd();

        // AMS初始化电源管理相关功能（依赖PMS已启动）
        t.traceBegin("InitPowerManagement");
        mActivityManagerService.initPowerManagement();
        t.traceEnd();

        // 启动恢复系统服务（RecoverySystemService）
        // 用于救援模式重启等场景
        t.traceBegin("StartRecoverySystemService");
        mSystemServiceManager.startService(RecoverySystemService.Lifecycle.class);
        t.traceEnd();

        // 初始化救援党（RescueParty），监控系统健康状态
        if (!Flags.refactorCrashrecovery()) {
            CrashRecoveryAdaptor.rescuePartyRegisterHealthObserver(mSystemContext);
        }

        // 启动灯光服务（LightsService）
        // 管理LED和显示屏背光，需在显示启动前启动
        t.traceBegin("StartLightsService");
        mSystemServiceManager.startService(LightsService.class);
        t.traceEnd();

        // 启动显示卸载服务（WearDisplayOffloadService）
        // 仅Wear设备启用（通过系统属性控制）
        t.traceBegin("StartDisplayOffloadService");
        if (SystemProperties.getBoolean("config.enable_display_offload", false)) {
            mSystemServiceManager.startService(WEAR_DISPLAYOFFLOAD_SERVICE_CLASS);
        }
        t.traceEnd();

        // 启动显示管理服务（DisplayManagerService，DMS）
        // PMS启动前需要显示指标信息
        t.traceBegin("StartDisplayManager");
        mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);
        t.traceEnd();

        // 等待默认显示就绪（PMS依赖默认显示信息）
        t.traceBegin("WaitForDisplay");
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        t.traceEnd();

        // 记录PMS初始化开始事件（非运行时重启场景）
        if (!mRuntimeRestart) {
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    FrameworkStatsLog
                            .BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__PACKAGE_MANAGER_INIT_START,
                    SystemClock.elapsedRealtime());
        }

        // 启动域名验证服务（DomainVerificationService）
        t.traceBegin("StartDomainVerificationService");
        DomainVerificationService domainVerificationService = new DomainVerificationService(
                mSystemContext, SystemConfig.getInstance(), platformCompat);
        mSystemServiceManager.startService(domainVerificationService);
        t.traceEnd();

        // 启动包管理服务（PackageManagerService，PMS）
        t.traceBegin("StartPackageManagerService");
        try {
            // 暂停看门狗监控当前线程（PMS初始化可能耗时）
            Watchdog.getInstance().pauseWatchingCurrentThread("packagemanagermain");
            mPackageManagerService = PackageManagerService.main(
                    mSystemContext, installer, domainVerificationService,
                    mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF);
        } finally {
            // 恢复看门狗监控
            Watchdog.getInstance().resumeWatchingCurrentThread("packagemanagermain");
        }

        mFirstBoot = mPackageManagerService.isFirstBoot(); // 标记是否为首次启动
        mPackageManager = mSystemContext.getPackageManager(); // 获取PackageManager实例
        t.traceEnd();

        // 初始化DexUseManagerLocal（Dex使用统计本地服务）
        // 需在PMS注册后、处理Binder调用前启动
        t.traceBegin("DexUseManagerLocal");
        LocalManagerRegistry.addManager(
                DexUseManagerLocal.class, DexUseManagerLocal.createInstance(mSystemContext));
        t.traceEnd();

        // 记录PMS初始化就绪事件（非运行时重启且非首次启动/升级）
        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    FrameworkStatsLog
                            .BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__PACKAGE_MANAGER_INIT_READY,
                    SystemClock.elapsedRealtime());
        }

        // 启动OTA Dex优化服务（OtaDexoptService）
        // 用于A/B OTA后的Dex优化，需在其他服务访问A/B artifacts前启动
        boolean disableOtaDexopt = SystemProperties.getBoolean("config.disable_otadexopt", false);
        if (!disableOtaDexopt) {
            t.traceBegin("StartOtaDexOptService");
            try {
                Watchdog.getInstance().pauseWatchingCurrentThread("moveab");
                OtaDexoptService.main(mSystemContext, mPackageManagerService);
            } catch (Throwable e) {
                reportWtf("starting OtaDexOptService", e);
            } finally {
                Watchdog.getInstance().resumeWatchingCurrentThread("moveab");
                t.traceEnd();
            }
        }

        // ARC设备：启动ARC系统健康服务
        if (Build.IS_ARC) {
            t.traceBegin("StartArcSystemHealthService");
            mSystemServiceManager.startService(ARC_SYSTEM_HEALTH_SERVICE);
            t.traceEnd();
        }

        // 启动用户管理服务（UserManagerService）
        t.traceBegin("StartUserManagerService");
        mSystemServiceManager.startService(UserManagerService.LifeCycle.class);
        t.traceEnd();

        // 初始化属性缓存（AttributeCache）
        // 用于缓存应用资源属性，提升性能
        t.traceBegin("InitAttributerCache");
        AttributeCache.init(mSystemContext);
        t.traceEnd();

        // AMS设置为系统进程（初始化系统进程相关状态）
        t.traceBegin("SetSystemProcess");
        mActivityManagerService.setSystemProcess();
        t.traceEnd();

        // 注册包接收器（依赖AMS已启动）
        platformCompat.registerPackageReceiver(mSystemContext);

        // 完成看门狗初始化（关联AMS，监听重启广播）
        // 需在AMS作为系统进程启动后执行
        t.traceBegin("InitWatchdog");
        watchdog.init(mSystemContext, mActivityManagerService);
        t.traceEnd();

        // DMS设置调度策略（AMS的setProcessGroup可能覆盖策略）
        mDisplayManagerService.setupSchedulerPolicies();

        // 启动覆盖层管理服务（OverlayManagerService）
        // 管理应用覆盖层（如主题、资源替换）
        t.traceBegin("StartOverlayManagerService");
        mSystemServiceManager.startService(new OverlayManagerService(mSystemContext));
        t.traceEnd();

        // 启动资源管理服务（ResourcesManagerService）
        t.traceBegin("StartResourcesManagerService");
        ResourcesManagerService resourcesService = new ResourcesManagerService(mSystemContext