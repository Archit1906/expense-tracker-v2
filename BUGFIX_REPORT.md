# Expense Tracker 1.1.0 — Reliability and security patch

This source archive is a modified copy of the original project. The application ID, UI theme, existing screens, Android SDK configuration and Gradle dependency versions are retained. Version code is 2; Room database schema version is 5. No destructive migration is configured.

## Changes

| Area | Change |
|---|---|
| SMS parsing | Ignore explicit failed, pending, authorization/OTP and reminder messages; prioritize confirmed refunds/reversals; reject malformed, nonpositive or over-precision amounts; support signed reported balances; match Instamart before Swiggy; use full VPA as merchant identity. |
| Unverified senders | Financial messages from unfamiliar sender headers require explicit review; their balances are not imported automatically. Bank header recognition is a heuristic, not cryptographic sender authentication. |
| Deduplication | Exact hashes and strong same-bank/account/reference/amount/direction matches replace the unsafe amount/time-only heuristic. Similar no-reference messages with different timestamps go to explicit review. Room transactions serialize duplicate checks, insertion and balance updates. Live and inbox capture use SMS sent timestamps when present. |
| Review queue | Preserve debit/credit/refund type; allow user confirmation of type; no automatic approval of explicitly flagged messages; use common insertion path and block duplicate references. |
| Deletion/dismissal | Persist ignored SMS hashes (and strong scoped reference for deleted transactions); dismissals and deletes cannot reinsert the same fingerprint; include ignored records in backups. |
| Merchant rules | Learning is opt-in and future-only; previously saved categories are not bulk rewritten. Rules cannot convert ordinary transactions to transfers or vice versa. Existing VPA-based rules using truncated keys may need to be re-learned. |
| Budget accuracy | Income no longer reduces spending totals; optional net outflow remains separately visible. Monetary inputs allow two decimal places; totals use decimal arithmetic at paise precision. Refunds reduce net spending and transfers are excluded. |
| Analytics | Exclude future-dated transactions from current periods; show uncapped budget percentage; compare equivalent month-to-date periods; label historical comparisons against the current budget; separate accounts by bank and last four digits; label reported balances accurately. |
| Editor | Wait for successful database save, handle errors, disable repeated save, require delete confirmation, prevent future date selection, validate amount. |
| Backup | Explicit format validation, required arrays, valid amounts/types/identifiers, duplicate checks, 32 MiB limit, consistent Room snapshot, validation/preview before replacement, transactionally restore. Format 2 includes ignored SMS; format 1 remains readable if valid. Password encryption remains available and is recommended; empty passwords intentionally still produce a plaintext file with a warning. PBKDF2 fallback supports older providers using the same derivation. |
| Privacy | Fail closed when biometric authentication is unavailable; show authentication errors; disable Android automatic backup and exclude app data from device-transfer rules; hide spending notifications from lock screen. |
| Export and import | Neutralize spreadsheet formulas in text fields; fail if destination stream is unavailable; mark first import complete only after success; handle permission and scan errors; refresh permissions on resume. |
| Android compatibility | Guard Android 8+ notification-channel creation; use elapsed realtime for lock timeout; preserve package ID so the patch can update the original install if signed with the same key. |
| Project hygiene | Remove duplicate dependency; enable Room schema export for future Gradle builds; ignore generated IDE/build/signing artifacts; remove hardcoded personal greeting. |

## Verification performed in this environment

- 44 existing/new JVM test methods executed with the actual pure Kotlin source, JDK cryptography and lightweight compile-time substitutes for Android/Room/JUnit/JSON classes. All 44 passed. The substitute JSON API was not used to exercise JSON parsing; Android JSON validation is covered by added instrumented tests.
- Backend Kotlin type-checked with substitute framework APIs. This is not an Android/Compose or Room annotation-processing build.
- Migration 4→5 SQL was executed on host SQLite using a version-4 fixture; existing transaction and queue records were preserved and the new columns/table were created.
- Android XML files parsed successfully as XML (not a full Android resource compilation).
- Six new Android instrumented tests cover concurrent duplicates, distinct equal-value payments, deletion, queue reference dedup/type, transfer protection, balances, backup roundtrip/validation and real Room migration. They have NOT been run here.
- Gradle build attempt (`gradlew --no-daemon testDebugUnitTest assembleDebug`) failed before compilation because `services.gradle.org` could not resolve; this environment cannot download Gradle 9.6.0. No APK or successful Android build is claimed.

## Deliberately deferred / limitations

- No silent rewrite or deletion of previously saved financial records. Historical false positives, incorrect categories or missing payments need user review. Legacy SMS timestamps can differ from sent timestamps; old no-reference records may need duplicate review. Unknown bank headers and novel/ambiguous SMS formats still require real-world testing.
- Room still stores amounts as REAL/Double for backward compatibility. New entry validation and exact decimal totals improve accuracy, but a full integer-paise schema migration (including balances and budgets) is a separate project. Existing malformed legacy amounts may need correction before a backup can pass the new strict validation.
- Transfers are protected against automatic merchant rules, but a fully independent transaction-kind/account ledger, ATM cash reconciliation, historical budgets, full account identity and refund linkage are deferred until the wallet/financial-engine redesign. Accounts at the same bank with the same last four digits remain ambiguous.
- The main local Room database is protected by Android's sandbox/device security; SQLCipher/application-level encryption and automatic encrypted recovery snapshots are not implemented. Make an encrypted backup of your existing data before restoring/replacing it. A full privacy/security penetration test is not performed.
- Original Gradle, AGP, SDK and Kotlin/KSP version choices were retained; compatibility with the installed Android Studio/SDK must be verified on your machine. No generated Room schema snapshot is included because KSP could not run here. Do not use destructive migrations or uninstall the old app to resolve an upgrade issue.

## How to run

1. Create an encrypted backup using the currently installed version.
2. Extract the new ZIP into a separate folder. Open the folder containing `settings.gradle.kts` in Android Studio.
3. Sync Gradle with an internet connection and the SDK/JDK requested by the existing project. If sync fails, preserve the project and inspect the actual error rather than changing toolchain versions blindly.
4. On Windows, run `gradlew.bat testDebugUnitTest assembleDebug` from the project root. On Linux/macOS use `./gradlew testDebugUnitTest assembleDebug` (make the wrapper executable if necessary).
5. With an emulator/device connected, run `gradlew.bat connectedDebugAndroidTest`. Verify the upgrade against a backed-up copy of existing data and check real bank SMS samples. An update must be signed with the same signing key as the installed app.
6. The debug APK, once successfully built, will normally be under `app/build/outputs/apk/debug/`.

See `verification/jvm-test-results.txt` and `verification/gradle-attempt.txt` for the executed checks. The original ZIP is unchanged.
