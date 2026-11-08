package com.example.core.messaging;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Host JNI boundary smoke, synthetic keys only. Never print account/session values. */
public final class OlmRatchetNative {
    private native String execute(String request, byte[] storageKey);
    private static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) throw new AssertionError("Missing engine field");
        return matcher.group(1);
    }
    private static String message(String json) {
        int start = json.indexOf("\"message\":{") + 10;
        if (start < 10) throw new AssertionError("Missing encrypted message");
        return json.substring(start, json.indexOf('}', start) + 1);
    }
    public static void main(String[] args) {
        System.load(args[0]);
        var engine = new OlmRatchetNative();
        byte[] ak = new byte[32], bk = new byte[32]; Arrays.fill(ak,(byte)3); Arrays.fill(bk,(byte)9);
        String a = engine.execute("{\"op\":\"create\"}",ak), b = engine.execute("{\"op\":\"create\"}",bk);
        String first = engine.execute("{\"op\":\"start\",\"account\":\""+field(a,"account")+"\",\"peerIdentity\":\""+field(b,"identity")+"\",\"peerOneTime\":\""+field(b,"oneTime")+"\",\"plaintext\":\"native-first\"}",ak);
        String accepted = engine.execute("{\"op\":\"accept\",\"account\":\""+field(b,"account")+"\",\"peerIdentity\":\""+field(a,"identity")+"\",\"message\":"+message(first)+"}",bk);
        if (!field(accepted,"plaintext").equals("native-first") || !field(accepted,"sessionId").equals(field(first,"sessionId"))) throw new AssertionError("JNI exchange mismatch");
        String reply = engine.execute("{\"op\":\"send\",\"session\":\""+field(accepted,"session")+"\",\"sessionId\":\""+field(accepted,"sessionId")+"\",\"plaintext\":\"native-reply\"}",bk);
        String received = engine.execute("{\"op\":\"receive\",\"session\":\""+field(first,"session")+"\",\"sessionId\":\""+field(first,"sessionId")+"\",\"message\":"+message(reply)+"}",ak);
        if (!field(received,"plaintext").equals("native-reply")) throw new AssertionError("JNI receive mismatch");
        try { engine.execute("{\"op\":\"JNI-PRIVATE-SENTINEL\"}",ak); throw new AssertionError("Invalid operation accepted"); }
        catch (IllegalArgumentException expected) {
            if (expected.getMessage().contains("JNI-PRIVATE-SENTINEL")) throw new AssertionError("Private error leaked");
        }
        Arrays.fill(ak,(byte)0); Arrays.fill(bk,(byte)0);
        System.out.println("PASS: actual JNI bidirectional exchange, encrypted state reopen and sanitized error boundary");
    }
}
