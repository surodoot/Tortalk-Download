package dev.gyeopdam.embeddedtor;

import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

/** Private, versioned IPC. No caller-supplied control commands or network destinations. */
final class TorProcessProtocol {
    static final int VERSION = 2;
    static final int START = 1, RENEW = 2, SAMPLE = 3, STOP = 4;
    static final int SAMPLE_RESULT = 101, STOP_ACK = 102, ERROR = 103;
    static final int ERROR_PROTOCOL = 1, ERROR_CONTROL = 2, ERROR_DEADLINE = 3,
            ERROR_OWNER = 4, ERROR_STARTUP = 5, ERROR_IDENTITY = 6;

    private TorProcessProtocol() { }

    static Message request(int what, long epoch, long seq, long sentAt, Messenger owner) {
        Message result = Message.obtain();
        result.what = what;
        result.replyTo = owner;
        Bundle data = base(epoch, seq);
        data.putLong("sentAt", sentAt);
        result.setData(data);
        return result;
    }

    static final class Request {
        final int what;
        final long epoch, seq, sentAt;
        final Messenger owner;
        Request(Message message, Bundle data) {
            what = message.what;
            epoch = data.getLong("epoch");
            seq = data.getLong("seq");
            sentAt = data.getLong("sentAt");
            owner = message.replyTo;
        }
    }

    static Request readRequest(Message message) {
        if (message == null || message.sendingUid != Process.myUid()
                || message.arg1 != 0 || message.arg2 != 0 || message.obj != null
                || message.replyTo == null || message.what < START || message.what > STOP) return null;
        try {
            Bundle data = message.peekData();
            if (!validBase(data, 4) || !has(data, "sentAt", Long.class)
                    || data.getLong("sentAt") < 0) return null;
            return new Request(message, data);
        } catch (RuntimeException invalidParcel) {
            return null;
        }
    }

    /** Includes the actual Messenger sender UID, exact keys/types, and value limits. */
    static boolean validReply(Message message) {
        if (message == null || message.sendingUid != Process.myUid()
                || message.arg1 != 0 || message.arg2 != 0 || message.obj != null
                || message.replyTo != null) return false;
        try {
            Bundle data = message.peekData();
            switch (message.what) {
                case SAMPLE_RESULT:
                    return validBase(data, 8) && has(data, "sampledAt", Long.class)
                            && data.getLong("sampledAt") >= 0
                            && intBetween(data, "progress", 0, 100)
                            && intBetween(data, "socksReady", 0, 1)
                            && intBetween(data, "pid", 1, Integer.MAX_VALUE)
                            && intBetween(data, "circuit", 0, 1);
                case STOP_ACK:
                    return validBase(data, 4) && has(data, "sampledAt", Long.class)
                            && data.getLong("sampledAt") >= 0;
                case ERROR:
                    return validBase(data, 4)
                            && intBetween(data, "error", ERROR_PROTOCOL, ERROR_IDENTITY);
                default:
                    return false;
            }
        } catch (RuntimeException invalidParcel) {
            return false;
        }
    }

    static Message sample(long epoch, long seq, long sampledAt, int progress, int socksReady, int circuit, int pid) {
        Bundle data = base(epoch, seq);
        data.putLong("sampledAt", sampledAt);
        data.putInt("progress", progress);
        data.putInt("socksReady", socksReady);
        data.putInt("pid", pid);
        data.putInt("circuit", circuit);
        return reply(SAMPLE_RESULT, data);
    }

    static Message stopped(long epoch, long seq, long acknowledgedAt) {
        Bundle data = base(epoch, seq);
        data.putLong("sampledAt", acknowledgedAt);
        return reply(STOP_ACK, data);
    }

    static Message error(long epoch, long seq, int error) {
        Bundle data = base(epoch, seq);
        data.putInt("error", error);
        return reply(ERROR, data);
    }

    private static Message reply(int what, Bundle data) {
        Message result = Message.obtain();
        result.what = what;
        result.setData(data);
        return result;
    }

    private static Bundle base(long epoch, long seq) {
        Bundle data = new Bundle();
        data.putInt("v", VERSION);
        data.putLong("epoch", epoch);
        data.putLong("seq", seq);
        return data;
    }

    private static boolean validBase(Bundle data, int size) {
        if (data == null) return false;
        data.setClassLoader(null);
        return !data.hasFileDescriptors() && data.size() == size
                && has(data, "v", Integer.class) && data.getInt("v") == VERSION
                && has(data, "epoch", Long.class) && data.getLong("epoch") > 0
                && has(data, "seq", Long.class) && data.getLong("seq") > 0;
    }

    @SuppressWarnings("deprecation") // Bundle.get is the API26-compatible exact-type check.
    private static boolean has(Bundle data, String key, Class<?> type) {
        Object value = data.get(key);
        return value != null && value.getClass() == type;
    }

    private static boolean intBetween(Bundle data, String key, int min, int max) {
        return has(data, key, Integer.class) && data.getInt(key) >= min && data.getInt(key) <= max;
    }
}
