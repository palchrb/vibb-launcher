// A phone on Locate's maps (design 85 §4.2, revision 3): its round avatar in its tone (its picture
// over the letter), grey when old, the time tag under it, and its accuracy circle - one look on the
// Alle view (static/locate-all.js, a group's avatars side by side) and the one-phone view
// (static/locate.js). A row is `{tone, initial, photo, fresh, tag}`, every word the server's; the
// phone's values reach the page only as text (textContent; security review H2). Loaded before both
// scripts, as `HandyMapMarker`. ES5. Its tests run with both scripts' (jstest/locate-all.test.js,
// jstest/locate.test.js).
(function (root) {
    "use strict";

    // A group shows at most this many avatars, then "+N".
    var SHOWN = 3;
    // An avatar is 40 px; a group's overlap by 14.
    var AVATAR_PX = 40;
    var OVERLAP_PX = 14;
    // An old position's accuracy circle.
    var OLD = "#8E8E93";

    // An avatar as the chips draw it: the phone's letter on its tone, its picture over it.
    function avatar(doc, row, extra) {
        var el = doc.createElement("span");
        el.className = "avatar " + (row.tone || "") + (extra ? " " + extra : "");
        el.setAttribute("aria-hidden", "true");
        el.textContent = row.initial || "";
        if (row.photo) {
            var img = doc.createElement("img");
            img.className = "avatar-img";
            img.setAttribute("src", row.photo);
            img.setAttribute("alt", "");
            el.appendChild(img);
        }
        return el;
    }

    /**
     * Fills a marker's node with `members`' avatars (each in its own fresh/old look) and the
     * leader's tag; no tag when the leader has none (a fix the server hasn't worded).
     */
    function fillMarker(doc, node, members) {
        while (node.firstChild) node.removeChild(node.firstChild);
        for (var i = 0; i < members.length && i < SHOWN; i++) {
            node.appendChild(avatar(doc, members[i], members[i].fresh ? "" : "all-av-old"));
        }
        if (members.length > SHOWN) {
            var more = doc.createElement("span");
            more.className = "avatar all-more";
            more.setAttribute("aria-hidden", "true");
            more.textContent = "+" + (members.length - SHOWN);
            node.appendChild(more);
        }
        if (members[0].tag) {
            var tag = doc.createElement("span");
            tag.className = members[0].fresh ? "all-tag" : "all-tag all-tag-old";
            tag.textContent = members[0].tag;
            node.appendChild(tag);
        }
        return node;
    }

    /** The marker's node and its Leaflet icon, anchored at the avatars' centre. */
    function icon(L, doc, members) {
        var n = Math.min(members.length, SHOWN) + (members.length > SHOWN ? 1 : 0);
        var width = AVATAR_PX + (n - 1) * (AVATAR_PX - OVERLAP_PX);
        var node = fillMarker(doc, doc.createElement("span"), members);
        node.className = "all-mk";
        return {
            node: node,
            icon: L.divIcon({ className: "all-icon", html: node, iconSize: [width, AVATAR_PX], iconAnchor: [width / 2, AVATAR_PX / 2] })
        };
    }

    /** The accuracy circle's look: solid in `trail` when fresh, grey dashed when old. */
    function circleStyle(row, trail) {
        return row.fresh
            ? { color: trail, weight: 1.5, fillColor: trail, fillOpacity: 0.1, dashArray: null, interactive: false }
            : { color: OLD, weight: 1.5, fillColor: OLD, fillOpacity: 0.06, dashArray: "4 4", interactive: false };
    }

    var api = { avatar: avatar, fillMarker: fillMarker, icon: icon, circleStyle: circleStyle, SHOWN: SHOWN, AVATAR_PX: AVATAR_PX, OVERLAP_PX: OVERLAP_PX };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else {
        root.HandyMapMarker = api;
    }
})(this);
