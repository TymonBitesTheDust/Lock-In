package app.lockin.core.network

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val firebase: FirebaseProvider,
) {
    private val profileChanges = MutableStateFlow(0)

    val isAvailable: Boolean get() = firebase.isConfigured

    /** The signed-in account, or null. Emits null forever when Firebase isn't configured. */
    val account: Flow<Account?> by lazy {
        if (!firebase.isConfigured) {
            flowOf(null)
        } else {
            val users = callbackFlow {
                val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
                firebase.auth.addAuthStateListener(listener)
                awaitClose { firebase.auth.removeAuthStateListener(listener) }
            }
            combine(users, profileChanges) { _, _ -> firebase.auth.currentUser?.toAccount() }.distinctUntilChanged()
        }
    }

    fun currentAccount(): Account? = if (firebase.isConfigured) firebase.auth.currentUser?.toAccount() else null

    suspend fun signUp(name: String, email: String, password: String): Unit = friendly {
        val result = firebase.auth.createUserWithEmailAndPassword(email.trim(), password).await()
        result.user?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build())?.await()
        firebase.auth.currentUser?.reload()?.await()
        profileChanges.value++
    }

    suspend fun signIn(email: String, password: String): Unit = friendly {
        firebase.auth.signInWithEmailAndPassword(email.trim(), password).await()
        Unit
    }

    suspend fun sendPasswordReset(email: String): Unit = friendly {
        firebase.auth.sendPasswordResetEmail(email.trim()).await()
        Unit
    }

    fun signOut() {
        if (firebase.isConfigured) firebase.auth.signOut()
    }

    private suspend fun <T> friendly(block: suspend () -> T): T = try {
        block()
    } catch (e: FirebaseAuthException) {
        throw GroupException(authMessage(e.errorCode))
    } catch (e: GroupException) {
        throw e
    } catch (e: Exception) {
        throw GroupException(e.message ?: "Something went wrong. Check your connection and try again.")
    }

    private fun authMessage(code: String): String = when (code) {
        "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Sign in instead."
        "ERROR_WEAK_PASSWORD" -> "Use a password of at least 6 characters."
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" -> "Email or password is incorrect."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a minute and try again."
        "ERROR_NETWORK_REQUEST_FAILED" -> "No connection. Check your internet and try again."
        else -> "Sign-in failed ($code)."
    }

    private fun FirebaseUser.toAccount() = Account(
        uid = uid,
        email = email,
        displayName = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: "Student",
    )
}
