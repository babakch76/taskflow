package com.taskflow.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskflow.app.data.local.TokenManager
import com.taskflow.app.data.model.LoginRequest
import com.taskflow.app.data.remote.ApiErrors
import com.taskflow.app.data.remote.RetrofitClient
import kotlinx.coroutines.launch

/**
 * Login screen for the auth flow.
 *
 * Calls POST /auth/login via Retrofit, saves the JWT via [TokenManager],
 * and reports success/failure. This is enough to verify:
 *  1. Retrofit reaches the Go backend at BuildConfig.BASE_URL
 *  2. Gson correctly parses the AuthResponse (including OffsetDateTime)
 *  3. The JWT is stored in EncryptedSharedPreferences
 *  4. Subsequent requests include the Authorization header
 *
 * @param tokenManager The process-wide instance from TaskFlowApp. Passed in
 *   rather than constructed here: building EncryptedSharedPreferences inside a
 *   composition puts a Keystore round-trip on the UI thread.
 * @param onLoginSuccess Called when the login succeeds (navigate to home).
 * @param onNavigateToRegister Called when the user taps "Create one".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    tokenManager: TokenManager,
    onLoginSuccess: () -> Unit = {},
    onNavigateToRegister: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    fun doLogin() {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Email and password are required"
            return
        }

        isLoading = true
        errorMessage = null
        successMessage = null

        scope.launch {
            try {
                val response = RetrofitClient.getInstance()
                    .authApi
                    .login(LoginRequest(email = email.trim(), password = password))

                val body = response.body()
                if (response.isSuccessful && body != null) {
                    tokenManager.saveToken(body.token)
                    successMessage = "Welcome, ${body.user.username}!"
                    onLoginSuccess()
                } else if (response.isSuccessful) {
                    // 2xx with no parseable body — the server answered, but not
                    // with the token we need, so there is nothing to log in with.
                    errorMessage = "Server returned an empty response. Please try again."
                } else {
                    errorMessage = ApiErrors.messageFor(response)
                }
            } catch (e: Exception) {
                errorMessage = "Network error: ${e.localizedMessage ?: "could not reach server"}"
            } finally {
                isLoading = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // imePadding + scroll: without these the soft keyboard covers
                // the submit button with no way to reach it.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ─── Header ───
            Text(
                text = "TaskFlow",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sign in to your workspace",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(40.dp))

            // ─── Email field ───
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; errorMessage = null },
                label = { Text("Email") },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ─── Password field ───
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { doLogin() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ─── Login button ───
            Button(
                onClick = { doLogin() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isLoading,
                shape = MaterialTheme.shapes.medium,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "Sign In",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ─── Toggle to Register ───
            TextButton(
                onClick = onNavigateToRegister,
                enabled = !isLoading,
            ) {
                Text(
                    text = "No account? Create one",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── Feedback messages ───
            AnimatedVisibility(visible = errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            AnimatedVisibility(visible = successMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = successMessage ?: "",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
