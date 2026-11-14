# XaliPlaytime

**XaliPlaytime** counts how long players are really playing, for Paper and Folia. A player who stands in a farm or leaves the
game open does not earn it: only **active** time counts. It keeps the totals and a row for every day in a database, ranks players
by day, week, month and all time, adds a page to [Plan](https://github.com/plan-player-analytics/Plan), and tells other plugins
how long a player has played through an API. On a network, a Velocity part adds the servers up on Plan's network pages.
[XaliRefer](../refer/README.md) uses it for the time a referred player has to play.

## Contents

- [Installation](#installation): [Requirements](#requirements), [Modules](#modules),
  [Building and installing](#building-and-installing), [Removal](#removal)
- [How time is counted](#how-time-is-counted)
- [Configuration](#configuration): [Files](#files), [config.yml](#configyml), [Time from before the plugin](#time-from-before-the-plugin), [Database](#database)
- [Usage](#usage): [Commands](#commands), [Permissions](#permissions), [Placeholders](#placeholders), [Plan](#plan),
  [Network](#network), [The API](#the-api)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [See also](#see-also)

## Installation

### Requirements

| Software | Needed | Purpose |
|---|---|---|
| Paper or Folia 26.x | Yes | The server. Java 25. |
| MariaDB or MySQL | For a network | The database that the servers share. SQLite is a file of one server. |
| PlaceholderAPI | No | The `%xaliplaytime_...%` placeholders. |
| Velocity 4 | For a network page | Where Plan's web server runs, with the `velocity` jar. |
| Plan | No | The play time on the player, server and network pages, see [Plan](#plan). |

### Modules

| Module | Description |
|---|---|
| `playtime/paper` | `mc.xalitoria.playtime.paper`: the plugin, `xali-playtime-<version>-paper.jar`. |
| `playtime/velocity` | `mc.xalitoria.playtime.velocity`: the proxy part, `xali-playtime-<version>-velocity.jar`. It adds the servers up for Plan. |
| `playtime/common` | `com.xalitoria.playtime`: the tables and the queries that add the servers up. Bundled by both jars. |
| `playtime/api` | `net.xalitoria.playtime`: what other plugins compile against. Bukkit and Java types only. |

### Building and installing

```console
user $ ./gradlew :playtime:paper:shadowJar :playtime:velocity:shadowJar
```

Copy `build/libs/playtime/xali-playtime-<version>-paper.jar` into the `plugins` folder of every server. The descriptor comes
from the [Gradle plugin](../xali-gradle/README.md); the `paper { }` block is in `playtime/build.gradle.kts`. The plugin loads
after PlaceholderAPI, and a plugin that uses it lists it as a dependency (`XaliRefer` does) so that it is enabled first. The
API is in the plugin's jar: a plugin that uses it compiles against `net.xalitoria.playtime:api` and does not bundle it.

The servers of a network share the database, and each one counts the players it has under its own `server-name`. The proxy part
counts nothing: copy `xali-playtime-<version>-velocity.jar` into the `plugins` folder of the proxy whose Plan shows the network, and
see [Network](#network).

### Removal

Delete the jar. `plugins/XaliPlaytime/` (`plugins/xaliplaytime/` on the proxy) and the tables `<prefix>player`, `<prefix>playtime` and `<prefix>daily` stay.

## How time is counted

Every player who is online is watched, with a task on their own scheduler (a region thread on Folia, where their position may be
read) every `counting.check-interval`. An event handler costs one lookup and one short lock.

**AFK.** Any sign of play keeps a player active for `counting.afk-threshold` (30 seconds by default). Past that with no
sign they are AFK and the time stops counting, until they do something. The signs are moving keys, turning the view, chat,
commands, building, using or hitting something, and clicking in an inventory or dropping or changing the held item.
Movement itself is not read from move events (they fire for every step of every player). It comes from the player's key
presses and from a look at their position and view every `counting.check-interval`, which reads a few numbers. Something that
another plugin cancels does not count, so a player pressing against a protected area is not building.

**AFK machines.** Something that moves the player or presses for them (a water stream, a minecart loop, a jump bot, an
auto-clicker, a bot pacing in a pen) shows as activity if only the position or one repeated action is looked at. So
`counting.machines` judges time in windows (2 minutes by default) and throws away what a failing window earned. A window
passes if

- it shows at least `min-variety` **different kinds** of activity (keys, looking, chat, commands, building, clicking, inventory), and
- if the player stayed within `radius` blocks the whole window, one of them was a **deliberate** one: chat, a command,
  building, using something or an inventory click.

A water stream or a vehicle loop sends no key presses and does not turn the view, so it earns nothing. A bot that repeats
one key or one click has one kind. A bot that presses keys and turns in a pen has no deliberate activity. A player who
builds, fights, talks or walks about passes without noticing. This is best effort: a machine that varies what it does and walks
over a wide area looks like a player. Raise `min-variety` or `radius`, or lengthen the window, to be stricter. With
`machines.enabled: false` time counts as it passes.

**Saving.** The time that passed the judge is added to the database every `counting.save-interval`, when the player leaves and
when the plugin stops, always as an addition (`active = active + ?`), so servers that share a database lose nothing of what
the others wrote. At each save the plugins that listen are told how much was added.

What is shown to a player is more up to date than the database: it adds what has been earned since the last save, calculated
from the timestamps of the activity at the moment of asking. A window that is still being judged is counted in full, so the
figure is the most a player will have: if the window fails, it drops again.

Every day is its own row (a day is a UTC day), which is what the day, week and month leaderboards and the Plan table read.
**Connected** time is kept next to **active** time; the difference is the time spent AFK.

## Configuration

### Files

| File | Holds |
|---|---|
| `plugins/XaliPlaytime/config.yml` | Settings, see below. |
| `plugins/XaliPlaytime/language/<language>.yml` | Every message, written with the defaults on the first start. |
| `plugins/XaliPlaytime/xaliplaytime.db` | The data, with `database.type: sqlite`. |

Times are written like `30s`, `5m`, `2h` or `1h 30m`. A wrong value stops the plugin from loading, or from reloading, and
names the setting.

### config.yml

| Key | Default | Meaning |
|---|---|---|
| `debug` | `false` | Log what the plugin does. `/xaliplaytime admin debug` toggles it. |
| `server-name` | `server` | The name this server's time is kept under (up to 64 characters). Give each server of a network its own, the name in `velocity.toml` for one. Needs a restart. |
| `language` | `en-us` | The file in `language/` that messages are read from. |
| `counting.afk-threshold` | `30s` | Time without a sign of play after which a player is AFK. |
| `counting.check-interval` | `5s` | How often players are looked at. Not longer than the AFK threshold. |
| `counting.save-interval` | `1m` | How often the time earned is written to the database and passed to the plugins that listen. |
| `counting.machines.enabled` | `true` | Judge windows for AFK machines. |
| `counting.machines.window` | `2m` | Length of a window. |
| `counting.machines.radius` | `6` | Blocks a player may range over and still count as standing still. |
| `counting.machines.min-variety` | `2` | Kinds of activity a window needs (1 to 7). |
| `import.enable` | `true` | Bring in the time a player had on this server before the plugin, see [Time from before the plugin](#time-from-before-the-plugin). |
| `leaderboard.refresh` | `5m` | How often the leaderboards are brought up to date from the database. |
| `leaderboard.per-page` | `10` | Lines per page of `/xaliplaytime top`. |
| `leaderboard.size` | `100` | Places each leaderboard keeps, for the pages, the placeholders and the API. |
| `plan.enable` | `true` | Add the play time to Plan. Needs a restart. |
| `plan.days` | `14` | Days in the table of a player's page (1 to 90). |
| `database` | | See [Database](#database). |

`/xaliplaytime admin reload` saves the time of the players online, reads the files again and connects to the database again
with the settings as they are now: the new connection is made and its tables checked before it replaces the old, so a database that
can't be reached leaves everything as it was, with the reason in the answer. Players who are being counted keep the rules they
started with. `database.table-prefix` and `plan` are read only when the plugin starts.

### Time from before the plugin

Minecraft keeps a player's time on a server as a statistic, and that is what EssentialsX's `/playtime` shows, so a server that
has used Essentials already has it. When a player joins and this server has no time of theirs in the database, the statistic is
added to their time on this server once, and the log says so. It is put on the day they first played, so it counts for all time
and for the server's total, and not for today's or this week's leaderboard unless that was when they began. Minecraft does not know
AFK, so all of it counts as active. A player with nothing recorded gets nothing. EssentialsX itself does not have to be installed.

A server with time of the player already (even a reset, which leaves a row of nothing) is not brought in again, and another
server of the network brings in its own. A purged player is brought in again when they next join.

### Database

The `database` section is the one the other Xali plugins use (`DatabaseConfig` in [util](../util/README.md)):

| Key | Default | Meaning |
|---|---|---|
| `database.type` | `sqlite` | `sqlite`, `mariadb` or `mysql`. Use `mariadb` for MariaDB: it has its own driver. |
| `database.table-prefix` | `xaliplaytime_` | Prepended to every table name: letters, digits and underscores. A new prefix means new, empty tables. |
| `database.sqlite.filename` | `xaliplaytime.db` | The SQLite file. |
| `database.mysql` | | `host`, `port`, `database`, `username`, `password`, `use-ssl` and `pool-size`. |

Servers that point at one MariaDB/MySQL database share their players and their time. There are three tables: `player` (names,
first and last seen), `playtime` (active and connected milliseconds and joins, one row per player) and `daily` (the same per UTC day).

## Usage

### Commands

`/xaliplaytime`, also `/playtime`, `/xplaytime`, `/ptime` and `/xptime`.

| Command | Effect |
|---|---|
| `/playtime` | How long you have played, how long you were connected and AFK, today, this week and this month, and your place. |
| `/playtime info <player>` | The same for another player. |
| `/playtime top [day\|week\|month\|all] [page]` | Who played the most. |
| `/playtime admin save` | Write the time of the players online to the database now, and pass it on. |
| `/playtime admin add <player> <time>`, `remove`, `set` | Change the player's time **on this server**, with a time such as `1h 30m`. `remove` takes the newest days first and no more than the server has; `set` makes the server's part exactly that. A player who is online has their time saved first. |
| `/playtime admin reset <player>` | Take all the time of the player, on every server and every day. Their joins and name stay. |
| `/playtime admin purge <player>` | Delete the player from the database: their time on every server and their row. They start again when they next join. |
| `/playtime admin purge-inactive <days>` | Say how many players have not been seen for that long. Add `confirm` to delete them. Players who are online are left alone. |
| `/playtime admin reload` | Save, read the files and connect to the database again, see [config.yml](#configyml). |
| `/playtime admin debug` | Toggle debug logging. |

The leaderboards are read from memory and brought up to date every `leaderboard.refresh`, so a new player is on them after a while.
A day is a UTC day, the week and the month are the last 7 and 30 days with today in them.

### Permissions

| Permission | Default | Allows |
|---|---|---|
| `xaliplaytime.use` | everyone | `/playtime` and `top`. |
| `xaliplaytime.others` | operators | `/playtime info <player>`. |
| `xaliplaytime.admin` | operators | `/playtime admin ...`. |

### Placeholders

With PlaceholderAPI, `%xaliplaytime_...%`. They answer from memory, never from the database: what the server has counted for
the player (empty for one who is not online here) and the leaderboards as of their last refresh.

| Placeholder | Value |
|---|---|
| `active`, `active_formatted` | Active seconds, and in words. |
| `connected`, `connected_formatted` | Seconds connected, and in words. |
| `afk` | `true` or `false`. |
| `position`, `position_<period>` | Place on the leaderboard of all time, or of `day`, `week`, `month` or `all`; empty when not on it. |
| `top_<n>_name`, `top_<n>_time`, `top_<n>_time_formatted`, `top_<n>_position` | Place `n` (from 1) of the leaderboard of all time. `time` is in seconds. |
| `top_<period>_<n>_...` | The same for a period: `top_week_1_name`. |

### Plan

With Plan installed and `plan.enable` on, XaliPlaytime adds an extension, **XaliPlaytime**, to Plan's plugin pages. It is hooked
whenever Plan enables, whichever of the two loads first, and again if Plan reloads. Plan has its own "playtime", which counts
every second connected; this is the part of it that was active.

| Where | What |
|---|---|
| Player page | Active time (all, 7 days, 30 days), AFK time and the place of all time. A *Days* tab lists the last `plan.days` days: active and connected time. |
| Server page | Active time of everyone on this server, today (UTC) and in all, and the ten players most active on it. A player's page also has their time on this server. |
| Network page | With the [proxy part](#network): the same for the whole network. |

Plan refreshes a player's data when they join and leave, and the server's now and then. Everyone who may see a player's page in Plan
sees their time.

### Network

Plan's web server runs on one proxy, and a network page there shows what the whole network played. The `velocity` jar is a plugin
for that proxy. It counts nothing and is never told anything by the servers: each server adds its time to the shared database
under its `server-name`, and the proxy reads the database and adds the servers up. It needs Plan on the proxy; without it the plugin
logs that and stays idle. It does not work with the servers on SQLite, which are files of their own.

On first start it writes `plugins/xaliplaytime/config.yml`. Give it the same `database` section as the servers, and a user that may
only SELECT will do; the proxy never creates or changes a table. A restart is needed for a change.

| Key | Default | Meaning |
|---|---|---|
| `debug` | `false` | Log more. |
| `plan.enable` | `true` | Add the network's play time to Plan. |
| `plan.days` | `7` | Days of the servers' recent time (1 to 90). |
| `plan.top` | `10` | Places in the most active players of the network. |
| `database` | | As [Database](#database), with `table-prefix` the same as the servers'. The type defaults to `mariadb`. |

| Where | What |
|---|---|
| Network page | Active time of every server today, in 7 days and in all, the number of servers counting, a *Servers* tab with each server's total, recent time and players, and the most active players of the whole network. |
| Player page | The player's active time over the network (all and 7 days), their AFK time, and a *Servers* tab with their time on each server. |

A server shows up when it has added time for the first time. A server renamed in `server-name` is a new server: its earlier time
stays under the old name, in the network's total.

### The API

Other plugins read the play time through `XaliPlaytimeApi`, found in the services manager. List XaliPlaytime as a dependency of
the plugin so it is enabled first, and compile against `net.xalitoria.playtime:api` (`libs.xali.playtime.api` in the workspace).
It is made of JDK and Bukkit types, and no Kotlin ones, so a plugin with its own copy of the Kotlin runtime can use it, and so can
one written in Java.

```kotlin
val playtime = XaliPlaytimeApi.get()

playtime.cachedActive(player.uniqueId)            // milliseconds, now; -1 when the player is not online here
playtime.isAfk(player.uniqueId)
playtime.top(PlaytimePeriod.WEEK, 10)             // the leaderboard as of its last refresh
playtime.stats(uuid).thenAccept { stats -> }      // from the database; null for a player who never played

playtime.addListener(plugin) { player, activeMillis -> }   // told at every save, with what was added
```

| Method | Answers |
|---|---|
| `cachedActive(player)`, `cachedConnected(player)` | Active and connected milliseconds of an online player, up to now; -1 when not online here. |
| `pendingActive(player)` | What the player earned that the listeners have not been told yet: what a message such as "3 of 10 minutes" needs to be right between two saves. |
| `isAfk(player)` | Whether an online player is AFK. |
| `stats(player)` | A future with the totals, the periods, the joins and the first and last time seen. Do not wait for it on a region thread. |
| `top(period, limit)`, `position(player, period)` | The leaderboard in memory. |
| `addListener(plugin, listener)`, `removeListeners(plugin)` | Called from a thread of XaliPlaytime's own after the time was saved. Removed when the plugin disables. |

## Troubleshooting

### The leaderboard is empty, or a new player is not on it

It is read from the database at the start and then every `leaderboard.refresh` (5 minutes). Players are on it from the next refresh;
`/playtime admin reload` does not refresh it. A player has to have played a second for a place.

### The time is less than the player says

Time that fell in a window that failed the [machine test](#how-time-is-counted) is thrown away. Try `counting.machines.min-variety: 1`,
a smaller `radius` or `counting.machines.enabled: false` to see whether that is it. A player is also AFK 30 seconds after the last sign of play.

### A plugin that uses the API does not enable

It has to list XaliPlaytime as a dependency, and the plugin must be installed on that server. `XaliPlaytimeApi.get()` fails with "XaliPlaytime
is not enabled" otherwise.

## Development

Sources are under `playtime/<module>/kotlin`, tests under `playtime/<module>/test`.

| Package | Holds |
|---|---|
| `api` (`net.xalitoria.playtime`) | `XaliPlaytimeApi` and what it returns. Bukkit and Java types only, no default method bodies. |
| `paper/.../tracking` | `Session.kt` (the pure AFK clock), `Rules.kt` (the settings and the machine test), `Activity.kt`, `PlaytimeTracker` (watches players on their own scheduler) and `ActivityListener` (feeds it). |
| `paper/.../service` | `PlaytimeService`: arriving, saving, the listeners of other plugins and the leaderboards. |
| `common` | `PlaytimeStorage` (prefix, server name, database), the three tables (a day's row is per player and server) and `NetworkRepository`, the blocking reads that add servers up. No platform API. |
| `paper/.../data` | `MainConfig` and `MessageConfig`, `PlaytimeRepository` (every change an addition) and the model. |
| `velocity` | The proxy part: `ProxyConfig`, `PlaytimeDatabase` (read-only), `PlanHook` and `ProxyPlanExtension`. |
| `paper/.../command`, `listener`, `api` | The Brigadier command, the join and quit events, the service for other plugins. |
| `paper/.../external` | PlaceholderAPI and Plan (`external/plan`). Only these touch the classes of those plugins, and only once the plugin is known to be there. |

```console
user $ ./gradlew :playtime:paper:test :playtime:common:test :playtime:velocity:test
```

| Test | Shows |
|---|---|
| `PlaytimeRules` | The AFK threshold and the machine window with a hand-driven clock: a player who plays, one who stops, a water stream, a one-key bot, an auto-clicker, a bot in a pen. The time that is pending between saves, and AFK and connected time. |
| `PlaytimeStore` | The database against SQLite: time added never set, the day, week and month edges, the leaderboards and their equal places, names in any case; the servers kept apart; time brought in once; admin changes, resets and purges. |
| `ConfigFile` | The defaults, wrong times and the written file. |
| `PlanExtension` (`velocity`) | Plan's own check of the proxy's extension, the way times are written and the proxy's `config.yml`. |
| `NetworkRepository` (`common`) | The servers added up against SQLite: the network, one server, each server's total and players, the top lists and a player's servers. |
| `PlanExtension` | Plan's own check of the extension. |

The time rules know nothing of the server: `Session` takes the clock and what a player did. Test a rule change in `PlaytimeRules`
before running a server. Checked live on Folia with bots: the time counts while a bot walks and chats and a referral by
[XaliRefer](../refer/README.md) completes from it.

**Adding a setting**

1. Add the field, with a default, to `MainConfig` (or the message to `MessageConfig`). Times are read with `parseDuration`
   so a wrong value names the setting.
2. Use it where it matters, and add a case to `ConfigFile` for the default and for a wrong value.
3. Add a row to [config.yml](#configyml).

**Changing the API**

`api` is only ever added to: a plugin that uses it has its own copy of what it compiled against, and the interfaces are
implemented here. Keep to Bukkit and JDK types, and no default methods. A change is released and the pin `xali-playtime-api` in the
catalog is raised once it is published.

## See also

- [XaliRefer](../refer/README.md) — a referral completes after the referred player has played long enough
- [util](../util/README.md) — `DatabaseConfig`, messages and YAML files
- [Gradle plugin](../xali-gradle/README.md) — descriptors and loaders
- [Root README](../README.md) — the workspace

## External resources

- [Plan](https://github.com/plan-player-analytics/Plan) — Player Analytics, and its extension API
- [PlaceholderAPI](https://wiki.placeholderapi.com/) — the placeholders
