/*
 * Dailies: friends (1.10.0). A small client for the Dailies Supabase project: signing in with a 6-digit
 * code sent by email, and the friends functions in supabase/setup.sql. The same code runs in the Android
 * app and the web version, so one account works in both.
 *
 * No library is loaded from the internet: Supabase's sign-in (GoTrue) and database (PostgREST) are plain
 * HTTPS calls. The session (a short-lived access token and the refresh token that renews it) is kept in
 * this device's storage, and renewed a minute before it runs out.
 *
 * The publishable key below is meant to be public (it ships in every copy of the app); what a signed-in
 * player can do is limited by the database's own rules, not by keeping it secret.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Social = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const PROJECT = {
    url: 'https://fyvvjmpoxqoowqdnqwbs.supabase.co',
    key: 'sb_publishable_uMtEG8zBfTRogWF7GGKlXg_8ibf85Vx'
  };
  const SESSION_KEY = 'dailies_social_session_v1';
  const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  /** An error with a message a person can act on, and the server's own code when there is one. */
  class SocialError extends Error {
    constructor(message, code, status) {
      super(message);
      this.name = 'SocialError';
      this.code = code || '';
      this.status = status || 0;
    }
  }

  /** What the server said, in words for the person: sign-in limits, wrong codes, being offline. */
  function friendlyError(status, body) {
    const raw = String((body && (body.msg || body.message || body.error_description || body.error)) || '').trim();
    const code = String((body && (body.error_code || body.code)) || '');
    if (status === 429 || /rate limit|too many/i.test(raw)) {
      return new SocialError('Too many tries. Wait a few minutes and try again.', code || 'rate_limited', status);
    }
    if (/not signed in/i.test(raw) || status === 401) {
      return new SocialError('You were signed out. Sign in again.', 'signed_out', status);
    }
    if (/otp_expired|expired|invalid.*(token|otp)|token.*(invalid|expired)/i.test(code + ' ' + raw)) {
      return new SocialError('That code is wrong or has expired. Check it, or send a new one.', code || 'bad_code', status);
    }
    if (code === 'PGRST202' || code === '42883') {
      return new SocialError('Friends isn\'t set up on the server yet.', 'not_set_up', status);
    }
    if (/name must be/i.test(raw)) return new SocialError('A name needs 1 to 30 characters.', 'bad_name', status);
    if (/email.*(invalid|address)/i.test(raw)) return new SocialError('That email address doesn\'t look right.', 'bad_email', status);
    if (/signups? not allowed|signup.*disabled/i.test(raw)) return new SocialError('New accounts are switched off for now.', 'signup_disabled', status);
    return new SocialError(raw || `Something went wrong (${status}). Try again.`, code, status);
  }

  /**
   * A client. `opts`: { fetch, storage ({ getItem, setItem, removeItem }), now (ms), url, key }, all optional
   * (the device's own fetch and localStorage by default).
   */
  function create(opts) {
    const o = opts || {};
    const url = o.url || PROJECT.url;
    const key = o.key || PROJECT.key;
    const fetchFn = o.fetch || (typeof fetch === 'function' ? fetch.bind(typeof self !== 'undefined' ? self : this) : null);
    const store = o.storage || (typeof localStorage !== 'undefined' ? localStorage : null);
    const now = o.now || (() => Date.now());
    let session = load();
    let refreshing = null;
    const listeners = new Set();

    function load() {
      try {
        const s = JSON.parse((store && store.getItem(SESSION_KEY)) || 'null');
        return s && s.access_token && s.refresh_token ? s : null;
      } catch (e) {
        return null;
      }
    }
    function save(s) {
      session = s;
      try {
        if (!store) return;
        if (s) store.setItem(SESSION_KEY, JSON.stringify(s));
        else store.removeItem(SESSION_KEY);
      } catch (e) {}
      listeners.forEach((fn) => { try { fn(signedIn()); } catch (e) {} });
    }
    /** The session from a sign-in or refresh response. */
    function fromAuth(body) {
      if (!body || !body.access_token || !body.refresh_token) throw new SocialError('Sign-in didn\'t finish. Try again.', 'no_session');
      const expiresIn = Number(body.expires_in) || 3600;
      const user = body.user || {};
      return {
        access_token: body.access_token,
        refresh_token: body.refresh_token,
        expires_at: Math.floor(now() / 1000) + expiresIn,
        user: { id: user.id || '', email: user.email || (session && session.user && session.user.email) || '' }
      };
    }

    async function request(path, init) {
      if (!fetchFn) throw new SocialError('This device can\'t reach the internet from Dailies.', 'no_fetch');
      let res;
      try {
        res = await fetchFn(url + path, init);
      } catch (e) {
        throw new SocialError('No connection. Check your internet and try again.', 'offline');
      }
      const text = await res.text();
      let body = null;
      try { body = text ? JSON.parse(text) : null; } catch (e) { body = { message: text }; }
      if (!res.ok) throw friendlyError(res.status, body);
      return body;
    }
    function headers(token) {
      const h = { apikey: key, 'Content-Type': 'application/json' };
      if (token) h.Authorization = `Bearer ${token}`;
      return h;
    }

    /** A fresh access token (renewed first when it ends within a minute); signs out if it can't be. */
    async function token() {
      if (!session) throw new SocialError('Sign in first.', 'signed_out');
      if (session.expires_at - 60 > now() / 1000) return session.access_token;
      if (!refreshing) {
        refreshing = request('/auth/v1/token?grant_type=refresh_token', {
          method: 'POST', headers: headers(), body: JSON.stringify({ refresh_token: session.refresh_token })
        }).then((body) => { save(fromAuth(body)); }, (e) => {
          // A refresh token that's no longer valid (signed out elsewhere, account deleted) ends the session;
          // being offline doesn't.
          if (e.code !== 'offline') save(null);
          throw e.code === 'offline' ? e : new SocialError('You were signed out. Sign in again.', 'signed_out', e.status);
        }).finally(() => { refreshing = null; });
      }
      await refreshing;
      return session.access_token;
    }

    /** Calls one of the database functions in supabase/setup.sql. */
    async function rpc(name, args) {
      const t = await token();
      try {
        return await request(`/rest/v1/rpc/${name}`, { method: 'POST', headers: headers(t), body: JSON.stringify(args || {}) });
      } catch (e) {
        if (e.code === 'signed_out') save(null);
        throw e;
      }
    }

    function signedIn() {
      return Boolean(session);
    }

    return {
      PROJECT: { url, key },
      signedIn,
      /** The signed-in email, or ''. */
      email: () => (session && session.user && session.user.email) || '',
      userId: () => (session && session.user && session.user.id) || '',
      /** Calls `fn(signedIn)` whenever that changes. */
      onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },

      /** Sends a 6-digit sign-in code to `email` (making the account the first time). */
      async sendCode(email) {
        const e = String(email || '').trim().toLowerCase();
        if (!EMAIL_RE.test(e)) throw new SocialError('That email address doesn\'t look right.', 'bad_email');
        await request('/auth/v1/otp', { method: 'POST', headers: headers(), body: JSON.stringify({ email: e, create_user: true }) });
        return e;
      },
      /** Signs in with the code from the email. A new account's first code is a sign-up code: both work. */
      async verifyCode(email, code) {
        const e = String(email || '').trim().toLowerCase();
        const c = String(code || '').replace(/\D/g, '');
        if (c.length < 6 || c.length > 10) throw new SocialError('Type the code from the email (6 digits).', 'bad_code');
        let body;
        try {
          body = await request('/auth/v1/verify', { method: 'POST', headers: headers(), body: JSON.stringify({ type: 'email', email: e, token: c }) });
        } catch (err) {
          if (err.code === 'offline' || err.code === 'rate_limited') throw err;
          body = await request('/auth/v1/verify', { method: 'POST', headers: headers(), body: JSON.stringify({ type: 'signup', email: e, token: c }) })
            .catch(() => { throw err; });
        }
        save(fromAuth(body));
        return true;
      },
      /** Signs out on this device (and ends the session on the server when it can). */
      async signOut() {
        const t = session && session.access_token;
        save(null);
        if (t) {
          try { await request('/auth/v1/logout', { method: 'POST', headers: headers(t) }); } catch (e) {}
        }
      },

      /** { display_name, friend_code } */
      async profile() {
        const rows = await rpc('my_profile');
        return (Array.isArray(rows) ? rows[0] : rows) || null;
      },
      /** The name as saved (trimmed). */
      setName: (name) => rpc('set_display_name', { new_name: String(name || '') }),
      /** 'sent' | 'accepted' | 'already' | 'self' | 'not_found' | 'too_many' */
      addFriend: (code) => rpc('send_friend_request', { code: String(code || '') }),
      /** Accepts (true) or declines (false) a request someone sent. */
      respond: (id, accept) => rpc('respond_friend_request', { other: id, accept: Boolean(accept) }),
      /** Removes a friend, or takes back a request you sent. */
      remove: (id) => rpc('remove_friend', { other: id }),
      /** [{ id, display_name, status: 'accepted' | 'incoming' | 'outgoing', since }] */
      async friends() {
        const rows = await rpc('list_friends');
        return Array.isArray(rows) ? rows : [];
      },
      /** Deletes the account and everything kept for it, then signs out here. */
      async deleteAccount() {
        await rpc('delete_my_account');
        save(null);
      }
    };
  }

  /** A friend code as typed ("vt98-vdjx ") in its stored form ("VT98VDJX"), or '' if it can't be one. */
  function normalizeCode(text) {
    const c = String(text || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
    return /^[2-9A-HJ-NP-Z]{8}$/.test(c) ? c : '';
  }
  /** "VT98VDJX" -> "VT98-VDJX" (easier to read out and type). */
  function formatCode(code) {
    const c = String(code || '');
    return c.length === 8 ? `${c.slice(0, 4)}-${c.slice(4)}` : c;
  }

  return { create, normalizeCode, formatCode, SocialError, PROJECT, SESSION_KEY };
});
