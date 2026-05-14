# avtchtask1 — Async Console Application with SQLite

[![CI](https://github.com/bukoeu/avtchtask1/actions/workflows/ci.yml/badge.svg)](https://github.com/bukoeu/avtchtask1/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![SQLite](https://img.shields.io/badge/SQLite-3.45-lightgrey?logo=sqlite)
![License](https://img.shields.io/badge/license-MIT-green)

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

---

## Tech Stack

| Technology | Version | Role |
|------------|---------|------|
| Java | 21 (Zulu) | Language / JDK |
| Maven | 3.9.x | Build tool |
| SQLite (xerial jdbc) | 3.45.1.0 | Embedded database |
| JUnit Jupiter | 5.10.2 | Unit & integration tests |
| Mockito | 5.10.0 | Mocking in unit tests |

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
| `ConcurrentUsersTest` | Load / concurrency | 100 users × 100 modifications; verifies zero errors and correct DB row counts. Generates `target/write-timing.html` with lock-wait and SQL-execution charts |

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
