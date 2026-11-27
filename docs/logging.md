# Logging and error handling (TASK-35)

## Asynchronous writes

`PrivateLog.log(...)` is called from UI, service and receiver threads (~965 call sites).
It now only checks the two preferences, resolves the configured data host, and offers a
small entry to a bounded `ArrayBlockingQueue` (capacity 512). `offer()` is non-blocking;
when the queue is full the entry is dropped and counted in `getDroppedLogCount()` rather
than stalling the caller. One daemon worker batches queued entries, applies central
redaction, formats the timestamp and appends the lines to `logs.txt`. No file I/O happens
on the calling thread.

`getLogs()`, `getLogsList()` and `clearLogs()` are explicit user/inspection actions. They
call `WRITER.flush(500 ms)` so pending entries are visible before the file is read or
deleted. This bounded wait is never incurred by ordinary log calls.

## Privacy gates from TASK-42 (unchanged)

* Redaction (geo URIs, coordinates, URLs, mail addresses, configured data host) runs in the
  worker before anything reaches the file or logcat, and again when old log files are read.
* Logcat output additionally requires the `log_to_logcat` preference **and**
  `Redaction.isLogcatAllowed(BuildConfig.DEBUG, BuildConfig.VERSION_NAME)`, i.e. debug or
  userdebug builds only. Release builds never emit logcat lines even if the preference
  was restored from an older install.

## Failures and stack traces

File sink errors, flush timeouts and failures from the public logging boundary are stored
in the writer together with the original throwable (including its stack trace). They are
inspectable via `PrivateLog.getLastLoggingFailure()`, `getLoggingFailureCount()` and
`getDroppedLogCount()`; nothing is swallowed silently. `PrivateLog.logException(context,
tag, severity, throwable)` gives callers a way to record a full stack trace through the
same redacting queue. The four former `catch (Exception)` blocks in `PrivateLog` were
replaced by `IOException`/`RuntimeException` catches that record the failure; the remaining
broad catches are at the public API boundary (logging must never break its caller) and at
log-read boundaries (return the historic `null` on failure, but keep the cause). No other
source files were owned by this task, so the broad catches in `APIReaders`, `Areas`,
`ForecastAdapter` and similar classes are intentionally left unchanged; reflowing their
error handling is follow-up work, not a silent acceptance here.

## Crash reporting

A local crash path exists: on the first log call a `CrashHandler` is installed as the
default uncaught-exception handler, chaining to the previous handler. It flushes pending
entries, formats `FATAL uncaught exception in thread ...` plus the full stack trace through
the same redaction, appends it synchronously to `logs.txt` (best effort, because the process
is dying) and then delegates the throwable. Diagnostics failures during this path are
recorded but never replace the original exception.

No network/third-party crash reporter was added: that would require new dependencies,
permissions and an external endpoint, and would conflict with the privacy goal of keeping
logs local. Limitation: crashes before the first log call are not captured, and an abrupt
process kill can still lose entries that were only in the queue.

## Tests

`PrivateLogAsyncWriterTest` proves non-blocking enqueue with a blocked sink, redaction
before file/logcat output, bounded-queue drop behaviour, recorded sink failures and
stack-trace formatting. `LoggingHardeningGuardTest` guards the source-level properties
(no file I/O in `log()`, no broad silent catches, preserved TASK-42 gates, crash path).
