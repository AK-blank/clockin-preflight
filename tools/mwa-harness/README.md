# MWA verification harness

Two tiny Android apps that prove the **Mobile Wallet Adapter 2.0.3** round-trip on an emulator,
independently of the main app. This exists because an MWA session normally needs a real wallet
installed — there is no Phantom or Solflare on a headless emulator — so the wallet side is supplied
here.

| Module | Role |
|---|---|
| `:mockwallet` | A wallet built on the official `com.solanamobile:mobile-wallet-adapter-walletlib:2.0.3`. Declares the `solana-wallet` intent filter, serves the localhost websocket, and signs. |
| `:mwadriver` | A minimal dApp that calls the exact API the real app calls: `MobileWalletAdapter(ConnectionIdentity(...))` → authorize → `signTransactions`. |

## Run it

From the repository root (the toolchain path is relative to it):

```bash
./scripts/mwa_proof.sh
```

That boots the AVD if needed, builds and installs both apps, drives a full session, and prints a
`VERDICT PASS/FAIL`. See [`../MWA-RUNTIME-REPORT.md`](../MWA-RUNTIME-REPORT.md) for the measured
results and the four integration gotchas this harness uncovered.

## What a passing run looks like

```
authorize=OK              → authToken, 32-byte pubkey, label
sign_transactions=OK      → 215 B in, 215 B out, 64-byte signature
verify_ed25519=PASS       → signature verified against the declared transaction
```

The session is real: ECDH + AES-GCM transport, JSON-RPC over `ws://127.0.0.1:<port>/solana-wallet`,
and a genuine Ed25519 signature over a legacy Solana transaction.

## Why the wallet is a test wallet

The harness counterparty is a `walletlib`-based test wallet, which is disclosed wherever the demo is
described. The **client** path is byte-for-byte the same one that talks to Phantom on a real Seeker —
same library version, same association intent, same protocol — which is exactly what makes the
harness meaningful as evidence.
