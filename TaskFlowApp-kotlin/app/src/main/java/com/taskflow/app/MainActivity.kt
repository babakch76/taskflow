package com.taskflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.taskflow.app.data.local.TokenManager
import com.taskflow.app.data.remote.RetrofitClient
import com.taskflow.app.data.remote.SessionManager
import com.taskflow.app.ui.navigation.AppNavGraph
import com.taskflow.app.ui.navigation.Routes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.taskflow.app.ui.theme.Appearance
import com.taskflow.app.ui.theme.AppearanceMode
import com.taskflow.app.ui.theme.TaskFlowTheme

/**
 * Single-activity entry point for the TaskFlow Android app.
 *
 * Responsibilities:
 *  1. Take the process-wide [TokenManager] from [TaskFlowApp] (which also
 *     initialized [RetrofitClient]) and hand it to the nav graph
 *  2. Host the [AppNavGraph] (Login/Register → Dashboard → GroupDetail)
 *  3. Observe [SessionManager.sessionExpired] to force navigation
 *     back to Login on any 401 response (global JWT expiry handling)
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Constructed once in TaskFlowApp.onCreate — never build another one.
        val tokenManager = (application as TaskFlowApp).tokenManager

        // Read before the first frame, so the app does not start in one
        // appearance and blink into the other.
        Appearance.load(this)

        setContent {
            val appearance by Appearance.mode.collectAsState()
            TaskFlowTheme(
                darkTheme = when (appearance) {
                    AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                    AppearanceMode.LIGHT -> false
                    AppearanceMode.DARK -> true
                },
            ) {
                val navController = rememberNavController()

                // Observe session expiry — redirect to login on 401
                LaunchedEffectSessionExpiry(navController, tokenManager)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavGraph(
                        navController = navController,
                        tokenManager = tokenManager,
                    )
                }
            }
        }
    }
}

/**
 * Composable-side effect that collects [SessionManager.sessionExpired] events
 * and navigates to the Login screen, clearing the entire back stack.
 */
@Composable
private fun LaunchedEffectSessionExpiry(
    navController: NavHostController,
    tokenManager: TokenManager,
) {
    LaunchedEffect(Unit) {
        SessionManager.sessionExpired.collect {
            tokenManager.clearToken()
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }
}
