# Emulator + Mobile Wallet Adapter runtime — verified facts

Measured on 2026-10-05 on Apple Silicon / macOS 27 with emulator 37.3.2. Everything below is a
number or a bytecode-level fact, not an inference — commands are quoted so they can be re-run.

---

## 1. Toolchain: dl.google.com bypass (the single biggest win)

`dl.google.com` measured **~2.2 MB/s fresh, then throttled to ~60 B/s** — unusable for a 1.2 GB
emulator + system image. A full fast mirror exists:

| Mirror | Path |
|---|---|
| `https://mirrors.cloud.tencent.com/AndroidSDK/` | `<root>/<package>.zip` |
| indexes | `<root>/repository2-3.xml`, `<root>/sys-img/android/sys-img2-3.xml`, `<root>/sys-img/google_apis/sys-img2-3.xml` |
| system images | `<root>/sys-img/android/<image>.zip` ← note the `sys-img/android/` prefix |

Measured throughput **4.2–5.3 MB/s**.

| Download | Bytes | Wall time | Rate |
|---|---|---|---|
| `emulator-darwin_aarch64-16433917.zip` | 419,933,346 | 100 s | 4.2 MB/s |
| `sys-img/android/arm64-v8a-35_r02.zip` | 769,099,654 | 175 s | 4.4 MB/s |

**Total 1.19 GB in ~4.6 minutes.** Helper: `scripts/dl_pkg.sh` (resumable, retry loop).

**Image choice:** installed `system-images;android-35;default;arm64-v8a` (769 MB, AOSP) rather
than the requested `google_apis` (1779 MB). MWA needs **no Google Play Services** — it is plain
Android intents plus a localhost websocket. Verified working: the whole MWA flow below runs on the
AOSP image. Switching to `google_apis` would cost +2.3x download for no functional gain.

`avdmanager` needs a `package.xml` for the `emulator` package (the legacy `source.properties`
loader does not cover the `generic` package type) — without it you get
`Error: "emulator" package must be installed!`. Generated with `scripts/mk_package_xml.py`.

## 2. Emulator + AVD + APK: measured timings

AVD `clockin35`: API 35, `default/arm64-v8a`, `medium_phone`, at `tools/avd/` (emulator 37.3.2).

| Step | Measured |
|---|---|
| Cold boot, headless (`boot_avd.sh`) | **28 s** to `sys.boot_completed=1` |
| `adb install -r` (11 MB debug APK) | **< 1 s** |
| `am start` → rendered | **~6 s** |
| Full MWA session (authorize + sign) | **1.9–2.3 s** |
| `scripts/mwa_proof.sh` end-to-end incl. install | **4 s** |

```
adb devices
emulator-5554   device  product:sdk_phone64_arm64 model:Android_SDK_built_for_arm64 device:emu64a
```

**Demo-video implication:** a clean take costs seconds, not minutes. Re-recording is cheap.

Boot: `./scripts/boot_avd.sh` (launches `-no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect`).

App render proof: `tools/shots/probe-launch.png` — confirmed by eye (not a black screen): the real
Pre-Flight UI ("Know what you sign — before you sign it", tabs Pre-Flight/Authority/Address).

> Debug builds install as **`com.clockin.probe.debug`** (`applicationIdSuffix`), activity still
> `com.clockin.probe.MainActivity`:
> `adb shell am start -n com.clockin.probe.debug/com.clockin.probe.MainActivity`

## 3. Mobile Wallet Adapter 2.0.3 — real public API

From `javap` over the actual AARs in `~/.gradle/caches` (not documentation). Sources JARs were also
pulled from Aliyun and read; unpacked copies are under `tools/mwa/`.

**`mobile-wallet-adapter-clientlib-ktx:2.0.3`** (Kotlin DSL, what the app depends on)

| Symbol | Signature |
|---|---|
| `MobileWalletAdapter` | `(ConnectionIdentity, timeout: Int, CoroutineDispatcher, AssociationScenarioProvider)` |
| | `suspend connect(ActivityResultSender): TransactionResult<Unit>` |
| | `suspend disconnect(ActivityResultSender)` |
| | `suspend signIn(ActivityResultSender, SignInWithSolana.Payload)` |
| | `suspend <T> transact(ActivityResultSender, SignInWithSolana.Payload? = null, block: suspend AdapterOperations.(AuthorizationResult) -> T): TransactionResult<T>` |
| `AssociationScenarioProvider` | `provideAssociationScenario(timeout: Int): LocalAssociationScenario` |
| `ActivityResultSender` | `(ComponentActivity)`; `startActivityForResult(Intent, callback)` |
| `AdapterOperations` | `authorize`, `reauthorize`, `deauthorize`, `getCapabilities`, `signTransactions`, `signMessages`, `signMessagesDetached`, `signAndSendTransactions` |
| `ConnectionIdentity` | `(identityUri: Uri, iconUri: Uri, identityName: String)` |
| `TransactionResult` | `Success(payload, authResult)` / `Failure(message, e)` / `NoWalletFound(message)`; ext `successPayload` |

