# Companion device protocol

Enroute Flight Navigation can publish the current flight route and the live navigation state to a
companion device, such as a smartwatch. This document is the normative description of that protocol.
It is the contract between the phone-side implementation in `src/companion/` and any client.

The protocol deliberately depends on nothing that the app does not already link: JSON built with
`QJsonDocument`, served either over the HTTP server that the app already uses for map tiles, or over
Bluetooth Low Energy using Qt Bluetooth. In particular it does **not** use the Google Play Services
Wearable Data Layer.

## Design rules

**Versioning has two layers.** Every document carries `"v": 1`, and the HTTP path carries the same
version (`/enroute/v1/...`). Adding a key is *not* a breaking change and does not bump the version;
clients must ignore keys they do not know. Removing or reinterpreting a key bumps both.

**Units travel twice.** Every quantity that a client may display is sent both as a raw SI number and
as the string that the phone itself would show. SI, because a client that draws a map or a course
deviation indicator needs real numbers. The formatted string, because `Navigation::Aircraft` already
knows the pilot's unit preferences, the rounding rules, and the translated unit suffixes. A client
that re-derives those will eventually disagree with the phone — the phone showing `12.4 nm` while
the watch shows `12.37 NM` on the same leg — and a navigation instrument that contradicts itself is
worse than no instrument. Clients render `*Text` and compute with the SI value.

Angles are the exception: degrees carry no unit preference, so they are plain numbers and the client
appends the degree sign itself.

**Missing values are omitted, never null-padded.** All `Units::Distance`, `Units::Speed`,
`Units::Angle` and `Units::Timespan` values in the app use NaN as their invalid marker, and JSON
cannot represent NaN. A key that is absent means "not known"; a client shows a placeholder. Whole
objects (`own`, `next`, `final`) are omitted when the thing they describe does not exist. Clients
must therefore treat every field as optional.

**Enumerations travel as strings**, not as integers. The numeric values of
`Navigation::RemainingRouteInfo::Status` are an implementation detail and may be reordered; the
strings are stable and readable in a browser.

**Count down from `ete`, not from `eta`.** The phone's clock and the client's clock can differ by
seconds or minutes. `ete` is a duration and is immune to that; `eta` and `etaText` are for display
only.

## Revisions

Three counters carry all cache coherency:

| Field | Type | Meaning |
|---|---|---|
| `sid` | `quint32` | Session id. Random, regenerated at every app start. Tells a client that the phone restarted and its cached counters are meaningless — without the client having to persist anything. |
| `routeRev` | `quint32` | Incremented whenever the route document changes. Also changes when the pilot's unit preferences change, because those alter `units` and every `*Text` field. |
| `navRev` | `quint32` | Incremented on every published navigation frame. Doubles as the HTTP `ETag`. |

Every navigation frame repeats `routeRev`. That one field is the whole caching protocol: a client
caches the route document keyed on `(sid, routeRev)` and refetches when either changes.

## Documents

### Capability document

```
GET /enroute/v1/hello
```

```json
{
  "v": 1,
  "app": "3.4.1",
  "sid": 2748393211,
  "routeRev": 7,
  "navRev": 1043,
  "navPeriodMs": 1000,
  "units": { "hDist": "nm", "vDist": "ft" }
}
```

A client fetches this once after pairing, to confirm that it is talking to Enroute and not to some
other server on the same network, and to learn how often navigation frames are published.
`units.hDist` is one of `nm`, `km`, `mil` (matching the suffixes `Aircraft` itself emits — note that statute miles render as `mil`, not `mi`); `units.vDist` is one of `ft`, `m`. They are informational —
a client that needs to abbreviate or label an axis may use them, but primary display always uses the
`*Text` fields.

### Route document

```
GET /enroute/v1/route
```

Sent on connect and whenever `routeRev` changes.

