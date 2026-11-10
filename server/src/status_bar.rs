//! The installed iPhone app's status bar style, by Safari's version (2026-11-10, `docs/notes/server/pwa.md`,
//! "The status bar by iOS version"). Every page carries `default` (`templates/partials/head.html`): Apple's direction for
//! iOS 27 and later (WebKit bug 317153: `black-translucent` is deprecated). Safari 26 and older get `black-translucent`,
//! the one combination the user has seen good there. iOS reads the style from the page Safari had loaded when the app
//! was added to the Home Screen, and Safari's User-Agent tells its version only by `Version/NN` (the OS token is frozen
//! at `18_6` since iOS 26), so the server answers each Safari with its own: [old_safari_bar]. Another iOS browser
//! (`CriOS/`, `FxiOS/`, `EdgiOS/`, `OPiOS/`) is never read as Safari, whatever `Version/` it carries.

use axum::body::Body;
use axum::extract::Request;
use axum::http::{HeaderValue, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

/// The style every page carries (head.html's line is exactly [meta] of it).
pub const DEFAULT: &str = "default";

/// The status bar style for a page answered to `user_agent`: `black-translucent` for an iPhone, iPad or Mac Safari
/// whose `Version/NN` is 26 or lower, [DEFAULT] for everything else - no User-Agent, no `Version/` (the installed
/// app's own requests), another iOS browser (a product token ending in `iOS/`: `CriOS/`, `FxiOS/`, `EdgiOS/`,
/// `OPiOS/`, some of which carry a `Version/` too), 27 and later, or a number that doesn't read.
pub fn status_bar_style(user_agent: Option<&str>) -> &'static str {
    let old_safari = user_agent.is_some_and(|ua| {
        ["iPhone", "iPad", "Macintosh"]
            .iter()
            .any(|device| ua.contains(device))
            && !ua.contains("iOS/")
            && ua
                .split_once("Version/")
                .and_then(|(_, rest)| {
                    let end = rest
                        .find(|c: char| !c.is_ascii_digit())
                        .unwrap_or(rest.len());
                    rest[..end].parse::<u32>().ok()
                })
                .is_some_and(|major| major <= 26)
    });
    if old_safari {
        "black-translucent"
    } else {
        DEFAULT
    }
}

/// head.html's status bar line with `style`.
pub fn meta(style: &str) -> String {
    format!("<meta name=\"apple-mobile-web-app-status-bar-style\" content=\"{style}\">")
}