**`mobile-wallet-adapter-clientlib:2.0.3`** (protocol internals — all public)

| Symbol | Notes |
|---|---|
| `scenario.LocalAssociationScenario` | `(int clientTimeoutMs)`; `getPort()`, `getSession()`, `start(): NotifyOnCompleteFuture<MobileWalletAdapterClient>`, `close()` |
| `scenario.LocalAssociationIntentCreator` | `static Intent createAssociationIntent(Uri prefix, int port, MobileWalletAdapterSession)`, `static boolean isWalletEndpointAvailable(PackageManager)` |
| `scenario.Scenario` | **abstract** with a protected ctor — sub-classable |
| `protocol.MobileWalletAdapterClient` | `authorize`, `reauthorize`, `deauthorize`, `getCapabilities`, `signTransactions`, `signMessages`, `signMessagesDetached`, `signAndSendTransactions` |
| `protocol.MobileWalletAdapterSession` | `getEncodedAssociationPublicKey()`, `getSessionProperties()` |
| `transport.websockets.MobileWalletAdapterWebSocket` | `close()`, `onDisconnected` |

**`mobile-wallet-adapter-walletlib:2.0.3`** — the *official wallet-side* library, **available on
both mirrors** (`maven.aliyun.com`, `repo.huaweicloud.com`), so a mock wallet is a legitimate build:
`AssociationUri.parse(Uri)`, `LocalAssociationUri.port`, `createScenario(Context, MobileWalletAdapterConfig, AuthIssuerConfig, Scenario.Callbacks)`,
`Scenario.Callbacks` (`onAuthorizeRequest`, `onSignTransactionsRequest`, `onSignMessagesRequest`, …),
`AuthorizeRequest.completeWithAuthorize(...)`, `SignPayloadsRequest.completeWithSignedPayloads(byte[][])`.

### Wire protocol (from bytecode string constants + source, both directions confirmed)

1. dApp picks the port; wallet must bind inside **49152–65535**.
2. Association intent: `ACTION_VIEW` + `CATEGORY_BROWSABLE`, data
   `solana-wallet:///v1/associate/local?association=<b64url-pubkey>&port=<P>&v=1`.
