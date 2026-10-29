package de.waschbeken.heliolink.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Locks the app with fingerprint, face or the phone's screen lock. */
object AppLock {
    /** The lock needs a screen lock on the phone; without one Android cannot check who opens the app. */
    fun available(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Shows Android's own prompt (fingerprint, face or PIN/pattern). On Android 9 and older only the
     * screen lock works; that path goes through [fallback], which starts the keyguard's intent.
     */
    fun prompt(activity: Activity, onSuccess: () -> Unit, onFail: (String?) -> Unit, fallback: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fallback()
            return
        }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("HelioLink entsperren")
            .setSubtitle("Deine Gesundheitsdaten sind geschützt")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFail(errString?.toString())
            },
        )
    }

    /** Intent for Android 8 and 9: the phone's lock screen confirms who you are. */
    @Suppress("DEPRECATION")
    fun keyguardIntent(context: Context) =
        context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent("HelioLink entsperren", null)
}

/** Shown instead of the app while it is locked; asks right away and offers a button to ask again. */
@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("HelioLink ist gesperrt", style = MaterialTheme.typography.titleLarge)
            Text(
                message ?: "Entsperre mit Fingerabdruck, Gesicht oder der PIN deines Handys.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onUnlock) { Text("Entsperren") }
        }
    }
}
