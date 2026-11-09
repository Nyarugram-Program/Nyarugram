package org.telegram.messenger.voip;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Process-local: a service without its original dispatch can only recover ordinary priority. */
public final class IncomingCallPolicy {
    public static final String ORDINARY_CHANNEL = "botgram_calls_ordinary_v2";
    public static final String SPECIAL_CHANNEL = "botgram_calls_special_v2";

    public static final class Decision {
        public final int importance;
        public final String channelId;
        private boolean fullScreenClaimed;
        private boolean automaticPageClaimed;

        private Decision(boolean special) {
            importance = special ? 4 : 3;
            channelId = special ? SPECIAL_CHANNEL : ORDINARY_CHANNEL;
        }

        public int priority() { return importance == 4 ? 2 : 0; }

        public synchronized boolean claimFullScreen(boolean systemAllows) {
            if (importance != 4 || !systemAllows || fullScreenClaimed) return false;
            fullScreenClaimed = true;
            return true;
        }

        public synchronized boolean claimAutomaticPage(boolean foreground, boolean screenOn) {
            return claimAutomaticPage(foreground, screenOn, false);
        }

        public synchronized boolean claimAutomaticPage(boolean foreground, boolean screenOn, boolean backgroundAllowed) {
            if (!screenOn || automaticPageClaimed || !foreground && !(importance == 4 && backgroundAllowed)) return false;
            automaticPageClaimed = true;
            return true;
        }
    }

    private static final class Key {
        final String session;
        final int account;
        final long call;
        final boolean group;
        Key(String session, int account, long call, boolean group) {
            this.session = session; this.account = account; this.call = call; this.group = group;
        }
        @Override public int hashCode() { return Objects.hash(session, account, call, group); }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return account == other.account && call == other.call && group == other.group && Objects.equals(session, other.session);
        }
    }

    private final Map<Key, Decision> calls = new HashMap<>();

    public synchronized Decision begin(String session, int account, long call, boolean group, boolean special) {
        Key key = new Key(session, account, call, group);
        Decision decision = calls.get(key);
        if (decision == null) {
            decision = new Decision(session != null && call != 0 && !group && special);
            calls.put(key, decision);
        }
        return decision;
    }

    public synchronized Decision recover(String session, int account, long call, boolean group) {
        return begin(session, account, call, group, false);
    }

    public synchronized void finish(String session, int account, long call, boolean group) {
        calls.remove(new Key(session, account, call, group));
    }

    public synchronized boolean contains(String session, int account, long call, boolean group) {
        return calls.containsKey(new Key(session, account, call, group));
    }

    public interface Channels {
        boolean exists(String id);
        void create(String id, int importance);
    }

    /**
     * CallStyle is legal only when the notification is published by a real foreground service
     * or when the notification itself carries a full-screen intent. Ordinary 3-level notify()
     * and a special call whose FSI permission was denied or whose single claim was already
     * consumed must use the ordinary template.
     */
    public static boolean callStyleLegal(boolean publishedFromForegroundService, boolean fullScreenIntent) {
        return publishedFromForegroundService || fullScreenIntent;
    }

    public static String ensureChannel(Decision decision, Channels channels) {
        if (!channels.exists(decision.channelId)) channels.create(decision.channelId, decision.importance);
        return decision.channelId;
    }
}
