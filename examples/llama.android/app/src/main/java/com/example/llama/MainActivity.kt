package com.example.llama

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.llama.ui.ChatScreen
import com.example.llama.ui.theme.AiChatTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let(viewModel::onModelSelected)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        is ChatUiEvent.ShowToast -> {
                            Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            AiChatTheme {
                ChatScreen(
                    uiState = uiState,
                    onInputChange = viewModel::onInputChange,
                    onPrimaryAction = {
                        if (uiState.isModelReady) {
                            viewModel.sendPrompt()
                        } else {
                            getContent.launch(arrayOf("*/*"))
                        }
                    },
                    modifier = Modifier,
                )
            }
        }
    }

    override fun onStop() {
        viewModel.cancelGeneration()
        super.onStop()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}
