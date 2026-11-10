# Services CaliGo reads

Every piece of transit data comes from public services of Metro Cali S.A.
([metrocali.gov.co](https://www.metrocali.gov.co)), which allows reusing
its information for informative, non-commercial ends citing the source.
None of them needs a key. They are not documented by their owner and may
change without notice.

## Balance

```
GET https://metrocali.gov.co/cts/api/cts.php?numero={13 digits}
```

Success (HTTP 200):

```json
{"cardNumber":1906051868981,"cd_id":6,"crd_snr":5186898,
 "tsn":525,"balance":15300.0,"balanceDate":1785631011000}
```

`balanceDate` is epoch milliseconds. A card without movements comes back
with `cardNumber` 0. Failures also answer HTTP 200, as
`{"error": "..."}` with a generic message, so the app checks the 13 digits
itself before asking.

## Arrivals

```
GET https://servicios.siur.com.co/buscarutas/api/paradas_con_buses_proximos_a_llegar.php
    ?latitud={lat}&longitud={lon}&radio={metres, 300 at most}
```

An array of stops, each with the buses on their way:

```json
[{"idParada":"500800","nombreParada":"Plaza de Cayzedo A1",
  "distanciaMetros":119.52,
  "buses":[{"nombreLinea":"E21","nombreDestino":"Est. Universidades",
            "tiempoEstimadoDeSalida":1787429222000,"vehiculoId":"637001"}]}]
```

`tiempoEstimadoDeSalida` is epoch milliseconds. Each stop lists its next
five buses at most, whatever their line, so a line missing from a busy
stop may still be coming; stops with no bus coming are left out. The
service takes coordinates only. It used to give no stop positions;
answers sampled in November 2026 carry each stop's `latitud` and
`longitud`, which the app does not read yet. The routes below give every
stop they call at, with the same ids; only for a stop they leave out does
the app query the same area from two nearby points and place the stop
from the three distances.

How buses leave the lists, from 40 answers around two central stations,
one request every 30 s, each point every minute, over 20 minutes in
November 2026: `vehiculoId` was never empty nor repeated within a stop,
and each vehicle kept it, with its line and destination at each stop,
from one answer to the next. One vehicle can be listed at up to four
stops at once, at the farther ones at times under the destination of its
next trip. A bus leaves a stop's list before the time it gave, never
after: of 107 that left for good, 68% were due within 2 minutes when
last listed (median 82 s), the rest up to 11 minutes away. A bus due
after the fifth one is pushed out and usually comes back. The time given
moves up to a minute either way between answers. The app keeps a bus
that leaves within 2 minutes of its time on its row, at the stop and
then passed, until 4 minutes past that time.

The service also reuses a vehicle's id for its next pass at the same
stop: it lists the vehicle about one loop later (45 minutes at stop
514208, sampled on the phone in November 2026) right as it reaches the
stop, sometimes without leaving the list in between. Of 1447 pairs of
answers for the same vehicle at the same stop, its time changed by +0 s
at the median, +82 s at p99 and +537 s at p99.9; the two next passes
jumped by +45.2 and +42.1 minutes in 30 s, due within 2.1 and 0.1
minutes. A delay builds up answer by answer, about as fast as the clock
at most, so the app takes a listing as the vehicle's next trip when its
time is later than the time last given by more than the time between the
two answers plus 5 minutes, and that trip was due within 5 minutes of the
answer that last listed it; the trip before has then reached the stop. A
bus stuck far away drifting later stays the same trip (the largest such
changes seen: +8.9 minutes in 120 s at 22.1 minutes away, +6.4 in 180 s
at 6.8, +2.5 in 60 s at 6.6).

`vehiculoId` is not the bus number of `operations/{line}`: the A47's live
buses 22074, 32057 and 32079 ran while the arrivals listed 261001, 815001
and 939001, all ending in 001, so likely a schedule block. Neither
`busInfo` nor a route's direction confirms a trip by that id.

## Catalog, lines and buses

Base: `https://wsmio.siur.com.co:8083/apiMIO/jaxrs`

| Resource | Returns | Cached |
| --- | --- | --- |
| `stations` | every station: id, name, address, lat/lon | 7 days |
| `linesByStop/{stop}` | lines serving a stop | 30 days |
| `lines` | the line catalog: `lineId`, `name` | 7 days |
| `linestops/{line}` | a line's route, by short name (`A47`): stops in order per direction (`orientation` 0/1, `stopSequence`, `stopId`, `stopNam`, lat/lon) | 7 days |
| `linesOperation` | each line's first and last service, `HH:mm:ss` | 1 day |
| `operations/{line}` | buses running now: `busNumber`, `gpsx`/`gpsy` as degrees times 10^7 | never |
| `busInfo/{bus}` | the bus's trip: direction (`orientation`), `tripId`, `startstop`, `endstop` | per trip |

The catalog server does not compress its answers and sends no validators,
so a copy past its age is fetched whole again. When that fails the old
copy still answers, and for planning, week-old routes answer at once while
new ones come in the background, two at a time.

`lines` can leave out a line that runs, while `linesOperation` lists
lines that do not: a line with hours but outside the catalog is used once
the arrivals have shown it running within two weeks.

A bus keeps its direction until the end of its trip, so `busInfo` is asked
again every couple of minutes only while a bus is near its trip's last
stop, and otherwise every 45 minutes.

Stop names tell a station's platforms by a letter up to E and a number
(`Universidades B1`). A `P` and a number is either two berths of one stop,
a few metres apart, or the stops numbered along a road out of town, up to
kilometres apart (`Vía La Buitrera P10`).

## Addresses

The trip planner looks addresses and places up with Android's own
geocoder, Google's through Play services, with no key: within Cali, and
only when asked. Where Google has no address ranges for a block it
answers with another address that shares a number, even as an exact
match, so an address is taken only with the numbers typed. Failing that,
the app asks for the crossing of its two streets, and then looks for the
MIO stop named after that crossing. A phone without Play services only
gets the stops.

## Being a good client

- A service that answers HTTP 429, or keeps failing after one retry, is
  left alone for 1, 2, 4, 8 and at most 16 minutes; the first normal
  answer clears the wait. Rate limits are never retried.
- Arrivals refresh every 30 s while a bus is within 5 minutes, every 60 s
  within 15, otherwise every 120 s, and only while the screen listing them
  is in view: never in the background, nor under another screen. A line's
  buses are followed every 30 s on the same terms.
- One request answers for every stop within 280 m of the point asked
  around, so favorites and the stops of a trip are grouped to need as few
  as possible, and a trip reuses areas asked about within a minute. With
  a trip open, only its own stops are asked about again.
- Identical requests in flight share one answer, and what barely changes
  is cached as above.