```json
{
  "v": 1,
  "sid": 2748393211,
  "routeRev": 7,
  "name": "EDTF (FREIBURG) - EDTL (LAHR)",
  "summary": "Total: 30.6 nm • ETE 0:20 h • 8.6 l",
  "units": { "hDist": "nm", "vDist": "ft" },
  "wp": [
    { "n": "EDTF", "en": "EDTF (FREIBURG)", "c": [7.83258, 48.02265], "e": 244, "t": "AD", "cat": "AD-GLD" },
    { "n": "KIRCHZARTEN", "c": [7.95000, 47.96667], "t": "WP", "cat": "WP" },
    { "n": "EDTL", "en": "EDTL (LAHR)", "c": [7.82778, 48.36917], "e": 156, "t": "AD", "cat": "AD" }
  ],
  "legs": [
    { "d": 10730, "tc": 125.4 },
    { "d": 45650, "tc": 348.5 }
  ]
}
```

| Key | Source | Notes |
|---|---|---|
| `name` | `FlightRoute::suggestedFilename()` | |
| `summary` | `FlightRoute::summary()` | **Plain text.** The property may contain rich text; the phone strips it before sending. |
| `wp[].n` | `Waypoint::shortName()` | ICAO code when present, otherwise the name. This is what a small display shows. |
| `wp[].en` | `Waypoint::extendedName()` | Sent only when it differs from `n`. |
| `wp[].c` | `Waypoint::coordinate()` | `[longitude, latitude]`, GeoJSON axis order, 5 decimal places (about 1.1 m). |
| `wp[].e` | `coordinate().altitude()` | Metres above mean sea level. Omitted when the coordinate carries no altitude. |
| `wp[].t` | `Waypoint::type()` | `AD` aerodrome, `NAV` navaid, `WP` waypoint. |
| `wp[].cat` | `Waypoint::category()` | For example `AD-GRASS`, `VOR-DME`. Clients may use it to pick an icon; they must tolerate unknown values. |
| `legs[i]` | `FlightRoute::legs()` | Connects `wp[i]` to `wp[i+1]`, so `legs.length == wp.length - 1`. |
| `legs[].d` | `Leg::distance()` | Whole metres. |
| `legs[].tc` | `Leg::TC()` | True course in degrees. **Omitted** when the leg is shorter than 100 m, where the course is undefined. |

A route holds at most 100 waypoints.

Per-leg ETE, ground speed and fuel are deliberately *not* here. They depend on wind and on the
aircraft and change continuously, which would make this document non-static and defeat `routeRev`.
The only estimates a client needs are in the navigation frame.

The full-fidelity route, with every waypoint property the app knows, is available separately at
`GET /enroute/v1/route.geojson` as the app's own GeoJSON. It is intended for debugging and for
desktop clients; it is an order of magnitude larger and is never pushed to a watch.

### Navigation frame

```
GET /enroute/v1/nav
GET /enroute/v1/nav?fmt=0        # omit the fmt object
```

```json
{
  "v": 1,
  "sid": 2748393211,
  "navRev": 1043,
  "routeRev": 7,
  "t": 1788255900,
  "status": "onRoute",
  "flightStatus": "flight",
  "note": "",
  "leg": 1,
  "own":   { "c": [7.87000, 48.00000], "alt": 1143, "agl": 812, "gs": 46.3, "tt": 122.4, "vs": -0.5 },
  "next":  { "n": "KIRCHZARTEN", "dist": 7014, "ete": 152, "eta": 1788256052, "tc": 121.8 },
  "final": { "n": "EDTL", "dist": 52664, "ete": 1137, "eta": 1788257037 },
  "fmt": {
    "nextName": "KIRCHZARTEN", "nextDist": "3.8 nm", "nextETE": "0:03", "nextETA": "9:47", "nextTC": "122°",
    "finalName": "EDTL", "finalDist": "28.4 nm", "finalETE": "0:19", "finalETA": "10:03",
    "alt": "3,750 ft", "gs": "90 kn", "statusText": ""
  }
}
```

