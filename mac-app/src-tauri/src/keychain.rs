//! API keys for the service's AI voice mode and speech-to-text, kept in the login Keychain as
//! generic passwords under service `dev.improve.vrcode`, one account per provider (what
//! `AiService.swift` and `ElevenLabsTranscription.swift` read): `anthropic-api-key`,
//! `openai-api-key`, `gemini-api-key`, `elevenlabs-api-key`.
//!
//! Each item is created with an access list that trusts both this app and the bundled
//! `vrcode-mac` helper. The helper is a separately signed binary, and without that list its
//! first read would raise a Keychain prompt, which nobody can answer with the lid closed. The
//! entries record the code signature, so they hold across rebuilds signed with the same identity.

use std::collections::BTreeMap;
use std::ffi::{c_void, CString};
use std::path::Path;
use std::ptr;

use core_foundation::base::{CFType, CFTypeRef, OSStatus, TCFType};
use core_foundation::data::CFData;
use core_foundation::dictionary::{CFDictionary, CFDictionaryRef};
use core_foundation::string::{CFString, CFStringRef};
use core_foundation_sys::array::{kCFTypeArrayCallBacks, CFArrayCreate, CFArrayRef};
use core_foundation_sys::base::{kCFAllocatorDefault, CFRelease};
use security_framework::item::{ItemClass, ItemSearchOptions};

const SERVICE: &str = "dev.improve.vrcode";
const ERR_SEC_ITEM_NOT_FOUND: OSStatus = -25300;

/// The key providers: the AI providers, then ElevenLabs for speech-to-text (id used by the
/// window, Keychain account, item label).
const PROVIDERS: [(&str, &str, &str); 4] = [
    ("anthropic", "anthropic-api-key", "VRcode Anthropic API key"),
    ("openai", "openai-api-key", "VRcode OpenAI API key"),
    ("gemini", "gemini-api-key", "VRcode Gemini API key"),
    ("elevenlabs", "elevenlabs-api-key", "VRcode ElevenLabs API key"),
];

#[link(name = "Security", kind = "framework")]
extern "C" {
    static kSecClass: CFStringRef;
    static kSecClassGenericPassword: CFStringRef;
    static kSecAttrService: CFStringRef;
    static kSecAttrAccount: CFStringRef;
    static kSecAttrLabel: CFStringRef;
    static kSecAttrAccess: CFStringRef;
    static kSecValueData: CFStringRef;
    fn SecItemAdd(attributes: CFDictionaryRef, result: *mut CFTypeRef) -> OSStatus;
    fn SecItemDelete(query: CFDictionaryRef) -> OSStatus;
    fn SecTrustedApplicationCreateFromPath(path: *const libc::c_char, app: *mut *mut c_void) -> OSStatus;
    fn SecAccessCreate(descriptor: CFStringRef, trusted: CFArrayRef, access: *mut *mut c_void) -> OSStatus;
}

/// The Keychain account and item label for a provider id.
fn provider(id: &str) -> Result<(&'static str, &'static str), String> {
    PROVIDERS
        .iter()
        .find(|(provider, _, _)| *provider == id)
        .map(|(_, account, label)| (*account, *label))
        .ok_or_else(|| format!("Unknown AI provider {id}."))
}

/// Which providers have a key. Reads attributes only, so it never prompts.
pub fn saved_keys() -> BTreeMap<&'static str, bool> {
    PROVIDERS.iter().map(|(id, account, _)| (*id, item_exists(SERVICE, account))).collect()
}

pub fn set_api_key(provider_id: &str, key: &str, helper: &Path) -> Result<(), String> {
    let (account, label) = provider(provider_id)?;
    let key = key.trim();
    if key.is_empty() || key.chars().any(char::is_whitespace) {
        return Err("The key must be one word with no spaces.".into());
    }
    set_item(SERVICE, account, label, key.as_bytes(), helper)
}

pub fn delete_api_key(provider_id: &str) -> Result<(), String> {
    let (account, _) = provider(provider_id)?;
    delete_item(SERVICE, account)
}

fn item_exists(service: &str, account: &str) -> bool {
    ItemSearchOptions::new()
        .class(ItemClass::generic_password())
        .service(service)
        .account(account)
        .load_attributes(true)
        .limit(1)
        .search()
        .map(|items| !items.is_empty())
        .unwrap_or(false)
}

/// Replaces the item, trusting this app and `helper` to read it without a prompt.
fn set_item(service: &str, account: &str, label: &str, secret: &[u8], helper: &Path) -> Result<(), String> {
    delete_item(service, account)?;
    unsafe {
        let access = access_for(helper, label)?;
        let attributes = CFDictionary::from_CFType_pairs(&[
            (key_of(kSecClass), CFString::wrap_under_get_rule(kSecClassGenericPassword).as_CFType()),
            (key_of(kSecAttrService), CFString::new(service).as_CFType()),
            (key_of(kSecAttrAccount), CFString::new(account).as_CFType()),
            (key_of(kSecAttrLabel), CFString::new(label).as_CFType()),
            (key_of(kSecValueData), CFData::from_buffer(secret).as_CFType()),
            (key_of(kSecAttrAccess), access),
        ]);
        status(SecItemAdd(attributes.as_concrete_TypeRef(), ptr::null_mut()), "save the key")
    }
}

