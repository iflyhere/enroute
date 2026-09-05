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

Ten counters carry all cache coherency:

| Field | Type | Meaning |
|---|---|---|
| `sid` | `quint32` | Session id. Random, regenerated at every app start. Tells a client that the phone restarted and its cached counters are meaningless — without the client having to persist anything. |
| `routeRev` | `quint32` | Incremented whenever the route document changes. Also changes when the pilot's unit preferences change, because those alter `units` and every `*Text` field. |
| `navRev` | `quint32` | Incremented on every published navigation frame. Doubles as the `ETag` of the navigation frame. Appears only in that frame, not in the capability document. |
| `notamRev` | `quint32` | Incremented whenever the NOTAM document's content changes. Derived by comparing successive encodings rather than counted from an event, so it moves only when a client would actually see something different. Appears only in the NOTAM document. |
| `weatherRev` | `quint32` | Incremented whenever the weather document's content changes, derived the same way as `notamRev`. Appears only in the weather document. |
| `vacRev` | `quint32` | Incremented whenever the set of approach charts changes, derived the same way as `notamRev`. Appears only in the approach chart document. |
| `logRev` | `quint32` | Incremented whenever the flight log document changes, derived the same way as `notamRev`. Appears only in the flight log document. |
| `nearbyRev` | `quint32` | Incremented whenever the nearby waypoint document changes, derived the same way as `notamRev`. Appears only in that document. |
| `prefsRev` | `quint32` | Incremented whenever the companion preferences change. Unlike every other counter it is not derived from the data moving on its own: nothing here changes unless a pilot changes it. |
| `trafficRev` | `quint32` | Incremented on **every** published traffic frame, unlike the counters above, which move only when their content changes. That is deliberate: a client has to be able to tell "no traffic" from "no data", and the only thing separating them is that frames keep arriving. |
| `mapRev` | `quint32` | Changes whenever the set of downloaded map files changes. Appears in the capability document, and in every tile URL. A client that sees it move must refetch the style: the URLs in the one it holds no longer resolve, which is what stops its tile cache from outliving the maps it was filled from. |

Every navigation frame carries `alarm`, the collision alarm level, zero when there is none. It is
also on the traffic document, where the rest of the warning lives; it is repeated here because a
client that had to fetch that document to learn it was warned had to fetch it on every tick. Against
one real phone's own documents that is 28 fragments a second over Bluetooth, beside the frame's 32 --
nearly half the link, to keep a list of airliners current for the sake of one integer. On the frame
it is free: ten bytes on 590 is the same 32 fragments. It also means a client whose traffic document
did not arrive, or could not be decoded, is still warned.

Every navigation frame repeats `routeRev` and carries a `rev` object with all six remaining document
counters:

```json
"rev": { "notam": 3, "weather": 12, "vac": 1, "log": 4, "nearby": 9, "traffic": 2841, "prefs": 7 }
```

Together they are the whole caching protocol: a client caches each document keyed on `(sid, <its>Rev)`
and refetches when either changes. A counter of zero means the phone has never published that
document; there is nothing behind it and asking wastes a round trip.

**Why they are in the frame and not only in the capability document.** A client on Wi-Fi could
revalidate each endpoint with its ETag, but a Bluetooth client reads the capability document once
when it connects and the frame is the only thing that streams afterwards. Without these counters such
a client either refetches a twenty-kilobyte NOTAM document on a timer in case it changed, or never
learns that it did. Sixty bytes a frame settles it for every transport at once.

**Refetch the capability document at the same moment.** It is rebuilt in lockstep with the route
document, so a changed `routeRev` is also the only signal that its contents may have moved. A client
that fetches it once and keeps it can miss a whole capability: connecting in the fraction of a second
before the app first publishes its map revision leaves such a client believing, for the rest of the
session, that no map is on offer.

## Documents

### Capability document

```
GET /enroute/v1/hello
```

```json
{
  "v": 1,
  "app": "4.0.0",
  "sid": 2748393211,
  "routeRev": 7,
  "navPeriodMs": 1000,
  "units": { "hDist": "nm", "vDist": "ft" }
}
```

A client fetches this once after pairing, to confirm that it is talking to Enroute and not to some
other server on the same network, and to learn how often navigation frames are published.
`units.hDist` is one of `nm`, `km`, `mil` (matching the suffixes `Aircraft` itself emits — note that statute miles render as `mil`, not `mi`); `units.vDist` is one of `ft`, `m`. They are informational —
a client that needs to abbreviate or label an axis may use them, but primary display always uses the
`*Text` fields.

