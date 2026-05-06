# llama.android 架构图

## 一、完整架构总览

```mermaid
graph TB
    subgraph APP[":app 模块 (com.example.llama)"]
        direction TB
        MA["MainActivity\n(单一入口 Activity)"]
        CS["ChatScreen\n(Compose UI)"]
        MM["MainViewModel\n(状态管理 / 流程编排)"]
        CUS["ChatUiState\n(不可变 UI 状态)"]

        MA -->|"setContent { }"| CS
        MA -->|"viewModels()"| MM
        CS -->|"collectAsStateWithLifecycle()"| MM
        CS -->|"事件: sendPrompt / cancel"| MM
        MM -->|"更新"| CUS
        CS -.->|"观察"| CUS
    end

    subgraph LIB[":lib 模块 (com.arm.aichat)"]
        direction TB
        AC["AiChat\n(单例入口 object)"]
        IE["InferenceEngine\n(公共接口 + State sealed class)"]
        IEI["InferenceEngineImpl\n(JNI 封装 + 状态机 + 单线程调度)"]
        GMR["GgufMetadataReader\n(GGUF 元数据读取接口)"]
        GMRI["GgufMetadataReaderImpl\n(纯 Kotlin 二进制解析器)"]
        GM["GgufMetadata\n(强类型数据类树)"]

        AC -->|"委托"| IEI
        IEI -.->|"实现"| IE
        GMR -.->|"实现"| GMRI
        GMRI -->|"构建"| GM
    end

    subgraph NATIVE["C++ 原生层 (libai-chat.so)"]
        direction TB
        JNI["ai_chat.cpp\n(JNI 实现)"]
        LLAMA["llama.cpp\n(推理引擎)"]
        GGML["ggml\n(张量运算后端)"]
        BACKEND["CPU Backend Variants\n(KleidiAI / OpenMP / 等)"]

        JNI -->|"llama_decode / llama_sample"| LLAMA
        LLAMA -->|"计算"| GGML
        GGML -->|"动态加载"| BACKEND
    end

    subgraph BUILD["构建系统"]
        direction LR
        GRADLE["Gradle (Kotlin DSL)\nAGP 8.13.2 / Kotlin 2.3.0"]
        CMAKE["CMake 3.31.6\nNDK 29.0"]
        ABIS["ABI: arm64-v8a + x86_64"]
        GRADLE --> CMAKE
        CMAKE --> ABIS
    end

    MM -->|"AiChat.getInferenceEngine(context)"| AC
    MM -->|"readMetadata(uri)"| GMR
    IEI -->|"System.loadLibrary('ai-chat')\n@FastNative JNI calls"| JNI
    IEI -->|"onCleared() → engine.destroy()"| JNI
```

### 模块划分

| 模块 | 包名 | 类型 | 职责 |
|------|------|------|------|
| `:app` | `com.example.llama` | Android Application | 示例 UI 消费者，展示库用法 |
| `:lib` | `com.arm.aichat` | Android Library | 可复用的推理库 (JNI + GGUF 解析) |
| native | `libai-chat.so` | C++ Shared Lib | JNI 桥接 + llama.cpp 封装 |

### 核心设计模式

| 模式 | 位置 | 说明 |
|------|------|------|
| **MVVM** | `:app` | ViewModel 持有 StateFlow，Compose 观察 |
| **单例 (Double-Checked Locking)** | `InferenceEngineImpl` | `@Volatile` + `synchronized` 确保唯一实例 |
| **状态机** | `InferenceEngine.State` | 13 种 sealed class 状态，每次操作带 `check()` 守卫 |
| **单线程调度器** | `llamaDispatcher` | `Dispatchers.IO.limitedParallelism(1)` 保证 C++ 线程安全 |
| **Stream 输出** | `sendUserPrompt()` | 返回 `Flow<String>`，Token 逐个发射 |
| **流式解析** | `GgufMetadataReaderImpl` | 读取二进制 GGUF 头部元数据，跳过大型数组 |
| **Context Shifting** | `ai_chat.cpp` | 超 8192 token 时丢弃旧上下文并位移 |

---

## 二、类图

