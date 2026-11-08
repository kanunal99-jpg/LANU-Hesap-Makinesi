//! Bounded JNI adapter over vodozemac; no cryptographic primitive is implemented here.
//! Account/session states are library-encrypted pickles, not raw secret JSON.
use jni::{objects::{JByteArray, JObject, JString}, sys::jstring, JNIEnv};
use serde_json::{json, Value};
use std::{panic::{catch_unwind, AssertUnwindSafe}, ptr, sync::Once};
use vodozemac::{olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle}, Curve25519PublicKey};
use zeroize::{Zeroize, Zeroizing};

const MAX_INPUT: usize = 2 * 1024 * 1024;
const MAX_STATE: usize = 1024 * 1024;
const MAX_PLAINTEXT: usize = 32 * 1024;
const ERROR: &str = "Secure session could not be authenticated; existing records preserved";
type Result<T> = std::result::Result<T, ()>;

fn wipe(value: &mut Value) {
    match value {
        Value::String(s) => s.zeroize(),
        Value::Array(values) => values.iter_mut().for_each(wipe),
        Value::Object(values) => values.values_mut().for_each(wipe),
        _ => {}
    }
}
struct SecretJson(Value);
impl Drop for SecretJson { fn drop(&mut self) { wipe(&mut self.0) } }
fn field<'a>(v: &'a Value, key: &str, max: usize) -> Result<&'a str> {
    v.get(key).and_then(Value::as_str).filter(|s| !s.is_empty() && s.len() <= max).ok_or(())
}
fn account(v: &Value, key: &[u8; 32]) -> Result<Account> {
    AccountPickle::from_encrypted(field(v, "account", MAX_STATE)?, key)
        .map(Account::from_pickle).map_err(|_| ())
}
fn session(v: &Value, key: &[u8; 32]) -> Result<Session> {
    let session = SessionPickle::from_encrypted(field(v, "session", MAX_STATE)?, key)
        .map(Session::from_pickle).map_err(|_| ())?;
    if session.session_id() != field(v, "sessionId", 128)? { return Err(()); }
    if session.session_config() != SessionConfig::version_2() { return Err(()); }
    Ok(session)
}
fn curve(v: &Value, key: &str) -> Result<Curve25519PublicKey> {
    Curve25519PublicKey::from_base64(field(v, key, 64)?).map_err(|_| ())
}
fn message(v: &Value) -> Result<OlmMessage> {
    let msg = v.get("message").ok_or(())?;
    let object = msg.as_object().ok_or(())?;
    if object.len() != 2 || !matches!(msg.get("type").and_then(Value::as_u64), Some(0 | 1)) { return Err(()); }
    field(msg, "body", 64 * 1024)?;
    serde_json::from_value(msg.clone()).map_err(|_| ())
}
fn outbound(mut s: Session, text: &str, key: &[u8; 32]) -> Result<Value> {
    let encrypted = s.encrypt(text).map_err(|_| ())?;
    Ok(json!({"sessionId":s.session_id(),"session":s.pickle().encrypt(key),"message":encrypted}))
}
fn inbound(mut s: Session, msg: &OlmMessage, key: &[u8; 32]) -> Result<Value> {
    let clear = Zeroizing::new(s.decrypt(msg).map_err(|_| ())?);
    let text = std::str::from_utf8(clear.as_slice()).map_err(|_| ())?;
    if text.len() > MAX_PLAINTEXT { return Err(()); }
    Ok(json!({"sessionId":s.session_id(),"session":s.pickle().encrypt(key),"plaintext":text}))
}
fn operate(v: &Value, key: &[u8; 32]) -> Result<Value> {
    let op = field(v, "op", 16)?;
    let allowed: &[&str] = match op {
        "create" => &["op"],
        "start" => &["op","account","peerIdentity","peerOneTime","plaintext"],
        "accept" => &["op","account","peerIdentity","message"],
        "send" => &["op","session","sessionId","plaintext"],
        "receive" => &["op","session","sessionId","message"],
        _ => return Err(())
    };
    let obj = v.as_object().ok_or(())?;
    if obj.len() != allowed.len() || obj.keys().any(|name| !allowed.contains(&name.as_str())) { return Err(()); }
    match op {
        "create" => {
            let mut a = Account::new();
            a.generate_one_time_keys(1);
            let otk = a.one_time_keys().values().next().ok_or(())?.to_base64();
            a.mark_keys_as_published();
            Ok(json!({"identity":a.curve25519_key().to_base64(),"oneTime":otk,"account":a.pickle().encrypt(key)}))
        },
        "start" => {
            let a = account(v, key)?;
            let s = a.create_outbound_session(SessionConfig::version_2(), curve(v,"peerIdentity")?, curve(v,"peerOneTime")?).map_err(|_| ())?;
            outbound(s, field(v,"plaintext",MAX_PLAINTEXT)?, key)
        },
        "accept" => {
            let mut a = account(v, key)?;
            let msg = message(v)?;
            let OlmMessage::PreKey(pre) = msg else { return Err(()); };
            let created = a.create_inbound_session(SessionConfig::version_2(), curve(v,"peerIdentity")?, &pre).map_err(|_| ())?;
            let clear = Zeroizing::new(created.plaintext);
            let text = std::str::from_utf8(clear.as_slice()).map_err(|_| ())?;
            if text.len() > MAX_PLAINTEXT { return Err(()); }
            Ok(json!({"account":a.pickle().encrypt(key),"sessionId":created.session.session_id(),"session":created.session.pickle().encrypt(key),"plaintext":text}))
        },
        "send" => outbound(session(v,key)?, field(v,"plaintext",MAX_PLAINTEXT)?, key),
        "receive" => inbound(session(v,key)?, &message(v)?, key),
        _ => Err(())
    }
}

