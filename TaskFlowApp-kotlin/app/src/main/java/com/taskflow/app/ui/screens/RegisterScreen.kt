package com.taskflow.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
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
import com.taskflow.app.data.model.RegisterRequest
import com.taskflow.app.data.remote.ApiErrors
import com.taskflow.app.data.remote.RetrofitClient
import kotlinx.coroutines.launch

/** Backend rule in handlers/auth.go: `len(req.Password) < 8` is rejected. */
private const val MIN_PASSWORD_LENGTH = 8

/**
 * Account creation screen — the mirror of [LoginScreen].
 *
 * Calls POST /auth/register, which returns the same `{ token, user }` payload as
 * login, so a successful registration logs the user straight in: the JWT is
 * saved and the caller navigates on.
 *
 * Client-side validation matches the backend's rules exactly (username, email
 * and password all required; password at least [MIN_PASSWORD_LENGTH]
 * characters) so the common mistakes are caught without a round trip. The
 * server remains the authority — anything it rejects is surfaced through
 * [ApiErrors].
 *
 * @param tokenManager The process-wide instance from TaskFlowApp.
 * @param onRegisterSuccess Called when registration succeeds (navigate to home).
 * @param onNavigateToLogin Called when the user taps "Sign in".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    tokenManager: TokenManager,
    onRegisterSuccess: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    fun doRegister() {
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            errorMessage = "Username, email and password are required"
            return
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            errorMessage = "Password must be at least $MIN_PASSWORD_LENGTH characters"
            return
        }

        isLoading = true
        errorMessage = null
        successMessage = null

        scope.launch {
            try {
                val response = RetrofitClient.getInstance()
                    .authApi
                    .register(
                        RegisterRequest(
                            username = username.trim(),
                            email = email.trim(),
                            password = password,
                        )
                    )

                val body = response.body()
                if (response.isSuccessful && body != null) {
                    // /auth/register returns a token too — no second login needed.
                    tokenManager.saveToken(body.token)
                    successMessage = "Account created. Welcome, ${body.user.username}!"
                    onRegisterSuccess()
                } else if (response.isSuccessful) {
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
                .padding(horizontal = 32.dp),
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
                text = "Create your account",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(40.dp))

            // ─── Username field ───
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; errorMessage = null },
                label = { Text("Username") },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

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
                supportingText = { Text("At least $MIN_PASSWORD_LENGTH characters") },
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { doRegister() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ─── Register button ───
            Button(
                onClick = { doRegister() },
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
                        text = "Create Account",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ─── Toggle to Login ───
            TextButton(
                onClick = onNavigateToLogin,
                enabled = !isLoading,
            ) {
                Text(
                    text = "Already have an account? Sign in",
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
