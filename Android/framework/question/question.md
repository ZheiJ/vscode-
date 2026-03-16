# Android 系统 Framework 面试题全解析

> 本文档整理了 140 道 Android 系统 Framework 开发面试题及答案解析
> 涵盖：Binder、JNI、系统启动流程、AMS、WMS、PMS、传感器、音频系统、设备树、Recovery 等核心知识点

---

## 目录

1. [Binder 机制](#1-binder-机制)
2. [JNI](#2-jni)
3. [Android 系统启动流程](#3-android-系统启动流程)
4. [AMS (ActivityManagerService)](#4-ams-activitymanagerservice)
5. [WMS (WindowManagerService)](#5-wms-windowmanagerservice)
6. [PMS (PackageManagerService)](#6-pms-packagemanagerservice)
7. [传感器系统](#7-传感器系统)
8. [音频系统框架](#8-音频系统框架)
9. [Kernel DTS 解析](#9-kernel-dts-解析)
10. [Recovery 框架](#10-recovery-框架)

---

## 1. Binder 机制

### 1.1 请介绍什么是 Binder 机制 ⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Binder** 是 Android 系统中一种高效的跨进程通信（IPC）机制。它是 Android 系统的核心基础，几乎所有系统服务都通过 Binder 进行通信。

**核心特点：**

- **基于 C/S 架构**：客户端通过 Binder 驱动与服务端通信
- **一次内存拷贝**：相比传统 IPC 的两次拷贝，效率更高
- **安全性高**：支持发送方 UID/PID 校验
- **基于 mmap**：利用内存映射实现高效数据传输

**架构组成：**

- **Binder 驱动**：运行在内核空间，负责进程间数据传输
- **ServiceManager**：管理系统服务的注册和查询
- **Binder 客户端**：通过代理对象访问远程服务
- **Binder 服务端**：提供具体服务实现

</details>

---

### 1.2 请介绍 Binder 机制流程 ⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Binder 通信流程：**

```
Client -> Binder Proxy -> Binder Driver -> Binder Stub -> Server
```

**详细流程：**

1. **服务注册**：服务端通过 `ServiceManager.addService()` 注册服务
2. **服务获取**：客户端通过 `ServiceManager.getService()` 获取服务代理
3. **数据打包**：客户端将请求数据通过 Parcel 打包
4. **内核传输**：Binder 驱动将数据从客户端进程拷贝到服务端进程
5. **方法调用**：服务端 Stub 解包数据并调用实际方法
6. **结果返回**：服务端将结果通过 Binder 驱动返回给客户端

**关键步骤：**

- `transact()`：客户端发起调用
- `onTransact()`：服务端接收并处理调用
- `mmap()`：建立共享内存映射

</details>

---

### 1.3 Binder 有什么优势？（字节跳动）⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

| 特性           | Binder        | Socket     | Pipe   | 共享内存     |
| -------------- | ------------- | ---------- | ------ | ------------ |
| **拷贝次数**   | 1 次          | 2 次       | 2 次   | 0 次         |
| **通信方式**   | 基于 C/S      | C/S 或 P2P | 半双工 | 需要同步机制 |
| **安全性**     | 高（UID/PID） | 低         | 低     | 低           |
| **性能**       | 高            | 中         | 中     | 高（但复杂） |
| **使用复杂度** | 中等          | 低         | 低     | 高           |

**Binder 的核心优势：**

1. **高效的数据传输**
   - 只需一次内存拷贝（用户空间 -> 内核空间）
   - 接收方直接访问内核空间数据，无需再次拷贝

2. **安全性保障**
   - 内核自动添加进程身份信息（UID/PID）
   - 支持权限验证机制

3. **基于对象的通信**
   - 可以传递对象引用，而不仅仅是数据
   - 支持远程对象的代理模式

4. **生命周期管理**
   - 自动管理 Binder 对象的引用计数
   - 死亡通知机制（DeathRecipient）

</details>

---

### 1.4 Binder 机制需要多少次内存拷贝 ⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Binder 机制只需要 1 次内存拷贝。**

**传统 IPC 机制（如管道、Socket）：**

```
发送方用户空间 -> 内核空间（第1次拷贝）
内核空间 -> 接收方用户空间（第2次拷贝）
```

**Binder 机制：**

```
发送方用户空间 -> 内核空间（第1次拷贝）
接收方直接访问内核空间的映射区域（无需拷贝）
```

**原理：**

- Binder 使用 `mmap()` 将接收方用户空间映射到内核空间
- 发送方数据拷贝到内核空间时，接收方可以直接访问
- 避免了从内核空间到用户空间的第二次拷贝

</details>

---

### 1.5 Binder 是如何做到一次拷贝？（腾讯）⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**核心原理：内存映射（mmap）**

```
发送进程                    Binder 驱动                    接收进程
    |                           |                            |
    |  1. 数据拷贝到内核空间     |                            |
    | ------------------------> |                            |
    |                           |  2. 映射到接收进程虚拟地址   |
    |                           | -------------------------> |
    |                           |                            |  直接访问
```

**具体实现：**

1. **接收方准备阶段**
   - 接收方通过 `mmap()` 分配一块物理内存
   - 这块内存同时映射到接收方用户空间和内核空间

2. **数据传输阶段**
   - 发送方将数据拷贝到内核空间的共享区域
   - 由于内存映射，接收方用户空间可以直接读取

3. **关键代码逻辑**
   ```cpp
   // 接收方 mmap 分配内存
   binder_mmap(struct file *filp, struct vm_area_struct *vma) {
       // 分配物理页
       // 映射到用户空间和内核空间
   }
   ```

**优势：**

- 减少一次数据拷贝，提升性能
- 减少内存占用
- 降低 CPU 使用率

</details>

---

### 1.6 Android 有很多跨进程通信方法，为何选择 Binder？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Android 选择 Binder 的主要原因：**

1. **性能考虑**
   - 移动设备资源有限，Binder 的高效性（1 次拷贝）更适合
   - 相比 Socket/Pipe 的 2 次拷贝，性能提升明显

2. **安全性要求**
   - Android 是多用户系统，需要严格的进程隔离
   - Binder 在内核层提供 UID/PID 身份验证
   - 防止恶意进程伪造身份

3. **架构设计**
   - Android 采用微内核设计，大量系统服务需要 IPC
   - Binder 的 C/S 架构符合系统服务设计模式
   - 支持面向对象的远程调用

4. **功能特性**
   - 支持同步和异步调用
   - 支持线程池管理
   - 死亡通知机制
   - 引用计数自动管理

5. **历史因素**
   - Binder 源于 BeOS 和 PalmOS
   - Google 收购 Android 前就已集成 Binder
   - 经过多年优化，已成为 Android 核心基础设施

</details>

---

### 1.7 MMAP 的原理讲解（腾讯）⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**MMAP（Memory Map）内存映射原理**

**基本概念：**
MMAP 是一种将文件或设备映射到进程虚拟地址空间的机制，使得对内存的访问等同于对文件的读写。

**工作原理：**

```
进程虚拟地址空间              物理内存
      |                          |
      |  虚拟地址映射              |
      | ------------------------>|
      |                          |
      |  访问虚拟地址 = 访问物理页  |
      |                          |
```

**mmap() 系统调用：**

```c
void *mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset);
```

**参数说明：**

- `addr`：建议映射的起始地址（通常为 NULL，由内核决定）
- `length`：映射区域长度
- `prot`：内存保护标志（读/写/执行）
- `flags`：映射类型（共享/私有）
- `fd`：文件描述符
- `offset`：文件偏移量

**Binder 中的 mmap：**

```cpp
// Binder 驱动中的 mmap 实现
static int binder_mmap(struct file *filp, struct vm_area_struct *vma) {
    // 1. 分配物理内存页
    // 2. 建立用户空间到物理页的映射
    // 3. 建立内核空间到同一物理页的映射
    // 4. 返回映射后的虚拟地址
}
```

**优势：**

- 减少数据拷贝次数
- 实现进程间内存共享
- 延迟加载（按需分配物理页）

</details>

---

### 1.8 Binder 机制是如何跨进程的（阿里）⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Binder 跨进程通信的核心机制：**

**1. 内核驱动层**

```
用户进程A                    Binder 驱动                    用户进程B
    |                           |                            |
    |  ioctl(BINDER_WRITE_READ) |                            |
    | ------------------------> |                            |
    |                           |  查找目标进程               |
    |                           |  拷贝数据到目标进程缓冲区    |
    |                           | -------------------------> |
    |                           |                            | 唤醒等待线程
    |                           | <------------------------- |
    |  返回结果                  |                            |
    | <------------------------ |                            |
```

**2. 核心数据结构**

- `binder_proc`：每个使用 Binder 的进程对应一个
- `binder_thread`：每个 Binder 线程对应一个
- `binder_node`：Binder 实体（服务端）
- `binder_ref`：Binder 引用（客户端）

**3. 跨进程调用流程**

**Step 1 - 客户端发起调用：**

```java
// 获取服务代理
IBinder binder = ServiceManager.getService("service_name");
Parcel data = Parcel.obtain();
Parcel reply = Parcel.obtain();
binder.transact(code, data, reply, flags);
```

**Step 2 - Binder 驱动处理：**

- 根据 handle 查找目标进程
- 将数据从客户端拷贝到服务端映射的内存区域
- 唤醒服务端等待的线程

**Step 3 - 服务端处理：**

```java
@Override
protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
    // 处理远程调用
    return super.onTransact(code, data, reply, flags);
}
```

**4. 关键机制**

- **线程迁移**：Binder 调用会阻塞客户端线程，服务端在线程池中处理
- **Binder 协议**：基于 ioctl 的命令协议
- **死亡通知**：服务端崩溃时通知客户端

</details>

---

### 1.9 描述 AIDL 生成的 Java 类细节（字节跳动）⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**AIDL 生成的 Java 类结构：**

当编译 `.aidl` 文件时，会生成包含以下部分的 Java 类：

```java
public interface IMyService extends android.os.IInterface {

    // 1. 本地 Stub 类（服务端使用）
    public static abstract class Stub extends android.os.Binder implements IMyService {

        // Binder 描述符，唯一标识接口
        private static final java.lang.String DESCRIPTOR = "com.example.IMyService";

        // 事务代码（方法标识）
        static final int TRANSACTION_method1 = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
        static final int TRANSACTION_method2 = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);

        // 构造方法
        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        // 将 IBinder 转换为接口
        public static IMyService asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            // 同进程返回本地对象，跨进程返回代理
            return ((iin != null && iin instanceof IMyService))
                ? (IMyService) iin
                : new Proxy(obj);
        }

        // 处理远程调用（在服务端执行）
        @Override
        public boolean onTransact(int code, android.os.Parcel data,
                                  android.os.Parcel reply, int flags) {
            switch (code) {
                case TRANSACTION_method1:
                    data.enforceInterface(DESCRIPTOR);
                    // 读取参数
                    // 调用本地方法
                    // 写入返回值
                    return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    // 2. 代理类（客户端使用）
    private static class Proxy implements IMyService {
        private android.os.IBinder mRemote;

        Proxy(android.os.IBinder remote) {
            mRemote = remote;
        }

        @Override
        public void method1(String param) throws android.os.RemoteException {
            android.os.Parcel _data = android.os.Parcel.obtain();
            android.os.Parcel _reply = android.os.Parcel.obtain();
            try {
                _data.writeInterfaceToken(DESCRIPTOR);
                _data.writeString(param);
                // 发起远程调用
                mRemote.transact(Stub.TRANSACTION_method1, _data, _reply, 0);
                _reply.readException();
            } finally {
                _reply.recycle();
                _data.recycle();
            }
        }
    }
}
```

**关键点：**

1. **DESCRIPTOR**：接口的唯一标识符
2. **TRANSACTION\_\***：每个方法对应的命令码
3. **asInterface()**：智能判断同进程/跨进程
4. **onTransact()**：服务端处理远程请求
5. **transact()**：客户端发起远程调用

</details>

---

### 1.10 为什么 Intent 不能传递大数据（阿里）⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Intent 传递数据的大小限制：**

**1. 限制来源**

- Intent 底层使用 Binder 进行 IPC
- Binder 缓冲区大小限制为 **1MB - 8KB**（不同版本略有差异）
- 这个限制是进程共享的，所有 Binder 调用共用

**2. 具体限制**

```java
// frameworks/native/libs/binder/processState.cpp
#define BINDER_VM_SIZE ((1*1024*1024) - (4096 *2))  // 约 1MB
```

**3. 为什么不能传递大数据**

```
进程A                    Binder 驱动                    进程B
   |                         |                           |
   |  发送大数据 (>1MB)       |                           |
   | ----------------------> |                           |
   |                         |  缓冲区溢出！              |
   |                         |  返回 TRANSACTION_FAILED  |
   | <---------------------- |                           |
   |  抛出异常                |                           |
```

**4. 传递大数据的替代方案**

| 方案                | 适用场景     | 实现方式                              |
| ------------------- | ------------ | ------------------------------------- |
| **ContentProvider** | 文件/数据库  | 通过 URI 共享                         |
| **文件共享**        | 大文件       | 写入文件，传递路径                    |
| **Socket**          | 流式数据     | 建立本地 Socket 连接                  |
| **共享内存**        | 极高性能要求 | Ashmem/AIDL 支持 ParcelFileDescriptor |
| **EventBus/RxBus**  | 同进程内     | 内存引用传递                          |

**5. 最佳实践**

```java
// 错误做法：直接传递大数据
Intent intent = new Intent();
intent.putExtra("bitmap", largeBitmap); // 可能崩溃！

// 正确做法：使用 ContentProvider
Intent intent = new Intent();
intent.setData(contentUri); // 传递 URI

// 或使用文件
File file = saveToFile(largeBitmap);
intent.putExtra("file_path", file.getAbsolutePath());
```

</details>

---

## 2. JNI

### 2.1 阐述你对 JNI 的理解 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**JNI（Java Native Interface）** 是 Java 提供的一种机制，允许 Java 代码与本地代码（C/C++）进行交互。

**核心概念：**

**1. 设计目标**

- 实现 Java 与本地代码的双向调用
- 保持平台无关性
- 提供安全的内存访问机制

**2. 架构层次**

```
Java 层
   |
JNI 层（接口层）
   |
Native 层（C/C++）
   |
系统库 / 硬件
```

**3. 核心组件**

| 组件        | 说明                        |
| ----------- | --------------------------- |
| `JNIEnv`    | 接口指针，提供 JNI 函数访问 |
| `jobject`   | Java 对象引用               |
| `jclass`    | Java 类引用                 |
| `jmethodID` | 方法标识符                  |
| `jfieldID`  | 字段标识符                  |

**4. 数据类型映射**

| Java 类型 | Native 类型 | 签名               |
| --------- | ----------- | ------------------ |
| boolean   | jboolean    | Z                  |
| byte      | jbyte       | B                  |
| char      | jchar       | C                  |
| short     | jshort      | S                  |
| int       | jint        | I                  |
| long      | jlong       | J                  |
| float     | jfloat      | F                  |
| double    | jdouble     | D                  |
| Object    | jobject     | Lclass;            |
| String    | jstring     | Ljava/lang/String; |
| array     | jarray      | [type              |

**5. 使用场景**

- 性能敏感的操作（图像处理、数学计算）
- 访问平台特定功能
- 复用已有的 C/C++ 库
- 底层硬件访问

</details>

---

### 2.2 使用 JNI 有什么优点 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**JNI 的主要优点：**

**1. 性能优化**

```
计算密集型任务性能对比：
- Java: 100ms
- Native: 10-30ms（提升 3-10 倍）
```

- 避免 JVM 解释执行开销
- 直接编译为机器码执行
- 适合图像处理、音视频编解码等

**2. 平台特性访问**

- 访问操作系统底层 API
- 调用硬件驱动程序
- 使用平台特定的优化指令（SIMD/NEON）

**3. 代码复用**

- 复用现有的 C/C++ 库
- 跨平台共享核心算法
- 保护知识产权（Native 代码更难反编译）

**4. 实时性保障**

- 绕过 JVM GC 延迟
- 精确控制内存分配
- 适合实时性要求高的场景

**5. 灵活性**

```cpp
// 可以直接操作内存
void* buffer = malloc(size);
// 直接调用系统调用
syscall(SYS_ioctl, fd, cmd, arg);
```

**注意事项：**

- 增加代码复杂度
- 降低可移植性
- 需要处理内存管理
- 调试困难

</details>

---

### 2.3 JNI 的应用场景 ⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**JNI 的典型应用场景：**

**1. 音视频处理**

```cpp
// FFmpeg 集成
extern "C" JNIEXPORT void JNICALL
Java_com_example_MediaPlayer_nativeDecode(JNIEnv* env, jobject thiz, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    // 调用 FFmpeg 解码
    avcodec_decode_video2(codecContext, frame, &gotPicture, &packet);
}
```

**2. 图像处理**

- OpenCV 集成
- 自定义滤镜算法
- GPU 加速渲染

**3. 游戏开发**

- Unity/Unreal 引擎集成
- 物理引擎（Box2D、Bullet）
- 3D 图形渲染

**4. 加密算法**

```cpp
// 保护核心算法
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_Crypto_nativeEncrypt(JNIEnv* env, jobject thiz, jbyteArray data) {
    // 在 Native 层实现加密，提高安全性
}
```

**5. 数据库访问**

- SQLite 底层优化
- 自定义存储引擎

**6. 网络通信**

- 高性能网络库（如 libuv）
- 自定义协议实现

**7. 系统级操作**

- 文件系统监控
- 进程管理
- 设备控制

**8. 机器学习**

- TensorFlow Lite 集成
- 模型推理加速

</details>

---

### 2.4 什么是 JNI？具体说说如何实现 Java 与 C++ 的互调 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**JNI 实现 Java 与 C++ 互调：**

**一、Java 调用 C++**

**Step 1 - 声明 Native 方法**

```java
public class NativeLib {
    // 加载 Native 库
    static {
        System.loadLibrary("native-lib");
    }

    // 声明 Native 方法
    public native String stringFromJNI();
    public native int add(int a, int b);
}
```

**Step 2 - 生成头文件**

```bash
javac NativeLib.java
javah -jni NativeLib  # 生成 NativeLib.h
```

**Step 3 - 实现 Native 方法**

```cpp
// native-lib.cpp
#include "NativeLib.h"

extern "C" JNIEXPORT jstring JNICALL
Java_NativeLib_stringFromJNI(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("Hello from C++");
}

extern "C" JNIEXPORT jint JNICALL
Java_NativeLib_add(JNIEnv* env, jobject thiz, jint a, jint b) {
    return a + b;
}
```

**Step 4 - 编译为动态库**

```cmake
# CMakeLists.txt
cmake_minimum_required(VERSION 3.4.1)
add_library(native-lib SHARED native-lib.cpp)
find_library(log-lib log)
target_link_libraries(native-lib ${log-lib})
```

---

**二、C++ 调用 Java**

**Step 1 - 获取 JNIEnv**

```cpp
// 在 Native 方法中，JNIEnv 作为参数传入
void nativeMethod(JNIEnv* env, jobject thiz) {
    // 使用 env 调用 Java 方法
}
```

**Step 2 - 查找类和获取方法 ID**

```cpp
// 获取类
jclass clazz = env->FindClass("com/example/MyClass");

// 获取方法 ID
jmethodID methodId = env->GetMethodID(clazz, "javaMethod", "(Ljava/lang/String;)V");

// 获取字段 ID
jfieldID fieldId = env->GetFieldID(clazz, "fieldName", "Ljava/lang/String;");
```

**Step 3 - 调用 Java 方法**

```cpp
// 创建对象
jobject obj = env->NewObject(clazz, constructorId);

// 调用实例方法
env->CallVoidMethod(obj, methodId, param);

// 调用静态方法
jmethodID staticMethod = env->GetStaticMethodID(clazz, "staticMethod", "()I");
jint result = env->CallStaticIntMethod(clazz, staticMethod);

// 访问字段
jstring value = (jstring)env->GetObjectField(obj, fieldId);
env->SetIntField(obj, intFieldId, 100);
```

**Step 4 - 处理异常和释放资源**

```cpp
// 检查异常
if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
}

// 释放本地引用
env->DeleteLocalRef(clazz);
env->DeleteLocalRef(obj);
```

</details>

---

### 2.5 什么是 NDK？为什么要使用 NDK？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**NDK（Native Development Kit）**

**定义：**
NDK 是 Android 提供的一套工具集，允许开发者使用 C 和 C++ 代码来开发 Android 应用。

**NDK 包含：**

- 交叉编译工具链（GCC/Clang）
- 构建系统（CMake/ndk-build）
- 平台库和头文件
- 调试工具

---

**为什么要使用 NDK：**

| 原因         | 说明                           |
| ------------ | ------------------------------ |
| **性能优化** | 计算密集型任务提升 3-10 倍性能 |
| **代码保护** | Native 代码比 Java 更难反编译  |
| **库复用**   | 复用现有的 C/C++ 开源库        |
| **跨平台**   | 核心代码可在多平台共享         |
| **底层访问** | 直接访问硬件和系统底层         |

---

**NDK 使用流程：**

```
1. 安装 NDK
   Android Studio -> SDK Manager -> SDK Tools -> NDK

2. 配置 build.gradle
   android {
       defaultConfig {
           externalNativeBuild {
               cmake {
                   cppFlags "-std=c++11"
               }
           }
       }
       externalNativeBuild {
           cmake {
               path "src/main/cpp/CMakeLists.txt"
           }
       }
   }

3. 编写 C++ 代码
   src/main/cpp/native-lib.cpp

4. 编写 CMakeLists.txt
   cmake_minimum_required(VERSION 3.4.1)
   add_library(native-lib SHARED native-lib.cpp)

5. 构建项目
   Gradle 自动编译 Native 代码
```

**注意事项：**

- NDK 增加 APK 体积
- 增加开发和维护复杂度
- 需要处理不同 CPU 架构（armeabi-v7a, arm64-v8a, x86 等）

</details>

---

### 2.6 JNI 开发的一般步骤是？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**JNI 开发完整步骤：**

**Step 1: 创建 Java 类声明 Native 方法**

```java
public class JniHelper {
    static {
        System.loadLibrary("jni-helper");
    }

    public native void nativeInit();
    public native String processData(String input);
    public native byte[] encrypt(byte[] data);
}
```

**Step 2: 生成 JNI 头文件**

```bash
# 编译 Java 文件
javac JniHelper.java

# 生成头文件
javah -jni com.example.JniHelper
# 或使用 javac -h
javac -h . JniHelper.java
```

**Step 3: 实现 Native 代码**

```cpp
// jni_helper.cpp
#include "com_example_JniHelper.h"
#include <jni.h>
#include <string>

extern "C" JNIEXPORT void JNICALL
Java_com_example_JniHelper_nativeInit(JNIEnv* env, jobject thiz) {
    // 初始化操作
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_JniHelper_processData(JNIEnv* env, jobject thiz, jstring input) {
    const char* str = env->GetStringUTFChars(input, nullptr);
    std::string result = "Processed: " + std::string(str);
    env->ReleaseStringUTFChars(input, str);
    return env->NewStringUTF(result.c_str());
}
```

**Step 4: 配置构建脚本**

```cmake
# CMakeLists.txt
cmake_minimum_required(VERSION 3.4.1)

add_library(jni-helper SHARED jni_helper.cpp)

find_library(log-lib log)

# 链接库
target_link_libraries(jni-helper ${log-lib})
```

**Step 5: 配置 Gradle**

```gradle
android {
    defaultConfig {
        ndk {
            abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86'
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
        }
    }
}
```

**Step 6: 加载和使用**

```java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        JniHelper helper = new JniHelper();
        helper.nativeInit();
        String result = helper.processData("Hello JNI");
        Log.d("JNI", result);
    }
}
```

</details>

---

### 2.7 JNI 函数的注册方法都有什么？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**JNI 函数注册的两种方式：**

#### 1. 静态注册

**原理：**
根据方法名自动匹配，遵循命名规则：`Java_包名_类名_方法名`

**示例：**

```cpp
// Java 方法：com.example.JniHelper.add(int, int)
// Native 实现：
extern "C" JNIEXPORT jint JNICALL
Java_com_example_JniHelper_add(JNIEnv* env, jobject thiz, jint a, jint b) {
    return a + b;
}
```

**特点：**

- 简单易用，自动生成
- 方法名冗长，包含完整包名
- 运行时有查找开销

---

#### 2. 动态注册

**原理：**
在 `JNI_OnLoad` 中手动注册方法映射表

**示例：**

```cpp
#include <jni.h>

// Native 方法实现
jint nativeAdd(JNIEnv* env, jobject thiz, jint a, jint b) {
    return a + b;
}

jstring nativeGetString(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("Dynamic Register");
}

// 方法映射表
static JNINativeMethod gMethods[] = {
    {"add", "(II)I", (void*)nativeAdd},
    {"getString", "()Ljava/lang/String;", (void*)nativeGetString},
};

// 在 JNI_OnLoad 中注册
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/example/JniHelper");
    if (clazz == nullptr) {
        return JNI_ERR;
    }

    // 注册 Native 方法
    jint result = env->RegisterNatives(clazz, gMethods,
                                        sizeof(gMethods) / sizeof(JNINativeMethod));
    if (result != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
```

**JNINativeMethod 结构：**

```c
typedef struct {
    const char* name;      // Java 方法名
    const char* signature; // 方法签名
    void* fnPtr;          // Native 函数指针
} JNINativeMethod;
```

**特点：**

- 方法名更简洁
- 运行时性能更好（无需查找）
- 需要手动维护映射表
- 适合大型项目

---

#### 对比

| 特性       | 静态注册   | 动态注册 |
| ---------- | ---------- | -------- |
| 实现复杂度 | 简单       | 较复杂   |
| 方法名长度 | 长         | 短       |
| 运行时性能 | 有查找开销 | 直接调用 |
| 灵活性     | 低         | 高       |
| 维护成本   | 低         | 高       |

</details>

---

### 2.8 谈谈你对 JNI 静态注册和动态注册的区别 ⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**静态注册 vs 动态注册 详细对比：**

#### 核心区别

| 维度         | 静态注册                         | 动态注册                  |
| ------------ | -------------------------------- | ------------------------- |
| **注册时机** | 首次调用 Native 方法时           | `System.loadLibrary()` 时 |
| **命名规范** | 必须遵循 `Java_包名_类名_方法名` | 任意命名                  |
| **实现位置** | 分散在各个文件中                 | 集中在 `JNI_OnLoad`       |
| **查找方式** | 运行时根据名字查找               | 直接通过函数指针调用      |

---

#### 静态注册详解

**工作流程：**

```
1. Java 调用 native 方法
2. JVM 在已加载的 so 中查找对应函数
3. 根据命名规则：Java_com_example_Class_method
4. 找到后建立映射，后续直接调用
```

**优点：**

- 简单直观，自动生成
- 不需要额外代码
- 适合小型项目

**缺点：**

- 方法名冗长
- 运行时查找有开销
- 方法重载需要特殊处理（加后缀）

---

#### 动态注册详解

**工作流程：**

```
1. System.loadLibrary() 加载 so
2. 调用 JNI_OnLoad() 函数
3. 在 JNI_OnLoad 中调用 RegisterNatives()
4. 建立 Java 方法名到 Native 函数的映射
```

**优点：**

- 方法名简洁
- 调用性能高
- 可以在运行时决定注册哪些方法
- 便于模块化设计

**缺点：**

- 需要手动维护映射表
- 代码量增加
- 注册失败需要额外处理

---

#### 使用建议

| 场景           | 推荐方式 |
| -------------- | -------- |
| 快速原型开发   | 静态注册 |
| 大型项目       | 动态注册 |
| 性能敏感       | 动态注册 |
| 需要运行时决定 | 动态注册 |
| 简单工具类     | 静态注册 |

</details>

---

## 3. Android 系统启动流程

### 3.1 你了解 Android 系统启动流程吗？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Android 系统启动完整流程：**

```
Boot ROM (固化在芯片中)
    ↓
Boot Loader (初始化硬件)
    ↓
Linux Kernel (加载驱动)
    ↓
Init 进程 (第一个用户进程)
    ↓
Zygote 进程 (应用进程孵化器)
    ↓
System Server (系统服务)
    ↓
System Services (AMS/WMS/PMS等)
    ↓
Launcher (桌面启动)
```

---

#### 详细阶段

##### 阶段 1: Boot ROM

```
1. 上电后，CPU 跳转到固定地址（0x00000000）
2. 执行 Boot ROM 代码（固化在芯片中）
3. 加载 Boot Loader 到 RAM
4. 跳转到 Boot Loader
```

##### 阶段 2: Boot Loader

```
1. 初始化硬件（CPU、内存、显示屏等）
2. 设置内核启动参数
3. 加载 Linux Kernel 到内存
4. 跳转到 Kernel 入口
```

##### 阶段 3: Linux Kernel

```
1. 初始化内核子系统
2. 加载设备驱动程序
3. 挂载根文件系统
4. 启动第一个用户进程 init
```

##### 阶段 4: Init 进程

```cpp
// system/core/init/init.cpp
int main(int argc, char** argv) {
    // 1. 创建和挂载基本文件系统
    mount("tmpfs", "/dev", "tmpfs", ...);

    // 2. 初始化属性系统
    property_init();

    // 3. 解析 init.rc 配置文件
    ActionManager& am = ActionManager::GetInstance();
    ServiceList& sm = ServiceList::GetInstance();
    LoadBootScripts(am, sm);

    // 4. 启动关键服务
    // - start zygote
    // - start servicemanager
}
```

##### 阶段 5: Zygote 进程

```cpp
// frameworks/base/cmds/app_process/app_main.cpp
int main(int argc, char* const argv[]) {
    // 1. 创建 AndroidRuntime
    AppRuntime runtime(argv[0], computeArgBlockSize(argc, argv));

    // 2. 启动 Java 虚拟机
    // 3. 注册 JNI 方法
    // 4. 调用 ZygoteInit.main()
}
```

**Zygote 主要职责：**

- 预加载常用类和资源
- 创建应用程序进程
- 通过 Socket 接收创建进程请求

##### 阶段 6: System Server

```java
// frameworks/base/services/java/com/android/server/SystemServer.java
public static void main(String[] args) {
    new SystemServer().run();
}

private void run() {
    // 1. 创建系统上下文
    createSystemContext();

    // 2. 启动各种系统服务
    startBootstrapServices();  // AMS、PMS等
    startCoreServices();       // 核心服务
    startOtherServices();      // 其他服务

    // 3. 进入 Looper 循环
    Looper.loop();
}
```

##### 阶段 7: Launcher 启动

```
1. AMS 启动 Home Activity
2. 加载桌面应用列表
3. 显示用户界面
4. 系统启动完成
```

</details>

---

### 3.2 system_server 为什么要在 Zygote 中启动，而不是由 init 直接启动呢？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**System Server 在 Zygote 中启动的原因：**

#### 核心原因：资源共享和快速启动

##### 1. 共享已预加载的资源

```
Zygote 预加载内容：
├── 预加载类（约 4000+ 个）
│   ├── android.* 包下的类
│   ├── java.* 包下的类
│   └── 系统资源类
├── 预加载资源
│   ├── 系统主题
│   ├── 常用 Drawable
│   └── 字符串资源
└── 共享库
    ├── libandroid.so
    ├── libandroid_runtime.so
    └── 其他系统库
```

**内存共享机制：**

```cpp
// Zygote 使用写时复制（COW）
// 子进程共享父进程的内存页，只在修改时才复制
```

##### 2. 启动速度优化

| 启动方式      | 启动时间 | 内存占用             |
| ------------- | -------- | -------------------- |
| init 直接启动 | 5-10s    | 需要重新加载所有资源 |
| Zygote fork   | 1-2s     | 共享预加载资源       |

##### 3. 避免重复初始化

```java
// ZygoteInit.java
public static void main(String argv[]) {
    // 这些只需执行一次
    preload();  // 预加载类和资源

    if (startSystemServer) {
        // fork 出 System Server
        Runnable r = forkSystemServer(...);
        if (r != null) {
            r.run();  // 在子进程中运行
            return;
        }
    }
}
```

##### 4. 统一的应用进程创建机制

```
System Server 和 App 进程创建方式一致：
- 都由 Zygote fork 产生
- 都继承预加载的资源
- 都使用相同的虚拟机配置
```

##### 5. 安全性考虑

```
Zygote 以 root 权限运行：
- 可以创建具有特定 UID 的进程
- System Server 需要系统权限
- 普通应用进程需要应用权限
```

---

#### 如果 init 直接启动会怎样？

```
问题：
1. 每个进程都要重新加载类库（内存浪费）
2. 启动时间大幅增加
3. 无法使用写时复制优化
4. 代码重复，维护困难
```

</details>

---

### 3.3 能说说具体是怎么导致死锁的吗？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Zygote 中启动 System Server 的死锁风险：**

#### 死锁场景分析

##### 场景：多线程 + fork

```cpp
// 假设 Zygote 中有多个线程
Thread 1 (主线程): 持有锁 A，准备 fork
Thread 2: 持有锁 B，等待锁 A
Thread 3: 持有锁 A 和 B
```

**fork 后的状态：**

```
父进程 (Zygote):
    - 锁 A: 被 Thread 1 持有
    - 锁 B: 被 Thread 2 持有

子进程 (System Server):
    - 锁 A: 被 Thread 1 持有（但 Thread 1 不存在了！）
    - 锁 B: 被 Thread 2 持有（但 Thread 2 不存在了！）
```

**死锁原因：**

```
子进程中：
- 锁 A 和锁 B 看起来被持有
- 但实际上持有它们的线程已经不存在
- 任何尝试获取这些锁的操作都会永远阻塞
```

---

#### Android 的解决方案

##### 方案 1: fork 前暂停其他线程

```cpp
// dalvik/vm/heap.cpp
int dvmFork() {
    // 1. 暂停所有其他线程
    dvmSuspendAllThreads(SUSPEND_FOR_GC);

    // 2. 执行 fork
    pid_t pid = fork();

    if (pid == 0) {
        // 子进程
        // 3. 清理线程状态
        dvmResetThreadStates();
    } else {
        // 父进程
        // 4. 恢复线程
        dvmResumeAllThreads();
    }

    return pid;
}
```

##### 方案 2: 使用特殊锁机制

```cpp
// pthread_atfork 机制
pthread_atfork(
    prepare_handler,   // fork 前调用：获取所有锁
    parent_handler,    // fork 后父进程调用：释放锁
    child_handler      // fork 后子进程调用：释放锁
);
```

##### 方案 3: Zygote 单线程设计

```java
// Zygote 设计为单线程模式
// 大部分工作在 fork 前完成
// fork 后才创建多线程
```

---

#### 具体死锁案例

```cpp
// 假设情况
class ResourceManager {
    Mutex mLock;

    void doSomething() {
        AutoMutex _l(mLock);  // 获取锁
        // ... 做一些操作
    }
};

// 线程 1 执行
void thread1() {
    gResourceManager.doSomething();  // 持有 mLock
    // 此时发生 fork
}

// fork 后子进程中
void childProcess() {
    gResourceManager.doSomething();  // 永远阻塞！
    // 因为 mLock 看起来被持有，但持有者已不存在
}
```

</details>

---

### 3.4 Zygote 为什么不采用 Binder 机制进行 IPC 通信？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Zygote 不使用 Binder 而使用 Socket 的原因：**

#### 核心原因

##### 1. 避免 Binder 驱动依赖

```
系统启动时序：
1. Kernel 启动
2. Init 进程启动
3. Zygote 启动
4. ServiceManager 启动（提供 Binder 服务注册）
5. System Server 启动（使用 Binder）

问题：Zygote 启动时，Binder 驱动已就绪，但 ServiceManager 还未启动
```

##### 2. 简单可靠的通信需求

**Zygote 的通信特点：**

```
- 通信简单：接收创建进程请求
- 单向为主：接收命令 -> 执行 fork
- 低频通信：只在创建应用时通信
- 本地通信：与 system_server 同机通信
```

**Socket 足够满足需求：**

```cpp
// 创建 ServerSocket
zygoteSocket = new LocalServerSocket(ZYGOTE_SOCKET);

// 等待连接
ZygoteConnection peer = zygoteSocket.accept();

// 读取命令
String[] args = peer.readArgumentList();

// 执行 fork
pid = Zygote.forkAndSpecialize(...);
```

##### 3. 避免 Binder 的复杂性

| 特性         | Binder                    | Socket         |
| ------------ | ------------------------- | -------------- |
| 初始化复杂度 | 高（需要 ServiceManager） | 低             |
| 线程模型     | 复杂（线程池）            | 简单（阻塞式） |
| 内存管理     | 需要 mmap                 | 内核自动管理   |
| 适用场景     | 复杂的 C/S 通信           | 简单的命令传输 |

##### 4. 安全性考虑

```
Zygote Socket 特点：
- 使用 Unix Domain Socket（本地通信）
- 文件权限控制访问
- 只有 system_server 可以连接
- 避免网络攻击风险
```

---

#### 为什么不能等 ServiceManager 启动后再用 Binder？

```
时序问题：
1. Zygote 必须先启动（孵化 System Server）
2. System Server 启动 ServiceManager
3. 如果 Zygote 等 ServiceManager，则产生循环依赖

解决方案：
- Zygote 使用 Socket（不依赖 ServiceManager）
- System Server 启动后使用 Binder
```

---

#### 总结

| 原因     | 说明                            |
| -------- | ------------------------------- |
| 启动时序 | Zygote 早于 ServiceManager 启动 |
| 需求匹配 | Socket 足以满足简单通信需求     |
| 复杂度   | 避免引入不必要的 Binder 复杂性  |
| 安全性   | Unix Domain Socket 更安全       |

</details>

---

### 3.5 请简述从点击图标到 app 启动的流程 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**从点击图标到 App 启动的完整流程：**

```
用户点击图标
    ↓
Launcher 捕获点击事件
    ↓
Launcher 请求 AMS 启动 Activity
    ↓
AMS 检查进程是否存在
    ├─ 不存在 → 请求 Zygote fork 新进程
    └─ 存在 → 直接使用现有进程
    ↓
创建 Application 和 Activity
    ↓
执行生命周期方法
    ↓
显示界面
```

---

#### 详细流程

##### Step 1: Launcher 处理点击

```java
// Launcher3 中的点击处理
public void onClick(View v) {
    Object tag = v.getTag();
    if (tag instanceof ShortcutInfo) {
        // 获取 Intent
        Intent intent = ((ShortcutInfo) tag).getIntent();

        // 启动应用
        startActivity(intent);
    }
}
```

##### Step 2: 调用 startActivity

```java
// Activity.java
public void startActivity(Intent intent) {
    startActivityForResult(intent, -1);
}

// 最终调用 Instrumentation
Instrumentation.ActivityResult ar =
    mInstrumentation.execStartActivity(
        this, mMainThread.getApplicationThread(), ...);
```

##### Step 3: 进入 AMS

```java
// ActivityTaskManagerService.java
@Override
public int startActivity(IApplicationThread caller, String callingPackage,
        Intent intent, ...) {
    return mActivityStartController.obtainStarter(intent, "startActivityAsUser")
        .setCaller(caller)
        .setCallingPackage(callingPackage)
        .execute();
}
```

##### Step 4: 检查进程状态

```java
// ActivityManagerService.java
final ProcessRecord startProcessLocked(...) {
    // 检查目标进程是否存在
    ProcessRecord app = getProcessRecordLocked(processName, info.uid);

    if (app != null && app.pid > 0) {
        // 进程已存在，复用
        return app;
    }

    // 进程不存在，需要创建
    startProcessLocked(app, ...);
}
```

##### Step 5: 请求 Zygote 创建进程

```java
// ProcessList.java
private Process.ProcessStartResult startProcess(...) {
    // 通过 Socket 发送请求给 Zygote
    return zygoteProcess.start(...);
}

// ZygoteProcess.java
private Process.ProcessStartResult attemptZygoteSendArgsLocked(...)
        throws ZygoteStartFailedEx {
    // 写入命令到 Zygote Socket
    writer.write(arg);
    // 读取 fork 结果
    result = mZygoteInputStream.readInt();
}
```

##### Step 6: Zygote fork 进程

```cpp
// Zygote 接收到请求
pid_t pid = fork();

if (pid == 0) {
    // 子进程
    // 1. 设置进程参数
    // 2. 初始化运行时
    // 3. 执行 ActivityThread.main()
}
```

##### Step 7: 新进程初始化

```java
// ActivityThread.java
public static void main(String[] args) {
    // 1. 创建主线程 Looper
    Looper.prepareMainLooper();

    // 2. 创建 ActivityThread
    ActivityThread thread = new ActivityThread();
    thread.attach(false, startSeq);

    // 3. 进入消息循环
    Looper.loop();
}

private void attach(boolean system, long startSeq) {
    // 向 AMS 注册应用进程
    final IActivityManager mgr = ActivityManager.getService();
    mgr.attachApplication(mAppThread, startSeq);
}
```

##### Step 8: AMS 通知创建 Activity

```java
// ActivityManagerService.java
@Override
public final void attachApplication(IApplicationThread thread, long startSeq) {
    // 1. 绑定应用进程
    // 2. 创建 Application
    // 3. 启动目标 Activity
    mAtmInternal.attachApplication(appWindowToken, thread);
}
```

##### Step 9: 创建和启动 Activity

```java
// ActivityThread.java
@Override
public void handleMessage(Message msg) {
    switch (msg.what) {
        case LAUNCH_ACTIVITY:
            handleLaunchActivity(r, null, "LAUNCH_ACTIVITY");
            break;
    }
}

private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
    // 1. 创建 Activity 实例
    Activity activity = mInstrumentation.newActivity(cl, component, r.intent);

    // 2. 创建 Application
    Application app = r.packageInfo.makeApplication(false, mInstrumentation);

    // 3.  attach Activity
    activity.attach(appContext, this, ...);

    // 4. 调用 onCreate
    mInstrumentation.callActivityOnCreate(activity, r.state);

    return activity;
}
```

##### Step 10: 显示界面

```java
// Activity.java
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);  // 设置布局

    // 执行 onStart
    // 执行 onResume
    // 界面显示到屏幕
}
```

---

#### 时序图

```
Launcher    AMS    Zygote    新进程    Activity
   |         |        |         |         |
   |----1--->|        |         |         |  startActivity
   |         |----2-->|         |         |  请求创建进程
   |         |        |----3--->|         |  fork
   |         |        |         |----4--->|  attach
   |         |<-------|<--------|----5----|  进程就绪
   |         |----6------------->|         |  创建 Activity
   |         |                  |----7--->|  onCreate
   |         |<-----------------|<---8----|  启动完成
```

</details>

---

### 3.6 说说 Activity 加载的流程 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Activity 加载的完整流程：**

```
AMS 决定启动 Activity
    ↓
计算启动模式（LaunchMode）
    ↓
查找或创建 Task
    ↓
暂停当前 Activity
    ↓
创建或复用 Activity 实例
    ↓
执行生命周期回调
    ↓
创建 Window 和 DecorView
    ↓
加载布局（setContentView）
    ↓
执行 onResume
    ↓
添加 Window 到 WMS
    ↓
界面显示
```

---

#### 详细流程

##### Step 1: AMS 处理启动请求

```java
// ActivityStarter.java
int execute() {
    // 1. 解析 Intent
    mRequest.resolveActivity(mSupervisor);

    // 2. 检查权限
    if (mService.mPermissionReviewRequired) {
        // 权限检查
    }

    // 3. 计算启动模式
    int launchFlags = mRequest.intent.getFlags();

    // 4. 查找 Task
    ActivityRecord reusedActivity = getReusableIntentActivity();

    // 5. 启动 Activity
    return startActivityUnchecked(r, ...);
}
```

##### Step 2: 处理 LaunchMode

```java
// ActivityStarter.java
private int startActivityUnchecked(...) {
    // 根据 LaunchMode 决定如何启动
    switch (mLaunchMode) {
        case LAUNCH_SINGLE_TOP:
            // 栈顶复用
            if (topActivity.realActivity.equals(r.realActivity)) {
                // 复用栈顶 Activity
                deliverNewIntent(topActivity);
                return START_DELIVERED_TO_TOP;
            }
            break;

        case LAUNCH_SINGLE_TASK:
            // 栈内复用
            ActivityRecord taskTop = findActivityInStack(r);
            if (taskTop != null) {
                // 清空其上所有 Activity
                // 复用该 Activity
            }
            break;

        case LAUNCH_SINGLE_INSTANCE:
            // 独立任务栈
            // 查找是否有包含该 Activity 的任务
            break;
    }
}
```

##### Step 3: 暂停当前 Activity

```java
// ActivityStack.java
boolean startPausingLocked(boolean userLeaving, boolean uiSleeping,
        ActivityRecord resuming, boolean pauseImmediately) {

    ActivityRecord prev = mResumedActivity;

    // 发送暂停消息
    if (prev.app != null && prev.app.thread != null) {
        prev.app.thread.schedulePauseActivity(prev.appToken, userLeaving,
                (prev.info.flags & FLAG_NO_HISTORY) != 0, ...);
    }
}

// ActivityThread.java
public final void schedulePauseActivity(IBinder token, boolean finished,
        boolean userLeaving, int configChanges, boolean dontReport, int seq) {
    sendMessage(PAUSE_ACTIVITY, token, ...);
}
```

##### Step 4: 创建 Activity 实例

```java
// ActivityThread.java
private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
    // 1. 获取 ComponentName
    ComponentName component = r.intent.getComponent();

    // 2. 创建 Activity 实例
    java.lang.ClassLoader cl = appContext.getClassLoader();
    Activity activity = mInstrumentation.newActivity(cl, component.getClassName(), r.intent);

    // 3. 创建 Application
    Application app = r.packageInfo.makeApplication(false, mInstrumentation);

    // 4. 创建 Context
    ContextImpl appContext = createBaseContextForActivity(r);

    // 5. attach Activity
    activity.attach(appContext, this, getInstrumentation(), r.token,
            r.ident, app, r.intent, r.activityInfo, ...);

    // 6. 设置主题
    int theme = r.activityInfo.getThemeResource();
    activity.setTheme(theme);

    // 7. 调用 onCreate
    if (r.isPersistable()) {
        mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
    } else {
        mInstrumentation.callActivityOnCreate(activity, r.state);
    }

    return activity;
}
```

##### Step 5: Activity.attach()

```java
// Activity.java
final void attach(Context context, ActivityThread aThread,
        Instrumentation instr, IBinder token, int ident,
        Application application, Intent intent, ActivityInfo info,
        CharSequence title, Activity parent, String id,
        NonConfigurationInstances lastNonConfigurationInstances,
        Configuration config, String referrer, IVoiceInteractor voiceInteractor,
        Window window, ActivityConfigCallback activityConfigCallback, IBinder assistToken,
        IBinder shareableActivityToken) {

    // 1. 创建 PhoneWindow
    mWindow = new PhoneWindow(this, window, activityConfigCallback);

    // 2. 设置 WindowManager
    mWindow.setWindowManager(
            (WindowManager)context.getSystemService(Context.WINDOW_SERVICE),
            mToken, mComponent.flattenToString(),
            (info.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0);

    // 3. 设置回调
    mWindow.setCallback(this);
    mWindow.setOnWindowDismissedCallback(this);
}
```

##### Step 6: onCreate 中设置布局

```java
// Activity.java
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // 设置布局
    setContentView(R.layout.activity_main);
}

public void setContentView(@LayoutRes int layoutResID) {
    getWindow().setContentView(layoutResID);
    initWindowDecorActionBar();
}
```

##### Step 7: PhoneWindow 创建 DecorView

```java
// PhoneWindow.java
@Override
public void setContentView(int layoutResID) {
    if (mContentParent == null) {
        // 1. 创建 DecorView
        installDecor();
    }

    // 2. 加载布局到 ContentParent
    mLayoutInflater.inflate(layoutResID, mContentParent);

    // 3. 回调 ContentChanged
    final Callback cb = getCallback();
    if (cb != null && !isDestroyed()) {
        cb.onContentChanged();
    }
}

private void installDecor() {
    mForceDecorInstall = false;
    if (mDecor == null) {
        // 创建 DecorView
        mDecor = generateDecor(-1);
        mDecor.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        mDecor.setIsRootNamespace(true);
    }

    if (mContentParent == null) {
        // 根据主题选择布局，创建 ContentParent
        mContentParent = generateLayout(mDecor);
    }
}
```

##### Step 8: 执行 onStart 和 onResume

```java
// ActivityThread.java
private void handleResumeActivity(IBinder token, boolean finalStateRequest,
        boolean isForward, String reason) {

    // 1. 执行 onResume
    final ActivityClientRecord r = performResumeActivity(token, finalStateRequest, reason);

    // 2. 获取 Window
    final Activity a = r.activity;
    final WindowManager wm = a.getWindowManager();
    View decor = r.window.getDecorView();

    // 3. 添加 View 到 WindowManager
    wm.addView(decor, l);

    // 4. 设置可见
    r.activity.makeVisible();
}
```

##### Step 9: 添加 Window 到 WMS

```java
// WindowManagerImpl.java
@Override
public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
    applyDefaultToken(params);
    mGlobal.addView(view, params, mContext.getDisplayNoVerify(), mParentWindow,
            mContext.getUserId());
}

// WindowManagerGlobal.java
public void addView(View view, ViewGroup.LayoutParams params,
        Display display, Window parentWindow, int userId) {

    // 创建 ViewRootImpl
    ViewRootImpl root = new ViewRootImpl(view.getContext(), display);

    // 设置布局参数
    view.setLayoutParams(wparams);

    // 保存记录
    mViews.add(view);
    mRoots.add(root);
    mParams.add(wparams);

    // 执行 ViewRootImpl.setView
    root.setView(view, wparams, panelParentView, userId);
}
```

##### Step 10: ViewRootImpl 请求布局

```java
// ViewRootImpl.java
public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView,
        int userId) {

    synchronized (this) {
        if (mView == null) {
            mView = view;

            // 请求布局
            requestLayout();

            // 添加到 WMS
            res = mWindowSession.addToDisplay(mWindow, mSeq, mWindowAttributes,
                    getHostVisibility(), mDisplay.getDisplayId(), mWinFrame,
                    mAttachInfo.mContentInsets, mAttachInfo.mStableInsets,
                    mAttachInfo.mOutsets, mAttachInfo.mDisplayCutout, mInputChannel,
                    mTempInsets);
        }
    }
}
```

---

#### 生命周期调用顺序

```
1. 构造函数
2. attach()
3. onCreate()
   └── setContentView()
       └── installDecor()
4. onStart()
5. onRestoreInstanceState() [如果有状态恢复]
6. onResume()
   └── handleResumeActivity()
       └── wm.addView()
           └── 界面显示
```

</details>

---

### 3.7 Zygote 为什么需要用到 Socket 通讯而不是 Binder？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Zygote 使用 Socket 而非 Binder 的完整原因：**

#### 1. 启动时序问题（最关键）

```
Android 启动顺序：
┌─────────────────────────────────────────────┐
│  1. Linux Kernel 启动                        │
│  2. Init 进程启动（第一个用户进程）           │
│  3. Zygote 进程启动                          │
│  4. ServiceManager 启动 ← Binder 服务注册中心 │
│  5. System Server 启动                       │
└─────────────────────────────────────────────┘

问题：Zygote 启动时，ServiceManager 还未启动！
      没有 ServiceManager，Binder 无法注册服务
```

---

#### 2. Binder 的依赖关系

**Binder 通信的必要条件：**

```
Binder 通信需要：
├── Binder 驱动（Kernel 层，已就绪）
├── ServiceManager（用户空间，未启动）
└── 进程间共享内存（通过 ServiceManager 分配）

Zygote 启动时：
- Binder 驱动 ✓ 可用
- ServiceManager ✗ 不可用
- 无法使用 Binder 注册或查询服务
```

---

#### 3. Socket 的独立性

**Unix Domain Socket 特点：**

```cpp
// 不依赖任何其他服务
// 只需要文件系统支持

// 创建 Socket
int socket(AF_UNIX, SOCK_STREAM, 0);

// 绑定地址（文件路径）
struct sockaddr_un addr;
strcpy(addr.sun_path, "/dev/socket/zygote");
bind(fd, (struct sockaddr*)&addr, sizeof(addr));

// 监听连接
listen(fd, 10);
```

**优势：**

- 只依赖内核，不依赖其他用户态服务
- 启动时序独立
- 简单可靠

---

#### 4. 通信需求匹配

**Zygote 的通信特点：**

| 特性       | Zygote 需求          | Socket 是否满足 |
| ---------- | -------------------- | --------------- |
| 通信频率   | 低（只在创建应用时） | ✓               |
| 通信复杂度 | 简单（命令+参数）    | ✓               |
| 性能要求   | 不敏感               | ✓               |
| 安全性     | 本地通信即可         | ✓               |
| 双向通信   | 需要                 | ✓               |

**Zygote 通信协议：**

```
请求格式：
[参数个数]\n
[参数1]\n
[参数2]\n
...

示例：
5
--runtime-args
--setuid=1000
--setgid=1000
com.example.app
android.app.ActivityThread
```

---

#### 5. 架构设计考虑

**如果 Zygote 用 Binder 会怎样？**

```
方案 A: Zygote 等 ServiceManager 启动
    问题：System Server 需要 Zygote 创建
         Zygote 等 ServiceManager
         ServiceManager 需要 System Server 启动
         → 循环依赖！

方案 B: Zygote 自己启动 ServiceManager
    问题：增加复杂度
         不符合职责分离原则
         ServiceManager 应该由 System Server 管理

方案 C: 使用 Socket（实际方案）
    优点：无依赖，简单可靠
```

---

#### 6. 安全性对比

| 特性     | Binder               | Unix Domain Socket   |
| -------- | -------------------- | -------------------- |
| 访问控制 | 基于 UID/PID（复杂） | 基于文件权限（简单） |
| 网络暴露 | 不会                 | 不会（本地）         |
| 认证机制 | 内核自动添加身份     | 通过文件权限控制     |
| 适用场景 | 复杂的 C/S 通信      | 简单的本地通信       |

**Zygote Socket 权限：**

```bash
# Zygote Socket 文件权限
srw-rw---- 1 root system 0 2024-01-01 00:00 /dev/socket/zygote

# 只有 system 组可以访问
```

---

#### 总结

| 原因         | 说明                                             |
| ------------ | ------------------------------------------------ |
| **启动时序** | Zygote 早于 ServiceManager 启动，无法使用 Binder |
| **依赖关系** | Socket 只依赖内核，Binder 依赖 ServiceManager    |
| **需求匹配** | Socket 完全满足 Zygote 的简单通信需求            |
| **架构设计** | 避免循环依赖，职责分离                           |
| **安全性**   | Unix Domain Socket 足够安全且简单                |

</details>

---

### 3.8 Zygote 进程最原始的进程是什么进程？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Zygote 进程的起源：**

#### 进程层级关系

```
Kernel (内核空间)
    ↓
Init 进程 (PID = 1，第一个用户进程)
    ↓
Zygote 进程 (由 Init fork 产生)
    ├── System Server 进程
    ├── App 进程 1
    ├── App 进程 2
    └── ...
```

---

#### Zygote 的创建过程

##### 1. Init 解析 init.rc

```bash
# system/core/rootdir/init.rc
service zygote /system/bin/app_process -Xzygote /system/bin --zygote --start-system-server
    class main
    priority -20
    user root
    group root readproc reserved_disk
    socket zygote stream 660 root system
    ...
```

##### 2. Init fork Zygote

```cpp
// system/core/init/service.cpp
bool Service::Start() {
    // fork 新进程
    pid_t pid = fork();

    if (pid == 0) {
        // 子进程（Zygote）
        // 1. 设置用户和组
        SetProcessAttributes();

        // 2. 执行程序
        execve(args_[0].c_str(), ...);
    }

    // 父进程（Init）记录子进程 PID
    pid_ = pid;
    return true;
}
```

##### 3. Zygote 启动

```cpp
// frameworks/base/cmds/app_process/app_main.cpp
int main(int argc, char* const argv[]) {
    // app_process 就是 Zygote 的可执行文件

    // 参数解析
    // --zygote: 以 Zygote 模式运行
    // --start-system-server: 启动 System Server

    if (zygote) {
        runtime.start("com.android.internal.os.ZygoteInit", args, zygote);
    }
}
```

---

#### Zygote 的父进程

```
Zygote 的父进程：Init 进程（PID = 1）

验证方法：
$ adb shell ps -A | grep zygote
USER     PID   PPID  NAME
root     1234  1     zygote
root     1235  1     zygote64

PPID = 1 表示父进程是 Init
```

---

#### 为什么不是 Kernel 直接启动？

```
Kernel 启动后：
1. 执行 init 程序（由 Kernel 启动参数指定）
2. Init 是第一个用户态进程
3. Init 根据配置文件启动其他服务
4. Zygote 是 Init 启动的众多服务之一

原因：
- Kernel 只负责启动第一个用户进程
- 用户空间的服务由 Init 管理
- 符合 Unix 传统设计
```

---

#### 总结

| 问题              | 答案                             |
| ----------------- | -------------------------------- |
| Zygote 的父进程   | Init 进程（PID = 1）             |
| Zygote 由谁创建   | Init 进程通过 fork + execve 创建 |
| Zygote 可执行文件 | /system/bin/app_process          |
| Zygote 启动配置   | init.rc 中的 service 定义        |

</details>

---

## 4. AMS (ActivityManagerService)

### 4.1 深入浅出 AMS 是什么？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**AMS（ActivityManagerService）** 是 Android 系统中最重要的服务之一，负责管理应用的四大组件（Activity、Service、BroadcastReceiver、ContentProvider）的生命周期和调度。

---

#### 核心职责

```
┌─────────────────────────────────────────────────────────┐
│                    AMS 核心职责                          │
├─────────────────────────────────────────────────────────┤
│  1. Activity 生命周期管理                                 │
│     - 启动、暂停、恢复、销毁                              │
│     - 任务栈管理（Task/Stack）                           │
│     - LaunchMode 处理                                    │
├─────────────────────────────────────────────────────────┤
│  2. Service 管理                                          │
│     - startService / bindService                         │
│     - Service 生命周期                                   │
│     - 前台/后台 Service 管理                              │
├─────────────────────────────────────────────────────────┤
│  3. Broadcast 管理                                        │
│     - 发送和接收广播                                     │
│     - 有序/无序广播                                      │
│     - 静态/动态注册                                      │
├─────────────────────────────────────────────────────────┤
│  4. 进程管理                                              │
│     - 应用进程创建和销毁                                  │
│     - 进程优先级管理（OOM_ADJ）                          │
│     - 内存不足时杀进程                                    │
├─────────────────────────────────────────────────────────┤
│  5. 任务调度                                              │
│     - Activity 启动调度                                  │
│     - 应用切换管理                                        │
│     - 最近任务列表                                        │
└─────────────────────────────────────────────────────────┘
```

---

#### AMS 在系统中的位置

```
┌─────────────────────────────────────────┐
│           应用层 (Applications)          │
│    Activity / Service / Broadcast       │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│         应用框架层 (Framework)           │
│    ActivityManager (客户端 API)         │
│         ↓                               │
│    ActivityManagerService (服务端)      │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│           本地层 (Native)               │
│    ActivityManagerNative (Binder 代理)  │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│           内核层 (Kernel)               │
│         Binder 驱动                      │
└─────────────────────────────────────────┘
```

---

#### 关键类关系

```java
// AMS 继承关系
public class ActivityManagerService extends IActivityManager.Stub
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback {

    // 核心组件
    final ActivityStackSupervisor mStackSupervisor;  // Activity 栈管理
    final ActivityStartController mActivityStartController;  // 启动控制
    final ProcessList mProcessList;  // 进程管理
    final BroadcastQueue mBroadcastQueue;  // 广播队列

    // 与 WMS 交互
    final WindowManagerService mWindowManager;
}
```

---

#### AMS 初始化流程

```java
// SystemServer.java
private void startBootstrapServices() {
    // 1. 创建 AMS 实例
    mActivityManagerService = ActivityManagerService.Lifecycle.startService(
            mSystemServiceManager, atm);

    // 2. 设置系统服务管理器
    mActivityManagerService.setSystemServiceManager(mSystemServiceManager);

    // 3. 设置应用安装器
    mActivityManagerService.setInstaller(installer);

    // 4. 初始化 PowerManager
    mActivityManagerService.initPowerManagement();

    // 5. 设置 SystemReady 回调
    mActivityManagerService.setSystemProcess();
}
```

</details>

---

### 4.2 AMS 在 Android 起到什么作用，简单的分析下 Android 的源码 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

#### AMS 的核心作用

##### 1. 组件生命周期管理

```java
// ActivityManagerService.java

// Activity 启动
@Override
public int startActivity(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho,
        int requestCode, int startFlags, ProfilerInfo profilerInfo,
        Bundle bOptions) {
    return mActivityStartController.obtainStarter(intent, "startActivityAsUser")
        .setCaller(caller)
        .setCallingPackage(callingPackage)
        .execute();
}

// Service 启动
@Override
public ComponentName startService(IApplicationThread caller, Intent service,
        String resolvedType, boolean requireForeground, String callingPackage,
        String callingFeatureId, int userId) {
    // 验证权限
    // 创建 ServiceRecord
    // 启动 Service 进程
    // 调用 Service.onStartCommand()
}

// Broadcast 发送
@Override
public Intent registerReceiver(IApplicationThread caller, String callerPackage,
        IIntentReceiver receiver, IntentFilter filter, String requiredPermission,
        int userId, int flags) {
    // 注册广播接收器
    // 管理 ReceiverList
}
```

##### 2. 进程管理

```java
// ProcessList.java
final ProcessRecord startProcessLocked(...) {
    // 1. 检查进程是否已存在
    ProcessRecord app = getProcessRecordLocked(processName, info.uid);

    // 2. 如果存在，复用
    if (app != null && app.pid > 0) {
        return app;
    }

    // 3. 创建新进程
    app = newProcessRecordLocked(info, processName, isolated, isolatedUid);

    // 4. 通过 Zygote 启动进程
    final boolean success = startProcessLocked(app, ...);

    return app;
}
```

### 3. 内存管理（OOM_ADJ）

```java
// OomAdjuster.java
boolean updateOomAdjLocked(ProcessRecord app, String oomAdjReason) {
    // 计算进程优先级
    computeOomAdjLocked(app, ...);

    // 应用 OOM 调整
    applyOomAdjLocked(app, ...);

    // 如果内存不足，杀死后台进程
    if (app.oomAdj >= OOM_ADJ_FOREGROUND_APP) {
        // 后台进程，可以被杀死
    }
}
```

---

#### 源码分析

##### 启动流程源码

```java
// ActivityStarter.java - Activity 启动核心类
class ActivityStarter {

    int execute() {
        // 1. 解析 Intent，找到目标 Activity
        mRequest.resolveActivity(mSupervisor);

        // 2. 检查启动权限
        if (mService.mPermissionReviewRequired) {
            // 权限检查逻辑
        }

        // 3. 处理 LaunchMode
        int launchFlags = mRequest.intent.getFlags();
        adjustLaunchFlags(launchFlags);

        // 4. 查找或创建 Task
        ActivityRecord reusedActivity = getReusableIntentActivity();

        // 5. 暂停当前 Activity
        mService.getActivityStartController().mPendingLaunches.add(this);
        mTargetStack.startPausingLocked(...);

        // 6. 启动目标 Activity
        return startActivityUnchecked(r, ...);
    }

    private int startActivityUnchecked(...) {
        // 计算启动模式
        final int launchMode = computeLaunchMode();

        // 根据 LaunchMode 处理
        switch (launchMode) {
            case LAUNCH_SINGLE_TOP:
                // 栈顶复用
                break;
            case LAUNCH_SINGLE_TASK:
                // 栈内复用
                break;
            case LAUNCH_SINGLE_INSTANCE:
                // 独立任务栈
                break;
            default:
                // 标准模式
                break;
        }

        // 创建或复用 Activity
        return startActivityInner(r, ...);
    }
}
```

##### Activity 栈管理

```java
// ActivityStack.java
class ActivityStack extends ConfigurationContainer {

    // Activity 记录列表
    final ArrayList<ActivityRecord> mTaskHistory = new ArrayList<>();

    // 启动 Activity
    void startActivityLocked(ActivityRecord r, ...) {
        // 1. 将 Activity 添加到栈顶
        mTaskHistory.add(r);

        // 2. 更新位置
        r.putInHistory();

        // 3. 通知 WMS 创建窗口
        r.createAppWindowToken();
    }

    // 暂停 Activity
    boolean startPausingLocked(boolean userLeaving, boolean uiSleeping,
            ActivityRecord resuming) {
        ActivityRecord prev = mResumedActivity;

        // 发送暂停消息到应用进程
        if (prev.app != null && prev.app.thread != null) {
            prev.app.thread.schedulePauseActivity(prev.appToken, ...);
        }

        // 设置暂停状态
        prev.setState(PAUSING, "startPausingLocked");
    }
}
```

##### 与 WMS 的交互

```java
// ActivityRecord.java
void createAppWindowToken() {
    // 创建窗口令牌
    mAppWindowToken = mAtmService.mWindowManager.mRoot.getAppWindowToken(mAppToken);

    if (mAppWindowToken == null) {
        // 创建新的 AppWindowToken
        mAppWindowToken = new AppWindowToken(mAtmService.mWindowManager, mAppToken);
        mAppWindowToken.attachToDisplayLocked(mStack.getDisplay());
    }
}
```

---

#### AMS 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    ActivityManagerService                     │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │ ActivityStarter │  │ ActivityStack   │  │ ProcessList  │ │
│  │   (启动控制)     │  │  (栈管理)        │  │  (进程管理)   │ │
│  └────────┬────────┘  └────────┬────────┘  └──────┬───────┘ │
│           │                    │                   │         │
│  ┌────────▼────────┐  ┌────────▼────────┐  ┌──────▼───────┐ │
│  │ ActivityRecord  │  │   TaskRecord    │  │ProcessRecord │ │
│  │  (Activity记录)  │  │   (任务记录)     │  │ (进程记录)    │ │
│  └─────────────────┘  └─────────────────┘  └──────────────┘ │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │  BroadcastQueue │  │  ServiceRecord  │                   │
│  │   (广播队列)     │  │   (服务记录)     │                   │
│  └─────────────────┘  └─────────────────┘                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              WindowManagerService (WMS)                     │
└─────────────────────────────────────────────────────────────┘
```

</details>

---

### 4.3 简述 ActivityManagerService 是什么时候初始化的？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**AMS 初始化时机：**

#### 初始化流程

```
Zygote 启动
    ↓
ZygoteInit.main()
    ↓
Zygote.forkSystemServer()  ← fork System Server 进程
    ↓
System Server 进程
    ↓
SystemServer.main()
    ↓
SystemServer.run()
    ↓
startBootstrapServices()  ← 在这里初始化 AMS
    ↓
AMS 初始化完成
    ↓
startCoreServices()
    ↓
startOtherServices()
    ↓
AMS.systemReady()  ← AMS 准备就绪
    ↓
启动 Launcher
```

---

#### 源码分析

##### 1. SystemServer 启动 AMS

```java
// SystemServer.java
public static void main(String[] args) {
    new SystemServer().run();
}

private void run() {
    // 1. 创建系统上下文
    createSystemContext();

    // 2. 创建 SystemServiceManager
    mSystemServiceManager = new SystemServiceManager(mSystemContext);

    // 3. 启动引导服务（包含 AMS）
    startBootstrapServices();

    // 4. 启动核心服务
    startCoreServices();

    // 5. 启动其他服务
    startOtherServices();

    // 6. 进入 Looper 循环
    Looper.loop();
}
```

### 2. startBootstrapServices 中初始化 AMS

```java
// SystemServer.java
private void startBootstrapServices() {
    // 创建 ATMS (ActivityTaskManagerService)
    ActivityTaskManagerService atm = mSystemServiceManager.startService(
            ActivityTaskManagerService.Lifecycle.class).getService();

    // 创建 AMS
    mActivityManagerService = ActivityManagerService.Lifecycle.startService(
            mSystemServiceManager, atm);

    // AMS 初始化
    mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
    mActivityManagerService.setInstaller(installer);
    mActivityManagerService.initPowerManagement();

    // 设置 SystemServer 进程信息
    mActivityManagerService.setSystemProcess();
}
```

### 3. AMS 构造方法

```java
// ActivityManagerService.java
public ActivityManagerService(Context systemContext, ActivityTaskManagerService atm) {
    // 1. 保存上下文
    mContext = systemContext;

    // 2. 创建核心组件
    mHandlerThread = new ServiceThread("ActivityManager",
            THREAD_PRIORITY_FOREGROUND, false);
    mHandlerThread.start();
    mHandler = new MainHandler(mHandlerThread.getLooper());

    // 3. 创建进程管理器
    mProcessList = new ProcessList(this);

    // 4. 创建 Activity 栈管理器
    mStackSupervisor = atm.mStackSupervisor;

    // 5. 创建广播队列
    mBroadcastQueues = new BroadcastQueue[2];
    mBroadcastQueues[0] = new BroadcastQueue(this, ...);
    mBroadcastQueues[1] = new BroadcastQueue(this, ...);

    // 6. 初始化其他组件
    mServices = new ActiveServices(this);
    mProviderMap = new ProviderMap(this);

    // 7. 注册到 ServiceManager
    ServiceManager.addService(Context.ACTIVITY_SERVICE, this, true);
}
```

### 4. setSystemProcess

```java
// ActivityManagerService.java
public void setSystemProcess() {
    try {
        // 1. 注册 AMS 服务
        ServiceManager.addService(Context.ACTIVITY_SERVICE, this, true);
        ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
        ServiceManager.addService("meminfo", new MemBinder(this));
        ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
        ServiceManager.addService("dbinfo", new DbBinder(this));

        // 2. 获取 ApplicationInfo
        ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                "android", STOCK_PM_FLAGS);

        // 3. 创建 ProcessRecord
        mProcessList.init(mContext, mConstants, info);

        // 4. 初始化系统进程信息
        ProcessRecord app = mProcessList.newProcessRecordLocked(info, ...);
        app.setPid(Process.myPid());
        app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);

        synchronized (mPidsSelfLocked) {
            mPidsSelfLocked.put(app.getPid(), app);
        }

        // 5. 更新进程优先级
        updateLruProcessLocked(app, false, null);

    } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException("Unable to find android system package", e);
    }
}
```

---

#### 初始化时序总结

| 阶段                     | 时间 | 操作                        |
| ------------------------ | ---- | --------------------------- |
| SystemServer.run()       | 早期 | 创建系统上下文              |
| startBootstrapServices() | ~2s  | 创建 AMS 实例               |
| AMS 构造方法             | ~2s  | 初始化核心组件              |
| setSystemProcess()       | ~2s  | 注册服务，初始化进程信息    |
| startOtherServices()     | ~5s  | 等待其他服务就绪            |
| AMS.systemReady()        | ~10s | AMS 完全就绪，启动 Launcher |

</details>

---

### 4.4 简述 Binder、ActivityManagerNative、ActivityManagerService 三者的关系 ⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

#### 三者关系概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         客户端进程                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  ActivityManager.getService()                                  │  │
│  │       ↓                                                        │  │
│  │  IActivityManager.Stub.asInterface(binder)                     │  │
│  │       ↓                                                        │  │
│  │  ActivityManagerProxy ──Binder──> Binder 驱动                   │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ↓ Binder IPC
┌─────────────────────────────────────────────────────────────────────┐
│                         服务端进程 (system_server)                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  Binder 驱动 ──Binder──> ActivityManagerNative.Stub.onTransact()│  │
│  │       ↓                                                        │  │
│  │  ActivityManagerService (继承 Stub)                            │  │
│  │       ↓                                                        │  │
│  │  具体业务逻辑实现                                               │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

#### 详细说明

##### 1. Binder

**作用：** Android 的跨进程通信（IPC）机制

```java
// Binder 是底层通信机制
public class Binder implements IBinder {
    // 本地 Binder
    public IInterface queryLocalInterface(String descriptor) {
        return null;
    }

    // 跨进程调用入口
    public boolean transact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        // 调用 native 方法
        return transactNative(code, data, reply, flags);
    }
}
```

##### 2. IActivityManager (AIDL 接口)

```java
// IActivityManager.aidl
interface IActivityManager {
    int startActivity(in IApplicationThread caller, ...);
    void finishActivity(IBinder token, ...);
    ComponentName startService(in IApplicationThread caller, ...);
    // ... 更多方法
}
```

**生成的接口类：**

```java
public interface IActivityManager extends IInterface {
    // 抽象 Stub 类（服务端使用）
    public static abstract class Stub extends Binder implements IActivityManager {
        // 本地实现
        public static IActivityManager asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && iin instanceof IActivityManager) {
                return (IActivityManager) iin;  // 同进程
            }
            return new Proxy(obj);  // 跨进程
        }

        // 处理远程调用
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            switch (code) {
                case TRANSACTION_startActivity:
                    // 调用本地实现
                    return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    // 代理类（客户端使用）
    private static class Proxy implements IActivityManager {
        private IBinder mRemote;

        @Override
        public int startActivity(...) throws RemoteException {
            Parcel _data = Parcel.obtain();
            Parcel _reply = Parcel.obtain();
            try {
                // 打包参数
                _data.writeInterfaceToken(DESCRIPTOR);
                // ...
                // 发起远程调用
                mRemote.transact(Stub.TRANSACTION_startActivity, _data, _reply, 0);
                // 读取返回值
                return _reply.readInt();
            } finally {
                _reply.recycle();
                _data.recycle();
            }
        }
    }
}
```

### 3. ActivityManagerNative (已弃用)

**历史版本中的类，现在直接使用 IActivityManager.Stub：**

```java
// 旧版本
public abstract class ActivityManagerNative extends Binder
        implements IActivityManager {
    // 等同于现在的 IActivityManager.Stub
}

// 新版本（Android 8.0+）
// 直接使用 IActivityManager.Stub
public class ActivityManagerService extends IActivityManager.Stub {
    // ...
}
```

### 4. ActivityManagerService

**AMS 是服务端实现：**

```java
public class ActivityManagerService extends IActivityManager.Stub
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback {

    // 实现 IActivityManager 接口的所有方法
    @Override
    public int startActivity(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho,
            int requestCode, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions) {
        // 具体实现
    }

    @Override
    public void finishActivity(IBinder token, int code, Intent data, int finishTask) {
        // 具体实现
    }

    // ... 更多实现
}
```

---

#### 调用流程

##### 客户端调用

```java
// 获取 AMS 代理
IActivityManager am = ActivityManager.getService();

// 调用方法（看起来是本地调用，实际是远程调用）
am.startActivity(...);

// 实际流程：
// 1. ActivityManager.getService() 获取 Binder 代理
// 2. 如果是跨进程，返回 IActivityManager.Stub.Proxy
// 3. Proxy.transact() 通过 Binder 驱动发送请求
```

##### 服务端处理

```java
// Binder 驱动接收到请求
// 1. 找到目标进程（system_server）
// 2. 唤醒目标线程
// 3. 调用 ActivityManagerService.onTransact()

// ActivityManagerService 处理
public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
    switch (code) {
        case TRANSACTION_startActivity:
            data.enforceInterface(IActivityManager.DESCRIPTOR);
            // 解析参数
            IApplicationThread caller = IApplicationThread.Stub.asInterface(
                data.readStrongBinder());
            // ...
            // 调用本地实现
            int result = this.startActivity(caller, ...);
            reply.writeInt(result);
            return true;
    }
    return super.onTransact(code, data, reply, flags);
}
```

---

#### 关系总结

| 组件                       | 角色       | 说明                                |
| -------------------------- | ---------- | ----------------------------------- |
| **Binder**                 | 通信基础   | 底层 IPC 机制，提供 transact() 能力 |
| **IActivityManager**       | 接口定义   | AIDL 定义的接口，包含 Stub 和 Proxy |
| **ActivityManagerService** | 服务端实现 | 继承 Stub，实现具体业务逻辑         |
| **ActivityManagerProxy**   | 客户端代理 | 封装远程调用，实现接口方法          |

</details>

---

### 4.5 简述 AMS 的注册流程 ⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

#### AMS 注册流程

```
SystemServer 启动
    ↓
AMS 构造方法
    ↓
AMS.setSystemProcess()
    ↓
ServiceManager.addService(Context.ACTIVITY_SERVICE, this)
    ↓
Binder 驱动注册
    ↓
服务注册完成
```

---

#### 详细流程

##### 1. AMS 创建

```java
// SystemServer.java
private void startBootstrapServices() {
    // 创建 AMS 实例
    mActivityManagerService = ActivityManagerService.Lifecycle.startService(
            mSystemServiceManager, atm);

    // 设置系统进程
    mActivityManagerService.setSystemProcess();
}
```

##### 2. setSystemProcess

```java
// ActivityManagerService.java
public void setSystemProcess() {
    try {
        // ========== 注册 AMS 服务 ==========
        ServiceManager.addService(Context.ACTIVITY_SERVICE, this, true);

        // 注册其他相关服务
        ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
        ServiceManager.addService("meminfo", new MemBinder(this));
        ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
        ServiceManager.addService("dbinfo", new DbBinder(this));
        ServiceManager.addService("cpuinfo", new CpuBinder(this));

        // 获取系统应用的 ApplicationInfo
        ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                "android", STOCK_PM_FLAGS);

        // 初始化进程列表
        mProcessList.init(mContext, mConstants, info);

        // 创建 System Server 进程的 ProcessRecord
        ProcessRecord app = mProcessList.newProcessRecordLocked(info, ...);
        app.setPid(Process.myPid());
        app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);

        // 保存到进程映射表
        synchronized (mPidsSelfLocked) {
            mPidsSelfLocked.put(app.getPid(), app);
        }

        // 更新进程优先级
        updateLruProcessLocked(app, false, null);

    } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException("Unable to find android system package", e);
    }
}
```

### 3. ServiceManager.addService

```java
// ServiceManager.java (客户端)
public static void addService(String name, IBinder service, boolean allowIsolated) {
    try {
        // 获取 ServiceManager 代理
        IServiceManager sm = getIServiceManager();
        // 调用远程方法
        sm.addService(name, service, allowIsolated);
    } catch (RemoteException e) {
        Log.e(TAG, "error in addService", e);
    }
}

// 获取 ServiceManager
private static IServiceManager getIServiceManager() {
    if (sServiceManager != null) {
        return sServiceManager;
    }

    // ServiceManager 的特殊 handle = 0
    sServiceManager = ServiceManagerNative.asInterface(BinderInternal.getContextObject());
    return sServiceManager;
}
```

### 4. 服务端处理注册

```cpp
// ServiceManager 是 Native 服务
// frameworks/native/cmds/servicemanager/service_manager.c

int svcmgr_handler(struct binder_state *bs,
                   struct binder_transaction_data *txn,
                   struct binder_io *msg,
                   struct binder_io *reply)
{
    struct svcinfo *si;
    uint16_t *s;
    size_t len;
    uint32_t handle;
    uint32_t strict_policy;
    int allow_isolated;

    // 解析添加服务请求
    strict_policy = bio_get_uint32(msg);
    s = bio_get_string16(msg, &len);

    handle = bio_get_ref(msg);
    allow_isolated = bio_get_uint32(msg) ? 1 : 0;

    // 注册服务
    if (do_add_service(bs, s, len, handle, txn->sender_euid,
            allow_isolated, txn->sender_pid))
        return -1;

    bio_put_uint32(reply, 0);
    return 0;
}

// 实际注册
int do_add_service(struct binder_state *bs, const uint16_t *s, size_t len,
                   uint32_t handle, uid_t uid, int allow_isolated,
                   pid_t spid)
{
    struct svcinfo *si;

    // 检查权限
    if (!svc_can_register(s, len, spid)) {
        return -1;
    }

    // 查找是否已存在
    si = find_svc(s, len);
    if (si) {
        if (si->handle) {
            return -1;  // 已存在
        }
        si->handle = handle;
    } else {
        // 创建新的服务信息
        si = malloc(sizeof(*si) + (len + 1) * sizeof(uint16_t));
        if (!si) {
            return -1;
        }
        si->handle = handle;
        si->len = len;
        memcpy(si->name, s, (len + 1) * sizeof(uint16_t));
        si->name[len] = '\0';
        si->death.func = (void*) svcinfo_death;
        si->death.ptr = si;
        si->allow_isolated = allow_isolated;

        // 添加到服务列表
        si->next = svclist;
        svclist = si;
    }

    // 设置死亡通知
    binder_acquire(bs, handle);
    binder_link_to_death(bs, handle, &si->death);

    return 0;
}
```

---

#### 服务注册后的使用

##### 客户端获取服务

```java
// 应用进程获取 AMS
IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
IActivityManager am = IActivityManager.Stub.asInterface(b);

// 调用 AMS 方法
am.startActivity(...);
```

##### 获取流程

```cpp
// ServiceManager 处理获取请求
int do_find_service(struct binder_state *bs, const uint16_t *s, size_t len,
                    uid_t uid, pid_t spid)
{
    // 在服务列表中查找
    struct svcinfo *si = find_svc(s, len);

    if (si && si->handle) {
        if (!si->allow_isolated) {
            // 检查是否允许 isolated 进程访问
            uid_t appid = uid % AID_USER;
            if (appid >= AID_ISOLATED_START && appid <= AID_ISOLATED_END) {
                return 0;
            }
        }
        return si->handle;  // 返回服务 handle
    }
    return 0;
}
```

---

#### 注册流程总结

| 步骤 | 操作             | 说明                                  |
| ---- | ---------------- | ------------------------------------- |
| 1    | AMS 创建         | SystemServer 创建 AMS 实例            |
| 2    | setSystemProcess | 调用注册方法                          |
| 3    | addService       | 调用 ServiceManager 注册              |
| 4    | Binder 传输      | 通过 Binder 驱动发送到 ServiceManager |
| 5    | 服务列表更新     | ServiceManager 将服务加入 svclist     |
| 6    | 死亡通知注册     | 设置 Binder 死亡通知                  |
| 7    | 注册完成         | 其他进程可以通过名字获取 AMS          |

</details>

---

### 4.6 ActivityThread 和 ApplicationThread，以及关系和区别 ⭐⭐⭐

<details>
<summary>点击查看答案</summary>

#### 概述

| 类                    | 所属进程 | 作用                     | 角色          |
| --------------------- | -------- | ------------------------ | ------------- |
| **ActivityThread**    | 应用进程 | 主线程，管理应用生命周期 | 客户端主类    |
| **ApplicationThread** | 应用进程 | AMS 与应用的 Binder 接口 | Binder 服务端 |

---

#### ActivityThread

**定义：** 应用进程的主线程，管理应用的所有 Activity、Service 等组件

```java
public final class ActivityThread extends ClientTransactionHandler {

    // 主线程 Looper
    final Looper mLooper = Looper.myLooper();

    // H 类：处理各种消息
    final H mH = new H();

    // ApplicationThread：与 AMS 通信的接口
    final ApplicationThread mAppThread = new ApplicationThread();

    // 应用包信息
    final ArrayMap<String, WeakReference<LoadedApk>> mPackages = new ArrayMap<>();

    // Activity 记录
    final ArrayMap<IBinder, ActivityClientRecord> mActivities = new ArrayMap<>();

    // 主入口
    public static void main(String[] args) {
        // 1. 准备主线程 Looper
        Looper.prepareMainLooper();

        // 2. 创建 ActivityThread
        ActivityThread thread = new ActivityThread();
        thread.attach(false, startSeq);

        // 3. 创建主线程 Handler
        if (sMainThreadHandler == null) {
            sMainThreadHandler = thread.getHandler();
        }

        // 4. 进入消息循环
        Looper.loop();
    }

    // 绑定到 AMS
    private void attach(boolean system, long startSeq) {
        // 获取 AMS
        final IActivityManager mgr = ActivityManager.getService();

        // 向 AMS 注册应用进程
        mgr.attachApplication(mAppThread, startSeq);
    }
}
```

---

#### ApplicationThread

**定义：** ActivityThread 的内部类，继承 IApplicationThread.Stub，是 AMS 调用应用的 Binder 接口

```java
private class ApplicationThread extends IApplicationThread.Stub {

    // AMS 调用：绑定应用
    @Override
    public final void bindApplication(String processName, ApplicationInfo appInfo,
            ProviderInfoList providers, ComponentName instrumentationName,
            ProfilerInfo profilerInfo, Bundle instrumentationArgs,
            IInstrumentationWatcher instrumentationWatcher,
            IUiAutomationConnection instrumentationUiConnection, int debugMode,
            boolean enableBinderTracking, boolean trackAllocation,
            boolean isRestrictedBackupMode, boolean persistent, Configuration config,
            CompatibilityInfo compatInfo, Map services, Bundle coreSettings,
            String buildSerial, AutofillOptions autofillOptions,
            ContentCaptureOptions contentCaptureOptions, long[] disabledCompatChanges) {

        AppBindData data = new AppBindData();
        data.processName = processName;
        data.appInfo = appInfo;
        data.providers = providers.getList();
        // ...

        // 发送消息到主线程
        sendMessage(H.BIND_APPLICATION, data);
    }

    // AMS 调用：启动 Activity
    @Override
    public final void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
            ActivityInfo info, Configuration curConfig, Configuration overrideConfig,
            CompatibilityInfo compatInfo, String referrer, IVoiceInteractor voiceInteractor,
            int procState, Bundle state, PersistableBundle persistentState,
            List<ResultInfo> pendingResults, List<ReferrerIntent> pendingNewIntents,
            boolean notResumed, boolean isForward, ProfilerInfo profilerInfo) {

        ActivityClientRecord r = new ActivityClientRecord();
        r.token = token;
        r.ident = ident;
        r.intent = intent;
        // ...

        // 发送消息到主线程
        sendMessage(H.LAUNCH_ACTIVITY, r);
    }

    // AMS 调用：暂停 Activity
    @Override
    public final void schedulePauseActivity(IBinder token, boolean finished,
            boolean userLeaving, int configChanges, boolean dontReport) {
        int seq = getLifecycleSeq();
        sendMessage(finished ? H.PAUSE_ACTIVITY_FINISHING : H.PAUSE_ACTIVITY,
                token, (userLeaving ? 1 : 0) | (dontReport ? 2 : 0), configChanges, seq);
    }

    // AMS 调用：停止 Activity
    @Override
    public final void scheduleStopActivity(IBinder token, boolean showWindow,
            int configChanges) {
        sendMessage(showWindow ? H.STOP_ACTIVITY_SHOW : H.STOP_ACTIVITY_HIDE,
                token, 0, configChanges);
    }

    // 发送消息到主线程
    private void sendMessage(int what, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.obj = obj;
        mH.sendMessage(msg);
    }
}
```

---

#### 关系图

```
┌─────────────────────────────────────────────────────────────────────┐
│                           system_server 进程                         │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    ActivityManagerService                       │  │
│  │                         ↓ 调用                                 │  │
│  │              ApplicationThreadProxy (Binder 代理)              │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ↓ Binder IPC
┌─────────────────────────────────────────────────────────────────────┐
│                           应用进程                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    ApplicationThread                           │  │
│  │              (继承 IApplicationThread.Stub)                    │  │
│  │                         ↓ 发送消息                             │  │
│  │                         H (Handler)                            │  │
│  │                         ↓ 处理                                 │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │                   ActivityThread                          │  │  │
│  │  │  - 管理 Activity 生命周期                                 │  │  │
│  │  │  - 管理 Application                                       │  │  │
│  │  │  - 管理 Service                                           │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

#### 区别对比

| 特性         | ActivityThread           | ApplicationThread               |
| ------------ | ------------------------ | ------------------------------- |
| **类型**     | 主类                     | 内部类                          |
| **继承**     | ClientTransactionHandler | IApplicationThread.Stub         |
| **职责**     | 管理应用组件生命周期     | 接收 AMS 命令                   |
| **线程**     | 主线程                   | Binder 线程（方法在主线程执行） |
| **通信方向** | 调用 AMS                 | 被 AMS 调用                     |
| **创建时机** | 应用进程启动时           | 随 ActivityThread 创建          |

---

#### 工作流程示例

##### Activity 启动流程中的交互

```
1. AMS 决定启动 Activity
   ↓
2. AMS 调用 ApplicationThread.scheduleLaunchActivity()
   ↓ (Binder IPC)
3. ApplicationThread 发送消息给 H
   ↓
4. H 在主线程处理 LAUNCH_ACTIVITY 消息
   ↓
5. ActivityThread.handleLaunchActivity()
   ↓
6. 创建 Activity，执行生命周期
```

```java
// ActivityThread.H
class H extends Handler {
    public static final int LAUNCH_ACTIVITY = 100;
    public static final int PAUSE_ACTIVITY = 101;
    public static final int STOP_ACTIVITY_SHOW = 103;

    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LAUNCH_ACTIVITY:
                handleLaunchActivity(r, ...);
                break;
            case PAUSE_ACTIVITY:
                handlePauseActivity(...);
                break;
            // ...
        }
    }
}
```

</details>

---

### 4.7 ActivityManagerService 和 zygote 进程通信是如何实现的 ⭐⭐⭐

<details>
<summary>点击查看答案</summary>

---

#### AMS 与 Zygote 通信方式

```
AMS (system_server 进程)
    ↓
ProcessList.startProcess()  // 准备进程参数
    ↓
ZygoteProcess.start()       // 通过 Socket 发送请求
    ↓
Zygote (Socket Server)      // 接收请求
    ↓
ZygoteConnection.runOnce()  // 处理请求
    ↓
Zygote.forkAndSpecialize()  // fork 新进程
    ↓
新应用进程创建完成
```

---

#### 通信实现

##### 1. AMS 发起请求

```java
// ProcessList.java
private Process.ProcessStartResult startProcess(...) {
    // 通过 Zygote 启动进程
    return mZygoteProcess.start(processClass, niceName, uid, gid, gids,
            runtimeFlags, mountExternal, targetSdkVersion, seInfo,
            requiredAbi, instructionSet, invokeWith, startTime, ...);
}
```

### 2. ZygoteProcess 发送请求

```java
// ZygoteProcess.java
public final Process.ProcessStartResult start(...) {
    try {
        return startViaZygote(processClass, niceName, uid, gid, gids,
                runtimeFlags, mountExternal, targetSdkVersion, seInfo,
                category, abiOverride, instructionSet, invokeWith,
                startTime, zygotePolicyFlags, ...);
    } catch (ZygoteStartFailedEx ex) {
        // 重试逻辑
    }
}

private Process.ProcessStartResult startViaZygote(...) {
    ArrayList<String> argsForZygote = new ArrayList<>();

    // 构建参数列表
    argsForZygote.add("--runtime-args");
    argsForZygote.add("--setuid=" + uid);
    argsForZygote.add("--setgid=" + gid);
    argsForZygote.add("--target-sdk-version=" + targetSdkVersion);
    argsForZygote.add(processClass);

    // 通过 Socket 发送
    synchronized(mLock) {
        return zygoteSendArgsAndGetResult(openZygoteSocketIfNeeded(abi),
                zygotePolicyFlags, argsForZygote);
    }
}

private Process.ProcessStartResult zygoteSendArgsAndGetResult(
        ZygoteState zygoteState, int zygotePolicyFlags,
        ArrayList<String> args) throws ZygoteStartFailedEx {

    // 写入参数数量
    writer.write(Integer.toString(args.size()));
    writer.newLine();

    // 写入每个参数
    for (String arg : args) {
        writer.write(arg);
        writer.newLine();
    }
    writer.flush();

    // 读取结果
    Process.ProcessStartResult result = new Process.ProcessStartResult();
    result.pid = inputStream.readInt();
    result.usingWrapper = inputStream.readBoolean();

    return result;
}
```

##### 3. Zygote 接收请求

```java
// ZygoteInit.java
private static void runSelectLoop(String abiList) {
    ArrayList<FileDescriptor> socketFDs = new ArrayList<>();
    ArrayList<ZygoteConnection> peers = new ArrayList<>();

    // 添加 Zygote Socket
    socketFDs.add(sServerSocket.getFileDescriptor());
    peers.add(null);

    while (true) {
        StructPollfd[] pollFDs = new StructPollfd[socketFDs.size()];

        // 轮询 Socket
        int pollIndex = 0;
        for (FileDescriptor socketFD : socketFDs) {
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = socketFD;
            pollfd.events = (short) POLLIN;
            pollFDs[pollIndex++] = pollfd;
        }

        Os.poll(pollFDs, -1);

        // 处理事件
        for (int i = pollFDs.length - 1; i >= 0; --i) {
            if ((pollFDs[i].revents & POLLIN) == 0) {
                continue;
            }

            if (i == 0) {
                // 新连接
                ZygoteConnection newPeer = acceptCommandPeer(abiList);
                peers.add(newPeer);
                socketFDs.add(newPeer.getFileDescriptor());
            } else {
                // 处理请求
                boolean done = peers.get(i).runOnce(this);
                if (done) {
                    peers.remove(i);
                    socketFDs.remove(i);
                }
            }
        }
    }
}
```

### 4. ZygoteConnection 处理请求

```java
// ZygoteConnection.java
boolean runOnce(ZygoteServer zygoteServer) {
    // 读取参数
    String[] args = readArgumentList();

    // 解析参数
    ZygoteArguments parsedArgs = new ZygoteArguments(args);

    // fork 进程
    pid = Zygote.forkAndSpecialize(
            parsedArgs.mUid, parsedArgs.mGid,
            parsedArgs.mGids, parsedArgs.mRuntimeFlags,
            parsedArgs.mMountExternal, parsedArgs.mSeInfo,
            parsedArgs.mNiceName, ...);

    if (pid == 0) {
        // 子进程
        zygoteServer.setForkChild();
        zygoteServer.closeServerSocket();
        IoUtils.closeQuietly(serverPipeFd);
        serverPipeFd = null;

        // 执行应用进程入口
        return handleChildProc(parsedArgs, childPipeFd);
    } else {
        // 父进程
        IoUtils.closeQuietly(childPipeFd);
        childPipeFd = null;
        return handleParentProc(pid, serverPipeFd);
    }
}

private boolean handleChildProc(ZygoteArguments parsedArgs,
        FileDescriptor pipeFd) {
    // 关闭 Socket
    closeSocket();

    // 设置进程参数
    Zygote.nativeSetAppProcessName(parsedArgs.mNiceName);

    // 执行目标类
    Zygote.nativeZygoteInit();

    // 调用 ActivityThread.main()
    return ZygoteInit.zygoteInit(parsedArgs.mTargetSdkVersion,
            parsedArgs.mDisabledCompatChanges,
            parsedArgs.mRemainingArgs, null);
}
```

---

#### 通信协议

##### 请求格式

```
[参数个数]\n
[参数1]\n
[参数2]\n
...

示例：
10
--runtime-args
--setuid=1000
--setgid=1000
--target-sdk-version=30
android.app.ActivityThread
```

##### 响应格式

```
[PID: int]
[usingWrapper: boolean]

示例：
12345
false
```

---

#### 为什么使用 Socket 而不是 Binder？

| 原因         | 说明                                             |
| ------------ | ------------------------------------------------ |
| **启动时序** | Zygote 先于 ServiceManager 启动，无法使用 Binder |
| **简单需求** | 只需简单的命令传输，Socket 足够                  |
| **进程创建** | Socket 可以传递文件描述符，用于创建后的通信      |
| **安全性**   | Unix Domain Socket 足够安全                      |

</details>

---

### 4.8 系统是如何存 AMS 服务对象的，以及应用层如何拿到 AMS 应用的？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

#### AMS 服务对象存储

##### 1. ServiceManager 存储

```cpp
// frameworks/native/cmds/servicemanager/service_manager.c

// 服务列表
struct svcinfo *svclist = NULL;

// 服务信息结构
struct svcinfo {
    struct svcinfo *next;       // 链表下一个节点
    uint32_t handle;            // Binder handle
    struct binder_death death;  // 死亡通知
    int allow_isolated;         // 是否允许 isolated 进程访问
    size_t len;                 // 服务名长度
    uint16_t name[0];           // 服务名（变长数组）
};

// 注册服务时添加到链表
int do_add_service(...) {
    struct svcinfo *si;

    // 创建服务信息
    si = malloc(sizeof(*si) + (len + 1) * sizeof(uint16_t));
    si->handle = handle;
    si->len = len;
    memcpy(si->name, s, (len + 1) * sizeof(uint16_t));

    // 添加到链表头部
    si->next = svclist;
    svclist = si;

    return 0;
}

// 查找服务
struct svcinfo *find_svc(const uint16_t *s16, size_t len) {
    struct svcinfo *si;

    // 遍历链表查找
    for (si = svclist; si; si = si->next) {
        if ((len == si->len) &&
            !memcmp(s16, si->name, len * sizeof(uint16_t))) {
            return si;
        }
    }
    return NULL;
}
```

---

#### 应用层获取 AMS

##### 1. 通过 ServiceManager

```java
// 底层获取方式
IBinder binder = ServiceManager.getService(Context.ACTIVITY_SERVICE);
IActivityManager am = IActivityManager.Stub.asInterface(binder);
```

##### 2. 通过 ActivityManager 类（推荐）

```java
// 应用层获取 AMS 的标准方式
// ActivityManager.java
public static IActivityManager getService() {
    return IActivityManagerSingleton.get();
}

private static final Singleton<IActivityManager> IActivityManagerSingleton =
        new Singleton<IActivityManager>() {
            @Override
            protected IActivityManager create() {
                // 获取 AMS 的 Binder 代理
                final IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
                final IActivityManager am = IActivityManager.Stub.asInterface(b);
                return am;
            }
        };
```

### 3. 通过 Context

```java
// 获取 ActivityManager 系统服务
ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

// 实际调用
@Override
public Object getSystemService(String name) {
    if (Context.ACTIVITY_SERVICE.equals(name)) {
        return mActivityManager;
    }
    // ...
}
```

---

#### 获取流程详解

```
应用进程
    ↓
ActivityManager.getService()
    ↓
ServiceManager.getService("activity")
    ↓
Binder 驱动
    ↓
system_server 进程
    ↓
ServiceManager 查询 svclist
    ↓
返回 AMS 的 Binder handle
    ↓
创建 AMS 代理对象
    ↓
应用可以使用 AMS 服务
```

---

#### 代码示例

##### 获取正在运行的任务

```java
// 需要权限：android.permission.GET_TASKS
ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(10);

for (ActivityManager.RunningTaskInfo task : tasks) {
    Log.d(TAG, "Task: " + task.baseActivity + ", " + task.numActivities);
}
```

##### 获取内存信息

```java
ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
am.getMemoryInfo(memoryInfo);

Log.d(TAG, "Total memory: " + memoryInfo.totalMem);
Log.d(TAG, "Available memory: " + memoryInfo.availMem);
Log.d(TAG, "Low memory: " + memoryInfo.lowMemory);
```

##### 获取运行中的服务

```java
List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(100);
for (ActivityManager.RunningServiceInfo service : services) {
    Log.d(TAG, "Service: " + service.service + ", PID: " + service.pid);
}
```

---

#### 注意事项

| 限制           | 说明                                      |
| -------------- | ----------------------------------------- |
| **权限限制**   | 部分 API 需要系统权限                     |
| **API 废弃**   | getRunningTasks() 等方法在 API 21+ 被限制 |
| **性能影响**   | 频繁调用 AMS 会影响性能                   |
| **跨进程开销** | 每次调用都是 Binder IPC                   |

</details>

---

### 4.9 AMS 与 servicemanage 进程是什么关系，app 启动流程讲一讲 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

#### AMS 与 ServiceManager 的关系

```
┌─────────────────────────────────────────────────────────────────────┐
│                         system_server 进程                           │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              ActivityManagerService (AMS)                      │  │
│  │                      ↓ 注册                                   │  │
│  │              ServiceManager.addService()                       │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ↓ Binder IPC
┌─────────────────────────────────────────────────────────────────────┐
│                      servicemanager 进程                             │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    service_manager.c                           │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │  svclist (服务链表)                                       │  │  │
│  │  │  ┌─────────┐    ┌─────────┐    ┌─────────┐             │  │  │
│  │  │  │ activity│ -> │ package │ -> │ window  │ -> ...       │  │  │
│  │  │  │  (AMS)  │    │  (PMS)  │    │  (WMS)  │              │  │  │
│  │  │  └─────────┘    └─────────┘    └─────────┘             │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

#### 关系说明

| 关系         | 说明                                               |
| ------------ | -------------------------------------------------- |
| **注册关系** | AMS 向 ServiceManager 注册自己                     |
| **查询关系** | 应用通过 ServiceManager 查询 AMS                   |
| **独立进程** | ServiceManager 是独立进程，AMS 在 system_server 中 |
| **启动时序** | ServiceManager 先于 AMS 启动                       |

---

#### App 启动流程

##### 完整流程图

```
用户点击图标
    ↓
Launcher.onClick()
    ↓
startActivity(intent)
    ↓
Instrumentation.execStartActivity()
    ↓
AMS.startActivity() [Binder IPC]
    ↓
AMS 检查进程是否存在
    ├─ 存在 → 直接使用
    └─ 不存在 → 创建进程
              ↓
        AMS 通过 Socket 请求 Zygote
              ↓
        Zygote.forkAndSpecialize()
              ↓
        新进程创建
              ↓
        ActivityThread.main()
              ↓
        ActivityThread.attach()
              ↓
        AMS.attachApplication() [Binder IPC]
              ↓
        AMS 发送启动 Activity 命令
              ↓
        ApplicationThread.scheduleLaunchActivity()
              ↓
        H.handleMessage(LAUNCH_ACTIVITY)
              ↓
        ActivityThread.handleLaunchActivity()
              ↓
        Activity.attach()
              ↓
        Activity.onCreate()
              ↓
        setContentView()
              ↓
        Activity.onStart()
              ↓
        Activity.onResume()
              ↓
        WindowManager.addView()
              ↓
        界面显示
```

---

#### 详细流程

##### Step 1: Launcher 发起启动

```java
// Launcher3
public void onClick(View v) {
    Object tag = v.getTag();
    if (tag instanceof ShortcutInfo) {
        Intent intent = ((ShortcutInfo) tag).getIntent();
        startActivity(intent);
    }
}
```

##### Step 2: 进入 AMS

```java
// ActivityManagerService.java
@Override
public int startActivity(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho,
        int requestCode, int startFlags, ProfilerInfo profilerInfo,
        Bundle bOptions) {

    return mActivityStartController.obtainStarter(intent, "startActivityAsUser")
        .setCaller(caller)
        .setCallingPackage(callingPackage)
        .execute();
}
```

##### Step 3: 检查并创建进程

```java
// ProcessList.java
private Process.ProcessStartResult startProcess(...) {
    // 检查进程是否存在
    ProcessRecord app = getProcessRecordLocked(processName, info.uid);

    if (app != null && app.pid > 0) {
        // 进程已存在
        return app;
    }

    // 创建新进程
    return mZygoteProcess.start(processClass, niceName, uid, gid, gids,
            runtimeFlags, mountExternal, targetSdkVersion, seInfo,
            requiredAbi, instructionSet, invokeWith, startTime, ...);
}
```

##### Step 4: Zygote 创建进程

```java
// ZygoteProcess.java
private Process.ProcessStartResult zygoteSendArgsAndGetResult(...) {
    // 通过 Socket 发送参数给 Zygote
    // Zygote 调用 forkAndSpecialize 创建新进程
}
```

##### Step 5: 应用进程启动

```java
// ActivityThread.java
public static void main(String[] args) {
    // 创建主线程 Looper
    Looper.prepareMainLooper();

    // 创建 ActivityThread 实例
    ActivityThread thread = new ActivityThread();
    thread.attach(false, startSeq);

    // 进入消息循环
    Looper.loop();
}
```

---

### 4.10 简述从点击图标到 app 启动的流程 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**完整流程：**

```
用户点击桌面图标
        ↓
Launcher 调用 startActivity()
        ↓
通过 Binder 进入 AMS
        ↓
AMS 解析 Intent，创建 ActivityRecord
        ↓
检查应用进程是否存在
        ↓
不存在 → 请求 Zygote 创建进程
        ↓
Zygote fork 新进程
        ↓
执行 ActivityThread.main()
        ↓
ActivityThread.attach() → 绑定 AMS
        ↓
AMS 发送启动 Activity 指令
        ↓
ApplicationThread.scheduleLaunchActivity()
        ↓
主线程 Handler 处理消息
        ↓
ActivityThread.handleLaunchActivity()
        ↓
创建 Activity 实例
        ↓
Activity.attach() → 创建 Window
        ↓
Activity.onCreate()
        ↓
setContentView() → 加载布局
        ↓
Activity.onStart()
        ↓
Activity.onResume()
        ↓
WindowManager.addView()
        ↓
界面显示到屏幕
```

**关键时间点：**

- **冷启动**：应用进程不存在，需要创建进程 + 启动应用（慢）
- **热启动**：应用进程存在，直接启动 Activity（快）

</details>

---

## 5. WMS (WindowManagerService)

### 5.1 WMS 是什么？有什么功能？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**WMS（WindowManagerService）** 是 Android 系统中负责窗口管理的核心服务。

**主要功能：**

| 功能           | 说明                           |
| -------------- | ------------------------------ |
| **窗口管理**   | 管理所有窗口的添加、删除、更新 |
| **窗口层级**   | 计算窗口的 Z-order 层级        |
| **窗口大小**   | 计算窗口的位置和大小           |
| **窗口动画**   | 管理窗口的进入、退出、切换动画 |
| **输入事件**   | 将输入事件分发给正确的窗口     |
| **壁纸管理**   | 管理桌面壁纸的显示             |
| **输入法管理** | 管理输入法窗口的显示和隐藏     |

**架构位置：**

```
应用层 (Activity/View)
        ↓
WindowManager (客户端)
        ↓
WindowManagerService (服务端)
        ↓
SurfaceFlinger (合成显示)
```

</details>

---

### 5.2 WMS、AMS 与 Activity 间的联系是什么？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**三者关系图：**

```
┌─────────────────────────────────────────────────────────────┐
│                        应用进程                              │
│  ┌──────────────┐      ┌──────────────┐                     │
│  │   Activity   │ <--> │ ActivityThread│                     │
│  └──────┬───────┘      └──────┬───────┘                     │
│         │                     │                             │
│         │  attach()           │  scheduleLaunchActivity()   │
│         ↓                     ↓                             │
│  ┌──────────────┐      ┌──────────────┐                     │
│  │    Window    │      │  Application │                     │
│  │   (PhoneWindow)     │   Thread     │                     │
│  └──────┬───────┘      └──────┬───────┘                     │
│         │                     │                             │
└─────────┼─────────────────────┼─────────────────────────────┘
          │                     │
          │ setWindowManager()  │ Binder IPC
          ↓                     ↓
┌─────────────────────────────────────────────────────────────┐
│                      system_server 进程                      │
│  ┌───────────────────────────────────────────────────────┐  │
│  │           ActivityManagerService (AMS)                │  │
│  │  - 管理 Activity 生命周期                              │  │
│  │  - 启动/停止 Activity                                  │  │
│  │  - 管理应用进程                                        │  │
│  └────────────────────┬──────────────────────────────────┘  │
│                       │ 启动 Activity 时创建 Window          │
│                       ↓                                      │
│  ┌───────────────────────────────────────────────────────┐  │
│  │          WindowManagerService (WMS)                   │  │
│  │  - 管理窗口的添加、删除、更新                          │  │
│  │  - 计算窗口大小和层级                                  │  │
│  │  - 管理窗口动画                                        │  │
│  └────────────────────┬──────────────────────────────────┘  │
│                       │                                      │
└───────────────────────┼──────────────────────────────────────┘
                        ↓
              SurfaceFlinger (显示合成)
```

**协作流程：**

1. AMS 启动 Activity
2. Activity 创建 PhoneWindow
3. Activity 调用 WindowManager.addView()
4. WMS 创建 WindowState，管理窗口
5. WMS 与 SurfaceFlinger 通信显示窗口

</details>

---

### 5.3 Activity 应用进程在什么时候会调用 addView，进而由 WMS 来处理 addWindow 呢？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**调用时机：**

```
Activity.onResume()
        ↓
Activity.makeVisible()
        ↓
WindowManager.addView(mDecor, params)
        ↓
WindowManagerImpl.addView()
        ↓
WindowManagerGlobal.addView()
        ↓
ViewRootImpl.setView() [Binder IPC]
        ↓
WindowManagerService.addWindow()
```

**详细流程：**

```java
// ActivityThread.java
handleResumeActivity() {
    // 调用 Activity.onResume()
    performResumeActivity(token, clearHide, reason);

    // 获取 DecorView
    View decor = r.window.getDecorView();

    // 设置可见
    decor.setVisibility(View.INVISIBLE);

    // 获取 WindowManager
    ViewManager wm = a.getWindowManager();

    // 添加 View
    wm.addView(decor, l);
}
```

**关键代码：**

```java
// WindowManagerService.java
public int addWindow(...) {
    // 1. 检查权限
    // 2. 创建 WindowToken
    // 3. 创建 WindowState
    // 4. 计算窗口层级
    // 5. 分配 Surface
    // 6. 通知 SurfaceFlinger
}
```

</details>

---

## 6. PMS (PackageManagerService)

### 6.1 简述 PMS 是什么？有什么作用？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**PMS（PackageManagerService）** 是 Android 系统中负责包管理的核心服务。

**主要作用：**

| 功能         | 说明                                           |
| ------------ | ---------------------------------------------- |
| **应用安装** | APK 解析、权限检查、数据目录创建               |
| **应用卸载** | 数据清理、权限移除                             |
| **应用查询** | 查询已安装应用、组件信息                       |
| **权限管理** | 权限授予、检查、撤销                           |
| **组件管理** | Activity、Service、Receiver、Provider 信息管理 |
| **签名验证** | APK 签名验证、系统应用识别                     |

**核心数据结构：**

```java
// PackageManagerService.java
class PackageManagerService extends IPackageManager.Stub {
    // 所有已安装包的信息
    final ArrayMap<String, PackageParser.Package> mPackages = new ArrayMap<>();

    // 权限信息
    final ArrayMap<String, BasePermission> mPermissions = new ArrayMap<>();

    // 共享库
    final ArrayMap<String, SharedLibraryEntry> mSharedLibraries = new ArrayMap<>();
}
```

</details>

---

### 6.2 分别就应用接口层、Framework 层、HAL 层、内核层介绍下 Android 电源管理系统？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Android 电源管理架构：**

```
┌─────────────────────────────────────────────────────────────────────┐
│                        应用接口层 (API Layer)                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  PowerManager / PowerManager.WakeLock                          │  │
│  │  - acquire() / release() 获取/释放唤醒锁                        │  │
│  │  - isInteractive() 检查屏幕状态                                 │  │
│  │  - reboot() 重启设备                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────┐
│                      Framework 层 (Java/Native)                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  PowerManagerService                                            │  │
│  │  - 管理 WakeLock 状态                                           │  │
│  │  - 处理用户活动超时                                             │  │
│  │  - 控制屏幕亮度                                                 │  │
│  │  - 与 Native 层交互                                             │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                              ↓                                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  com_android_server_power_PowerManagerService.cpp               │  │
│  │  - JNI 接口，连接 Java 层和 Native 层                           │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────┐
│                        HAL 层 (硬件抽象层)                           │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  power.default.so (Power HAL)                                   │  │
│  │  - setInteractive() 设置交互状态                                │  │
│  │  - powerHint() 电源提示                                         │  │
│  │  - setFeature() 设置电源特性                                    │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────┐
│                        内核层 (Kernel)                               │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  Kernel Power Management                                        │  │
│  │  - wakelock.c 内核唤醒锁                                        │  │
│  │  - cpufreq CPU 频率调节                                         │  │
│  │  - cpuidle CPU 空闲状态                                         │  │
│  │  - suspend/resume 挂起/恢复                                     │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

</details>

---

### 6.3 PMS 的作用是什么？PMS 跟咱们的安装速度和启动速度有关系吗？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**PMS 对安装速度的影响：**

| 阶段             | 耗时操作                 | 优化方向                 |
| ---------------- | ------------------------ | ------------------------ |
| **APK 解析**     | 解析 AndroidManifest.xml | 使用二进制 XML 格式      |
| **DEX 优化**     | dex2oat 编译             | 使用 Profile-guided 编译 |
| **权限检查**     | 验证权限声明             | 并行化处理               |
| **数据目录创建** | 创建应用数据目录         | 延迟创建                 |
| **签名验证**     | 验证 APK 签名            | 缓存签名结果             |

**PMS 对启动速度的影响：**

```
应用启动
    ↓
AMS 查询 PMS 获取应用信息
    ↓
PMS 从 mPackages 中查找 Package 信息
    ↓
返回 ApplicationInfo、组件信息等
    ↓
继续启动流程
```

**优化建议：**

1. 减少 PMS 查询次数
2. 缓存常用应用信息
3. 使用延迟加载策略

</details>

---

### 6.4 PMS 被谁启动的，它是一个单独进程运行吗？如果不是，又是在哪个进程呢？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**PMS 启动流程：**

```
init 进程
    ↓
启动 system_server 进程
    ↓
SystemServer.main()
    ↓
SystemServer.run()
    ↓
startBootstrapServices()
    ↓
mPackageManagerService = PackageManagerService.main()
    ↓
创建 PackageManagerService 实例
    ↓
扫描系统应用和数据分区应用
```

**运行进程：**

- **PMS 运行在 system_server 进程中**，不是独立进程
- 与 AMS、WMS 等系统服务在同一进程

**代码位置：**

```java
// SystemServer.java
private void startBootstrapServices() {
    // 启动 Installer 服务
    Installer installer = mSystemServiceManager.startService(Installer.class);

    // 启动 PMS
    mPackageManagerService = PackageManagerService.main(mSystemContext, installer,
            mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);

    mFirstBoot = mPackageManagerService.isFirstBoot();
    mPackageManager = mSystemContext.getPackageManager();
}
```

</details>

---

### 6.5 PMS 的启动过程是怎么样的？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**PMS 启动详细流程：**

```java
// PackageManagerService.java
public static PackageManagerService main(Context context, Installer installer,
        boolean factoryTest, boolean onlyCore) {
    // 创建 PMS 实例
    PackageManagerService m = new PackageManagerService(context, installer,
            factoryTest, onlyCore);

    // 注册到 ServiceManager
    ServiceManager.addService("package", m);

    return m;
}

private PackageManagerService(...) {
    // 1. 初始化设置
    // 2. 获取系统配置
    // 3. 启动 PackageHandler 线程
    // 4. 加载共享库

    // 5. 扫描系统应用
    scanDirTracedLI(new File(Environment.getRootDirectory(), "app"), ...);
    scanDirTracedLI(new File(Environment.getRootDirectory(), "priv-app"), ...);

    // 6. 扫描供应商应用
    scanDirTracedLI(new File(VENDOR_OVERLAY_DIR), ...);

    // 7. 扫描第三方应用
    scanDirTracedLI(new File(Environment.getDataDirectory(), "app"), ...);

    // 8. 权限更新
    updatePermissionsLPw(null, null, StorageManager.UUID_PRIVATE_INTERNAL, true, false, false);

    // 9. 写入设置
    mSettings.writeLPr();
}
```

**启动时序：**

```
SystemServer 启动
    ↓
startBootstrapServices()
    ↓
PMS.main()
    ↓
PMS 构造函数
    ↓
扫描 /system/app
    ↓
扫描 /system/priv-app
    ↓
扫描 /vendor/app
    ↓
扫描 /data/app
    ↓
权限更新
    ↓
PMS 启动完成
```

</details>

---

### 6.6 应用要怎么样才能调用 PowerManager 进行系统休眠或者唤醒呢？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**使用 PowerManager 控制电源：**

```java
// 获取 PowerManager
PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

// 1. 获取唤醒锁（保持 CPU 运行）
PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK, "MyApp::WakeLockTag");

// 获取锁
wakeLock.acquire();

// 释放锁
wakeLock.release();
```

**唤醒锁类型：**

| 类型                      | 说明                            | 权限要求            |
| ------------------------- | ------------------------------- | ------------------- |
| `PARTIAL_WAKE_LOCK`       | 保持 CPU 运行，屏幕和键盘可关闭 | 需要 WAKE_LOCK 权限 |
| `SCREEN_DIM_WAKE_LOCK`    | 保持屏幕微亮                    | 已废弃              |
| `SCREEN_BRIGHT_WAKE_LOCK` | 保持屏幕高亮                    | 已废弃              |
| `FULL_WAKE_LOCK`          | 保持屏幕和键盘高亮              | 已废弃              |

**系统休眠/唤醒：**

```java
// 需要系统权限或 root 权限

// 使设备立即休眠
powerManager.goToSleep(SystemClock.uptimeMillis());

// 唤醒设备
powerManager.wakeUp(SystemClock.uptimeMillis());
```

**需要的权限：**

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.DEVICE_POWER" />
```

**注意：** `DEVICE_POWER` 权限仅限系统应用使用。

</details>

---

## 7. 传感器系统

### 7.1 简述 Android 系统关机流程 ⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Android 关机流程：**

```
用户选择关机
        ↓
PowerManagerService.shutdown()
        ↓
发送关机广播
        ↓
SystemServer 关闭服务
        ↓
ActivityManagerService 关闭应用
        ↓
PackageManagerService 保存状态
        ↓
关闭系统服务
        ↓
调用 nativeShutdown()
        ↓
Linux 内核关机
        ↓
硬件断电
```

**详细步骤：**

```java
// PowerManagerService.java
public void shutdown(...) {
    // 1. 检查权限
    // 2. 显示关机对话框
    // 3. 发送关机广播
    Intent intent = new Intent(Intent.ACTION_SHUTDOWN);
    intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
    mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL, null, ...);

    // 4. 关闭系统
    ShutdownThread.shutdown(mContext, reason);
}
```

</details>

---

### 7.2 以应用调用 Shutdown 为例，分析下系统的关机流程？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**应用调用关机流程：**

```java
// 应用调用（需要系统权限）
Intent intent = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
startActivity(intent);
```

**流程分析：**

```
应用发送关机 Intent
        ↓
系统捕获 Intent (ShutdownActivity)
        ↓
确认关机操作
        ↓
调用 PowerManagerService.shutdown()
        ↓
ShutdownThread 执行关机
        ↓
1. 发送关机广播
2. 关闭 ActivityManager
3. 关闭 PackageManager
4. 关闭其他服务
5. 同步文件系统
6. 调用 reboot() 或 shutdown()
        ↓
系统重启或关机
```

</details>

---

### 7.3 简单说下 Android 上层都有哪些触发关机的方法？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**上层触发关机的方法：**

| 方法             | 说明                      | 权限要求 |
| ---------------- | ------------------------- | -------- |
| **长按电源键**   | 系统默认关机方式          | 无       |
| **设置菜单关机** | 设置 -> 电源 -> 关机      | 无       |
| **ADB 命令**     | `adb reboot -p`           | 调试模式 |
| **系统 API**     | `PowerManager.reboot()`   | 系统权限 |
| **Intent 调用**  | `ACTION_REQUEST_SHUTDOWN` | 系统权限 |
| **低电量关机**   | 电量低于阈值自动关机      | 系统     |
| **过热保护**     | 温度过高自动关机          | 系统     |

**代码示例：**

```java
// 方法1: 使用 PowerManager (需要系统权限)
PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
pm.reboot("user_requested");

// 方法2: 使用 Intent (需要系统权限)
Intent intent = new Intent(Intent.ACTION_SHUTDOWN);
sendBroadcast(intent);

// 方法3: Runtime 执行命令 (需要 root)
Runtime.getRuntime().exec("reboot -p");
```

</details>

---

### 7.4 如果你遇到一个系统异常关机的问题，请简述下你的分析思路，以及可以通过获取什么 log 进行分析？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**分析思路：**

```
确认问题现象
    ↓
收集 Log 信息
    ↓
分析关机原因
    ↓
定位问题模块
    ↓
修复验证
```

**需要获取的 Log：**

| Log 类型       | 命令                           | 作用         |
| -------------- | ------------------------------ | ------------ |
| **System Log** | `adb logcat -d > system.log`   | 系统日志     |
| **Kernel Log** | `adb shell dmesg > kernel.log` | 内核日志     |
| **Event Log**  | `adb logcat -b events`         | 事件日志     |
| **Crash Log**  | `/data/tombstones/`            | Native Crash |
| **ANR Log**    | `/data/anr/`                   | ANR 信息     |
| **Last Kmsg**  | `/proc/last_kmsg`              | 上次内核日志 |

**关键分析点：**

```bash
# 1. 查看关机相关日志
adb logcat | grep -i "shutdown\|reboot\|power"

# 2. 查看异常信号
adb logcat | grep -i "signal\|fatal\|kill"

# 3. 查看 Java Crash
adb logcat | grep -i "exception\|crash"

# 4. 查看 Native Crash
adb logcat | grep -i "tombstone\|segmentation"

# 5. 查看电池状态
adb logcat | grep -i "battery\|thermal"
```

**常见关机原因：**

1. **低电量关机** - 检查电池日志
2. **过热保护** - 检查温度日志
3. **Watchdog 超时** - 检查 SystemServer 日志
4. **Native Crash** - 检查 Tombstone
5. **Kernel Panic** - 检查 Kernel Log

</details>

---

## 8. 音频系统框架

### 8.1 简述声卡的添加流程 ⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**声卡添加流程：**

```
内核检测到音频设备
        ↓
加载声卡驱动
        ↓
ALSA 注册声卡
        ↓
AudioFlinger 检测到新设备
        ↓
加载音频策略配置
        ↓
更新音频路由
        ↓
应用可以使用新声卡
```

**关键步骤：**

```c
// 内核层
// 1. 注册声卡
snd_card_register(struct snd_card *card);

// 2. 创建 PCM 设备
snd_pcm_new(struct snd_card *card, ...);

// HAL 层
// 3. 检测设备变化
adev->hw_device.open_output_stream(...);
```

</details>

---

### 8.2 简述 Android 音频系统框架 ⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Android 音频系统架构：**

```
┌─────────────────────────────────────────────────────────────────────┐
│                         应用层 (Applications)                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  AudioTrack / AudioRecord / MediaPlayer                        │  │
│  │  - 音频播放和录制 API                                           │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────┐
│                      Framework 层 (Java/Native)                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  AudioManager / AudioService                                   │  │
│  │  - 音频焦点管理                                                 │  │
│  │  - 音量控制                                                     │  │
│  │  - 音频设备管理                                                 │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                    ↓                                │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  AudioFlinger (Native)                                         │  │
│  │  - 音频流管理                                                   │  │
│  │  - 音频混音 (Mixer)                                             │  │
│  │  - 音频路由                                                     │  │
│  │  - 与 HAL 层交互                                                │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────┐
│                        HAL 层 (硬件抽象层)                           │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  audio.primary.xxx.so (Audio HAL)                              │  │
│  │  - 实现硬件特定的音频接口                                       │  │
│  │  - 打开/关闭音频流                                              │  │
│  │  - 设置音频参数                                                 │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────┐
│                        内核层 (Kernel)                               │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  ALSA / TinyALSA                                               │  │
│  │  - 声卡驱动                                                     │  │
│  │  - PCM 设备管理                                                 │  │
│  │  - 音频 DMA                                                     │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

</details>

---

### 8.3 调节音量，系统流程是怎么走的？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**音量调节流程：**

```
用户按音量键
        ↓
PhoneWindowManager 拦截按键
        ↓
AudioManager.adjustStreamVolume()
        ↓
AudioService.adjustStreamVolume()
        ↓
计算目标音量值
        ↓
设置音量到 HAL
        ↓
AudioFlinger.setStreamVolume()
        ↓
Audio HAL 设置硬件音量
        ↓
更新音量 UI
```

**详细代码流程：**

```java
// 1. 按键处理
// PhoneWindowManager.java
public long interceptKeyBeforeDispatching(...) {
    case KeyEvent.KEYCODE_VOLUME_UP:
    case KeyEvent.KEYCODE_VOLUME_DOWN:
        handleVolumeKey(...);
        return -1;
}

// 2. AudioManager 调用
// AudioManager.java
public void adjustStreamVolume(int streamType, int direction, int flags) {
    AudioService.adjustStreamVolume(streamType, direction, flags, ...);
}

// 3. AudioService 处理
// AudioService.java
protected void adjustStreamVolume(int streamType, int direction, int flags, ...) {
    // 获取当前音量
    int oldIndex = mStreamStates[streamType].getIndex(...);

    // 计算新音量
    int index = mStreamStates[streamType].getIndex(direction);

    // 设置音量
    setStreamVolumeInt(streamType, index, device, false);

    // 发送音量变化广播
    sendVolumeUpdate(streamType, oldIndex, index, flags);
}
```

</details>

---

### 8.4 简述插入一个音频设备，系统流程是怎么走的？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**音频设备插入流程：**

```
耳机插入
        ↓
内核检测到中断
        ↓
发送 uevent 通知
        ↓
AudioService 接收 uevent
        ↓
更新音频设备列表
        ↓
重新配置音频路由
        ↓
发送设备变化广播
        ↓
应用响应设备变化
```

**详细流程：**

```java
// 1. 内核检测
// 耳机插拔检测 -> 发送 uevent

// 2. AudioService 处理
// AudioService.java
private void onSetWiredDeviceConnectionState(...) {
    synchronized (mConnectedDevices) {
        // 更新设备连接状态
        if (state == 1) {
            // 设备连接
            makeA2dpDeviceAvailable(address);
        } else {
            // 设备断开
            makeA2dpDeviceUnavailable(address);
        }
    }

    // 更新音频策略
    sendMsg(MSG_SET_DEVICE_CONNECTION_STATE, ...);
}

// 3. 更新音频路由
// AudioPolicyManager.cpp
status_t AudioPolicyManager::setDeviceConnectionState(...) {
    // 更新可用设备列表
    // 重新计算音频路由
    // 应用新的路由策略
}
```

</details>

---

### 8.5 如果插入一个设备没有声音，怎么 debug？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Debug 步骤：**

```
1. 确认设备是否被识别
   ↓
2. 检查音频路由配置
   ↓
3. 检查 HAL 层状态
   ↓
4. 检查内核驱动
   ↓
5. 检查硬件连接
```

**Debug 命令：**

```bash
# 1. 查看已连接设备
adb shell dumpsys audio | grep "Devices"

# 2. 查看音频路由
adb shell dumpsys audio | grep "Output"

# 3. 查看音频 HAL 日志
adb logcat -s AudioHardwareInterface

# 4. 查看 ALSA 设备
adb shell cat /proc/asound/devices

# 5. 查看声卡信息
adb shell cat /proc/asound/cards

# 6. 测试音频播放
adb shell tinyplay /data/test.wav

# 7. 查看内核日志
adb shell dmesg | grep -i audio
```

**检查清单：**

- [ ] 设备是否正确连接（物理检查）
- [ ] 设备是否被系统识别（dumpsys audio）
- [ ] 音频路由是否正确配置
- [ ] 音量是否被静音或设为 0
- [ ] 音频焦点是否被占用
- [ ] HAL 层是否返回错误
- [ ] 内核驱动是否正常工作

</details>

---

## 9. Kernel DTS 解析

### 9.1 设备树是什么？它在嵌入式系统中扮演什么角色？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**设备树（Device Tree）** 是一种描述硬件配置的数据结构，用于向操作系统传递硬件信息。

**角色和作用：**

| 作用         | 说明                           |
| ------------ | ------------------------------ |
| **硬件描述** | 描述 CPU、内存、外设等硬件信息 |
| **驱动匹配** | 内核通过设备树匹配对应的驱动   |
| **配置参数** | 传递硬件相关的配置参数         |
| **平台无关** | 同一内核支持不同硬件平台       |

**为什么需要设备树：**

```
传统方式（ARM）：
内核代码中包含大量板级支持代码 (arch/arm/mach-xxx)
    ↓
每个新板子都需要修改内核代码
    ↓
内核代码臃肿，维护困难

设备树方式：
硬件描述从内核代码中分离
    ↓
使用独立的 DTS 文件描述硬件
    ↓
内核启动时解析设备树
    ↓
同一内核支持多种硬件平台
```

</details>

---

### 9.2 什么是 DTS 和 DTSI 文件？它们有什么不同？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**DTS（Device Tree Source）**：设备树源文件，描述具体硬件平台的配置。

**DTSI（Device Tree Source Include）**：设备树头文件，包含可复用的硬件定义。

**区别：**

| 特性     | DTS              | DTSI              |
| -------- | ---------------- | ----------------- |
| **用途** | 描述完整硬件平台 | 提供通用定义      |
| **编译** | 编译为 DTB       | 被 DTS 包含       |
| **内容** | 包含板级特定配置 | 包含 SoC 通用配置 |
| **关系** | 包含 DTSI        | 被 DTS 包含       |

**示例：**

```dts
// msm8998.dtsi - SoC 通用定义
/ {
    cpus {
        cpu0: cpu@0 {
            compatible = "arm,cortex-a53";
            ...
        };
    };

    soc: soc {
        // 通用外设定义
    };
};

// msm8998-mtp.dts - 具体板级配置
#include "msm8998.dtsi"

/ {
    model = "Qualcomm Technologies, Inc. MSM 8998 MTP";
    compatible = "qcom,msm8998-mtp", "qcom,msm8998";

    // 板级特定配置
};
```

</details>

---

### 9.3 在设备树中，什么是节点？什么是属性？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**节点（Node）**：设备树中的硬件单元，用花括号 `{}` 表示。

**属性（Property）**：描述节点特性的键值对。

**示例：**

```dts
/ {
    // 这是一个节点
    cpus {
        // 这是子节点
        cpu0: cpu@0 {
            // 这些是属性
            compatible = "arm,cortex-a53";
            reg = <0x0>;
            clock-frequency = <0x8f0d180>;
        };
    };

    // 外设节点
    uart0: serial@78af000 {
        compatible = "qcom,msm-uartdm-v1.4";
        reg = <0x78af000 0x200>;
        interrupts = <0 108 IRQ_TYPE_LEVEL_HIGH>;
        clocks = <&clock_gcc GCC_BLSP1_UART2_APPS_CLK>;
    };
};
```

**节点命名规范：**

- `label: node-name@unit-address`
- `label`：标签，用于引用
- `node-name`：节点名称
- `unit-address`：单元地址（通常是寄存器基地址）

</details>

---

### 9.4 什么是设备树的兼容性字符串？它们的作用是什么？⭐⭐

<details>
<summary>点击查看答案</summary>

**兼容性字符串（compatible）**：用于匹配设备和驱动的字符串。

**作用：**

```dts
uart0: serial@78af000 {
    // 兼容性字符串，按优先级排列
    compatible = "qcom,msm-uartdm-v1.4", "qcom,msm-uartdm";
    ...
};
```

**匹配过程：**

```
内核启动
    ↓
解析设备树
    ↓
遍历所有节点
    ↓
读取 compatible 属性
    ↓
在驱动列表中查找匹配
    ↓
找到匹配 → 绑定驱动
```

**驱动中的匹配：**

```c
static const struct of_device_id msm_uartdm_match[] = {
    { .compatible = "qcom,msm-uartdm-v1.4" },
    { .compatible = "qcom,msm-uartdm" },
    {}
};

static struct platform_driver msm_uartdm_driver = {
    .driver = {
        .name = "msm_uartdm",
        .of_match_table = msm_uartdm_match,
    },
    .probe = msm_uartdm_probe,
    .remove = msm_uartdm_remove,
};
```

</details>

---

### 9.5 在设备树中，如何使用 `#include` 语句引用其他 DTS 和 DTSI 文件？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**包含语法：**

```dts
// 方式1: 包含 DTSI 文件
#include "msm8998.dtsi"
#include "msm8998-pinctrl.dtsi"

// 方式2: 包含带路径的文件
#include "dt-bindings/clock/msm-clocks-8998.h"

// 方式3: 条件包含（使用 C 预处理器）
#ifdef CONFIG_ENABLE_FEATURE
#include "feature.dtsi"
#endif
```

**包含规则：**

```dts
// 父节点定义 (parent.dtsi)
/ {
    soc {
        // 基础定义
    };
};

// 子节点扩展 (child.dts)
#include "parent.dtsi"

/ {
    // 添加新节点
    new_device {
        compatible = "example,new-device";
    };
};

&soc {
    // 修改已有节点
    existing_device {
        status = "okay";
    };
};
```

</details>

---

### 9.6 什么是 DTB 文件？它们在嵌入式系统中扮演什么角色？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**DTB（Device Tree Blob）**：设备树二进制文件，是 DTS 编译后的二进制格式。

**编译过程：**

```
DTS 源文件
    ↓
dtc (Device Tree Compiler)
    ↓
DTB 二进制文件
```

**编译命令：**

```bash
# 编译 DTS 为 DTB
dtc -I dts -O dtb -o output.dtb input.dts

# 反编译 DTB 为 DTS
dtc -I dtb -O dts -o output.dts input.dtb
```

**角色和作用：**

| 阶段         | 作用                        |
| ------------ | --------------------------- |
| **启动时**   | Bootloader 加载 DTB 到内存  |
| **内核启动** | 内核解析 DTB 获取硬件信息   |
| **驱动加载** | 根据 DTB 信息匹配和加载驱动 |

**启动流程：**

```
Bootloader
    ↓
加载 kernel 到内存
    ↓
加载 DTB 到内存
    ↓
传递 DTB 地址给内核
    ↓
内核启动
    ↓
解析 DTB
    ↓
创建设备节点
    ↓
加载驱动
```

</details>

---

### 9.7 请描述一个 Android 设备的设备树结构，并解释每个节点和属性的作用 ⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**典型 Android 设备树结构：**

```dts
/ {
    // 根节点
    model = "Google Pixel 2";
    compatible = "google,Pixel2", "qcom,msm8998";

    // 1. CPU 节点
    cpus {
        #address-cells = <2>;
        #size-cells = <0>;

        cpu@0 {
            compatible = "arm,cortex-a73";
            reg = <0x0 0x0>;
            enable-method = "psci";
        };
    };

    // 2. 内存节点
    memory {
        device_type = "memory";
        reg = <0x0 0x80000000 0x0 0x40000000>;
    };

    // 3. 中断控制器
    interrupt-controller {
        compatible = "arm,gic-v3";
        #interrupt-cells = <3>;
    };

    // 4. 时钟
    clocks {
        compatible = "qcom,msm8998-clocks";
    };

    // 5. SoC 外设
    soc {
        // UART
        serial@78af000 {
            compatible = "qcom,msm-uartdm";
            reg = <0x78af000 0x200>;
            interrupts = <GIC_SPI 108 IRQ_TYPE_LEVEL_HIGH>;
        };

        // I2C
        i2c@78b6000 {
            compatible = "qcom,i2c-msm-v2";
            reg = <0x78b6000 0x1000>;
            clocks = <&clock_gcc GCC_BLSP1_QUP2_I2C_APPS_CLK>;
        };

        // SPI
        spi@78b5000 {
            compatible = "qcom,spi-qup-v2";
            reg = <0x78b5000 0x1000>;
        };

        // USB
        usb@a800000 {
            compatible = "qcom,dwc3";
            reg = <0xa800000 0xfc000>;
        };
    };
};
```

</details>

---

### 9.8 在设备树中，如何描述和配置设备的中断信息？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**中断描述：**

```dts
// 1. 中断控制器定义
gic: interrupt-controller@17a00000 {
    compatible = "arm,gic-v3";
    #interrupt-cells = <3>;  // 中断描述符有3个cell
    interrupt-controller;
};

// 2. 设备中断配置
uart0: serial@78af000 {
    compatible = "qcom,msm-uartdm";
    reg = <0x78af000 0x200>;

    // 中断描述: <中断类型 中断号 触发方式>
    interrupts = <GIC_SPI 108 IRQ_TYPE_LEVEL_HIGH>;

    // 使用的中断控制器
    interrupt-parent = <&gic>;
};
```

**中断描述符（3 个 cell）：**

| Cell | 说明     | 值                                                  |
| ---- | -------- | --------------------------------------------------- |
| 1    | 中断类型 | `GIC_SPI` (共享外设中断) / `GIC_PPI` (私有外设中断) |
| 2    | 中断号   | 硬件中断号                                          |
| 3    | 触发方式 | `IRQ_TYPE_EDGE_RISING` / `IRQ_TYPE_LEVEL_HIGH` 等   |

</details>

---

### 9.9 如何使用设备树在内核中注册硬件设备驱动程序？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**设备树与驱动匹配流程：**

```dts
// 设备树定义
my_device: my-device@10000000 {
    compatible = "myvendor,mydevice";
    reg = <0x10000000 0x1000>;
    interrupts = <GIC_SPI 50 IRQ_TYPE_LEVEL_HIGH>;
    clocks = <&clk_controller 10>;
};
```

```c
// 驱动程序
#include <linux/module.h>
#include <linux/platform_device.h>
#include <linux/of.h>

// 1. 定义匹配表
static const struct of_device_id my_device_match[] = {
    { .compatible = "myvendor,mydevice" },
    { }
};
MODULE_DEVICE_TABLE(of, my_device_match);

// 2. 探测函数
static int my_device_probe(struct platform_device *pdev)
{
    struct device_node *np = pdev->dev.of_node;
    struct resource *res;
    int irq;

    // 获取寄存器资源
    res = platform_get_resource(pdev, IORESOURCE_MEM, 0);

    // 获取中断号
    irq = platform_get_irq(pdev, 0);

    // 获取时钟
    struct clk *clk = devm_clk_get(&pdev->dev, NULL);

    // 初始化设备...

    return 0;
}

// 3. 移除函数
static int my_device_remove(struct platform_device *pdev)
{
    // 清理资源
    return 0;
}

// 4. 注册平台驱动
static struct platform_driver my_device_driver = {
    .probe = my_device_probe,
    .remove = my_device_remove,
    .driver = {
        .name = "my-device",
        .of_match_table = my_device_match,
    },
};

module_platform_driver(my_device_driver);
```

</details>

---

### 9.10 设备树的使用有哪些优势？如何使设备树更加灵活和易于开发？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**设备树的优势：**

| 优势         | 说明                         |
| ------------ | ---------------------------- |
| **硬件抽象** | 硬件描述与内核代码分离       |
| **可维护性** | 修改硬件配置无需重新编译内核 |
| **可移植性** | 同一内核支持多种硬件平台     |
| **减少代码** | 移除大量板级支持代码         |
| **易于定制** | 针对不同产品定制硬件配置     |

**提高灵活性的方法：**

```dts
// 1. 使用 overlay (DTBO)
// 基础 DTB + 叠加层 DTBO

// 2. 使用标签引用
&uart0 {
    status = "disabled";  // 禁用设备
};

&i2c1 {
    status = "okay";
    clock-frequency = <400000>;
};

// 3. 使用宏定义
#include <dt-bindings/gpio/gpio.h>

my_gpio: gpio-controller {
    gpios = <&msm_gpio 25 GPIO_ACTIVE_HIGH>;
};

// 4. 条件编译
#ifdef CONFIG_FEATURE_A
#include "feature-a.dtsi"
#endif
```

**开发工具：**

- `dtc`：设备树编译器
- `fdtdump`：DTB 查看工具
- `fdtget`/`fdtput`：DTB 读写工具

</details>

---

## 10. Recovery 框架

### 10.1 Recovery 是什么？有什么作用？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Recovery** 是 Android 系统的一个独立运行环境，用于系统维护和恢复。

**主要作用：**

| 功能             | 说明               |
| ---------------- | ------------------ |
| **OTA 升级**     | 系统在线升级       |
| **恢复出厂设置** | 清除用户数据       |
| **系统修复**     | 修复损坏的系统分区 |
| **备份还原**     | 系统备份和恢复     |
| **刷机**         | 手动刷入 ROM       |

**Recovery 类型：**

| 类型                | 说明                            |
| ------------------- | ------------------------------- |
| **Stock Recovery**  | 厂商提供的官方 Recovery         |
| **Custom Recovery** | 第三方 Recovery（TWRP、CWM 等） |

</details>

---

### 10.2 Recovery 的启动流程是什么？⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**Recovery 启动流程：**

```
Bootloader
    ↓
检测启动标志 (misc 分区)
    ↓
判断进入 Recovery 模式？
    ├─ 是 → 加载 Recovery 镜像
    └─ 否 → 正常启动系统
    ↓
加载 recovery.img
    ↓
启动 Linux 内核
    ↓
挂载 ramdisk
    ↓
执行 /init
    ↓
启动 recovery 服务
    ↓
显示 Recovery 界面
    ↓
处理用户命令
```

**触发 Recovery 的方式：**

```bash
# 1. 按键组合（通常是 Power + Volume Up）

# 2. ADB 命令
adb reboot recovery

# 3. 系统设置
设置 -> 系统 -> 重置选项 -> 进入 Recovery

# 4. 写入 misc 分区
echo "boot-recovery" > /misc
```

</details>

---

### 10.3 OTA 升级流程是怎样的？⭐⭐⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**OTA 升级完整流程：**

```
1. 下载阶段
   ↓
系统下载 OTA 包到 /data/ota_package/
   ↓
验证 OTA 包签名
   ↓
2. 准备阶段
   ↓
写入 misc 分区（升级命令）
   ↓
重启进入 Recovery
   ↓
3. 安装阶段
   ↓
Recovery 读取 misc 分区命令
   ↓
挂载系统分区
   ↓
应用 OTA 补丁
   ↓
更新 system、vendor、boot 等分区
   ↓
4. 完成阶段
   ↓
清除 misc 分区
   ↓
重启系统
   ↓
系统启动，完成升级
```

**详细流程：**

```cpp
// Recovery 升级代码
int install_package(const char* path) {
    // 1. 挂载分区
    ensure_path_mounted(path);

    // 2. 打开 OTA 包
    ZipArchive zip;
    int err = mzOpenZipArchive(path, &zip);

    // 3. 验证签名
    if (!verify_package(&zip)) {
        return INSTALL_CORRUPT;
    }

    // 4. 执行升级脚本
    err = try_update_binary(path, &zip);

    // 5. 完成
    mzCloseZipArchive(&zip);
    return err;
}
```

</details>

---

### 10.4 Recovery 和主系统如何通信？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**通信方式：**

```
┌─────────────────┐         ┌─────────────────┐
│   主系统 (Android) │         │   Recovery 模式   │
│                 │         │                 │
│  写入 misc 分区  │ ──────> │  读取 misc 分区   │
│  (boot-recovery) │         │  (执行命令)      │
│                 │         │                 │
│  读取执行结果    │ <────── │  写入执行结果    │
│                 │         │                 │
└─────────────────┘         └─────────────────┘
```

**misc 分区结构：**

```cpp
struct bootloader_message {
    char command[32];        // 命令: "boot-recovery"
    char status[32];         // 状态
    char recovery[768];      // Recovery 命令和参数
};
```

**通信流程：**

```cpp
// 主系统写入命令
void reboot_to_recovery(const char* command) {
    struct bootloader_message boot;
    memset(&boot, 0, sizeof(boot));

    strcpy(boot.command, "boot-recovery");
    snprintf(boot.recovery, sizeof(boot.recovery),
             "recovery\n--update_package=%s\n", command);

    // 写入 misc 分区
    set_bootloader_message(&boot);

    // 重启
    reboot(RB_AUTOBOOT);
}
```

</details>

---

### 10.5 如何制作和签名 OTA 包？⭐⭐⭐

<details>
<summary>点击查看答案</summary>

**OTA 包制作：**

```bash
# 1. 生成差分包
./build/tools/releasetools/ota_from_target_files \
    -i old_target_files.zip \
    new_target_files.zip \
    update.zip

# 2. 生成完整包
./build/tools/releasetools/ota_from_target_files \
    new_target_files.zip \
    full_update.zip
```

**OTA 包签名：**

```bash
# 1. 生成密钥
openssl genrsa -out releasekey.pem 2048
openssl req -new -key releasekey.pem -out releasekey.csr
openssl x509 -req -days 365 -in releasekey.csr -signkey releasekey.pem -out releasekey.x509.pem

# 2. 签名 OTA 包
java -jar out/host/linux-x86/framework/signapk.jar \
    -w releasekey.x509.pem releasekey.pem \
    unsigned_update.zip \
    signed_update.zip
```

**OTA 包结构：**

```
update.zip
├── META-INF/
│   └── com/
│       └── google/
│           └── android/
│               ├── update-binary    # 升级程序
│               └── updater-script   # 升级脚本
├── system/
│   └── ...                          # 系统文件
├── boot.img                         # 内核镜像
└── ...
```

</details>

---

## 附录

### 面试技巧

1. **理解原理**：不仅要记住答案，更要理解底层原理
2. **结合实际**：结合自己的项目经验回答问题
3. **画图辅助**：面试时可以画图帮助说明
4. **深入细节**：对重点问题要能够深入细节
5. **举一反三**：从一个问题延伸到相关问题

### 推荐学习资源

- Android 官方文档
- AOSP 源码
- 《深入理解 Android 内核设计思想》
- 《Android 系统源代码情景分析》

---

> **注意**：本文档中的答案仅供参考，建议结合最新的 Android 版本源码进行学习。