```mermaid
classDiagram
    direction TB

    class MainActivity {
        +onCreate()
        +onStop()
    }

    class ChatScreen {
        <<Composable>>
    }

    class MainViewModel {
        -_engineDeferred: Deferred~InferenceEngine~
        -_uiState: MutableStateFlow~ChatUiState~
        -_events: MutableSharedFlow~ChatUiEvent~
        +uiState: StateFlow~ChatUiState~
        +onModelSelected(uri: Uri)
        +sendPrompt()
        +cancelGeneration()
        -readMetadata(uri)
        -awaitInitializedEngine()
        -updateAssistantMessage(token)
    }

    class ChatUiState {
        <<data class>>
        +statusText: String
        +metadataText: String
        +messages: List~ChatMessage~
        +inputText: String
        +isModelReady: Boolean
        +isInputEnabled: Boolean
        +isActionEnabled: Boolean
        +isBusy: Boolean
        +isGenerating: Boolean
    }

    class ChatUiEvent {
        <<sealed interface>>
        ShowToast(msg: String)
    }

    class AiChat {
        <<object>>
        +getInferenceEngine(context: Context): InferenceEngine
    }

    class InferenceEngine {
        <<interface>>
        +loadModel(path: String)
        +setSystemPrompt(prompt: String)
        +sendUserPrompt(msg: String): Flow~String~
        +bench(pp, tg, pl, nr): Flow~String~
        +cleanUp()
        +destroy()
        +state: StateFlow~State~
    }

    class IE_State["InferenceEngine.State<br/>(sealed class)"] {
        Uninitialized
        Initializing
        Initialized
        UnsupportedArchitecture
        LoadingModel
        ModelReady
        Generating
        Cancelled
        EmptyResponse
        ModelError
        ContextError
        Error
        Shutdown
    }

    class InferenceEngineImpl {
        -llamaDispatcher: CoroutineDispatcher
        -llamaScope: CoroutineScope
        -_state: MutableStateFlow~State~
        +getInstance(nativeLibDir)
        -init(libDir)
        -load(path)
        -prepare()
        -systemInfo()
        -benchModel()
        -processSystemPrompt()
        -processUserPrompt()
        -generateNextToken()
        -unload()
        -shutdown()
    }

    class GgufMetadataReader {
        <<interface>>
        +ensureSourceFileFormat(inputStream, size)
        +readStructuredMetadata(inputStream, size)
        +readStructuredMetadata(inputStream, size, skipKeys)
    }

    class GgufMetadataReaderImpl {
        -arraySummariseThreshold: Int
        -defaultSkipKeys: Set~String~
        +readStructuredMetadata()
        -readKeyValuePairs()
        -readValue()
        -readString()
    }

    class GgufMetadata {
        +version: Int
        +basicInfo: BasicInfo
        +authorInfo: AuthorInfo?
        +architectureInfo: ArchitectureInfo?
        +tokenizerInfo: TokenizerInfo?
        +dimensionsInfo: DimensionsInfo?
        +attentionInfo: AttentionInfo?
    }

    MainActivity --> MainViewModel : viewModels()
    MainActivity --> ChatScreen : setContent
    ChatScreen --> MainViewModel : 观察 uiState
    MainViewModel *-- ChatUiState
    MainViewModel --> ChatUiEvent : 发射事件
    MainViewModel --> AiChat : 获取引擎
    MainViewModel --> GgufMetadataReader : 读取元数据
    AiChat --> InferenceEngineImpl : 创建
    InferenceEngineImpl ..|> InferenceEngine : 实现
    InferenceEngine *-- IE_State : 嵌套
    InferenceEngineImpl ..> GgufMetadataReaderImpl
    GgufMetadataReaderImpl ..|> GgufMetadataReader : 实现
    GgufMetadataReaderImpl --> GgufMetadata : 构建
```

### 状态机流转

```mermaid
stateDiagram-v2
    [*] --> Uninitialized
    Uninitialized --> Initializing : getInstance()
    Initializing --> Initialized : init() 成功
    Initializing --> UnsupportedArchitecture : 加载 .so 失败
    Initializing --> Error : 初始化异常
    Initialized --> LoadingModel : loadModel(path)
    LoadingModel --> ModelReady : 加载成功
    LoadingModel --> UnsupportedArchitecture : 模型架构不支持
    LoadingModel --> Error : 加载失败
    ModelReady --> Generating : sendUserPrompt()
    Generating --> ModelReady : 生成完成
    Generating --> Cancelled : cancelGeneration()
    Generating --> EmptyResponse : 无输出
    Generating --> Error : 生成异常
    Cancelled --> ModelReady : 自动过渡
    ModelReady --> Initialized : cleanUp()
    EmptyResponse --> ModelReady : 自动过渡
    Initialized --> Shutdown : destroy()
    ModelReady --> Shutdown : destroy()
    Error --> Shutdown : destroy()
    Shutdown --> [*]
```

---

## 三、时序图：从用户输入到 Token 生成

