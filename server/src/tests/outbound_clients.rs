//! Every outbound HTTP client the server builds is in a file named here, with why its own client is right there. A
//! fetch of an address a parent typed goes through `music_net` (its [Reach](crate::music_net::Reach) and redirect
//! rules); a new client elsewhere fails this scan until it is either moved there or added with its reason.

/// The files that build a reqwest client, and why.
const ALLOWED: &[(&str, &str)] = &[
    (
        "music_net.rs",
        "the guarded client itself: public or LAN reach, no proxy from the environment, checked redirects",
    ),
    (
        "push.rs",
        "Web Push to the parents' browsers' push services: no redirects, a short timeout",
    ),
    (
        "fdroid.rs",
        "F-Droid's repository API and index, the catalog's trust path",
    ),
    (
        "launcher_check.rs",
        "the setup QR's launcher release from GitHub, https-only redirects",
    ),
    (
        "clock_skew.rs",
        "a HEAD to GitHub's API for the server's own clock",
    ),
    ("startup.rs", "the TLS self-test against api.github.com"),
    (
        "attestation/status_list.rs",
        "Google's attestation revocation list, a fixed address",
    ),
    (
        "handlers/tracked_apps.rs",
        "the catalog's GitHub releases and their assets, https-only redirects",
    ),
    (
        "handlers/system_update.rs",
        "the server's own newest release from GitHub",
    ),
];

/// `text` without its `#[cfg(test)]` modules (`mod name { ... }`, braces counted; strings, chars and comments are
/// skipped while counting), with CRLF read as LF.
fn production_code(text: &str) -> String {
    let text = text.replace("\r\n", "\n");
    let mut out = String::new();
    let mut rest = text.as_str();
    while let Some(at) = rest.find("#[cfg(test)]") {
        out.push_str(&rest[..at]);
        let after = &rest[at + "#[cfg(test)]".len()..];
        let item = after.trim_start();
        let item = item.strip_prefix("pub ").unwrap_or(item);
        let Some(module) = item.strip_prefix("mod ") else {
            // A test-only item that isn't a module: kept, the scan reads it too.
            out.push_str("#[cfg(test)]");
            rest = after;
            continue;
        };
        let Some(open) = module.find(['{', ';']) else {
            rest = "";
            break;
        };
        if module.as_bytes()[open] == b';' {
            // `mod name;`: its own file, skipped by the caller.
            rest = &module[open + 1..];
            continue;
        }
        rest = skip_block(&module[open..]);
    }
    out.push_str(rest);
    out
}

/// What follows the block that `text` opens with `{`.
fn skip_block(text: &str) -> &str {
    let bytes = text.as_bytes();
    let mut depth = 0usize;
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'/' if bytes.get(i + 1) == Some(&b'/') => {
                while i < bytes.len() && bytes[i] != b'\n' {
                    i += 1;
                }
            }
            b'/' if bytes.get(i + 1) == Some(&b'*') => {
                i += 2;
                while i + 1 < bytes.len() && !(bytes[i] == b'*' && bytes[i + 1] == b'/') {
                    i += 1;
                }
                i += 1;
            }
            b'"' => {
                i += 1;
                while i < bytes.len() && bytes[i] != b'"' {
                    if bytes[i] == b'\\' {
                        i += 1;
                    }
                    i += 1;
                }
            }
            b'\'' if bytes.get(i + 2) == Some(&b'\'') => i += 2,
            b'{' => depth += 1,
            b'}' => {
                depth -= 1;
                if depth == 0 {
                    return &text[i + 1..];
                }
            }
            _ => {}
        }
        i += 1;
    }
    ""
}

/// The files `#[cfg(test)] mod name;` names in `dir`'s `text`.
fn test_module_files(dir: &std::path::Path, text: &str) -> Vec<std::path::PathBuf> {
    let text = text.replace("\r\n", "\n");
    let mut files = Vec::new();
    let mut rest = text.as_str();
    while let Some(at) = rest.find("#[cfg(test)]") {
        rest = &rest[at + "#[cfg(test)]".len()..];
        let item = rest.trim_start();
        let item = item.strip_prefix("pub ").unwrap_or(item);
        if let Some(module) = item.strip_prefix("mod ")
            && let Some(end) = module.find(['{', ';'])
            && module.as_bytes()[end] == b';'
        {
            let name = module[..end].trim();
            files.push(dir.join(format!("{name}.rs")));
            files.push(dir.join(name));
        }
    }
    files
}

#[test]
fn every_outbound_client_is_in_a_known_file() {
    // Spelled in parts, so this test's own text isn't a client.
    let needles = [
        concat!("Client::", "builder("),
        concat!("Client::", "new("),
        concat!("Client::", "default("),
        concat!("Client", "Builder::new("),
        concat!("reqwest::", "get("),
        concat!("reqwest::blocking", "::"),
    ];
    let src = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src");
    // The tests' own clients talk to their own listeners.
    let mut skipped = vec![src.join("tests")];
    let mut files = Vec::new();
    let mut dirs = vec![src.clone()];
    while let Some(dir) = dirs.pop() {
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                dirs.push(path);
            } else if path.extension().is_some_and(|e| e == "rs") {
                let text = std::fs::read_to_string(&path).unwrap();
                // A module's own directory: `foo.rs`'s is `foo/`, `mod.rs`'s and `main.rs`'s their own.
                let module_dir = match path.file_stem().and_then(|s| s.to_str()) {
                    Some("mod" | "main" | "lib") => dir.clone(),
                    Some(stem) => dir.join(stem),
                    None => dir.clone(),
                };
                skipped.extend(test_module_files(&module_dir, &text));
                files.push((path, text));
            }
        }
    }
    let mut found = std::collections::BTreeSet::new();
    for (path, text) in files {
        if skipped.iter().any(|skip| path.starts_with(skip)) {
            continue;
        }
        let code = production_code(&text);
        let builds = code.lines().any(|line| {
            let line = line.trim_start();
            !line.starts_with("//") && needles.iter().any(|n| line.contains(n))
        });
        if builds {
            let name = path
                .strip_prefix(&src)
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/");
            found.insert(name);
        }
    }
    for name in &found {
        assert!(
            ALLOWED.iter().any(|(file, _)| file == name),
            "src/{name} builds an HTTP client: fetch through `music_net`, or add it to ALLOWED with why"
        );
    }
    for (file, _) in ALLOWED {
        assert!(
            found.contains(*file),
            "src/{file} no longer builds a client: take it off ALLOWED"
        );
    }
    assert!(!found.contains("dns_engine.rs"));
}

#[test]
fn the_scan_reads_production_code_only() {
    let text = "fn a() { reqwest::Client::builder(); }\r\n#[cfg(test)]\r\nmod tests {\r\n    fn b() { let s = \"}\"; reqwest::get(\"x\"); }\r\n}\r\nfn c() {}\r\n#[cfg(test)]\nmod other { fn d() { reqwest::Client::new(); } }\n";
    let code = production_code(text);
    assert!(code.contains("Client::builder("), "{code}");
    assert!(code.contains("fn c()"), "{code}");
    assert!(!code.contains("reqwest::get("), "{code}");
    assert!(!code.contains("Client::new("), "{code}");
    let dir = std::path::Path::new("/src/foo");
    assert_eq!(
        test_module_files(dir, "#[cfg(test)]\r\nmod tests;\n"),
        vec![dir.join("tests.rs"), dir.join("tests")]
    );
}
