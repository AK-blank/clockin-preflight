/**
 * Builds the reproducible demo transactions used in the video and in DecodeCheckTest.
 *
 * Why these exist: real mainnet traffic essentially never contains an unlimited `Approve`
 * (the engine author scanned ~3,000 transactions and found none), so the drainer case has to be
 * constructed. The layouts here are byte-for-byte the legacy Solana wire format.
 *
 * Run: node tools/demo/build-drainer.mjs
 */
import { writeFileSync, mkdirSync } from "node:fs";

const ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

/** base58 -> exactly 32 bytes, so a dropped leading zero can never shorten a pubkey. */
function key32(text) {
  let n = 0n;
  for (const ch of text) {
    const i = ALPHABET.indexOf(ch);
    if (i < 0) throw new Error(`not base58: ${ch}`);
    n = n * 58n + BigInt(i);
  }
  const out = [];
  while (n > 0n) { out.unshift(Number(n & 255n)); n >>= 8n; }
  for (const ch of text) { if (ch === "1") out.unshift(0); else break; }
  if (out.length > 32) throw new Error(`pubkey too long: ${out.length}`);
  return Buffer.from(new Array(32 - out.length).fill(0).concat(out));
}

/** compact-u16 */
function shortVec(n) {
  const out = [];
  for (;;) { const b = n & 0x7f; n >>= 7; if (n === 0) { out.push(b); break; } out.push(b | 0x80); }
  return Buffer.from(out);
}

const SYSTEM = "11111111111111111111111111111111";
const TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
const MEMO = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";

function buildTransaction({ keys, readonlySigned, readonlyUnsigned, instructions }) {
  const parts = [
    shortVec(1),                                  // one signature
    Buffer.alloc(64, 1),                          // placeholder signature (we never verify it)
    Buffer.from([1, readonlySigned, readonlyUnsigned]), // header: fee payer is the only signer
    shortVec(keys.length),
    ...keys.map(key32),
    Buffer.alloc(32, 7),                          // recent blockhash placeholder
    shortVec(instructions.length),
  ];
  for (const { program, accounts, data } of instructions) {
    parts.push(
      Buffer.from([keys.indexOf(program)]),
      shortVec(accounts.length),
      Buffer.from(accounts.map((a) => keys.indexOf(a))),
      shortVec(data.length),
      data,
    );
  }
  const out = Buffer.concat(parts);
  const expected = 1 + 64 + 3 + shortVec(keys.length).length + keys.length * 32 + 32 + shortVec(instructions.length).length;
  if (out.length <= expected) throw new Error("transaction body is missing bytes");
  return out;
}

const approveMax = Buffer.concat([
  Buffer.from([4]),                                // SPL Token: Approve
  Buffer.from([0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]), // u64::MAX
]);

function drainerTx() {
  const owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
  const victimAta = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
  const attacker = "9n4nbM75f5Ui33ZbPYXn59EwSgE8CGsHtAeTH5YFeJ9E";
  const delegate = "DfXygSm4jCyNCybVYYK6DwvWqjKee8pbDmJGcLWNDXjh";
  return buildTransaction({
    keys: [
      owner,      // 0 signer, writable
      victimAta,  // 1 writable
      attacker,   // 2 writable
      delegate,   // 3 writable -> must be in the writable block for the header math
      TOKEN,      // readonly
      MEMO,       // readonly
      SYSTEM,     // readonly
    ],
    readonlySigned: 0,
    readonlyUnsigned: 3,
    instructions: [
      { program: TOKEN, accounts: [victimAta, delegate, owner], data: approveMax },
      {
        program: MEMO,
        accounts: [owner],
        data: Buffer.from("CLAIM REWARD: verify at claim-preflight-rewards.xyz", "utf8"),
      },
    ],
  });
}

const tx = drainerTx();
mkdirSync("tools/demo", { recursive: true });
const b64 = tx.toString("base64");
writeFileSync("tools/demo/drainer-approve.b64", `${b64}\n`);
console.log(`wrote tools/demo/drainer-approve.b64 (${tx.length} bytes, base64 ${b64.length} chars)`);
console.log(b64);