fn delete_item(service: &str, account: &str) -> Result<(), String> {
    unsafe {
        let query = CFDictionary::from_CFType_pairs(&[
            (key_of(kSecClass), CFString::wrap_under_get_rule(kSecClassGenericPassword).as_CFType()),
            (key_of(kSecAttrService), CFString::new(service).as_CFType()),
            (key_of(kSecAttrAccount), CFString::new(account).as_CFType()),
        ]);
        match SecItemDelete(query.as_concrete_TypeRef()) {
            ERR_SEC_ITEM_NOT_FOUND => Ok(()),
            code => status(code, "remove the old key"),
        }
    }
}

/// An access list trusting this app (a null path means the caller) and the helper.
unsafe fn access_for(helper: &Path, label: &str) -> Result<CFType, String> {
    let helper = CString::new(helper.as_os_str().as_encoded_bytes()).map_err(|error| error.to_string())?;
    let mut apps: [*mut c_void; 2] = [ptr::null_mut(); 2];
    status(SecTrustedApplicationCreateFromPath(ptr::null(), &mut apps[0]), "trust VRcode Mac")?;
    if let Err(error) = status(SecTrustedApplicationCreateFromPath(helper.as_ptr(), &mut apps[1]), "trust vrcode-mac") {
        CFRelease(apps[0]);
        return Err(error);
    }
    let list = CFArrayCreate(kCFAllocatorDefault, apps.as_ptr() as *const *const c_void, 2, &kCFTypeArrayCallBacks);
    CFRelease(apps[0]);
    CFRelease(apps[1]);
    let mut access: *mut c_void = ptr::null_mut();
    let result = SecAccessCreate(CFString::new(label).as_concrete_TypeRef(), list, &mut access);
    CFRelease(list as *const c_void);
    status(result, "create the Keychain access list")?;
    Ok(CFType::wrap_under_create_rule(access as CFTypeRef))
}

unsafe fn key_of(key: CFStringRef) -> CFString {
    CFString::wrap_under_get_rule(key)
}

fn status(code: OSStatus, action: &str) -> Result<(), String> {
    if code == 0 {
        Ok(())
    } else {
        Err(format!("Couldn't {action} (Keychain error {code})."))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Writes, reads back, replaces and deletes a throwaway login-Keychain item through the same
    /// access-list path as the API keys. Run on a Mac: cargo test -- --ignored keychain
    #[test]
    #[ignore]
    fn live_keychain_round_trip() {
        let service = format!("dev.improve.vrcode.test-{}", std::process::id());
        let helper = std::env::current_exe().unwrap();
        let read = || security_framework::passwords::get_generic_password(&service, "probe").ok();
        assert!(!item_exists(&service, "probe"));
        set_item(&service, "probe", "VRcode keychain test", b"first", &helper).unwrap();
        assert!(item_exists(&service, "probe"));
        assert_eq!(read().as_deref(), Some(&b"first"[..]), "a trusted app reads it without a prompt");
        set_item(&service, "probe", "VRcode keychain test", b"second", &helper).unwrap();
        assert_eq!(read().as_deref(), Some(&b"second"[..]), "saving again replaces it");
        delete_item(&service, "probe").unwrap();
        assert!(!item_exists(&service, "probe"));
        delete_item(&service, "probe").unwrap();
    }

    /// A separately signed binary named in the access list (`/usr/bin/security` standing in for
    /// the bundled vrcode-mac) reads the item without a prompt. Without the list it would block on
    /// a Keychain dialog. Run on a Mac: cargo test -- --ignored keychain
    #[test]
    #[ignore]
    fn live_keychain_trusts_the_helper() {
        use std::process::{Command, Stdio};
        use std::time::{Duration, Instant};
        let service = format!("dev.improve.vrcode.test-helper-{}", std::process::id());
        let helper = Path::new("/usr/bin/security");
        set_item(&service, "probe", "VRcode keychain test", b"shared", helper).unwrap();
        let mut child = Command::new(helper)
            .args(["find-generic-password", "-s", &service, "-a", "probe", "-w"])
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        let status = loop {
            if let Some(status) = child.try_wait().unwrap() {
                break Some(status);
            }
            if Instant::now() > deadline {
                let _ = child.kill();
                break None;
            }
            std::thread::sleep(Duration::from_millis(50));
        };
        let mut out = String::new();
        std::io::Read::read_to_string(child.stdout.as_mut().unwrap(), &mut out).unwrap();
        delete_item(&service, "probe").unwrap();
        assert!(status.is_some(), "the helper was asked for permission instead of being trusted");
        assert_eq!(out.trim(), "shared");
    }

    #[test]
    fn providers_map_to_the_accounts_the_service_reads() {
        assert_eq!(provider("anthropic").unwrap().0, "anthropic-api-key");
        assert_eq!(provider("openai").unwrap().0, "openai-api-key");
        assert_eq!(provider("gemini").unwrap().0, "gemini-api-key");
        assert_eq!(provider("elevenlabs").unwrap().0, "elevenlabs-api-key");
        assert!(provider("other").is_err());
    }
}
