package com.example.llama

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>()
    val events = _events.asSharedFlow()

    private var engine: InferenceEngine? = null
    private var generationJob: Job? = null

    private val engineDeferred = viewModelScope.async(Dispatchers.Default) {
        AiChat.getInferenceEngine(getApplication<Application>().applicationContext).also {
            engine = it
        }
    }

    init {
        observeEngineState()
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun onModelSelected(uri: Uri) {
        if (_uiState.value.isBusy) return

        _uiState.update {
            it.copy(
                metadataText = "Parsing metadata from selected file\n$uri",
                statusText = "Parsing GGUF...",
                inputHint = "Parsing GGUF...",
                isActionEnabled = false,
                isInputEnabled = false,
                isBusy = true,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val metadata = readMetadata(uri)
                _uiState.update {
                    it.copy(
                        metadataText = metadata.toString(),
                        statusText = "Metadata parsed.",
                    )
                }

                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                val modelFile = copyModelIfNeeded(uri, modelName)
                val inferenceEngine = awaitInitializedEngine()

                _uiState.update {
                    it.copy(
                        statusText = "Loading model $modelName...",
                        inputHint = "Loading model...",
                    )
                }
                inferenceEngine.loadModel(modelFile.path)

                _uiState.update {
                    it.copy(
                        statusText = "Model ready: $modelName",
                        inputHint = "Type and send a message!",
                        isModelReady = true,
                        isInputEnabled = true,
                        isActionEnabled = true,
                        isBusy = false,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load selected model", e)
                showFailure("Failed to load model: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun sendPrompt() {
        val currentState = _uiState.value
        val userMessage = currentState.inputText
        if (userMessage.isEmpty()) {
            viewModelScope.launch {
                _events.emit(ChatUiEvent.ShowToast("Input message is empty!"))
            }
            return
        }
        if (!currentState.isModelReady || currentState.isGenerating) return

        val assistantMessageId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                inputText = "",
                inputHint = "Generating...",
                messages = it.messages +
                    ChatMessage(UUID.randomUUID().toString(), userMessage, isUser = true) +
                    ChatMessage(assistantMessageId, "", isUser = false),
                isActionEnabled = false,
                isInputEnabled = false,
                isBusy = true,
                isGenerating = true,
            )
        }

        generationJob = viewModelScope.launch(Dispatchers.Default) {
            val assistantMessage = StringBuilder()
            try {
                awaitReadyEngine().sendUserPrompt(userMessage).collect { token ->
                    assistantMessage.append(token)
                    updateAssistantMessage(assistantMessageId, assistantMessage.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate assistant response", e)
                _events.emit(ChatUiEvent.ShowToast("Generation failed: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                _uiState.update {
                    it.copy(
                        inputHint = if (it.isModelReady) "Type and send a message!" else it.inputHint,
                        isActionEnabled = true,
                        isInputEnabled = it.isModelReady,
                        isBusy = false,
                        isGenerating = false,
                    )
                }
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    override fun onCleared() {
        cancelGeneration()
        engine?.destroy()
        super.onCleared()
    }

    private fun observeEngineState() {
        viewModelScope.launch {
            try {
                engineDeferred.await().state.collect { state ->
                    _uiState.update { current ->
                        current.copy(statusText = state.toStatusText(current.statusText))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize inference engine", e)
                showFailure("Failed to initialize engine: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun readMetadata(uri: Uri): GgufMetadata {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openInputStream(uri)?.use {
            GgufMetadataReader.create().readStructuredMetadata(it)
        } ?: error("Unable to open selected file.")
    }

    private suspend fun copyModelIfNeeded(uri: Uri, modelName: String): File =
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.openInputStream(uri)?.use { input ->
                ensureModelFile(modelName, input)
            } ?: error("Unable to reopen selected file.")
        }

    private suspend fun ensureModelFile(modelName: String, input: InputStream): File =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    _uiState.update {
                        it.copy(
                            statusText = "Copying file...",
                            inputHint = "Copying file...",
                        )
                    }
                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                    _uiState.update { it.copy(statusText = "Using cached model file...") }
                }
            }
        }

    private fun ensureModelsDirectory(): File =
        File(getApplication<Application>().filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) it.delete()
            if (!it.exists()) it.mkdirs()
        }

    private suspend fun awaitInitializedEngine(): InferenceEngine {
        val inferenceEngine = engineDeferred.await()
        when (val state = inferenceEngine.state.first { it.canLoadModel }) {
            is InferenceEngine.State.Error -> throw state.exception
            else -> return inferenceEngine
        }
    }

    private suspend fun awaitReadyEngine(): InferenceEngine {
        val inferenceEngine = engineDeferred.await()
        when (val state = inferenceEngine.state.first { it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error }) {
            is InferenceEngine.State.Error -> throw state.exception
            else -> return inferenceEngine
        }
    }

    private fun updateAssistantMessage(messageId: String, content: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == messageId) message.copy(content = content) else message
                },
            )
        }
    }

    private suspend fun showFailure(message: String) {
        _uiState.update {
            it.copy(
                statusText = message,
                inputHint = if (it.isModelReady) "Type and send a message!" else "Please first pick a GGUF model file to import.",
                isActionEnabled = true,
                isInputEnabled = it.isModelReady,
                isBusy = false,
                isGenerating = false,
            )
        }
        _events.emit(ChatUiEvent.ShowToast(message))
    }

    private val InferenceEngine.State.canLoadModel: Boolean
        get() = this is InferenceEngine.State.Initialized ||
            this is InferenceEngine.State.ModelReady ||
            this is InferenceEngine.State.Error

    private fun InferenceEngine.State.toStatusText(previous: String): String =
        when (this) {
            InferenceEngine.State.Uninitialized -> "Engine waiting to initialize..."
            InferenceEngine.State.Initializing -> "Engine initializing..."
            InferenceEngine.State.Initialized -> "Engine initialized. Pick a GGUF model."
            InferenceEngine.State.LoadingModel -> "Loading model..."
            InferenceEngine.State.UnloadingModel -> "Unloading model..."
            InferenceEngine.State.ModelReady -> "Model ready."
            InferenceEngine.State.Benchmarking -> "Benchmarking..."
            InferenceEngine.State.ProcessingSystemPrompt -> "Processing system prompt..."
            InferenceEngine.State.ProcessingUserPrompt -> "Processing user prompt..."
            InferenceEngine.State.Generating -> "Generating..."
            is InferenceEngine.State.Error -> "Engine error: ${exception.message ?: exception.javaClass.simpleName}"
        }.takeUnless { isBusyStatus(previous) } ?: previous

    private fun isBusyStatus(status: String): Boolean =
        status == "Parsing GGUF..." ||
            status == "Copying file..." ||
            status == "Using cached model file..."

    companion object {
        private val TAG = MainViewModel::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"
    }
}

private fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toString(16)}"
    }
}
