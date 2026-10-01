package com.eona.app.data.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.eona.app.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

sealed interface GoogleResult {
    data class Token(val idToken: String) : GoogleResult
    data class Failure(val message: String) : GoogleResult
}

/** Google fournit jeton ; backend vérifie identité. */
object GoogleAuth {
    val isAvailable: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    suspend fun identityToken(context: Context): GoogleResult {
        if (!isAvailable) return GoogleResult.Failure("Connexion Google indisponible dans cette version.")
        val activity = activity(context)
            ?: return GoogleResult.Failure("Ferme cet écran puis réessaie Google.")
        val manager = CredentialManager.create(activity)
        val foreground = MutableContextWrapper(activity)
        return try {
            val button = GetCredentialRequest.Builder().addCredentialOption(
                GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build(),
            ).build()
            val credential = try {
                manager.getCredential(foreground, button).credential
            } catch (e: GetCredentialCancellationException) {
                throw e
            } catch (e: GetCredentialException) {
                if (configurationError(e)) throw e
                // Fournisseur du bouton indisponible : propose tous les comptes.
                val accounts = GetGoogleIdOption.Builder()
                    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                manager.getCredential(foreground,
                    GetCredentialRequest.Builder().addCredentialOption(accounts).build()).credential
            }
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else GoogleResult.Failure("Réponse inattendue de Google.")
        } catch (e: GetCredentialCancellationException) {
            GoogleResult.Failure("")
        } catch (e: NoCredentialException) {
            GoogleResult.Failure("Ajoute un compte Google au téléphone puis réessaie.")
        } catch (e: GoogleIdTokenParsingException) {
            GoogleResult.Failure("Réponse inattendue de Google.")
        } catch (e: GetCredentialException) {
            // Type et code seulement. Aucun jeton, email ou message brut.
            val code = Regex("\\b(?:10|8|16|28444)\\b").find(e.message.orEmpty())?.value
            Log.w("GoogleAuth", "Échec Google : ${e.type}, code=${code ?: "inconnu"}")
            GoogleResult.Failure(if (configurationError(e)) "Configuration Google Android invalide (10)."
                else "Connexion Google impossible pour l'instant." + (code?.let { " Code $it." } ?: ""))
        }
    }

    private fun configurationError(error: GetCredentialException): Boolean =
        error.message.orEmpty().contains("DEVELOPER_ERROR", ignoreCase = true) ||
            Regex("\\b10\\b").containsMatchIn(error.message.orEmpty())

    private fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return current as? Activity
    }

    suspend fun signOut(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
