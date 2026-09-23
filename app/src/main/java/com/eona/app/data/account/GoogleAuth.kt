package com.eona.app.data.account

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.eona.app.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** What Google's sheet came back with: an identity token, or a message (empty = closed). */
sealed interface GoogleResult {
    data class Token(val idToken: String) : GoogleResult
    data class Failure(val message: String) : GoogleResult
}

/**
 * "Continuer avec Google", through Android's Credential Manager. The phone only fetches an
 * identity token and hands it to the server, which checks it with Google itself: nothing is
 * decided here.
 */
object GoogleAuth {
    /** Shown only when this build carries the web client id. */
    val isAvailable: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Google's own sheet: pick an account, get its token. [context] must be the activity. */
    suspend fun identityToken(context: Context): GoogleResult {
        if (!isAvailable) return GoogleResult.Failure("Connexion Google indisponible dans cette version.")
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleResult.Failure("Réponse inattendue de Google.")
            }
        } catch (e: GetCredentialCancellationException) {
            // The driver simply closed Google's sheet.
            GoogleResult.Failure("")
        } catch (e: NoCredentialException) {
            GoogleResult.Failure("Aucun compte Google sur ce téléphone.")
        } catch (e: GoogleIdTokenParsingException) {
            GoogleResult.Failure("Réponse inattendue de Google.")
        } catch (e: GetCredentialException) {
            GoogleResult.Failure("Connexion Google impossible pour l'instant.")
        }
    }

    /** Forgets the Google choice on this phone (after unlinking, or signing out). */
    suspend fun signOut(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