| Key | Source | Notes |
|---|---|---|
| `t` | | Generation time, seconds since the Unix epoch, UTC. A client greys its display when `now - t` exceeds roughly 20 s, matching `PositionInfo::lifetime` on mobile. |
| `status` | `RemainingRouteInfo::status` | One of `noRoute`, `positionUnknown`, `offRoute`, `nearDestination`, `onRoute`. |
| `flightStatus` | `Navigator::flightStatus` | One of `ground`, `flight`, `unknown`. |
| `note` | `RemainingRouteInfo::note` | Already translated by the phone. Non-empty when ETE could not be computed because wind or aircraft data are missing. |
| `leg` | `FlightRoute::currentLeg()` | Index into `legs`. Omitted when there is no current leg. |
| `own` | `PositionProvider::positionInfo()` | **Omitted entirely** when the position is invalid. Individual keys omitted when NaN. `alt` = true altitude AMSL in m, `agl` = true altitude AGL in m, `gs` = ground speed in m/s, `tt` = true track in degrees, `vs` = vertical speed in m/s. |
| `next`, `final` | `RemainingRouteInfo` | **Omitted unless `status` is `onRoute`** — `RemainingRouteInfo` guarantees its `nextWP*` fields only in that state. `final` is additionally omitted when the final waypoint is not valid, which is the case when it is the same as the next waypoint. `dist` in whole metres, `ete` in whole seconds, `eta` in epoch seconds, `tc` in degrees. |
| `fmt` | `Navigation::Aircraft` formatters | Pre-formatted, localised display strings. Suppressed by `?fmt=0`, and always suppressed on Bluetooth to keep a frame inside one notification. Nothing is lost: the SI half is a complete description. |
| `fmt.nextETE` | `Units::Timespan::toHoursAndMinutes()` | Bare `"0:03"`. The phone's own UI appends `" h"` and clients should too. `"-:--"` when unknown. |
| `fmt.nextETA` | `RemainingRouteInfo::nextWP_ETAAsUTCString()` | `"H:mm"` in UTC, or `"-:--"`. |
| `fmt.statusText` | | The translated message for the current status, empty when `status` is `onRoute`. Produced with `QCoreApplication::translate("RemainingRouteBar", ...)`, so it reuses the phone's existing translations verbatim and matches what the phone's own status bar shows. |

Publication rate: at most once per second while `flightStatus` is `flight`, at most once every five
seconds on the ground, and suppressed entirely when nothing changed. A keep-alive frame is published
every ten seconds regardless, so that a Bluetooth client — which cannot poll — can distinguish a
live link from a dead one.

## Transport 1: HTTP over the local network

The app listens on TCP port **8973** on all interfaces, but only while the feature is enabled in
Settings.

| Request | Response |
|---|---|
| `GET /enroute/v1/hello` | capability document |
| `GET /enroute/v1/route` | route document, `ETag: W/"<routeRev>"` |
| `GET /enroute/v1/nav` | navigation frame, `ETag: W/"<navRev>"`, `304 Not Modified` when `If-None-Match` matches |
| `GET /enroute/v1/route.geojson` | the app's own GeoJSON route |
| `GET /` | a small HTML page that polls `/nav`, for development and for checking the link from a desktop browser |
| any other path | `404` |
| any method other than `GET` | `405` |

Responses carry `Content-Type: application/json`, `Cache-Control: no-store` and
`X-Content-Type-Options: nosniff`. No CORS headers are sent, so a web page in the pilot's browser
cannot read the data cross-origin.

Clients poll `/enroute/v1/nav` once per second with `If-None-Match`, which costs a bare `304` when
nothing has changed, and fetch `/enroute/v1/route` only when `routeRev` changes. Polling is
deliberately preferred over a streamed response: a watch radio wakes for each poll either way, a
poll is its own reconnect logic, and `QHttpServerResponder`'s lifetime ends when the request handler
returns.

### Authentication

Every request must present a **pairing code**: a six-digit decimal number generated when the feature
is first enabled and shown in Settings. It travels either as `Authorization: Bearer <code>` or, for
the browser development page only, as a `?k=<code>` query parameter.