/// Fixed-error JSON bridge. Callers must atomically persist returned states with
/// their inbox/outbox mutation and validate their own authenticated peer binding.
/// This engine alone does NOT negotiate, pin or activate a LANU wire protocol.
fn process(request: &str, key: &[u8;32]) -> Result<Zeroizing<String>> {
    if request.len() > MAX_INPUT || key.iter().all(|byte| *byte == 0) { return Err(()); }
    let value = SecretJson(serde_json::from_str(request).map_err(|_| ())?);
    let result = SecretJson(operate(&value.0, key)?);
    serde_json::to_string(&result.0).map(Zeroizing::new).map_err(|_| ())
}

static SILENT_PANIC: Once = Once::new();
/// JNI errors never include request/state/key/plaintext or a Rust panic payload.
#[no_mangle]
pub extern "system" fn Java_com_example_core_messaging_OlmRatchetNative_execute(
    mut env: JNIEnv, _this: JObject, request: JString, storage_key: JByteArray
) -> jstring {
    SILENT_PANIC.call_once(|| std::panic::set_hook(Box::new(|_| {})));
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring> {
        if env.get_array_length(&storage_key).map_err(|_| ())? != 32 { return Err(()); }
        let bytes = Zeroizing::new(env.convert_byte_array(&storage_key).map_err(|_| ())?);
        let mut key = Zeroizing::new([0u8;32]); key.copy_from_slice(bytes.as_slice());
        let input = Zeroizing::new(env.get_string(&request).map_err(|_| ())?.to_string_lossy().into_owned());
        let output = process(input.as_str(), &key)?;
        env.new_string(output.as_str()).map(|s| s.into_raw()).map_err(|_| ())
    }));
    match result {
        Ok(Ok(value)) => value,
        _ => { let _ = env.throw_new("java/lang/IllegalArgumentException", ERROR); ptr::null_mut() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const A: [u8;32] = [3;32]; const B: [u8;32] = [9;32];
    fn run(v: Value, key: &[u8;32]) -> Value {
        serde_json::from_str(process(&v.to_string(),key).unwrap().as_str()).unwrap()
    }
    fn pair() -> (Value,Value,Value,Value) {
        let a=run(json!({"op":"create"}),&A); let b=run(json!({"op":"create"}),&B);
        let sa=run(json!({"op":"start","account":a["account"],"peerIdentity":b["identity"],"peerOneTime":b["oneTime"],"plaintext":"first"}),&A);
        let sb=run(json!({"op":"accept","account":b["account"],"peerIdentity":a["identity"],"message":sa["message"]}),&B);
        assert_eq!(sb["plaintext"],"first"); assert_eq!(sa["sessionId"],sb["sessionId"]);
        (a,b,sa,sb)
    }
    #[test] fn bidirectional_ratchets_survive_encrypted_state_reopen() {
        let (_,_,mut a,mut b)=pair();
        for i in 0..50 {
            b=run(json!({"op":"send","session":b["session"],"sessionId":b["sessionId"],"plaintext":format!("reply{i}")}),&B);
            a=run(json!({"op":"receive","session":a["session"],"sessionId":a["sessionId"],"message":b["message"]}),&A);
            assert_eq!(a["plaintext"],format!("reply{i}"));
            a=run(json!({"op":"send","session":a["session"],"sessionId":a["sessionId"],"plaintext":format!("next{i}")}),&A);
            b=run(json!({"op":"receive","session":b["session"],"sessionId":b["sessionId"],"message":a["message"]}),&B);
            assert_eq!(b["plaintext"],format!("next{i}"));
        }
    }
    #[test] fn out_of_order_messages_work_once_and_replay_fails() {
        let (_,_,a,b)=pair();
        let first=run(json!({"op":"send","session":a["session"],"sessionId":a["sessionId"],"plaintext":"older"}),&A);
        let second=run(json!({"op":"send","session":first["session"],"sessionId":first["sessionId"],"plaintext":"newer"}),&A);
        let b2=run(json!({"op":"receive","session":b["session"],"sessionId":b["sessionId"],"message":second["message"]}),&B);
        let b3=run(json!({"op":"receive","session":b2["session"],"sessionId":b2["sessionId"],"message":first["message"]}),&B);
        assert_eq!(b2["plaintext"],"newer"); assert_eq!(b3["plaintext"],"older");
        assert!(operate(&json!({"op":"receive","session":b3["session"],"sessionId":b3["sessionId"],"message":first["message"]}),&B).is_err());
    }
    #[test] fn wrong_storage_key_peer_or_session_never_yields_plaintext() {
        let (a,b,sa,sb)=pair();
        assert!(operate(&json!({"op":"send","session":sa["session"],"sessionId":sa["sessionId"],"plaintext":"secret"}),&B).is_err());
        assert!(operate(&json!({"op":"send","session":sa["session"],"sessionId":"other","plaintext":"secret"}),&A).is_err());
        assert!(operate(&json!({"op":"accept","account":b["account"],"peerIdentity":b["identity"],"message":sa["message"]}),&B).is_err());
        assert!(operate(&json!({"op":"accept","account":sb["account"],"peerIdentity":a["identity"],"message":sa["message"]}),&B).is_err());
    }
    #[test] fn malformed_and_oversized_inputs_are_rejected() {
        assert!(process("{",&A).is_err());
        assert!(process(r#"{"op":"create"}"#,&[0;32]).is_err());
        assert!(process(&" ".repeat(MAX_INPUT+1),&A).is_err());
        assert!(process(r#"{"op":"create","unknown":"ignored?"}"#,&A).is_err());
        let a=run(json!({"op":"create"}),&A);
        for bad in ["", "!!!", "a", "A".repeat(42).as_str(), "A".repeat(44).as_str()] {
            assert!(operate(&json!({"op":"start","account":a["account"],"peerIdentity":bad,"peerOneTime":bad,"plaintext":"data"}),&A).is_err());
        }
    }
}
