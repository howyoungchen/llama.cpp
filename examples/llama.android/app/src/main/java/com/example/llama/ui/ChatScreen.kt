package com.example.llama.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.llama.ChatMessage
import com.example.llama.ChatUiState
import com.example.llama.ui.theme.AiChatTheme

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onInputChange: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lastMessageContent = uiState.messages.lastOrNull()?.content

    LaunchedEffect(uiState.messages.size, lastMessageContent) {
        if (uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetadataCard(
                metadataText = uiState.metadataText,
                statusText = uiState.statusText,
                isBusy = uiState.isBusy,
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                if (uiState.messages.isEmpty()) {
                    item {
                        EmptyConversation()
                    }
                } else {
                    items(
                        items = uiState.messages,
                        key = { it.id },
                    ) { message ->
                        MessageBubble(message = message)
                    }
                }
            }

            InputBar(
                inputText = uiState.inputText,
                hint = uiState.inputHint,
                isInputEnabled = uiState.isInputEnabled,
                isActionEnabled = uiState.isActionEnabled,
                isModelReady = uiState.isModelReady,
                onInputChange = onInputChange,
                onPrimaryAction = onPrimaryAction,
            )
        }
    }
}

@Composable
private fun MetadataCard(
    metadataText: String,
    statusText: String,
    isBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Model",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = metadataText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (message.isUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
        ) {
            Text(
                text = message.content.ifEmpty { "..." },
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Pick a GGUF model to start chatting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InputBar(
    inputText: String,
    hint: String,
    isInputEnabled: Boolean,
    isActionEnabled: Boolean,
    isModelReady: Boolean,
    onInputChange: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            enabled = isInputEnabled,
            placeholder = { Text(hint) },
            maxLines = 4,
        )
        Spacer(modifier = Modifier.width(12.dp))
        FilledIconButton(
            onClick = onPrimaryAction,
            modifier = Modifier.size(56.dp),
            enabled = isActionEnabled,
        ) {
            Icon(
                imageVector = if (isModelReady) Icons.Filled.Send else Icons.Outlined.FolderOpen,
                contentDescription = if (isModelReady) "Send message" else "Pick model",
            )
        }
    }
}

@Preview
@Composable
private fun ChatScreenPreview() {
    AiChatTheme {
        ChatScreen(
            uiState = ChatUiState(
                statusText = "Model ready.",
                isModelReady = true,
                isInputEnabled = true,
                messages = listOf(
                    ChatMessage("1", "Hello", isUser = true),
                    ChatMessage("2", "Hi, how can I help?", isUser = false),
                ),
            ),
            onInputChange = {},
            onPrimaryAction = {},
        )
    }
}