```mermaid
sequenceDiagram
    actor User
    participant CS as ChatScreen<br/>(Compose)
    participant VM as MainViewModel
    participant AC as AiChat<br/>(单例)
    participant IEI as InferenceEngineImpl<br/>(JNI 封装)
    participant DISP as llamaDispatcher<br/>(单线程)
    participant JNI as ai_chat.cpp
    participant LLM as llama.cpp

    Note over VM, AC: 初始化阶段 (onCreate 时异步触发)
    VM->>AC: getInferenceEngine(context)
    AC->>IEI: getInstance(nativeLibDir)
    IEI->>JNI: init() → 加载 CPU Backend
    IEI->>JNI: 状态 → Initialized

    Note over User, LLM: 选择模型

    User->>CS: 选择 GGUF 文件
    CS->>VM: onModelSelected(uri)
    VM->>VM: readMetadata(uri) → 解析 GGUF 头
    VM->>VM: 复制模型到 filesDir/models/
    VM->>AC: engine.loadModel(path)
    AC->>IEI: loadModel(path)
    IEI->>DISP: dispatch → load(path)
    DISP->>JNI: llama_model_load_from_file()
    JNI->>LLM: 加载模型权重
    DISP->>JNI: prepare() → 创建 context + sampler
    IEI-->>VM: 状态 → ModelReady

    Note over User, LLM: 发送提示词

    User->>CS: 输入消息 → 点击发送
    CS->>VM: sendPrompt()
    VM->>AC: engine.sendUserPrompt(msg)
    AC->>IEI: sendUserPrompt(msg)

    IEI->>DISP: dispatch → processUserPrompt()
    DISP->>JNI: processUserPrompt(msg)
    JNI->>LLM: tokenize + llama_decode()

    loop 逐 Token 生成
        IEI->>DISP: generateNextToken()
        DISP->>JNI: generateNextToken()
        JNI->>LLM: common_sampler_sample() → 采样
        JNI->>LLM: llama_decode() → 解码
        JNI-->>DISP: UTF-8 字符串
        DISP-->>IEI: emit(token)
        IEI-->>VM: Flow<String> 发射 token
        VM->>VM: updateAssistantMessage(token)
        VM-->>CS: uiState 更新
        CS-->>User: 渲染新 token
    end

    IEI-->>VM: Flow 完成 (EOG)
    IEI->>IEI: 状态 → ModelReady

    Note over User, LLM: 清理

    User->>CS: 退出应用
    CS->>VM: onCleared()
    VM->>AC: engine.destroy()
    AC->>IEI: destroy()
    IEI->>DISP: shutdown()
    DISP->>JNI: unload() + shutdown()
    JNI->>LLM: llama_backend_free()
```

### 关键异步机制

| 阶段 | 线程 | 调度器 | 说明 |
|------|------|--------|------|
| 引擎初始化 | 后台 | `Dispatchers.Default` + `viewModelScope.async` | 延迟初始化，多处 `await()` |
| 模型加载 | 串行 | `llamaDispatcher` (IO, parallelism=1) | 防止并发操作 C++ 全局状态 |
| Token 生成 | 串行 | `llamaDispatcher` | 每个 token 通过 Flow emit |
| UI 更新 | 主线程 | `collectAsStateWithLifecycle()` | Compose 自动重组 |
| GGUF 解析 | I/O | `Dispatchers.IO` | 纯 Kotlin，不涉及 JNI |

---

## 四、构建系统

```mermaid
graph LR
    subgraph Gradle["Gradle 8.14.3 + AGP 8.13.2"]
        direction LR
        SET["settings.gradle.kts\n:app + :lib"]
        ROOT["build.gradle.kts\n插件声明"]
        APP["app/build.gradle.kts\ncompileSdk 36 | minSdk 33\nCompose BOM 2026.04.01"]
        LIB["lib/build.gradle.kts\nNDK 29.0 | CMake 3.31.6\nABI: arm64-v8a, x86_64"]
        SET --> ROOT
        ROOT --> APP
        ROOT --> LIB
        APP -->|"implementation(project(':lib'))"| LIB
    end

    subgraph CMake["CMake"]
        direction LR
        CL["CMakeLists.txt\n(src/main/cpp/)"]
        SRC["llama.cpp 仓库根目录\nadd_subdirectory()"]
        FLAGS["ABI 特定编译标志\narm64: KleidiAI + OpenMP\nx86_64: 无额外加速"]
        CL --> SRC
        CL --> FLAGS
    end

    LIB --> CMake

    subgraph OUTPUT["产物"]
        direction LR
        APK["app-debug.apk / app-release.apk"]
        AAR["lib-release.aar\n(可独立发布)"]
    end

    APP --> APK
    LIB --> AAR
```

### Gradle 传递给 CMake 的参数

```
-DBUILD_SHARED_LIBS=ON
-DLLAMA_BUILD_COMMON=ON
-DLLAMA_OPENSSL=OFF
-DGGML_NATIVE=OFF
-DGGML_BACKEND_DL=ON
-DGGML_CPU_ALL_VARIANTS=ON
-DGGML_LLAMAFILE=OFF
```

---

## 五、Context Shifting 机制

```mermaid
sequenceDiagram
    participant JNI as ai_chat.cpp
    participant LLM as llama.cpp

    Note over JNI: 假设 context 大小 = 8192

    JNI->>LLM: processSystemPrompt() → 填充 system tokens
    Note over JNI: system_prompt_size = 200

    JNI->>LLM: processUserPrompt("问题1") → 追加 tokens
    Note over JNI: current_position = 350

    JNI->>LLM: generateNextToken() x N → 生成回答1
    Note over JNI: current_position = 650

    JNI->>LLM: processUserPrompt("问题2") → 追加 tokens
    Note over JNI: current_position = 800

    loop 持续对话...
        Note over JNI: 当 current_position >= 8192

        JNI->>LLM: ctx_kv_shift(shift_start, shift_len, offset)
        Note over JNI: 保留 system_prompt (0-200)<br/>丢弃旧对话前半部分<br/>剩余 tokens 向左位移
    end
```
