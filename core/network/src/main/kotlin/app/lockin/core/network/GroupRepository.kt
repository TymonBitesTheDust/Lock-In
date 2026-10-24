package app.lockin.core.network

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore layout (rules in firestore.rules):
 * - groups/{id}            name, code, ownerId, memberIds[] (max 30), createdAt
 * - groupCodes/{code}      groupId, ownerId: lets someone join by code without reading groups
 * - groups/{id}/messages   uid, name, text, sentAt
 * - presence/{uid}         see [MemberPresence]
 * - nudges/{uid}/items     fromUid, fromName, groupName, sentAt, seen
 */
@Singleton
class GroupRepository @Inject constructor(
    private val firebase: FirebaseProvider,
) {
    private val db get() = firebase.db

    fun myGroups(uid: String): Flow<List<StudyGroup>> = callbackFlow {
        val registration = db.collection(GROUPS).whereArrayContains(MEMBER_IDS, uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toGroup() }.sortedBy { it.name.lowercase() })
            }
        awaitClose { registration.remove() }
    }

    suspend fun create(name: String, owner: Account): StudyGroup {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty()) throw GroupException("Give the group a name.")
        repeat(5) {
            val code = randomCode()
            val codeRef = db.collection(CODES).document(code)
            if (codeRef.get().await().exists()) return@repeat
            val groupRef = db.collection(GROUPS).document()
            db.batch()
                .set(
                    groupRef,
                    mapOf(
                        "name" to trimmed,
                        "code" to code,
                        "ownerId" to owner.uid,
                        MEMBER_IDS to listOf(owner.uid),
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
                .set(codeRef, mapOf("groupId" to groupRef.id, "ownerId" to owner.uid))
                .commit()
                .await()
            return StudyGroup(groupRef.id, trimmed, code, owner.uid, listOf(owner.uid))
        }
        throw GroupException("Couldn't create the group. Try again.")
    }

    /** Joins by invite code. Returns the group id. */
    suspend fun join(code: String, account: Account): String {
        val normalized = code.trim().uppercase()
        if (normalized.length != CODE_LENGTH) throw GroupException("Invite codes have $CODE_LENGTH characters.")
        val codeDoc = try {
            db.collection(CODES).document(normalized).get().await()
        } catch (e: Exception) {
            throw GroupException("Couldn't check that code. Check your connection.")
        }
        val groupId = codeDoc.getString("groupId") ?: throw GroupException("No group has that code.")
        try {
            db.collection(GROUPS).document(groupId).update(MEMBER_IDS, FieldValue.arrayUnion(account.uid)).await()
        } catch (e: Exception) {
            throw GroupException("Couldn't join. The group may be full ($MAX_MEMBERS members).")
        }
        return groupId
    }

    suspend fun leave(groupId: String, uid: String) {
        db.collection(GROUPS).document(groupId).update(MEMBER_IDS, FieldValue.arrayRemove(uid)).await()
    }

    fun presence(uids: List<String>): Flow<List<MemberPresence>> {
        if (uids.isEmpty()) return flowOf(emptyList())
        return callbackFlow {
            val registration = db.collection(PRESENCE)
                .whereIn(FieldPath.documentId(), uids.take(MAX_MEMBERS))
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    trySend(snapshot?.documents.orEmpty().mapNotNull { it.toPresence() })
                }
            awaitClose { registration.remove() }
        }
    }

    suspend fun publishPresence(presence: MemberPresence) {
        db.collection(PRESENCE).document(presence.uid).set(
            mapOf(
                "displayName" to presence.displayName,
                "state" to presence.state.name,
                "subject" to presence.subject,
                "runningSinceMs" to presence.runningSinceMs,
                "todayKey" to presence.todayKey,
                "todayMs" to presence.todayMs,
                "weekKey" to presence.weekKey,
                "weekMs" to presence.weekMs,
                "updatedAtMs" to presence.updatedAtMs,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    fun messages(groupId: String): Flow<List<ChatMessage>> = callbackFlow {
        val registration = db.collection(GROUPS).document(groupId).collection(MESSAGES)
            .orderBy("sentAt", Query.Direction.ASCENDING)
            .limitToLast(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(
                    snapshot?.documents.orEmpty().map { doc ->
                        ChatMessage(
                            id = doc.id,
                            uid = doc.getString("uid").orEmpty(),
                            name = doc.getString("name").orEmpty(),
                            text = doc.getString("text").orEmpty(),
                            sentAtMs = doc.getTimestamp("sentAt", ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
                        )
                    },
                )
            }
        awaitClose { registration.remove() }
    }

    suspend fun send(groupId: String, from: Account, text: String) {
        val trimmed = text.trim().take(500)
        if (trimmed.isEmpty()) return
        db.collection(GROUPS).document(groupId).collection(MESSAGES).add(
            mapOf("uid" to from.uid, "name" to from.displayName, "text" to trimmed, "sentAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    suspend fun nudge(toUid: String, from: Account, groupName: String) {
        db.collection(NUDGES).document(toUid).collection(ITEMS).add(
            mapOf(
                "fromUid" to from.uid,
                "fromName" to from.displayName,
                "groupName" to groupName,
                "sentAt" to FieldValue.serverTimestamp(),
                "seen" to false,
            ),
        ).await()
    }

    /** Unseen nudges for [uid], live. */
    fun pendingNudges(uid: String): Flow<List<Nudge>> = callbackFlow {
        val registration = db.collection(NUDGES).document(uid).collection(ITEMS)
            .whereEqualTo("seen", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.documents.orEmpty().map { it.toNudge() })
            }
        awaitClose { registration.remove() }
    }

    suspend fun markNudgeSeen(uid: String, nudgeId: String) {
        db.collection(NUDGES).document(uid).collection(ITEMS).document(nudgeId).update("seen", true).await()
    }

    private fun DocumentSnapshot.toGroup(): StudyGroup? {
        val name = getString("name") ?: return null
        @Suppress("UNCHECKED_CAST")
        val members = (get(MEMBER_IDS) as? List<String>).orEmpty()
        return StudyGroup(id, name, getString("code").orEmpty(), getString("ownerId").orEmpty(), members)
    }

    private fun DocumentSnapshot.toPresence(): MemberPresence? {
        val state = runCatching { PresenceState.valueOf(getString("state") ?: "IDLE") }.getOrDefault(PresenceState.IDLE)
        return MemberPresence(
            uid = id,
            displayName = getString("displayName") ?: return null,
            state = state,
            subject = getString("subject"),
            runningSinceMs = getLong("runningSinceMs"),
            todayKey = getString("todayKey").orEmpty(),
            todayMs = getLong("todayMs") ?: 0L,
            weekKey = getString("weekKey").orEmpty(),
            weekMs = getLong("weekMs") ?: 0L,
            updatedAtMs = getLong("updatedAtMs") ?: 0L,
        )
    }

    private fun DocumentSnapshot.toNudge() = Nudge(
        id = id,
        fromUid = getString("fromUid").orEmpty(),
        fromName = getString("fromName").orEmpty(),
        groupName = getString("groupName").orEmpty(),
        sentAtMs = getTimestamp("sentAt", ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L,
    )

    private fun randomCode(): String = (1..CODE_LENGTH).map { CODE_ALPHABET.random() }.joinToString("")

    companion object {
        const val MAX_MEMBERS = 30
        const val CODE_LENGTH = 6
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val GROUPS = "groups"
        private const val CODES = "groupCodes"
        private const val MESSAGES = "messages"
        private const val PRESENCE = "presence"
        private const val NUDGES = "nudges"
        private const val ITEMS = "items"
        private const val MEMBER_IDS = "memberIds"
    }
}
