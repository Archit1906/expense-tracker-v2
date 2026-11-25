# Expense Tracker v1.2.0 — Financial architecture

Baseline: the working v1.1.0 bugfix ZIP. Application ID unchanged; versionCode 3, versionName 1.2.0. No destructive migrations.

## Implemented
- Room schema v6: transactions, review queue amounts, reported balances and ignored-SMS amounts are persisted as integer paise; migrated v5 REAL amounts are rounded to nearest paise. IDs, merchant rules, SMS hashes and original categories are preserved. Legacy Transfers category becomes an independent isTransfer flag. Display and forecast adapters still use Double rupees; core spending totals and wallet balances use Long paise.
- Wallet tab: create/edit bank, credit card, cash and UPI wallets with signed opening balances. Assign existing or new transactions manually; a linked outgoing transfer debits one wallet and credits another once. Imported SMS account metadata and last reported balances remain separate from calculated wallets. No automatic account linkage, reconciliation or live bank access.
- Transaction editor: expense, income, refund, reimbursement, transfer out and transfer in. Refunds and reimbursements reduce net spending in the month and category recorded. Income does not reduce spending. Transfers do not count as spending regardless of category. No original-transaction matching yet; users must select the correct type/category. Avoid linking an outgoing transfer to a destination if the incoming leg was separately recorded and linked.
- Category budgets and overall monthly overrides; past/future months editable, existing Settings budget serves as current-month fallback. Historical budget not invented when none stored. No automatic rollover or threshold notifications.
- Custom categories available in editor and SMS review; add-only to protect historic entries. Wallets can be edited but not deleted. Links validated in ViewModel and backup, not enforced by SQLite foreign keys in this version.
- Home cash-flow breakdown, current category budgets; transaction filters support linked wallets and transfers; insights uses recorded monthly budget if available. CSV includes transfer flag and wallet IDs.
- Backup format 3 includes paise, wallets, custom categories and monthly budgets. Older format 1/2 can be imported; old backups do not contain newly added data. Restore replaces data, so create an encrypted backup first. Format 3 cannot be restored into the old app.

## Verification
- 45 selected JVM tests passed using Kotlin compiler, lightweight Room/JUnit/JSON stubs. Includes 6 new finance tests; backup cryptographic tests ran against actual JCA. JSON serialization was compile-checked against stubs only; real JSON round trip requires Android tests. Five existing alert tests were not included in this run.
- SQLite migration fixture passed (all 21 statements, IDs and fingerprints preserved, transfer classification, signed balances, queue, ignored records, unique index and integrity check).
- Added Android persistence and encrypted v3 backup round-trip test and updated migration test to cover v4 -> v6. Not executed here.
- Full Android build attempted; Gradle 9.6 download failed with UnknownHostException. Compose compilation, Room annotation processing, Android migration and device behavior remain unverified. Prior v1.1 verification logs are historical only.

## Install/test on Windows
1. Create and save an encrypted backup from v1.1, then extract this ZIP into a new folder and open the directory containing settings.gradle.kts in Android Studio.
2. Sync Gradle; run ` .\gradlew.bat testDebugUnitTest` and ` .\gradlew.bat assembleDebug` in PowerShell. With a connected test device, run ` .\gradlew.bat connectedDebugAndroidTest`.
3. Update over the existing installation only when signing/application ID match. Never uninstall or clear app storage to work around a migration error. Keep the older backup separate from new backups.
4. Test existing transaction count and values; create Bank ₹1,000 and Cash ₹0; record one linked transfer out ₹200. Expected bank ₹800, cash ₹200, no spending increase. Add ₹100 cash expense: cash ₹100; spending +₹100. Add income ₹500: spending unchanged. Add ₹25 reimbursement: net spending decreases ₹25. Add custom category and ₹300 category budget, test historical month override and encrypted backup restoration on a test install.