3. The **wallet runs the websocket server**; the **dApp connects to `ws://127.0.0.1:<P>/solana-wallet`
   and retries** until the wallet is up. (`Walletlib`: `LocalWebSocketServerScenario`, "uri must end
   with v1/associate/local".)
4. ECDH + AES-GCM session, then JSON-RPC 2.0 `authorize` / `sign_transactions`.

A wallet is any app resolving `VIEW`+`BROWSABLE` for scheme `solana-wallet`.

## 4. RESULT: a mock wallet completes a real sign request — **PASS**

A two-app harness was built under `tools/mwa-harness/` (standalone Gradle project, deliberately not
part of the app's `settings.gradle.kts`):

- `mockwallet` — `walletlib:2.0.3`, declares the `solana-wallet` intent filter, serves the
  websocket, answers authorize/sign.
- `mwadriver` — `clientlib-ktx:2.0.3`, calls **exactly** the API the product will call:
  `MobileWalletAdapter(ConnectionIdentity(...)).transact(sender) { signTransactions(...) }`.

Run: `./scripts/mwa_proof.sh` → **VERDICT PASS** (exit 0), wall clock 4 s.

Observed, from `tools/logs/mwa-*.log`:

```
WALLET  parsed association: local=true port=64364 associationPubkey=65B protocols=[1]
WALLET  callback: onScenarioServingClients (dApp connected)
DRIVER  generateSessionECDHSecret / "Encrypted session established"   (encryption layer real)
DRIVER  Session properties: version = 1
DRIVER  STAGE authorize=OK  +1673ms
DRIVER    authToken=… (123 chars)   account[0].publicKey=… (32 bytes)   label=Mock Wallet
WALLET  onSignTransactionsRequest count=1 sizes=215   tx sigCount=1 messageOffset=65 messageLen=150
DRIVER  STAGE sign_transactions=OK  +90ms
DRIVER    request 215 B -> response 215 B ; message 150 B ; signature 64 B
DRIVER    signature slot wrote back byte-identical to declared tx = true
DRIVER  STAGE verify_ed25519=PASS
DRIVER  RESULT MWA_SESSION=PASS  total=1850ms
DRIVER  VERDICT PASS
```

The payload is a **real 215-byte legacy Solana transaction** (1 signature slot, 3 account keys,
System Program transfer of 1,000,000 lamports, message = 150 B). The wallet signs the **message**
exactly as a production wallet does, and the dApp **cryptographically verifies the Ed25519
signature** over those message bytes. So this is not a mock protocol — it is the real MWA session
encryption, the real JSON-RPC framing, and a real signature.

Evidence: `tools/shots/mwa-driver-pass.png` (on-screen PASS chain, read and confirmed),
`tools/mwa-result.json`, `tools/logs/mwa-driver.log`, `tools/logs/mwa-wallet.log`.

### Integration gotchas the app WILL hit (all found the hard way)

1. **`ConnectionIdentity.iconUri` must be RELATIVE.** An absolute URI throws
   `IllegalArgumentException: If non-null, iconRelativeUri must be a relative Uri` from
   `MobileWalletAdapterClient.authorize`.
2. **That exception is NOT caught.** `MobileWalletAdapter.associate()` only catches a fixed set
   (`IOException`, `TimeoutException`, `JsonRpc20*`, `IllegalStateException`, …). An
   `IllegalArgumentException` escapes and **crashes the host app**. Wrap `transact { }` in
   try/catch around the call site; the harness now does.
3. **`sign_transactions` is an advertised optional feature.** If the wallet does not list
   `solana:signTransactions` in its `MobileWalletAdapterConfig.optionalFeatures`, the dApp gets
   `-32601 method 'sign_transactions' not available` *even though authorize succeeded*. Real
   wallets (Phantom/Solflare) do advertise it; a hand-rolled wallet must.
4. **No software Ed25519 JCA provider on the AOSP API-35 image.** All three JCA routes fail:
   `AndroidOpenSSL` → `NoSuchAlgorithmException`, `BC` → `NoSuchAlgorithmException`, default →
   resolves to **AndroidKeyStore** → `IllegalStateException: Not initialized`. Any local Ed25519
   verification (e.g. SIWS) needs BouncyCastle primitives; the harness uses
   `org.bouncycastle:bcprov-jdk18on:1.78.1`.
5. `onScenarioComplete` does **not** fire on the normal path (the dApp tears the scenario down
   first) — a wallet must dismiss itself from `onScenarioServingComplete`.
6. The clientlib AAR already declares the `<queries>` element for `solana-wallet`, so the app gets
   wallet visibility without touching its own manifest.
7. `:app:mergeDebugJavaResource` can fail with `NoSuchFileException …/zip-cache/…` when builds
   race the incremental cache; the APK may still be emitted. Fix:
   `rm -rf app/build/intermediates/incremental/debug-mergeJavaRes`.

## 5. What the demo video can now show

The runtime is proven, so the demo can show a **genuine** MWA session on a real Android runtime
(no wallet app required from the Play Store):

- Emulator with the CLOCK IN app intercepting a transaction, plus the mock wallet as the counterparty.
- A real `authorize` → real encrypted session → real `sign_transactions` → verified signature.
- Honest framing: *"the wallet here is our walletlib-based test wallet; against Phantom on a
  Solana Mobile device the identical clientlib 2.0.3 code path runs unchanged."*

## 6. Files

```
scripts/dl_pkg.sh              resumable mirror downloader (4.4 MB/s)
scripts/mk_package_xml.py      generate SDK package.xml from a repo index
scripts/emulator_env.sh        shared env (SDK/AVD/APK paths, correct package id)
scripts/boot_avd.sh            headless boot + boot-time measurement
scripts/mwa_proof.sh           end-to-end MWA proof, exits non-zero unless VERDICT PASS
tools/mwa-harness/             standalone Gradle project: :mockwallet + :mwadriver
tools/mwa/                     unpacked MWA 2.0.3 AARs + sources (API evidence)
tools/mirror/                  repository2-3.xml + sys-img2-3.xml indexes
tools/shots/                   probe-launch.png, mwa-driver-pass.png
tools/logs/                    emulator.log, mwa-driver.log, mwa-wallet.log
tools/mwa-result.json          pulled on-device evidence
tools/dl/                      downloaded zips (emulator, system image)
tools/avd/clockin35.avd        the AVD
```
