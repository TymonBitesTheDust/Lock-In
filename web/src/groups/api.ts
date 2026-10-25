/**
 * Study groups on Firebase, the same Firestore layout and rules as Android (core/network),
 * so iPhone and Android friends can share one group.
 *
 * - groups/{id}            name, code, ownerId, memberIds[] (max 30), createdAt
 * - groupCodes/{code}      groupId, ownerId
 * - groups/{id}/messages   uid, name, text, sentAt
 * - presence/{uid}         see MemberPresence
 * - nudges/{uid}/items     fromUid, fromName, groupName, sentAt, seen
 *
 * The Firebase SDK is loaded only when the Groups tab needs it, so people who never use groups
 * never download it.
 */
import type { FirebaseOptions } from 'firebase/app';

export interface Account {
  uid: string;
  email: string | null;
  displayName: string;
}

export interface StudyGroup {
  id: string;
  name: string;
  code: string;
  ownerId: string;
  memberIds: string[];
}

export type PresenceState = 'STUDYING' | 'BREAK' | 'PAUSED' | 'IDLE';
export const PRESENCE_ORDER: PresenceState[] = ['STUDYING', 'BREAK', 'PAUSED', 'IDLE'];

/** What a member publishes. Never per second: viewers add `now - runningSinceMs` themselves. */
export interface MemberPresence {
  uid: string;
  displayName: string;
  state: PresenceState;
  subject: string | null;
  /** Wall-clock start of the focus stretch not yet included in todayMs/weekMs. */
  runningSinceMs: number | null;
  todayKey: string;
  todayMs: number;
  weekKey: string;
  weekMs: number;
  updatedAtMs: number;
}

/** A stale "studying" record (phone died) never adds more than 12 hours. */
const MAX_LIVE_MS = 12 * 3_600_000;

function liveMs(p: MemberPresence, nowMs: number): number {
  return p.state === 'STUDYING' && p.runningSinceMs !== null ? Math.min(Math.max(nowMs - p.runningSinceMs, 0), MAX_LIVE_MS) : 0;
}

export const todayTotal = (p: MemberPresence, dayKey: string, nowMs: number) => (p.todayKey === dayKey ? p.todayMs : 0) + liveMs(p, nowMs);
export const weekTotal = (p: MemberPresence, weekKey: string, nowMs: number) => (p.weekKey === weekKey ? p.weekMs : 0) + liveMs(p, nowMs);

export interface ChatMessage {
  id: string;
  uid: string;
  name: string;
  text: string;
  sentAtMs: number;
}

export interface Nudge {
  id: string;
  fromUid: string;
  fromName: string;
  groupName: string;
  sentAtMs: number;
}

/** Thrown with a message that can be shown as is. */
export class GroupError extends Error {}

export const MAX_MEMBERS = 30;
export const CODE_LENGTH = 6;
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

