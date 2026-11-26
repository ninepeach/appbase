# State

## Design principles
Typed identity keys and StateFlow hold latest values, not history. Registry capacity defaults to 256 (1–4096). Immutable values are caller responsibility. No persistence or worker.

## API
StateStore(capacity = 256); declare(key, initial); observe(key): StateFlow<T>; set(key, value); update(key, transform); size(): Int. StateKey<T>(name) is an identity token, not name lookup. Names 1–128 characters and nonblank.

Example: val count = StateKey<Int>("count"); app.state.declare(count, 0); app.state.update(count) { it + 1 }.

## Problems and solutions
CAS update prevents lost concurrent increments; transform may execute repeatedly and must be pure. Registry lookup is synchronized; transform runs outside registry lock. StateFlow conflates equal values and intermediate updates. Duplicate identity and full registry reject declaration. Registry lifetime is App lifetime; dynamic removal, byte budgets and explicit disposal are not implemented. Do not retain large mutable payloads.

## Acceptance
Test concurrent increments, capacity, duplicate keys, unknown keys, conflation and transform retry. Source implemented; build/tests not verified.
