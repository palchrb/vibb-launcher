//! The backup check's schema comparison: a restore takes only a database whose schema is exactly what the
//! migrations make (`handlers::backups::check_db`).
//!
//! The comparison is by structure, not by the stored `CREATE` text: SQLite keeps a table's text as it was written and
//! rewrites it on `ALTER TABLE`, so two databases with the same tables can store different text (a migration run by
//! another SQLite version, quoted identifiers). Per table, case-folded: its columns (`pragma_table_xinfo`), its
//! indexes (`pragma_index_list` and `pragma_index_xinfo`; SQLite's automatic indexes by their columns, not their
//! names), its foreign keys (`pragma_foreign_key_list`) and its `CHECK` clauses; every trigger and view by name and
//! its normalised SQL ([normalise]). Exact normalised text is compared only for a partial index's `WHERE` and a
//! `CHECK` clause. SQLite's own tables (`sqlite_sequence`, `sqlite_stat*`) and sqlx's `_sqlx_migrations` table are
//! left out; a trigger or a view is never left out, whatever its name or table.

use std::collections::{BTreeMap, BTreeSet};

/// One column as `pragma_table_xinfo` gives it, case-folded.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Column {
    name: String,
    kind: String,
    notnull: bool,
    default: Option<String>,
    pk: i64,
    hidden: i64,
}

/// One indexed column: its name (`None` for an expression or the rowid), collation, order and whether it is a key.
type IndexColumn = (Option<String>, String, bool, bool);

/// One index. `name` is `None` for SQLite's automatic indexes (a `UNIQUE` or `PRIMARY KEY` constraint), compared by
/// their columns.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct Index {
    name: Option<String>,
    unique: bool,
    origin: String,
    partial: bool,
    columns: Vec<IndexColumn>,
    /// A partial index's `WHERE`, normalised.
    where_clause: Option<String>,
}

/// One foreign key row: `seq`, `table`, `from`, `to`, `on_update`, `on_delete`, `match`, case-folded.
type ForeignKey = (i64, String, String, Option<String>, String, String, String);

#[derive(Debug, Clone, PartialEq, Eq)]
struct Table {
    columns: Vec<Column>,
    indexes: BTreeSet<Index>,
    foreign_keys: BTreeSet<ForeignKey>,
    /// The table's `CHECK` clauses, normalised, in order.
    checks: Vec<String>,
}

/// A database's schema as the backup check compares it.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(crate) struct Schema {
    tables: BTreeMap<String, Table>,
    triggers: BTreeMap<String, String>,
    views: BTreeMap<String, String>,
}

impl Schema {
    /// What differs from `reference`, for the server log: the kinds and names of the extra, missing and changed
    /// objects. Empty when they are equal.
    pub(crate) fn differences(&self, reference: &Schema) -> Vec<String> {
        let mut out = Vec::new();
        let mut compare =
            |kind: &str, ours: Vec<(&String, String)>, theirs: Vec<(&String, String)>| {
                let ours: BTreeMap<_, _> = ours.into_iter().collect();
                let theirs: BTreeMap<_, _> = theirs.into_iter().collect();
                for (name, shape) in &ours {
                    match theirs.get(name) {
                        None => out.push(format!("extra {kind} {name}")),
                        Some(other) if other != shape => out.push(format!("changed {kind} {name}")),
                        Some(_) => {}
                    }
                }
                for name in theirs.keys() {
                    if !ours.contains_key(name) {
                        out.push(format!("missing {kind} {name}"));
                    }
                }
            };
        let shape = |t: &Table| format!("{t:?}");
        compare(
            "table",
            self.tables.iter().map(|(n, t)| (n, shape(t))).collect(),
            reference
                .tables
                .iter()
                .map(|(n, t)| (n, shape(t)))
                .collect(),
        );
        compare(
            "trigger",
            self.triggers.iter().map(|(n, s)| (n, s.clone())).collect(),
            reference
                .triggers
                .iter()
                .map(|(n, s)| (n, s.clone()))
                .collect(),
        );
        compare(
            "view",
            self.views.iter().map(|(n, s)| (n, s.clone())).collect(),
            reference
                .views
                .iter()
                .map(|(n, s)| (n, s.clone()))
                .collect(),
        );
        out
    }

    /// The names of its triggers and views.
    pub(crate) fn trigger_and_view_names(&self) -> BTreeSet<String> {
        self.triggers
            .keys()
            .chain(self.views.keys())
            .cloned()
            .collect()
    }
}

/// SQL text as the comparison reads it: comments and whitespace dropped, quoted identifiers (`"x"`, `` `x` ``,
/// `[x]`) unquoted, everything lowercased but a string literal's content.
pub(crate) fn normalise(sql: &str) -> String {
    normalise_with(sql, false)
}