A client drawing its own text over the map should use **`mapOverlay`**, which carries a `label` and
a `halo` colour as CSS-style values. The app keeps that pair for its own overlays and swaps it with
night mode, because a fixed colour is unreadable on one of the two base maps — white text on a
daylight map is white on white. A client that ignores this and uses its own palette will get that
wrong in exactly the way the app already learned not to.

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
  "pAlt": 1981,
  "next":  { "n": "KIRCHZARTEN", "dist": 7014, "ete": 152, "eta": 1788256052, "tc": 121.8 },
  "final": { "n": "EDTL", "dist": 52664, "ete": 1137, "eta": 1788257037 },
  "fmt": {
    "nextName": "KIRCHZARTEN", "nextDist": "3.8 nm", "nextETE": "0:03", "nextETA": "9:47", "nextTC": "122°",
    "finalName": "EDTL", "finalDist": "28.4 nm", "finalETE": "0:19", "finalETA": "10:03",
    "alt": "3,750 ft", "gs": "90 kn", "pAlt": "FL065", "statusText": ""
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
| `pAlt` | `PositionProvider::pressureAltitude` | Pressure altitude in metres, from the phone's barometer. **Not inside `own`**, deliberately: a barometer reads without a satellite in sight, so this survives a lost fix. Omitted when there is no reading, or when the app does not believe the one it has. |
| `pAltImplausible` | `PositionProvider::pressureAltitudeImplausible` | Present and `true` when there **is** a reading and the app has flagged it implausible. A disbelieved barometer is not the same as no barometer, and a client should say so rather than show a flight level as fact. |
| `fmt.pAlt` | | The flight level as the moving map writes it — `FL065`, zero padded to three digits — or `-` when there is nothing to show. |
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

### NOTAM document

What the app itself would show for the waypoints of the current route. **No relevance logic is
added on the way out**: the document is the app's own `NOTAMProvider::notams()` restricted to each
waypoint, in the app's own order, and nothing else.

```
{
  "v": 1,
  "sid": 2748219411,
  "notamRev": 4,
  "warning": "NOTAMs not current around waypoint, requesting update",
  "filter": { "radius": 37040, "horizontalOnly": true, "flightLevelApplied": false },
  "groups": [
    { "wp": 0, "n": "EDNY", "data": true, "retrieved": "2026-09-03T06:12:44Z",
      "notams": [
        { "n": "A1234/26",
          "icao": "EDNY",
          "txt": "RWY 06/24 CLSD DUE TO WIP",
          "cat": "NOTAM",
          "sect": "Current",
          "traffic": "IV",
          "read": false,
          "from": "2026-09-01T06:00:00Z",
          "to":   "2026-09-30T16:00:00Z",
          "area": { "c": [9.51139, 47.67139], "r": 9260 } }
      ] },
    { "wp": 1, "n": "EDTL", "data": true, "retrieved": "2026-09-03T06:12:51Z" },
    { "wp": 2, "n": "EDTG", "data": false }
  ],
  "n": 1,
  "retrieved": "2026-09-03T06:12:44Z"
}
```

| Field | Meaning |
|---|---|
| `warning` | The app's own, already translated warning that its NOTAM data is not current. **Omitted when there is nothing to warn about**, so its mere presence is the signal. |
| `filter.radius` | Radius in metres around each waypoint within which a NOTAM is listed. Currently 20 NM. |
| `filter.horizontalOnly` | Always `true` in v1. The filter is a horizontal circle; a NOTAM's vertical band plays no part in it. |
| `filter.flightLevelApplied` | Always `false` in v1. A NOTAM's flight-level band is parsed by the app but is **not** used to decide whether the NOTAM is listed. |
| `groups[].wp` | Index into the route document's `wp` array. The join key; `n` is for display when the cached route is older than this document. |
| `groups[].data` | `true` if NOTAM data for this waypoint was actually retrieved. |
| `groups[].retrieved` | When it was retrieved, ISO 8601 UTC. Present exactly when `data` is `true`. |
| `groups[].notams` | The NOTAMs, in the app's own order: unread first, then by effective time. **Omitted when empty.** |
| `groups[].cut` | Number of NOTAMs this group could not carry because the document hit its cap. Omitted when none were dropped. |
| `n` | Number of NOTAMs actually in the document. |
| `dropped` | Total dropped across all groups. Omitted when none were. |
| `retrieved` | The **oldest** retrieval among the groups — a document is only as current as its stalest part. Omitted when no group has data. |

Per NOTAM:

| Field | Meaning |
|---|---|
| `n` | NOTAM number, e.g. `A1234/26`. Unique; usable as a list key. |
| `icao` | ICAO location. Omitted when empty. |
| `txt` | The NOTAM text as the app shows it. |
| `cat` | The app's category, derived there from the ICAO Q-code's subject letters. The complete set is `NOTAM-OBST` (obstacle or obstacle lights), `NOTAM-PJE` (parachute jumping), `NOTAM-UAS` (unmanned aircraft), `NOTAM-RA` (restricted area) and plain `NOTAM` for everything else. Treat an unknown value as a generic NOTAM. |
| `sect` | The section heading the app puts above this NOTAM: `Marked as read`, `Current`, `Next 24h`, `Next 90 days`. Untranslated on the phone too. Omitted when empty. |
| `traffic` | Affected traffic as the ICAO field, e.g. `IV`. Omitted when empty. |
| `read` | Whether the pilot marked it read on the phone. |
| `from`, `to` | Effective period, ISO 8601 UTC. **Either may be missing**: a permanent NOTAM has no end, and an unparsable date yields no start. |
| `area.c`, `area.r` | Affected circle: centre as `[lon, lat]`, radius in metres. Omitted when the app has no valid region. |

**A client must not present this as an airspace check.** Three limits make that the wrong reading,
and all three are stated in the document so that a client can state them to the pilot:

- the filter is horizontal only, so a NOTAM 500 ft above the ground is listed for an aircraft at
  FL100 and vice versa;
- it is anchored on route **waypoints**, not on the legs between them, so a NOTAM beside a long leg
  is not listed at all;
- `data: false` means nothing is known, which is not the same as nothing being there.

The one rule that matters most: **a group means "no NOTAMs here" only when `data` is `true`, `notams`
is absent, and `cut` is absent.** Any other combination means the client does not know.

### Weather document

METAR and TAF for the stations the app has downloaded, **in the app's own order** — `ObserverList`
sorts by distance to the last known position, so the nearest station is first and a client needs no
geometry of its own.

```
{
  "v": 1,
  "sid": 2748219411,
  "weatherRev": 3,
  "qnh": "1022 hPa in LFGA, 33 min ago",
  "sun": "SS 20:12, SR 06:41",
  "downloading": false,
  "st": [
    { "wp": { "n": "LFGA", "en": "COLMAR HOUSSEN", "c": [7.35917, 48.11028],
              "e": 191, "t": "AD", "cat": "AD-PAVED" },
      "way": "DIST 20 nm · QUJ 286°",
      "metar": { "raw": "METAR LFGA 032100Z AUTO VRB03KT CAVOK 21/12 Q1022",
                 "sum": "METAR 33 min ago: CAVOK",
                 "cat": "VFR",
                 "col": "green",
                 "obs": "2026-09-03T21:00:00Z" },
      "taf":   { "raw": "TAF LFGA 031700Z 0318/0324 27007KT CAVOK",
                 "iss": "2026-09-03T17:00:00Z" } }
  ]
}
```

| Field | Meaning |
|---|---|
| `qnh` | The app's own QNH sentence, naming the station it came from and the age of the reading. Omitted when the app has nothing to say. |
| `sun` | The app's own sunrise and sunset line. Omitted likewise. While the position is unknown the app itself says so, and that sentence travels verbatim rather than being replaced by an omission. |
| `downloading` | True while the app is fetching. A client should say so, or a short list reads as the final answer while more is on its way. |
| `st[].wp` | The station's waypoint, in **the same shape the route document uses**, so one decoder serves both. |
| `st[].way` | Distance and bearing from the current position, worded by the app. Omitted while the position is unknown. |
| `st[].metar.raw` | The report verbatim. Travels because a pilot reads METAR out loud and no summary replaces that. |
| `st[].metar.sum` | The app's own one-line summary. It already states the observation's age, so a client must not compute a second age from `obs` and show both. |
| `st[].metar.cat` | `VFR`, `MVFR`, `IFR`, `LIFR` or `unknown` — the enum name, not its number. |
| `st[].metar.col` | The app's **own** colour for that category. A client must use this rather than deriving a colour from `cat`: the app maps five categories onto its palette, and a second mapping would show a different colour for the same weather. `transparent` for `unknown`. |
| `st[].metar.obs`, `st[].taf.iss` | Observation and issue time, ISO 8601 UTC. Omitted when the app does not know them. |

A station appears only if it has a METAR or a TAF. Either member may be absent; both being absent
does not occur.

**This is not a weather service.** The app downloads reports for stations near the route and the
position, and this document carries exactly those. A station missing from the array has no report
in the app, which is not the same claim as good weather there.

### Approach chart document

Every visual approach chart the app has, manually imported and from a downloaded collection
alike, with the four corners each one is drawn on. The image bytes are **not** in here; each
entry is fetched separately from `/enroute/v1/map/vac/<n>`.

```
{
  "v": 1,
  "sid": 2748219411,
  "vacRev": 2,
  "available": true,
  "vac": [
    { "n": "EDTF",
      "d": "EDTF (FREIBURG)",
      "sect": "Germany",
      "q": [[7.70, 48.10], [7.95, 48.10], [7.95, 47.95], [7.70, 47.95]],
      "bbox": [7.70, 47.95, 7.95, 48.10] }
  ]
}
```

| Field | Meaning |
|---|---|
| `available` | Whether the app could reach its chart library at all. **Not the same claim as an empty `vac` array**: false means the question could not be asked, an empty array means it was asked and there are no charts. A client must be able to tell a pilot which of the two happened. |
| `vac[].n` | The chart's name in the library, and the last path element of its image URL. |
| `vac[].d` | The app's own description. Omitted when it adds nothing to the name. |
| `vac[].sect` | The app's own section heading, e.g. the country. Omitted when empty. |
| `vac[].q` | The four corners as `[longitude, latitude]`, in the order **top left, top right, bottom right, bottom left**. That is the app's own image-source order and what a renderer's quad expects, so it passes straight through. A manually imported chart is always axis aligned, because its corners come from its file name; a chart from a GeoTIFF can be a true quadrilateral, which is why four corners travel and not a rectangle. |
| `vac[].bbox` | `[west, south, east, north]`. Derivable from `q`, and sent anyway because it is what the app's own selection rule tests. |

**Which chart to show.** The app's rule is `GeoMaps::VACLibrary::vacs4Point()`, which is plain
bounding-box containment, boundary included, with the results sorted by name. A client applies that
same rule to `bbox` and stays in step with the phone without asking. Sorting matters: where two
charts overlap, two devices that pick differently show a pilot two different approaches.

**Where to draw it.** Above the aviation overlay and below the client's own route and aircraft.
That is where the app puts it — its chart layer is declared after every aviation layer, and it
keeps a second copy of the waypoint layer above the chart — so the chart covers the airspaces
while the route stays visible.

**Draw one at a time.** An image source holds its whole image. A pilot with a country's worth of
charts installed must not have a watch trying to hold all of them.

The image is served as `image/webp`, which is what the app stores. A chart from a downloaded
collection has no image file until the app extracts it; the server extracts on first request, so
the first fetch of such a chart is slower than the rest.

### Flight log document

The app's own logbook entries in the app's own order, newest first, with what its flight
detector currently believes.

**Read only.** A flight is started, ended and corrected on the phone, which owns the record.
There is no endpoint that changes one, and a client should not offer a control that suggests
otherwise.

```
{
  "v": 1,
  "sid": 2748219411,
  "logRev": 6,
  "state": "InFlight",
  "recording": true,
  "n": 57,
  "flights": [
    { "id": "7f1c2d90-0000-4000-8000-000000000001",
      "dep": "EDTF", "arr": "EDTL",
      "start": "2026-09-03T08:14:00Z", "land": "2026-09-03T09:01:00Z",
      "off":   "2026-09-03T08:02:00Z", "on":   "2026-09-03T09:10:00Z",
      "ft": "0:47", "bt": "1:08",
      "cs": "D-EABC", "ldg": 1, "track": true }
  ],
  "dropped": 53
}
```

| Field | Meaning |
|---|---|
| `state` | The flight detector's state: `Idle`, `TakeoffPhase`, `InFlight` or `LandingPhase`, as the enum name. |
| `recording` | Whether the app is recording a GPS track. |
| `n` | Entries in the whole logbook. |
| `flights` | The most recent entries, currently at most 25. |
| `dropped` | `n` minus the number sent. Carried so a list can say it is not the whole logbook instead of quietly looking like one. Omitted when nothing was left behind. |
| `dep`, `arr` | Aerodromes. **Either may be absent**: a landing away from an ICAO field leaves the arrival blank, and that is a real state, not missing data. |
| `start`, `land`, `off`, `on` | Airborne and block times, ISO 8601 UTC. Any of the four may be absent — a flight still running has no landing time, and an entry written by hand may have none at all. |
| `ft`, `bt` | Flight time and block time as the app's own `H:MM` strings, which is what a logbook column contains. Both absent when the recorded times do not permit one. |
| `cs` | Aircraft callsign. |
| `ldg` | Landings. Absent when zero. |
| `track` | Whether a GPS track is stored with the entry. The track itself is not served. |

The three detector states have colours on the app's own page, but those live in its QML and never
reach the encoder, so they are not on the wire. A client picks its own and should follow the
app's intent: green airborne, blue for a landing being confirmed, amber for a takeoff being
confirmed.

### Traffic document

What the pilot's traffic receiver is reporting, plus the receiver's own state.

```
{
  "v": 1,
  "sid": 2748219411,
  "trafficRev": 918,
  "rx": true,
  "status": "Receiving traffic data via Bluetooth.",
  "warning": { "lvl": 2, "type": 2, "d": "Traffic, 2 o'clock, same altitude",
               "hd": 900, "vd": 30 },
  "tfc": [
    { "id": "DD4711", "cs": "D-KABC",
      "lvl": 0, "col": "green", "t": "Glider",
      "hd": 2400, "vd": 220,
      "d": "Glider, +722 ft relative", "rel": true,
      "c": [7.8427, 48.0008], "trk": 220, "unc": 60 }
  ],
  "noBearing": { "id": "DD9999", "lvl": 1, "col": "yellow", "t": "Aircraft",
                 "hd": 3100, "vd": -90, "rel": true,
                 "d": "Traffic nearby, bearing unknown" }
}
```

| Field | Meaning |
|---|---|
| `rx` | Whether a receiver's heartbeat is reaching the app. **This is the field that carries the weight.** An empty `tfc` array means one of two entirely different things — nothing is flying nearby, or nothing is listening — and a display that shows an empty sky without saying which is lying by omission. |
| `status` | The app's own sentence about the receiver, which names what it tried. |
| `err`, `selfTest` | The receiver's runtime and self-test errors, when it reports any. Omitted otherwise. |
| `warning` | The app's current collision warning, present only while its alarm level is above zero. Its level and type are the receiver's. |
| `tfc[].lvl` | Alarm level, 0 to 3, as the receiver reports it. |
| `tfc[].col` | The app's **own** colour for that level, night mode included. A client must not derive one from `lvl`: the app maps three levels onto its palette twice over, and a second mapping would show a different colour for the same warning. |
| `tfc[].t` | Aircraft type, as the enum name: `Glider`, `Aircraft`, `Jet`, `Copter`, `Balloon`, `Drone`, `Paraglider`, `Skydiver`, `TowPlane`, `StaticObstacle`, `Airship`, `HangGlider` or `unknown`. |
| `tfc[].hd`, `tfc[].vd` | Horizontal distance in metres, and height difference in metres — positive above the aircraft. SI rather than formatted, unlike everything else on this link, because the app composes no separation line of its own to copy. A client formats these itself. |
| `tfc[].d` | The app's own composed line about the target. |
| `tfc[].rel` | The app's own relevance flag: within 1500 m vertically and 20 NM horizontally. **A client that draws traffic should draw exactly these.** The app's own map does — `Traffic.qml` gates its marker on this flag — and a client drawing more will put an airliner at FL320 on the same display as a glider five hundred feet above, and let it set the scale. Measured against a live Open Glider Network feed: 19 contacts, of which 3 were outside the band and one of those drove a watch's range rings to 50 km. |
| `tfc[].c` | `[longitude, latitude]`, the **extrapolated** position — the one the app draws, so that two screens do not show the same aircraft in two places. |
| `tfc[].trk` | Extrapolated true track in degrees. |
| `tfc[].unc` | Position uncertainty radius in metres. |
| `noBearing` | A target whose range the receiver knows but whose bearing it does not. FLARM reports this often. It cannot go on a map, and it must not be dropped — hence a member of its own that no map code will read by accident. |

**What a client does with an alarm.** `warning.lvl` is the receiver's own escalation, and it
rises and falls as the geometry changes. A client that alerts on every frame of a level-two
encounter will alert for half a minute continuously, which stops the alert meaning anything;
alert when the level **rises**, and again only if it rises further.

**A stale traffic picture is worse than none.** A client must discard what it holds when the
link drops, unlike the NOTAM and weather documents, which stay useful for hours. Ten-second-old
traffic shown as current is the one failure this document must not enable.

### Nearby waypoint document

Aerodromes, navaids and waypoints near the aircraft. This is the app's own
`GeoMapProvider::nearbyWaypoints()` for each of the three types it offers — sorted by
distance, **twenty of each**, which is the app's limit and not one invented here.

```
{
  "v": 1,
  "sid": 2748219411,
  "nearbyRev": 21,
  "positionKnown": true,
  "near": {
    "AD": [
      { "n": "BOCKWIESE", "c": [9.2612, 48.6483], "e": 386,
        "t": "AD", "cat": "AD-GRASS",
        "way": "DIST 4,7 nm \u00b7 QUJ 136\u00b0",
        "dist": 8704, "brg": 136 }
    ],
    "NAV": [ ... ],
    "WP":  [ ... ]
  }
}
```

| Field | Meaning |
|---|---|
| `positionKnown` | Whether the app knows where the aircraft is. **Not the same as three empty lists**: "nothing near here" and "we do not know where here is" are different answers, and only one of them lets a pilot stop looking. When false, `near` is absent entirely. |
| `near` | Keyed by the app's own type codes: `AD` aerodromes, `NAV` navaids, `WP` waypoints. Kept apart rather than merged into one distance-ordered list — a pilot looking for somewhere to land is not helped by three reporting points sitting between them and the nearest aerodrome. |
| `near[].n` … `cat` | The same waypoint shape the route document uses, so one decoder serves both. |
| `near[].way` | Distance and bearing as the app words it, with the pilot's own units. |
| `near[].dist`, `near[].brg` | The same in metres and degrees, because a client that sorts or filters needs a number and `way` is prose. |

**These distances go stale as the aircraft moves**, and that is true of the app's own page as
well: it computes them when the page opens and never again. The document is rebuilt every
thirty seconds, so a client polling it once a minute is better informed than a pilot looking
at the phone.

## The map

A companion device with its own map renderer is served everything that renderer needs, and all of
it comes off the phone: **a client needs no internet connection and no map service of its own.**

| Request | Response |
|---|---|
| `GET /enroute/v1/map/style.json` | the app's own style document, with its URLs pointing back here |
| `GET /enroute/v1/map/base-<mapRev>` | TileJSON for the vector base map |
| `GET /enroute/v1/map/base-<mapRev>/{z}/{x}/{y}.pbf` | one vector tile, gzipped |
| `GET /enroute/v1/map/terrain-<mapRev>` | TileJSON for the terrain layer used for hillshading |
| `GET /enroute/v1/map/terrain-<mapRev>/{z}/{x}/{y}.png` | one terrain tile |
| `GET /enroute/v1/map/aviationData.geojson` | the aviation data overlay |
| `GET /enroute/v1/map/notams.geojson` | the NOTAM overlay, as the moving map draws it |
| `GET /enroute/v1/map/flightMap/sprites/sprite[@2x].{json,png}` | the sprite sheet |
| `GET /enroute/v1/map/flightMap/fonts/{fontstack}/{range}.pbf` | one glyph range |

**A client does not construct these URLs.** It fetches `style.json` and follows what is inside it.
Every URL there is absolute and is built from the `Host` header of the request that asked for the
style, so a phone answering on Wi-Fi, on loopback and on a tethering interface at the same time
always names the address the client actually reached.

`style.json` is the app's own style file, unmodified except for its three URL placeholders. That
means a companion device draws the map the pilot has configured, night mode included, rather than
a second styling maintained separately.

Notes that matter when writing a client:

- **Vector tiles arrive gzipped**, with `Content-Encoding: gzip`, because that is how they are
  stored. An HTTP client that does not decompress transparently gets a corrupt protobuf.
- **A tile that is not in any downloaded map is `204 No Content`, not `404`.** Asking beyond the
  edge of a downloaded region is normal, not an error.
- **A tile set with no files still returns a valid TileJSON.** The style always declares its terrain
  source, but the terrain map is a separate optional download, so the common case is a pilot who has
  a base map and no hillshading.
- `mapRev` is in every tile URL. When it changes, refetch the style.
- Everything under `/map/` needs the pairing code like every other endpoint.

### Preferences document

`GET /enroute/v1/prefs`, or `{"get":"prefs"}` over Bluetooth. The companion device's own display
settings, held by the phone.

```json
{ "v": 1, "sid": 812739, "prefsRev": 7,
  "pageOrder": "map,data,notam,weather,log,settings",
  "hiddenPages": "vacs",
  "bezel": "pages", "charts": "auto",
  "alarmVibration": true, "transport": "auto" }
```

| Field | Meaning |
|---|---|
| `pageOrder` | Screen identifiers in the order they should appear, comma separated. **Empty means the companion's own default** and never "no screens". |
| `hiddenPages` | Screen identifiers not to show. A companion that hides everything still shows its settings screen, or there would be no way back. |
| `bezel` | `pages` or `zoom`: what a rotary input does. |
| `charts` | `auto`, `on` or `off`: whether approach charts are drawn on the map. |
| `alarmVibration` | Whether a collision alarm vibrates. Absent means **true**: a warning that is silent by accident is the wrong way for a default to be wrong. |
| `transport` | `auto`, `wifi` or `ble`: which link the companion should use. |

**Why the phone holds them.** Arranging nine screens with a fingertip on a 454 pixel disc is a poor
way to spend a pre-flight. The identifiers are the companion's, not the phone's: a phone lists the
ones it knows about, a companion ignores any it does not recognise, and inserts any screen of its own
that the list omits. So a phone and a companion of different versions still agree about the rest,
and a list that has fallen behind is a cosmetic problem rather than a broken one.

**Applied when `prefsRev` moves, and not otherwise.** The counter is incremented when someone
changes something on the phone and at no other time, which is what makes applying the whole document
right rather than surprising: it is always a deliberate act. Between two such moments a companion's
own settings screen owns these values and may differ. That asymmetry is real and a companion should
say so plainly rather than let a pilot discover it — the wire runs one way, and inventing a merge
for two editors would be inventing a problem.

### Attribution is not optional

The base map is OpenStreetMap data and the aviation data is openAIP and open flightmaps, licensed
for non-commercial use with an attribution requirement. The attribution string travels in the
TileJSON's `attribution` member, and **a client that renders these tiles must render the notice**.
This is the one obligation that follows the data onto the second screen.

Where the notice is shown is the client's choice; that it is shown is not. Drawing it across a
watch face costs two lines of a 454 pixel disc on every glance, so the companion app puts it on a
permanent settings page one swipe away, which is the arrangement the renderer's own info button
makes. What is not acceptable is dropping a source: the notice names three, and an attribution
that ellipsises one away is not an attribution.

### What is not served

Raster base maps. The style uses the vector map, and the raster map reaches the app's own renderer
by a different route. A client that wants it can ask for it in a later version of this protocol.

## Transport 1: HTTP over the local network

The app listens on TCP port **8973** on all interfaces, but only while the feature is enabled in
Settings.

| Request | Response |
|---|---|
| `GET /enroute/v1/hello` | capability document |
| `GET /enroute/v1/route` | route document, `ETag: W/"<routeRev>"` |
| `GET /enroute/v1/nav` | navigation frame, `ETag: W/"<navRev>"`, `304 Not Modified` when `If-None-Match` matches |
| `GET /enroute/v1/notams` | NOTAM document, `ETag: W/"<notamRev>"`, `304 Not Modified` when `If-None-Match` matches |
| `GET /enroute/v1/weather` | weather document, `ETag: W/"<weatherRev>"`, `304 Not Modified` when `If-None-Match` matches |
| `GET /enroute/v1/vacs` | approach chart document, `ETag: W/"<vacRev>"`, `304 Not Modified` when `If-None-Match` matches |
| `GET /enroute/v1/map/vac/<name>` | the chart image named `<name>`, `image/webp`; `404` if the library has no such chart |
| `GET /enroute/v1/log` | flight log document, `ETag: W/"<logRev>"`, `304 Not Modified` when `If-None-Match` matches |
| `GET /enroute/v1/traffic` | traffic document, `ETag: W/"<trafficRev>"`. The counter moves every second, so a `304` here means the app stopped publishing, not that nothing changed. |
| `GET /enroute/v1/nearby` | nearby waypoint document, `ETag: W/"<nearbyRev>"`, `304 Not Modified` when `If-None-Match` matches |
| `GET /enroute/v1/map/…` | the pilot's own map, see below |
| `GET /enroute/v1/route.geojson` | the app's own GeoJSON route |
| `GET /` | a small HTML page that polls `/nav`, for development and for checking the link from a desktop browser |
| any other path | `404` |
| any method other than `GET` | `405` |

Responses carry `Content-Type: application/json`, `Cache-Control: no-store` and
`X-Content-Type-Options: nosniff`. No CORS headers are sent, so a web page in the pilot's browser
cannot read the data cross-origin.

Clients poll `/enroute/v1/nav` once per second with `If-None-Match`, which costs a bare `304` when
nothing has changed, and fetch `/enroute/v1/route` only when `routeRev` changes. `/enroute/v1/notams`
is polled far more slowly — **60 seconds is the recommended period** — because its content moves when
NOTAM data is downloaded, which happens a few times a day, and otherwise only as the wall clock
carries a NOTAM into or out of force. `/enroute/v1/weather` sits between the two at **150 seconds**:
a METAR is issued about every half hour, but the summary states the observation's age, so a list
left alone for longer is visibly wrong about how old it is. `/enroute/v1/vacs` is slower
still at **300 seconds**, because the chart library changes only when the pilot imports or
removes a chart, which never happens in flight; that poll exists so a client which connected
before an import still learns about it. `/enroute/v1/log` sits at **30 seconds**, which is
fast enough for the detector's takeoff banner to feel live without being a feed.
`/enroute/v1/traffic` is the exception to all of this and is polled **at the navigation
rate**: it is the other thing on this link worth a second of a pilot's attention, and a
target that moved two seconds ago is a target drawn in the wrong place. `/enroute/v1/nearby`
goes the other way at **60 seconds**, which is still more current than the app's own nearby
page: that one computes its distances when it opens and never again.

Polling is deliberately preferred over a streamed response: a watch radio wakes for each poll either
way, a poll is its own reconnect logic, and `QHttpServerResponder`'s lifetime ends when the request
handler returns.

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

| `e5c0a002-...` | Nav | read, notify | the navigation frame, `fmt` included |
| `e5c0a003-...` | DocMeta | read, notify | `{"doc":"route","len":2104,"enc":"zlib","hash":"a91c33f2","chunk":19,"frags":111}` |
| `e5c0a004-...` | DocData | notify | the requested document, compressed and chunked |
| `e5c0a005-...` | Control | write | `{"get":"route","from":0}` or `{"rate":2000}` |

**`frags` of zero means the name is valid and there is nothing behind it.** A client
records the revision it asked at and stops asking. Silence cannot carry that meaning: a client
cannot tell it from a transfer that has not started yet, so it waits, gives up, and asks again for
the life of the connection.

`get` takes **any** of the document names this protocol serves — `prefs`, `route`, `notams`,
`weather`, `vacs`, `log`, `traffic`, `nearby` — not only the route. The two characteristics were named
RouteMeta and RouteData when the route was the only large document; they carry whichever one was
last asked for, and `doc` in the metadata says which. An unknown name is answered with silence,
because a client from a later version asking for something this one does not have is not an
error worth reporting to a pilot.

**Verified end to end in emulators, 2026-09-05.** Two Android emulators on one host see each other
over BLE, which makes this transport testable without an aircraft. A Wear OS emulator running the
companion app scanned, connected, negotiated an MTU of 517, subscribed, read Info and received the
route document from a phone emulator running this app -- with no IP path between the two. Four
consecutive clients were served without restarting the phone.

Three things had to be right for that, each of which fails silently when it is not:

- **The advertised local name does not survive.** Android sends the adapter's own name regardless of
  what is asked for, so a scan list shows the phone's Bluetooth name rather than "Enroute". A client
  filters on the service UUID and never on a name.
- **Android closes the GATT server on every disconnect and registers a new one, empty.** The service
  has to be added again for each client, not once at startup. Without that the phone serves exactly
  one client per app run and afterwards advertises a service it does not offer: connections succeed,
  discovery returns only the two services the stack adds itself, and the symptom looks like a broken
  watch.
- **Advertising cannot be restarted at the instant of the disconnect.** The stack still holds the
  previous advertiser and answers `ADVERTISE_FAILED_ALREADY_STARTED`; worse, a failed attempt returns
  the controller to `UnconnectedState`, which is the same signal that prompted the restart, so an
  immediate retry becomes a loop that ends with the stack refusing to advertise at all. The restart
  is deferred by half a second and coalesced.

A client has its own version of the second point: Android caches a peer's service list and returns
the cached one from a discovery, so a phone whose GATT database changed since the last connection
stays wrong. A client that connects and finds no companion service should drop that cache once and
reconnect before reporting the service missing.

**A client must also handle `onServiceChanged`, or it will not notice the phone restarting.** When
the app on the phone dies and comes back, the link-layer connection survives it: no disconnect is
delivered, the notifications simply stop, and the central is told only that the peer's services
changed. A client that ignores that callback holds a connection which will never carry anything
again -- correctly aged stale numbers on the display, and no retry, forever. The right response is to
drop the cached service list and rebuild the session from scratch rather than merely rediscover: the
phone's session id changes when it restarts, which invalidates every revision the client holds.
Measured: with the callback handled, a phone restart costs about eleven seconds and repairs itself.

**The client must request a larger MTU, and DocMeta is why.** The default ATT MTU of 23 leaves 20
usable bytes; a DocMeta document is around a hundred. At the default it arrives truncated, fails to
parse, and the transfer then stalls with nothing reported anywhere -- the client is waiting for a
document it was never told the name of. Nav frames and DocData are fragmented and survive the
default; DocMeta is not fragmented and does not. A central therefore asks for the largest MTU it can
before it subscribes to anything. Measured between two emulators: asking for 517 was granted in
full.

**Framing.** The usable payload of a notification is the negotiated MTU minus three bytes: 20 bytes
in the worst case, typically 244 once the central requests a larger MTU. Every Nav and DocData
notification is therefore prefixed with one byte: bit 7 set marks the last fragment, bits 0 to 6
carry the fragment index modulo 128. A single-fragment frame begins with `0x80`.

Qt does not expose the negotiated MTU in the peripheral role, so the phone starts at the guaranteed
floor: 19 payload bytes, which is never wrong and fifteen times slower than the link can carry.
**A client therefore states the MTU it negotiated** by writing `{"mtu":517}` to Control, before it
asks for anything, and the phone fragments to that size from then on. `chunk` in the metadata always
says which size is in use, so a client never has to assume.

The phone clamps what it is told to 244 payload bytes and never below 19. Not because the arithmetic
forbids more -- an MTU of 517 permits 513 -- but because a GATT attribute value may be 512 bytes, and
sending 514 killed the Android system server on an emulator: one byte over, and the stack did not
object, it died. 244 is the payload of the 247-byte MTU that every stack handles, and the difference
between it and the theoretical maximum is six fragments a second against four.

Measured between two emulators, the same eight documents including a 21738-byte NOTAM document:

| fragment payload | notifications for everything | time |
|---|---|---|
| 19 bytes | several hundred | 2.7 s |
| 244 bytes | **42** | **1.9 s** |

The steady state is the more important half: a navigation frame and a traffic document once a second
is 60 notifications a second at the floor and 6 at 244.

**No pairing code travels over Bluetooth.** Over Wi-Fi the code is what keeps a stranger on the same
network from reading the aircraft's position. Being connected to this GATT server already means being
within a few metres of the aircraft, so the phone hands the code *out* in Info rather than asking for
one. A pilot who only ever flies with Bluetooth therefore types nothing at all.

**What Info is for beyond bootstrapping.** A client that stored the phone's Wi-Fi address will
find it wrong the first time the network changes -- a stored address belongs to whichever network
it was learned on. Reading Info over Bluetooth recovers the current one without the pilot typing
anything, which is the difference between a link that repairs itself and one that needs a menu.

**Route compression.** The route document is compressed with `qCompress()`, advertised as
`"enc": "zlib"`. Note that this is **not** gzip: `qCompress()` emits a four-byte big-endian
uncompressed length, followed by a raw zlib stream. A client must skip those four bytes and inflate
the remainder. `hash` is the first four bytes of the SHA-1 of the *uncompressed* document, hex
encoded; on a mismatch the client re-requests from fragment zero.

**Flow control.** Writing a hundred notifications in a loop overflows the Android Bluetooth queue.
The client writes `{"get":"<name>","from":N}` to Control; the phone sends at most eight fragments and
then waits for the next request. A `from` of zero re-reads and re-compresses the document; any other
value continues the transfer already prepared, so a document cannot change halfway through one.

A navigation frame is fragmented the same way but sent in full rather than in windows: the compact
frame is a few hundred bytes, and a window would cost a round trip per frame at one hertz.

`{"rate":N}` is accepted and deliberately not obeyed. The publish rate belongs to the app and is
shared with every other client; a client that could set it would be told it had slowed the link
down when it had not.

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
