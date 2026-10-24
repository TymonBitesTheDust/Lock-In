package app.lockin.core.network

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase is optional at build time: without app/google-services.json the app works fully
 * offline and the Groups tab explains that groups are not set up in this build.
 */
@Singleton
class FirebaseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isConfigured: Boolean by lazy {
        FirebaseApp.getApps(context).isNotEmpty() || runCatching { FirebaseApp.initializeApp(context) != null }.getOrDefault(false)
    }

    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
}
