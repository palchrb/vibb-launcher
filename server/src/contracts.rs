//! The files both sides read, kept once in the repo's `contracts/` folder (design 133; `contracts/README.md`).
//! Test-only: the one production read outside the crate (`dns_server`'s DoH hosts) is a literal `include_str!`.

/// A contract file's text, compiled in: `crate::contracts::contract!("core/weather.json")`.
#[allow(unused_macros)]
macro_rules! contract {
    ($p:literal) => {
        include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/../contracts/", $p))
    };
}
#[allow(unused_imports)]
pub(crate) use contract;

/// A contract file's path, for the tests that read or write it at run time.
pub(crate) fn path(p: &str) -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../contracts")
        .join(p)
}

/// Every file under `contracts/`, as `<root>/<file>`, README.md included.
pub(crate) fn all_files() -> std::collections::BTreeSet<String> {
    fn walk(
        dir: &std::path::Path,
        root: &std::path::Path,
        out: &mut std::collections::BTreeSet<String>,
    ) {
        for entry in std::fs::read_dir(dir).unwrap() {
            let p = entry.unwrap().path();
            if p.is_dir() {
                walk(&p, root, out);
            } else {
                out.insert(
                    p.strip_prefix(root)
                        .unwrap()
                        .to_string_lossy()
                        .replace('\\', "/"),
                );
            }
        }
    }
    let root = path("");
    let mut out = std::collections::BTreeSet::new();
    walk(&root, &root, &mut out);
    out
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;

    /// Design 133 §6.2: every file under `contracts/` has a row in its README's table, and every row names a file.
    #[test]
    fn every_file_has_a_row() {
        let mut on_disk = super::all_files();
        on_disk.remove("README.md");
        let readme = std::fs::read_to_string(super::path("README.md")).unwrap();
        let table = readme
            .split("## Files")
            .nth(1)
            .expect("the README's Files table");
        let rows: BTreeSet<String> = table
            .lines()
            .filter_map(|l| l.strip_prefix("| `"))
            .map(|l| l.split('`').next().unwrap().to_string())
            .collect();
        let without_row: Vec<_> = on_disk.difference(&rows).collect();
        let without_file: Vec<_> = rows.difference(&on_disk).collect();
        assert!(
            without_row.is_empty(),
            "files without a README row: {without_row:?}"
        );
        assert!(
            without_file.is_empty(),
            "README rows without a file: {without_file:?}"
        );
    }
}