/// [normalise], with `spaces` a whitespace run (or a comment) between two tokens kept as one space - for finding a
/// keyword at a word's edge ([check_clauses]).
fn normalise_with(sql: &str, spaces: bool) -> String {
    let mut out = String::with_capacity(sql.len());
    let chars: Vec<char> = sql.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        match c {
            '\'' => {
                // A string literal, '' inside it an escaped quote: kept as written.
                out.push('\'');
                i += 1;
                while i < chars.len() {
                    if chars[i] == '\'' {
                        if chars.get(i + 1) == Some(&'\'') {
                            out.push_str("''");
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    out.push(chars[i]);
                    i += 1;
                }
                out.push('\'');
                i += 1;
            }
            '"' | '`' | '[' => {
                let close = if c == '[' { ']' } else { c };
                i += 1;
                while i < chars.len() {
                    if chars[i] == close {
                        if close != ']' && chars.get(i + 1) == Some(&close) {
                            out.extend(close.to_lowercase());
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    if !chars[i].is_whitespace() {
                        out.extend(chars[i].to_lowercase());
                    }
                    i += 1;
                }
                i += 1;
            }
            '-' if chars.get(i + 1) == Some(&'-') => {
                while i < chars.len() && chars[i] != '\n' {
                    i += 1;
                }
            }
            '/' if chars.get(i + 1) == Some(&'*') => {
                i += 2;
                while i < chars.len() && !(chars[i] == '*' && chars.get(i + 1) == Some(&'/')) {
                    i += 1;
                }
                i += 2;
            }
            c if c.is_whitespace() => {
                if spaces && !out.is_empty() && !out.ends_with(' ') {
                    out.push(' ');
                }
                i += 1;
            }
            c => {
                out.extend(c.to_lowercase());
                i += 1;
            }
        }
    }
    out
}

/// The content of every `CHECK (...)` in a `CREATE TABLE`, normalised, in order. Found in the spaced form, so `CHECK`
/// is a word of its own (not the end of `recheck` or of a type run into it).
fn check_clauses(sql: &str) -> Vec<String> {
    let spaced = normalise_with(sql, true);
    let bytes = spaced.as_bytes();
    let word = |b: u8| b.is_ascii_alphanumeric() || b == b'_';
    let mut out = Vec::new();
    let mut from = 0;
    let mut in_string = false;
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'\'' {
            in_string = !in_string;
            i += 1;
            continue;
        }
        let at_check = !in_string
            && spaced[i..].starts_with("check")
            && (i == 0 || !word(bytes[i - 1]))
            && i >= from;
        if !at_check {
            i += 1;
            continue;
        }
        let mut open = i + "check".len();
        if bytes.get(open) == Some(&b' ') {
            open += 1;
        }
        if bytes.get(open) != Some(&b'(') {
            i += 1;
            continue;
        }
        let start = open + 1;
        let mut depth = 1;
        let mut end = start;
        let mut quoted = false;
        while end < bytes.len() && depth > 0 {
            match bytes[end] {
                b'\'' => quoted = !quoted,
                b'(' if !quoted => depth += 1,
                b')' if !quoted => depth -= 1,
                _ => {}
            }
            end += 1;
        }
        out.push(normalise(&spaced[start..end.saturating_sub(1)]));
        from = end;
        i = end;
    }
    out
}

/// A partial index's `WHERE`, from its normalised `CREATE INDEX`.
fn where_clause(normalised: &str) -> Option<String> {
    normalised
        .find(")where")
        .map(|at| normalised[at + ")where".len()..].to_string())
}

fn fold(text: &str) -> String {
    text.to_lowercase()
}

/// Reads the schema of the database behind `conn` (read-only: `sqlite_master` and the pragma functions).
pub(crate) async fn read(conn: &mut sqlx::SqliteConnection) -> Result<Schema, sqlx::Error> {
    let objects: Vec<(String, String, Option<String>)> = sqlx::query_as(
        "SELECT type, name, sql FROM sqlite_master WHERE type IN ('table', 'trigger', 'view')",
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut schema = Schema::default();
    for (kind, name, raw) in objects {
        let raw = raw.unwrap_or_default();
        match kind.as_str() {
            "trigger" => {
                schema.triggers.insert(fold(&name), normalise(&raw));
            }
            "view" => {
                schema.views.insert(fold(&name), normalise(&raw));
            }
            _ => {
                let lower = fold(&name);
                if lower.starts_with("sqlite_") || lower == "_sqlx_migrations" {
                    continue;
                }
                let table = read_table(conn, &name, &raw).await?;
                schema.tables.insert(lower, table);
            }
        }
    }
    Ok(schema)
}

async fn read_table(
    conn: &mut sqlx::SqliteConnection,
    name: &str,
    sql: &str,
) -> Result<Table, sqlx::Error> {
    let columns: Vec<(String, String, i64, Option<String>, i64, i64)> = sqlx::query_as(
        "SELECT name, type, \"notnull\", dflt_value, pk, hidden FROM pragma_table_xinfo(?) ORDER BY cid",
    )
    .bind(name)
    .fetch_all(&mut *conn)
    .await?;
    let columns = columns
        .into_iter()
        .map(|(name, kind, notnull, default, pk, hidden)| Column {
            name: fold(&name),
            kind: normalise(&kind),
            notnull: notnull != 0,
            default: default.as_deref().map(normalise),
            pk,
            hidden,
        })
        .collect();
    let list: Vec<(String, i64, String, i64)> =
        sqlx::query_as("SELECT name, \"unique\", origin, partial FROM pragma_index_list(?)")
            .bind(name)
            .fetch_all(&mut *conn)
            .await?;
    let mut indexes = BTreeSet::new();
    for (index, unique, origin, partial) in list {
        let parts: Vec<(Option<String>, i64, Option<String>, i64)> = sqlx::query_as(
            "SELECT name, \"desc\", coll, key FROM pragma_index_xinfo(?) ORDER BY seqno",
        )
        .bind(&index)
        .fetch_all(&mut *conn)
        .await?;
        let where_sql = if partial != 0 {
            let sql: Option<Option<String>> = sqlx::query_scalar(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
            )
            .bind(&index)
            .fetch_optional(&mut *conn)
            .await?;
            sql.flatten().and_then(|sql| where_clause(&normalise(&sql)))
        } else {
            None
        };
        let automatic = fold(&index).starts_with("sqlite_autoindex_");
        indexes.insert(Index {
            name: (!automatic).then(|| fold(&index)),
            unique: unique != 0,
            origin: fold(&origin),
            partial: partial != 0,
            columns: parts
                .into_iter()
                .map(|(column, desc, coll, key)| {
                    (
                        column.as_deref().map(fold),
                        fold(coll.as_deref().unwrap_or_default()),
                        desc != 0,
                        key != 0,
                    )
                })
                .collect(),
            where_clause: where_sql,
        });
    }
    let keys: Vec<ForeignKey> = sqlx::query_as(
        "SELECT seq, \"table\", \"from\", \"to\", on_update, on_delete, \"match\" \
             FROM pragma_foreign_key_list(?)",
    )
    .bind(name)
    .fetch_all(&mut *conn)
    .await?;
    let foreign_keys = keys
        .into_iter()
        .map(|(seq, table, from, to, on_update, on_delete, matching)| {
            (
                seq,
                fold(&table),
                fold(&from),
                to.as_deref().map(fold),
                fold(&on_update),
                fold(&on_delete),
                fold(&matching),
            )
        })
        .collect();
    Ok(Table {
        columns,
        indexes,
        foreign_keys,
        checks: check_clauses(sql),
    })
}

/// The trigger and view names a database at any migration version may hold: every one a migration creates (also one
/// a later migration drops), from the migrations' own SQL.
pub(crate) fn migration_trigger_and_view_names(
    migrator: &sqlx::migrate::Migrator,
) -> BTreeSet<String> {
    static CREATE: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(
            r#"(?is)\bcreate\s+(?:temp\s+|temporary\s+)?(?:trigger|view)\s+(?:if\s+not\s+exists\s+)?["`\[]?([A-Za-z0-9_]+)"#,
        )
        .expect("a valid pattern")
    });
    migrator
        .iter()
        .flat_map(|migration| {
            CREATE
                .captures_iter(&migration.sql)
                .map(|found| fold(&found[1]))
                .collect::<Vec<_>>()
        })
        .collect()
}

/// The names of the triggers and views in the database behind `conn`, case-folded (read-only).
pub(crate) async fn trigger_and_view_names(
    conn: &mut sqlx::SqliteConnection,
) -> Result<BTreeSet<String>, sqlx::Error> {
    let names: Vec<String> =
        sqlx::query_scalar("SELECT name FROM sqlite_master WHERE type IN ('trigger', 'view')")
            .fetch_all(conn)
            .await?;
    Ok(names.iter().map(|name| fold(name)).collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use sqlx::Connection;

    #[test]
    fn normalise_unquotes_folds_and_drops_whitespace() {
        assert_eq!(
            normalise(
                "CREATE TABLE \"Foo\" ( [Bar] TEXT  DEFAULT 'A b' -- note\n, `baz` INT /* x */)"
            ),
            "createtablefoo(bartextdefault'A b',bazint)"
        );
        assert_eq!(normalise("'it''s'"), "'it''s'");
        assert_eq!(
            check_clauses(
                "CREATE TABLE t (a INT CHECK (a IN (1, 2)), recheck INT, b TEXT CHECK(b != 'check(x)'), CHECK(a > 0))"
            ),
            ["ain(1,2)", "b!='check(x)'", "a>0"]
        );
        assert_eq!(
            where_clause(&normalise("CREATE INDEX i ON t (a, b) WHERE a IS NOT NULL")),
            Some("aisnotnull".to_string())
        );
    }

    async fn schema_after(statements: &[&str]) -> Schema {
        let mut conn = sqlx::SqliteConnection::connect("sqlite::memory:")
            .await
            .unwrap();
        for statement in statements {
            sqlx::raw_sql(statement).execute(&mut conn).await.unwrap();
        }
        read(&mut conn).await.unwrap()
    }

    /// A table renamed, a column renamed and one dropped: the same schema as the same tables written at once with
    /// quoted identifiers, whatever text SQLite stored for each.
    #[tokio::test]
    async fn the_same_structure_matches_whatever_text_sqlite_stored() {
        let migrated = schema_after(&[
            "CREATE TABLE old_items (id INTEGER PRIMARY KEY, name TEXT NOT NULL, gone TEXT, \
             kind TEXT DEFAULT 'a' CHECK (kind IN ('a', 'b')), UNIQUE (name, kind))",
            "CREATE TABLE owners (id INTEGER PRIMARY KEY)",
            "ALTER TABLE old_items RENAME TO items",
            "ALTER TABLE items RENAME COLUMN name TO title",
            "ALTER TABLE items DROP COLUMN gone",
            "ALTER TABLE items ADD COLUMN owner_id INTEGER REFERENCES owners (id) ON DELETE CASCADE",
            "CREATE INDEX items_by_owner ON items (owner_id) WHERE owner_id IS NOT NULL",
            "CREATE TRIGGER items_gone AFTER DELETE ON owners BEGIN DELETE FROM items WHERE owner_id = old.id; END",
        ])
        .await;
        let written = schema_after(&[
            "CREATE TABLE \"owners\" (\"id\" INTEGER PRIMARY KEY)",
            "CREATE TABLE \"items\" (\"id\" INTEGER PRIMARY KEY, \"title\" TEXT NOT NULL,\n\
             \"kind\" TEXT DEFAULT 'a' CHECK (\"kind\" IN ('a', 'b')),\n\
             \"owner_id\" INTEGER REFERENCES \"owners\" (\"id\") ON DELETE CASCADE, UNIQUE (\"title\", \"kind\"))",
            "CREATE INDEX \"items_by_owner\" ON \"items\" (\"owner_id\") WHERE \"owner_id\" IS NOT NULL",
            "CREATE TRIGGER \"items_gone\" AFTER DELETE ON \"owners\" BEGIN \
             DELETE FROM \"items\" WHERE \"owner_id\" = old.\"id\"; END",
        ])
        .await;
        assert_eq!(migrated.differences(&written), Vec::<String>::new());
        assert_eq!(migrated, written);
    }

    /// Each change is seen: a column's type, a default, a check, an index's columns, a foreign key, a trigger.
    #[tokio::test]
    async fn every_kind_of_change_differs() {
        let base = [
            "CREATE TABLE owners (id INTEGER PRIMARY KEY)",
            "CREATE TABLE items (id INTEGER PRIMARY KEY, title TEXT DEFAULT 'x' CHECK (length(title) < 9), \
             owner_id INTEGER REFERENCES owners (id))",
            "CREATE INDEX items_by_title ON items (title)",
        ];
        let reference = schema_after(&base).await;
        for (what, changed) in [
            ("type", base[1].replace("title TEXT", "title BLOB")),
            ("default", base[1].replace("'x'", "'y'")),
            ("check", base[1].replace("< 9", "< 10")),
            ("foreign key", base[1].replace("REFERENCES owners (id)", "")),
        ] {
            let other = schema_after(&[base[0], &changed, base[2]]).await;
            assert!(!other.differences(&reference).is_empty(), "{what}");
        }
        let other = schema_after(&[
            base[0],
            base[1],
            "CREATE INDEX items_by_title ON items (title, owner_id)",
        ])
        .await;
        assert!(!other.differences(&reference).is_empty(), "index columns");
        let mut with = base.to_vec();
        with.push("CREATE TRIGGER t AFTER INSERT ON items BEGIN SELECT 1; END");
        assert_eq!(
            schema_after(&with).await.differences(&reference),
            ["extra trigger t"]
        );
    }

    #[test]
    fn the_migrations_trigger_names_are_read() {
        let names = migration_trigger_and_view_names(&crate::MIGRATOR);
        assert!(names.contains("calendar_sources_orphaned"), "{names:?}");
    }
}