export function parseConfig(raw: unknown): FirebaseOptions | null {
  if (typeof raw !== 'string' || !raw.trim()) return null;
  try {
    const text = raw.trim();
    // Accept plain JSON or the `const firebaseConfig = {…};` snippet the Firebase console shows.
    const body = text.startsWith('{') ? text : text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1);
    const json = body.replace(/([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:/g, '$1"$2":').replace(/'/g, '"').replace(/,\s*}/g, '}');
    const config = JSON.parse(json) as FirebaseOptions;
    return config.apiKey && config.projectId && config.appId ? config : null;
  } catch {
    return null;
  }
}

export const firebaseConfig: FirebaseOptions | null = parseConfig(import.meta.env.VITE_FIREBASE_CONFIG);

/** End-to-end tests (`vite build --mode e2e`) swap Firebase for an in-memory fake. Never in real builds. */
const fakeApi: GroupsApi | undefined =
  import.meta.env.MODE === 'e2e' ? (globalThis as { __LOCKIN_FAKE_GROUPS__?: GroupsApi }).__LOCKIN_FAKE_GROUPS__ : undefined;

export const groupsAvailable = firebaseConfig !== null || fakeApi !== undefined;

type Unsubscribe = () => void;

export interface GroupsApi {
  onAccount(cb: (a: Account | null) => void): Unsubscribe;
  current(): Account | null;
  signIn(email: string, password: string): Promise<void>;
  signUp(name: string, email: string, password: string): Promise<void>;
  resetPassword(email: string): Promise<void>;
  signOut(): Promise<void>;
  myGroups(uid: string, cb: (groups: StudyGroup[]) => void): Unsubscribe;
  create(name: string, owner: Account): Promise<StudyGroup>;
  join(code: string, account: Account): Promise<string>;
  leave(groupId: string, uid: string): Promise<void>;
  presence(uids: string[], cb: (list: MemberPresence[]) => void): Unsubscribe;
  publishPresence(p: MemberPresence): Promise<void>;
  messages(groupId: string, cb: (list: ChatMessage[]) => void): Unsubscribe;
  send(groupId: string, from: Account, text: string): Promise<void>;
  nudge(toUid: string, from: Account, groupName: string): Promise<void>;
  pendingNudges(uid: string, cb: (list: Nudge[]) => void): Unsubscribe;
  markNudgeSeen(uid: string, nudgeId: string): Promise<void>;
}

function authMessage(code: string): string {
  switch (code) {
    case 'auth/invalid-email':
      return "That email address doesn't look right.";
    case 'auth/email-already-in-use':
      return 'An account with this email already exists. Sign in instead.';
    case 'auth/weak-password':
      return 'Use a password of at least 6 characters.';
    case 'auth/wrong-password':
    case 'auth/invalid-credential':
    case 'auth/invalid-login-credentials':
    case 'auth/user-not-found':
      return 'Email or password is incorrect.';
    case 'auth/too-many-requests':
      return 'Too many attempts. Wait a minute and try again.';
    case 'auth/network-request-failed':
      return 'No connection. Check your internet and try again.';
    case 'auth/unauthorized-domain':
    case 'auth/requests-from-referer-are-blocked':
      return "This website isn't allowed to sign in yet. The app's owner needs to add it in Firebase.";
    default:
      return `Sign-in failed (${code}).`;
  }
}

async function friendly<T>(block: () => Promise<T>): Promise<T> {
  try {
    return await block();
  } catch (e) {
    if (e instanceof GroupError) throw e;
    const code = (e as { code?: string })?.code;
    if (code?.startsWith('auth/')) throw new GroupError(authMessage(code));
    throw new GroupError('Something went wrong. Check your connection and try again.');
  }
}

let loading: Promise<GroupsApi> | null = null;

export function loadGroupsApi(): Promise<GroupsApi> {
  if (fakeApi) return Promise.resolve(fakeApi);
  if (!firebaseConfig) return Promise.reject(new GroupError('Study groups are not set up for this website.'));
  loading ??= create(firebaseConfig);
  return loading;
}

async function create(config: FirebaseOptions): Promise<GroupsApi> {
  const [{ initializeApp }, authMod, fs] = await Promise.all([import('firebase/app'), import('firebase/auth'), import('firebase/firestore')]);
  const app = initializeApp(config);
  const auth = authMod.getAuth(app);
  const db = fs.getFirestore(app);
  const {
    collection,
    doc,
    getDoc,
    setDoc,
    addDoc,
    updateDoc,
    writeBatch,
    query,
    where,
    orderBy,
    limitToLast,
    onSnapshot,
    arrayUnion,
    arrayRemove,
    serverTimestamp,
    documentId,
    Timestamp,
  } = fs;

  const toAccount = (u: import('firebase/auth').User | null): Account | null =>
    u ? { uid: u.uid, email: u.email, displayName: u.displayName?.trim() || u.email?.split('@')[0] || 'Student' } : null;

  // Bumped after sign-up so the new display name shows without waiting for a token refresh.
  const accountListeners = new Set<(a: Account | null) => void>();
  const emitAccount = () => accountListeners.forEach((cb) => cb(toAccount(auth.currentUser)));

  const millis = (v: unknown): number => (v instanceof Timestamp ? v.toMillis() : 0);
  const str = (v: unknown): string => (typeof v === 'string' ? v : '');
  const num = (v: unknown): number => (typeof v === 'number' ? v : 0);

  const randomCode = () => {
    const bytes = new Uint32Array(CODE_LENGTH);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('');
  };

  return {
    onAccount(cb) {
      accountListeners.add(cb);
      const off = authMod.onAuthStateChanged(auth, (u) => cb(toAccount(u)));
      return () => {
        accountListeners.delete(cb);
        off();
      };
    },
    current: () => toAccount(auth.currentUser),
    signIn: (email, password) =>
      friendly(async () => {
        await authMod.signInWithEmailAndPassword(auth, email.trim(), password);
      }),
    signUp: (name, email, password) =>
      friendly(async () => {
        if (!name.trim()) throw new GroupError('Add a name your group will see.');
        const result = await authMod.createUserWithEmailAndPassword(auth, email.trim(), password);
        await authMod.updateProfile(result.user, { displayName: name.trim().slice(0, 40) });
        await result.user.reload();
        emitAccount();
      }),
    resetPassword: (email) =>
      friendly(async () => {
        if (!email.trim()) throw new GroupError('Enter your email first.');
        await authMod.sendPasswordResetEmail(auth, email.trim());
      }),
    signOut: () => authMod.signOut(auth),

    myGroups(uid, cb) {
      return onSnapshot(
        query(collection(db, 'groups'), where('memberIds', 'array-contains', uid)),
        (snap) => {
          const list = snap.docs
            .map((d) => {
              const data = d.data();
              const name = data.name;
              if (typeof name !== 'string') return null;
              return { id: d.id, name, code: str(data.code), ownerId: str(data.ownerId), memberIds: Array.isArray(data.memberIds) ? (data.memberIds as string[]) : [] };
            })
            .filter((g): g is StudyGroup => g !== null)
            .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
          cb(list);
        },
        () => cb([]),
      );
    },

    create: (name, owner) =>
      friendly(async () => {
        const trimmed = name.trim().slice(0, 40);
        if (!trimmed) throw new GroupError('Give the group a name.');
        for (let attempt = 0; attempt < 5; attempt++) {
          const code = randomCode();
          const codeRef = doc(db, 'groupCodes', code);
          if ((await getDoc(codeRef)).exists()) continue;
          const groupRef = doc(collection(db, 'groups'));
          const batch = writeBatch(db);
          batch.set(groupRef, { name: trimmed, code, ownerId: owner.uid, memberIds: [owner.uid], createdAt: serverTimestamp() });
          batch.set(codeRef, { groupId: groupRef.id, ownerId: owner.uid });
          await batch.commit();
          return { id: groupRef.id, name: trimmed, code, ownerId: owner.uid, memberIds: [owner.uid] };
        }
        throw new GroupError("Couldn't create the group. Try again.");
      }),

    async join(code, account) {
      const normalized = code.trim().toUpperCase();
      if (normalized.length !== CODE_LENGTH) throw new GroupError(`Invite codes have ${CODE_LENGTH} characters.`);
      let groupId: string;
      try {
        const snap = await getDoc(doc(db, 'groupCodes', normalized));
        groupId = str(snap.data()?.groupId);
      } catch {
        throw new GroupError("Couldn't check that code. Check your connection.");
      }
      if (!groupId) throw new GroupError('No group has that code.');
      try {
        await updateDoc(doc(db, 'groups', groupId), { memberIds: arrayUnion(account.uid) });
      } catch {
        throw new GroupError(`Couldn't join. The group may be full (${MAX_MEMBERS} members).`);
      }
      return groupId;
    },

    leave: (groupId, uid) => friendly(() => updateDoc(doc(db, 'groups', groupId), { memberIds: arrayRemove(uid) })),

    presence(uids, cb) {
      if (uids.length === 0) {
        cb([]);
        return () => {};
      }
      return onSnapshot(
        query(collection(db, 'presence'), where(documentId(), 'in', uids.slice(0, MAX_MEMBERS))),
        (snap) => {
          cb(
            snap.docs.flatMap((d) => {
              const data = d.data();
              if (typeof data.displayName !== 'string') return [];
              const state = PRESENCE_ORDER.includes(data.state) ? (data.state as PresenceState) : 'IDLE';
              return [
                {
                  uid: d.id,
                  displayName: data.displayName,
                  state,
                  subject: typeof data.subject === 'string' ? data.subject : null,
                  runningSinceMs: typeof data.runningSinceMs === 'number' ? data.runningSinceMs : null,
                  todayKey: str(data.todayKey),
                  todayMs: num(data.todayMs),
                  weekKey: str(data.weekKey),
                  weekMs: num(data.weekMs),
                  updatedAtMs: num(data.updatedAtMs),
                },
              ];
            }),
          );
        },
        () => {},
      );
    },

    async publishPresence(p) {
      await setDoc(doc(db, 'presence', p.uid), {
        displayName: p.displayName.slice(0, 40),
        state: p.state,
        subject: p.subject,
        runningSinceMs: p.runningSinceMs,
        todayKey: p.todayKey,
        todayMs: p.todayMs,
        weekKey: p.weekKey,
        weekMs: p.weekMs,
        updatedAtMs: p.updatedAtMs,
        updatedAt: serverTimestamp(),
      });
    },

    messages(groupId, cb) {
      return onSnapshot(
        query(collection(db, 'groups', groupId, 'messages'), orderBy('sentAt', 'asc'), limitToLast(100)),
        (snap) =>
          cb(
            snap.docs.map((d) => {
              const data = d.data({ serverTimestamps: 'estimate' });
              return { id: d.id, uid: str(data.uid), name: str(data.name), text: str(data.text), sentAtMs: millis(data.sentAt) };
            }),
          ),
        () => {},
      );
    },

    send: (groupId, from, text) =>
      friendly(async () => {
        const trimmed = text.trim().slice(0, 500);
        if (!trimmed) return;
        await addDoc(collection(db, 'groups', groupId, 'messages'), { uid: from.uid, name: from.displayName, text: trimmed, sentAt: serverTimestamp() });
      }),

    nudge: (toUid, from, groupName) =>
      friendly(async () => {
        await addDoc(collection(db, 'nudges', toUid, 'items'), { fromUid: from.uid, fromName: from.displayName, groupName, sentAt: serverTimestamp(), seen: false });
      }),

    pendingNudges(uid, cb) {
      return onSnapshot(
        query(collection(db, 'nudges', uid, 'items'), where('seen', '==', false)),
        (snap) =>
          cb(
            snap.docs.map((d) => {
              const data = d.data({ serverTimestamps: 'estimate' });
              return { id: d.id, fromUid: str(data.fromUid), fromName: str(data.fromName), groupName: str(data.groupName), sentAtMs: millis(data.sentAt) };
            }),
          ),
        () => {},
      );
    },

    markNudgeSeen: (uid, nudgeId) => updateDoc(doc(db, 'nudges', uid, 'items', nudgeId), { seen: true }),
  };
}
