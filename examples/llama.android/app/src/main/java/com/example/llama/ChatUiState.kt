package com.example.llama

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
)

data class ChatUiState(
    val metadataText: String = "Selected GGUF model's metadata will show here.",
    val statusText: String = "Engine initializing...",
    val inputText: String = "",
    val inputHint: String = "Please first pick a GGUF model file to import.",
    val messages: List<ChatMessage> = emptyList(),
    val isModelReady: Boolean = false,
    val isInputEnabled: Boolean = false,
    val isActionEnabled: Boolean = true,
    val isBusy: Boolean = false,
    val isGenerating: Boolean = false,
)

sealed interface ChatUiEvent {
    data class ShowToast(val message: String) : ChatUiEvent
}