/// Every HTML answer varies by the User-Agent (`Vary`, for any cache that keeps a page); one to an old Safari
/// ([status_bar_style]) has its status bar line swapped - read whole, the page being the server's own. Any other
/// answer, and every page to anything else, passes as it is: never the event stream, a download or a static file.
pub async fn old_safari_bar(request: Request, next: Next) -> Response {
    let style = status_bar_style(
        request
            .headers()
            .get(header::USER_AGENT)
            .and_then(|value| value.to_str().ok()),
    );
    let mut response = next.run(request).await;
    let html = response
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.starts_with("text/html"));
    if !html {
        return response;
    }
    response
        .headers_mut()
        .append(header::VARY, HeaderValue::from_static("User-Agent"));
    if style == DEFAULT {
        return response;
    }
    let (mut parts, body) = response.into_parts();
    let bytes = match axum::body::to_bytes(body, usize::MAX).await {
        Ok(bytes) => bytes,
        Err(err) => {
            tracing::error!(%err, "couldn't read a page to set its status bar");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let from = meta(DEFAULT);
    let Some(at) = bytes
        .windows(from.len())
        .position(|window| window == from.as_bytes())
    else {
        return Response::from_parts(parts, Body::from(bytes));
    };
    let mut out = Vec::with_capacity(bytes.len() + style.len());
    out.extend_from_slice(&bytes[..at]);
    out.extend_from_slice(meta(style).as_bytes());
    out.extend_from_slice(&bytes[at + from.len()..]);
    parts.headers.remove(header::CONTENT_LENGTH);
    Response::from_parts(parts, Body::from(out))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safari_26_and_older_keep_the_translucent_bar() {
        const TRANSLUCENT: &str = "black-translucent";
        for (ua, style) in [
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/18.6 Mobile/15E148 Safari/604.1",
                TRANSLUCENT,
            ),
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/26.1 Mobile/15E148 Safari/604.1",
                TRANSLUCENT,
            ),
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/27.2 Mobile/15E148 Safari/604.1",
                DEFAULT,
            ),
            // An iPad asking for the desktop site says it is a Mac.
            (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/26.0 Safari/605.1.15",
                TRANSLUCENT,
            ),
            (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/27.0 Safari/605.1.15",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/26.0 Mobile/15E148 Safari/604.1",
                TRANSLUCENT,
            ),
            // Another iOS browser is never Safari, with a Version/ of 26 or lower too.
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 CriOS/141.0.7390.96 Mobile/15E148 Safari/604.1",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/26.0 CriOS/141.0.7390.96 Mobile/15E148 Safari/604.1",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/26.0 FxiOS/144.0 Mobile/15E148 Safari/605.1.15",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/18.0 EdgiOS/141.0.3537.71 Mobile/15E148 Safari/605.1.15",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Version/26.0 OPiOS/5.3.0 Mobile/15E148 Safari/604.1",
                DEFAULT,
            ),
            // The installed app's own requests: no Version/.
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) \
                 Mobile/15E148",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) \
                 Chrome/141.0.0.0 Mobile Safari/537.36",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0",
                DEFAULT,
            ),
            ("", DEFAULT),
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) Version/ Mobile/15E148",
                DEFAULT,
            ),
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) Version/999999999999999999999 Mobile",
                DEFAULT,
            ),
        ] {
            assert_eq!(status_bar_style(Some(ua)), style, "{ua}");
        }
        assert_eq!(status_bar_style(None), DEFAULT);
    }

    /// A page that comes with a Content-Length never leaves with it once its bar line was swapped (the new line is
    /// longer): a kept length would cut the page short in the browser.
    #[tokio::test]
    async fn a_rewritten_page_never_keeps_its_old_length() {
        use tower::ServiceExt;

        let page = format!(
            "<html><head>{}</head><body>Emma</body></html>",
            meta(DEFAULT)
        );
        let sent = page.clone();
        let router = axum::Router::new()
            .route(
                "/",
                axum::routing::get(move || {
                    let page = sent.clone();
                    async move {
                        (
                            [
                                (header::CONTENT_TYPE, "text/html; charset=utf-8".to_string()),
                                (header::CONTENT_LENGTH, page.len().to_string()),
                            ],
                            page,
                        )
                    }
                }),
            )
            .layer(axum::middleware::from_fn(old_safari_bar));
        for (ua, style) in [
            ("Version/27.2 (iPhone)", DEFAULT),
            ("Version/26.1 (iPhone)", "black-translucent"),
        ] {
            let request = Request::builder()
                .uri("/")
                .header(header::USER_AGENT, ua)
                .body(Body::empty())
                .unwrap();
            let response = router.clone().oneshot(request).await.unwrap();
            let length = response
                .headers()
                .get(header::CONTENT_LENGTH)
                .map(|value| value.to_str().unwrap().parse::<usize>().unwrap());
            let body = axum::body::to_bytes(response.into_body(), usize::MAX)
                .await
                .unwrap();
            assert!(
                String::from_utf8_lossy(&body).contains(&meta(style)),
                "{ua}"
            );
            if style == DEFAULT {
                // The premise: the page reaches the middleware with its length.
                assert_eq!(length, Some(page.len()), "{ua}");
            } else {
                // None, or the one axum's router sets from the new body - never the page's own.
                assert_ne!(body.len(), page.len(), "{ua}");
                assert!(length.is_none_or(|length| length == body.len()), "{ua}");
            }
        }
    }
}