A missing or wrong code produces a `401` with `WWW-Authenticate: Bearer` and an empty body,
identical in every case. The comparison is constant-time. After ten failed attempts from one peer
address within sixty seconds, that address is refused for sixty seconds — without which a
six-digit code falls to brute force in minutes over Wi-Fi.

Requests are additionally refused unless the peer address is loopback, private-use or link-local, so
the endpoint is unreachable from the public internet even if the phone holds a public address.

This transport publishes live aircraft position on a local network. It is off by default, and the
first time it is enabled the app says so explicitly.

### Discovery

While the HTTP transport is running, the app broadcasts this datagram to `255.255.255.255:8973`
every five seconds:

```json
{ "App": "Enroute Flight Navigation", "companion": { "port": 8973, "v": 1, "sid": 2748393211 } }
```

The pairing code is **not** in the beacon. A client learns the phone's address from the datagram's
sender and is paired with the code that the pilot entered once.

Discovery is a convenience. Many networks block broadcast between clients, so manual entry of
address and port is always available and is the only mechanism a client must implement.

## Transport 2: Bluetooth Low Energy

The phone acts as a GATT peripheral and the companion device as a central. The same JSON documents
are carried, so a client shares its parser between transports.

| UUID | Characteristic | Properties | Payload |
|---|---|---|---|
| `e5c0a000-9b6f-4a1e-8d3c-1f7a2b6d4e10` | service | | |
| `e5c0a001-...` | Info | read | the capability document, plus `ip` and `code`, so that Bluetooth can bootstrap the Wi-Fi transport without typing |
| `e5c0a002-...` | Nav | read, notify | navigation frame with `fmt` suppressed |
| `e5c0a003-...` | RouteMeta | read, notify | `{"routeRev":7,"len":2104,"enc":"zlib","hash":"a91c33f2","chunk":240}` |
| `e5c0a004-...` | RouteData | notify | route document, compressed and chunked |
| `e5c0a005-...` | Control | write | `{"get":"route","from":0}` or `{"rate":2000}` |

**Framing.** The usable payload of a notification is the negotiated MTU minus three bytes: 20 bytes
in the worst case, typically 244 once the central requests a larger MTU. Every Nav and RouteData
notification is therefore prefixed with one byte: bit 7 set marks the last fragment, bits 0 to 6
carry the fragment index modulo 128. A single-fragment frame begins with `0x80`.

**Route compression.** The route document is compressed with `qCompress()`, advertised as
`"enc": "zlib"`. Note that this is **not** gzip: `qCompress()` emits a four-byte big-endian
uncompressed length, followed by a raw zlib stream. A client must skip those four bytes and inflate
the remainder. `hash` is the first four bytes of the SHA-1 of the *uncompressed* document, hex
encoded; on a mismatch the client re-requests from fragment zero.

**Flow control.** Writing a hundred notifications in a loop overflows the Android Bluetooth queue.
The client writes `{"get":"route","from":N}` to Control; the phone sends at most eight fragments and
then waits for the next request.

**Rate.** Nav notifications follow the same cadence as HTTP, but only while a client has actually
subscribed, and drop to one every four seconds while `flightStatus` is `ground`. A client may
request a different period by writing `{"rate":<milliseconds>}` to Control — which is how a watch
reduces its power draw when its screen is off.

## Client expectations

A client must:

- treat every field as optional and show a placeholder for anything absent;
- accept and ignore unknown keys, and accept an unknown `status` value by falling back to a neutral
  rendering rather than failing — this is what lets an older client keep working against a newer
  phone;
- not display `next` or `final` values when `status` is anything other than `onRoute`;
- show the age of the data whenever it is older than a few seconds, and never present a stale value
  as if it were current;
- keep displaying the last known values during a brief loss of connection, annotated with their age.
  A blank display is a worse answer to a two-second radio dropout than an old number honestly
  labelled as old.
