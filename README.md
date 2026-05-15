# avtchtask1 — Async Console Application with SQLite

[![CI](https://github.com/bukoeu/avtchtask1/actions/workflows/ci.yml/badge.svg)](https://github.com/bukoeu/avtchtask1/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![SQLite](https://img.shields.io/badge/SQLite-3.45-lightgrey?logo=sqlite)
![License](https://img.shields.io/badge/license-MIT-green)
[![Class Diagram](https://img.shields.io/badge/diagram-class--diagram-orange?logo=diagramsdotnet)](https://viewer.diagrams.net/?url=https://raw.githubusercontent.com/bukoeu/avtchtask1/main/class-diagram.drawio)
[![Async Flow](https://img.shields.io/badge/diagram-async--flow-orange?logo=diagramsdotnet)](https://viewer.diagrams.net/?url=https://raw.githubusercontent.com/bukoeu/avtchtask1/main/async-flow.drawio)
[![AsyncBatchWriter Flow](https://img.shields.io/badge/diagram-async--batch--writer--flow-orange?logo=diagramsdotnet)](https://viewer.diagrams.net/?url=https://raw.githubusercontent.com/bukoeu/avtchtask1/main/async-batch-writer-flow.drawio)

A plain Java 21 console application (no frameworks) that reads commands from stdin and persists
them asynchronously into a SQLite database. Written to demonstrate Java/JDK know-how, OOP
principles, clean code, concurrent programming, and unit testing.

---

## Task Requirements

| # | Requirement |
|---|-------------| 
| 1 | Read commands from stdin and process them **asynchronously** |
| 2 | **Thread-safe** — all concurrent access to shared state is synchronized |
| 3 | Invalid commands must not stop or block the application |
| 4 | Any persistence solution (SQLite chosen) |
| 5 | At least one unit test; optionally a concurrent simulation test |
| 6 | No application frameworks |

---

## Supported Commands

```
LOGIN(user_id)
```
Logs in a user. A user who is already logged in cannot log in again (idempotent UPSERT).

```
LOGOUT(user_id)
```
Logs out a user. A user who is not logged in cannot be logged out.

```
DATA_MODIFY(user_id)
```
Records a data modification for the given user. Ignored if the user is not logged in.
Multiple commands with the same `user_id` produce multiple entries.

```
STATS()
```
Prints to stdout:
- Number of currently logged-in users
- Number of modifications per user

```
EXIT()
```
Gracefully shuts down the command processor and closes the database connection.

---

## Architecture

The application follows the **Single Responsibility Principle** — each class has one job.

> [View interactive class diagram](https://viewer.diagrams.net/?url=https://raw.githubusercontent.com/bukoeu/avtchtask1/main/class-diagram.drawio)

```
com.avtchtask
├── Main.java                          Entry point — wires everything together
│
├── command/
│   ├── Command.java                   Interface: execute()
│   ├── CommandParser.java             Parses raw strings → Command objects
│   ├── CommandProcessor.java          Thread pool (ExecutorService) for async execution
│   ├── LoginCommand.java
│   ├── LogoutCommand.java
│   ├── DataModifyCommand.java
│   ├── StatsCommand.java
│   ├── ExitCommand.java
│   └── InvalidCommand.java            Silently ignored (no crash)
│
├── service/
│   ├── UserSessionService.java        Login / logout logic
│   ├── ModificationService.java       DATA_MODIFY logic
│   └── StatsReportService.java        STATS query + formatting
│
├── repository/
│   ├── UserRepository.java            Interface
│   ├── SQLiteUserRepository.java      SQLite implementation (UPSERT)
│   ├── ModificationRepository.java    Interface
│   └── SQLiteModificationRepository.java
│
└── db/
    ├── DatabaseManager.java           Facade — connection, schema, reads
    ├── AsyncBatchWriter.java          Write queue + group-commit writer thread
    └── WriteMetrics.java              Timing samples for test reporting
```

### Concurrency Design

```
Main thread          CommandProcessor        db-writer-0 thread
(stdin reader)       (4 worker threads)      (single writer)
     │                      │                      │
     │── submit(cmd) ──────>│                      │
     │                      │── cmd.execute() ──>  │
     │                      │   enqueue(sql)        │
     │                      │   future.get() ──────>│ poll queue
     │                      │        <── done ──────│ batch commit
```

- **CommandProcessor**: fixed thread pool (`command.processor.threads`, default 4) — one thread per command
- **AsyncBatchWriter**: single dedicated `db-writer-0` daemon thread drains up to `db.write.batch.size` (default 100) write tasks per SQLite transaction (group commit)
- **ReentrantLock(fair=true)**: shared between reads (DatabaseManager) and writes (AsyncBatchWriter) to prevent writer starvation

> [View interactive Async Command Execution Flow diagram](https://viewer.diagrams.net/?url=https://raw.githubusercontent.com/bukoeu/avtchtask1/main/async-flow.drawio)

> [View interactive AsyncBatchWriter Flow diagram](https://viewer.diagrams.net/?url=https://raw.githubusercontent.com/bukoeu/avtchtask1/main/async-batch-writer-flow.drawio)

---

## Tech Stack

| Technology | Version | Role |
|------------|---------|------|
| Java | 21 (Zulu) | Language / JDK |
| Maven | 3.9.x | Build tool |
| SQLite (xerial jdbc) | 3.45.1.0 | Embedded database |
| JUnit Jupiter | 5.10.2 | Unit & integration tests |
| Mockito | 5.10.0 | Mocking in unit tests |
| SLF4J Simple | 2.0.13 | Logging backend for sqlite-jdbc (bundled in fat JAR) |

No Spring, no Hibernate, no external frameworks.

---

## Building & Running

### Prerequisites

- JDK 21 (e.g. [Azul Zulu](https://www.azul.com/downloads/))
- Maven 3.9+

### Build fat JAR

```powershell
$env:JAVA_HOME = "C:\Java\zulu-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn package -DskipTests
```

### Run

```powershell
java -jar target/avtchtask1-1.0-SNAPSHOT.jar
```

### Example session

```
Ready. Enter commands (LOGIN, LOGOUT, DATA_MODIFY, STATS, EXIT):
LOGIN(alice)
LOGIN(bob)
DATA_MODIFY(alice)
DATA_MODIFY(alice)
DATA_MODIFY(bob)
STATS()
  Logged-in users: 2
  alice: 2 modification(s)
  bob: 1 modification(s)
LOGOUT(alice)
EXIT()
```

### Configuration (`src/main/resources/app.properties`)

```properties
command.processor.threads=4
db.write.batch.size=100
```

---

## Running Tests

```powershell
$env:JAVA_HOME = "C:\Java\zulu-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn clean test --no-transfer-progress
```

Or use the included CI scripts:

```powershell
.\run-tests.ps1    # PowerShell
run-tests.cmd      # cmd.exe
```

### Test Suite

| Test class | Type | Description |
|------------|------|-------------|
| `AppTest` | Unit | 21 tests with Mockito — CommandParser, UserSessionService, DataModifyCommand, StatsReportService |
| `DatabaseIntegrationTest` | Integration | Schema, CRUD, concurrent reads/writes against real in-memory SQLite |
| `ConcurrentUsersTest` | Load / concurrency | 100 users × 50 modifications per user; explicitly tests **AsyncBatchWriter** under high concurrency — verifies zero errors and exact DB row counts. Generates `target/write-timing.html` with lock-wait and SQL-execution charts |

After running, open the timing report:

```powershell
start target\write-timing.html
```

---

## Project Files

```
avtchtask1/
├── pom.xml
├── README.md
├── run-tests.ps1              CI script (PowerShell)
├── run-tests.cmd              CI script (cmd.exe)
├── class-diagram.drawio       Architecture class diagram
├── async-flow.drawio          Async Command Execution Flow diagram
├── async-batch-writer-flow.drawio  AsyncBatchWriter Flow diagram
├── src/
│   ├── main/
│   │   ├── java/com/avtchtask/
│   │   └── resources/app.properties
│   └── test/
│       ├── java/com/avtchtask/
│       └── resources/chartjs.min.js   (Chart.js 4.4.0, bundled offline)
└── target/
    ├── avtchtask1-1.0-SNAPSHOT.jar    Fat JAR
    ├── write-timing.html              Concurrency timing report
    └── write-log.txt                  Verbose write log (generated by ConcurrentUsersTest)
```

---

## Test Items

### `AppTest` — Unit tests (Mockito)

#### CommandParser — valid commands
- `LOGIN(user)` is parsed as `LoginCommand`
- `LOGOUT(user)` is parsed as `LogoutCommand`
- `DATA_MODIFY(user)` is parsed as `DataModifyCommand`
- `STATS()` is parsed as `StatsCommand`
- `EXIT()` is parsed as `ExitCommand`

#### CommandParser — invalid / malformed commands (must not crash)
- `LOGIN()` without argument is invalid
- `DATA_MODIFY()` without argument is invalid
- `EXIT(123)` with argument is invalid
- Unknown command string is invalid
- `null` input is invalid
- Empty string input is invalid

#### UserSessionService — LOGIN logic
- Login when not logged in → calls repository
- Login when already logged in → does nothing (idempotent)

#### UserSessionService — LOGOUT logic
- Logout when logged in → calls repository
- Logout when not logged in → does nothing

#### DataModifyCommand — execution logic
- `DATA_MODIFY` records modification when user is logged in
- `DATA_MODIFY` does not record when user is not logged in

#### StatsReportService — STATS output
- `STATS` prints correct number of logged-in users
- `STATS` prints modification counts per user

#### InvalidCommand
- `InvalidCommand.execute()` does not throw

---

### `DatabaseIntegrationTest` — Integration tests (real in-memory SQLite)

#### Schema / DatabaseManager
- `initSchema` creates both tables (`users`, `modifications`)

#### SQLiteUserRepository — login / logout
- `login` persists user as logged-in
- Unknown user is not logged in
- `logout` sets user as logged-out
- `login` twice does not duplicate record (UPSERT)
- `getLoggedInUsers` returns only currently logged-in users
- `getLoggedInUsers` is empty when no users are logged in

#### SQLiteModificationRepository
- `addModification` persists a record
- `getModificationCountByUser` counts entries correctly
- `getUsersWithModifications` returns distinct users
- `getUsersWithModifications` is empty when no modifications exist

#### Cross-repository
- Modification row persists in DB regardless of login/logout state

---

### `ConcurrentUsersTest` — Load / Concurrency test

Explicitly tests **`AsyncBatchWriter`** — the single-writer, group-commit thread that batches SQL writes under concurrent load.

| Parameter | Value |
|-----------|-------|
| Concurrent user threads | 100 |
| Modifications per user | 100 |
| Total DB writes | 10 000 |
| Timeout | 600 s |

#### Three phased stages (enforced by `CountDownLatch` barriers)
1. **Phase 1 — LOGIN**: all 100 threads log in simultaneously; no thread proceeds until every user is logged in
2. **Phase 2 — DATA_MODIFY**: all 100 threads write 50 modifications concurrently; drives maximum contention on `AsyncBatchWriter`
3. **Phase 3 — LOGOUT**: all 100 threads log out simultaneously; no logout starts before all modifications are committed

#### Assertions
- Zero exceptions thrown across all threads
- All 100 logins succeeded
- All 10 000 modifications recorded by the service layer
- All 100 logouts succeeded
- Zero logged-in users remain in the DB after phase 3
- Each of the 100 users has exactly 100 modification rows in the DB
- Total modification rows in DB = 10 000

#### Outputs generated
- `target/write-timing.html` — interactive Chart.js charts (lock-wait time, SQL execution time per write)
- `target/write-log.txt` — verbose per-write timing log
