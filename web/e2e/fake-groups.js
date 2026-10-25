// An in-memory stand-in for Firebase, injected into `vite build --mode e2e` pages by e2e/groups.mjs.
// Same shape as GroupsApi in src/groups/api.ts. A friend, Mina, is studying in every group.
(() => {
  const s = { user: null, groups: [], presence: {}, messages: {}, nudges: [], published: [], nudgesSent: [] };
  const L = { account: new Set(), groups: new Set(), presence: new Set(), messages: new Set(), nudges: new Set() };
  const later = (fn) => queueMicrotask(fn);
  const pad = (n) => String(n).padStart(2, '0');
  const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  // Study day starting at 05:00, like the app's default.
  const studyDay = () => iso(new Date(Date.now() - 5 * 3_600_000));
  const weekKey = () => {
    const d = new Date(Date.now() - 5 * 3_600_000);
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
    return iso(d);
  };
  const off = (set, entry) => () => set.delete(entry);
  const emitAccount = () => L.account.forEach((cb) => cb(s.user));
  const emitGroups = () => L.groups.forEach(({ uid, cb }) => cb(s.groups.filter((g) => g.memberIds.includes(uid)).map((g) => ({ ...g }))));
  const emitPresence = () => L.presence.forEach(({ uids, cb }) => cb(uids.filter((u) => s.presence[u]).map((u) => ({ ...s.presence[u] }))));
  const emitMessages = () => L.messages.forEach(({ gid, cb }) => cb([...(s.messages[gid] ?? [])]));
  const emitNudges = () => L.nudges.forEach(({ uid, cb }) => cb(s.nudges.filter((n) => n.to === uid)));

  window.__LOCKIN_FAKE_GROUPS__ = {
    state: s,
    nudgeMe(fromName) {
      s.nudges.push({ id: `n${s.nudges.length + 1}`, to: s.user.uid, fromUid: 'mina', fromName, groupName: s.groups[0]?.name ?? 'Group', sentAtMs: Date.now() });
      emitNudges();
    },
    onAccount(cb) {
      L.account.add(cb);
      later(() => cb(s.user));
      return off(L.account, cb);
    },
    current: () => s.user,
    async signIn(email) {
      s.user = { uid: 'me', email, displayName: email.split('@')[0] };
      later(emitAccount);
    },
    async signUp(name, email) {
      s.user = { uid: 'me', email, displayName: name.trim() };
      later(emitAccount);
    },
    async resetPassword() {},
    async signOut() {
      s.user = null;
      later(emitAccount);
    },
    myGroups(uid, cb) {
      const entry = { uid, cb };
      L.groups.add(entry);
      later(emitGroups);
      return off(L.groups, entry);
    },
    async create(name, owner) {
      const g = { id: `g${s.groups.length + 1}`, name: name.trim(), code: 'K7PX2Q', ownerId: owner.uid, memberIds: [owner.uid, 'mina'] };
      s.groups.push(g);
      s.presence.mina = {
        uid: 'mina',
        displayName: 'Mina',
        state: 'STUDYING',
        subject: 'Chemistry',
        runningSinceMs: Date.now() - 20 * 60_000,
        todayKey: studyDay(),
        todayMs: 40 * 60_000,
        weekKey: weekKey(),
        weekMs: 5 * 3_600_000,
        updatedAtMs: Date.now(),
      };
      later(() => {
        emitGroups();
        emitPresence();
      });
      return { ...g };
    },
    async join() {
      throw new Error('No group has that code.');
    },
    async leave(gid, uid) {
      const g = s.groups.find((x) => x.id === gid);
      if (g) g.memberIds = g.memberIds.filter((m) => m !== uid);
      later(emitGroups);
    },
    presence(uids, cb) {
      const entry = { uids, cb };
      L.presence.add(entry);
      later(emitPresence);
      return off(L.presence, entry);
    },
    async publishPresence(p) {
      s.presence[p.uid] = { ...p };
      s.published.push({ ...p });
      later(emitPresence);
    },
    messages(gid, cb) {
      const entry = { gid, cb };
      L.messages.add(entry);
      later(emitMessages);
      return off(L.messages, entry);
    },
    async send(gid, from, text) {
      (s.messages[gid] ??= []).push({ id: `m${Date.now()}`, uid: from.uid, name: from.displayName, text: text.trim(), sentAtMs: Date.now() });
      later(emitMessages);
    },
    async nudge(toUid, from, groupName) {
      s.nudgesSent.push({ toUid, from: from.uid, groupName });
    },
    pendingNudges(uid, cb) {
      const entry = { uid, cb };
      L.nudges.add(entry);
      later(emitNudges);
      return off(L.nudges, entry);
    },
    async markNudgeSeen(_uid, id) {
      s.nudges = s.nudges.filter((n) => n.id !== id);
      later(emitNudges);
    },
  };
})();
