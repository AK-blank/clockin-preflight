/**
 * Publish the working tree to GitHub through the REST "Git Data" API.
 *
 * Why this exists: on this network `github.com:443` (the Smart HTTP git endpoint) is unreachable —
 * connections hang — while `api.github.com` answers in ~0.5s. `git push` therefore cannot work, but
 * the same objects can be created over the API: blobs -> tree -> commit -> ref update.
 *
 * Usage:  node tools/publish-via-api.mjs
 * Env:    GITHUB_REPO=owner/name   GITHUB_BRANCH=main   GITHUB_TOKEN=... (defaults to `gh auth token`)
 *
 * It reads the committed tree (`git ls-files`), so anything gitignored or uncommitted is never sent.
 */
import { execSync } from "node:child_process";
import { readFileSync, statSync } from "node:fs";

const REPO = process.env.GITHUB_REPO ?? "AK-blank/clockin-preflight";
const BRANCH = process.env.GITHUB_BRANCH ?? "main";
const TOKEN = process.env.GITHUB_TOKEN ?? execSync("gh auth token", { encoding: "utf8" }).trim();
const API = "https://api.github.com";
const CONCURRENCY = 6;

async function api(path, init = {}) {
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "clockin-preflight-publisher",
      ...(init.headers ?? {}),
    },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${init.method ?? "GET"} ${path} -> HTTP ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

/**
 * Every tracked file plus its real mode, so the published tree matches the commit exactly.
 * Reading the mode from the index matters: hardcoding 100644 silently strips the executable bit
 * from scripts/*.sh, which would leave `./scripts/bootstrap_toolchain.sh` unrunnable for a judge.
 */
function trackedFiles() {
  return execSync("git ls-files -s -z", { encoding: "utf8" })
    .split("\0")
    .filter(Boolean)
    .map((entry) => {
      // "<mode> <object> <stage>\t<path>"
      const [meta, path] = entry.split("\t");
      const [mode] = meta.split(/\s+/);
      return { path, mode };
    });
}

async function createBlobs(files) {
  const entries = [];
  let index = 0;
  async function worker() {
    for (;;) {
      const i = index++;
      if (i >= files.length) return;
      const { path, mode } = files[i];
      const content = readFileSync(path).toString("base64");
      const blob = await api(`/repos/${REPO}/git/blobs`, {
        method: "POST",
        body: JSON.stringify({ content, encoding: "base64" }),
      });
      entries.push({ path, mode, type: "blob", sha: blob.sha });
      process.stdout.write(`\r  blobs: ${entries.length}/${files.length}`);
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  process.stdout.write("\n");
  return entries;
}

const files = trackedFiles();
console.log(`publishing ${files.length} tracked files to ${REPO}@${BRANCH}`);
const bytes = files.reduce((n, f) => n + statSync(f.path).size, 0);
console.log(`total payload: ${(bytes / 1024 / 1024).toFixed(2)} MB`);

const treeEntries = await createBlobs(files);

const message = execSync("git log -1 --pretty=%B", { encoding: "utf8" }).trim();

// A brand-new GitHub repo rejects blob creation until it has at least one commit
// ("409 Git Repository is empty"), so seed it first via the Contents API if needed.
//
// FORCE_ROOT=1 publishes a root commit instead of a child of the current remote head. That is how
// you REPLACE history rather than extend it — the previous commits become unreachable, which is the
// only way to unpublish a file whose blob still exists in an older commit. (Deleting the whole repo
// would also work but needs the admin-only `delete_repo` scope.)
const forceRoot = process.env.FORCE_ROOT === "1";
const headRes = await fetch(`${API}/repos/${REPO}/git/ref/heads/${BRANCH}`, {
  headers: { Authorization: `Bearer ${TOKEN}`, Accept: "application/vnd.github+json" },
});
const remoteHead = headRes.ok ? (await headRes.json()).object.sha : null;
const parents = forceRoot || !remoteHead ? [] : [remoteHead];
if (parents.length === 0) {
  console.log(
    forceRoot && remoteHead
      ? `(FORCE_ROOT=1 — replacing ${remoteHead.slice(0, 8)} with a root commit)`
      : "(no branch yet — this will be a root commit)",
  );
}

// A tree replaces the whole branch by default, so:
//  - build the new tree on base_tree = current commit (so unchanged files are inherited cheaply), then
//  - if anything was removed locally, build a second tree whose entries set `sha: null` for those
//    paths. GitHub only accepts `sha: null` when a `base_tree` is supplied — sending it on a
//    parentless tree fails with 422 GitRPC::BadObjectState.
const parentSha = parents[0];
const tree = await api(`/repos/${REPO}/git/trees`, {
  method: "POST",
  body: JSON.stringify({
    base_tree: parentSha,
    tree: treeEntries.filter((e) => e.sha !== null),
  }),
});
console.log(`tree: ${tree.sha}`);

let finalTree = tree.sha;
if (parents.length > 0) {
  const remote = await api(`/repos/${REPO}/git/trees/${parentSha}?recursive=1`);
  const local = new Set(treeEntries.map((e) => e.path));
  const deletions = (remote.tree ?? [])
    .filter((n) => n.type === "blob" && !local.has(n.path))
    .map((n) => n.path);

  if (deletions.length > 0) {
    console.log(`pruning ${deletions.length} remote path(s) no longer tracked locally:`);
    for (const d of deletions) console.log(`  - ${d}`);
    const pruned = await api(`/repos/${REPO}/git/trees`, {
      method: "POST",
      body: JSON.stringify({
        base_tree: tree.sha,
        tree: deletions.map((path) => ({ path, mode: "100644", type: "blob", sha: null })),
      }),
    });
    finalTree = pruned.sha;
    console.log(`tree after prune: ${finalTree}`);
  }
}

const commit = await api(`/repos/${REPO}/git/commits`, {
  method: "POST",
  body: JSON.stringify({ message, tree: finalTree, parents }),
});
console.log(`commit: ${commit.sha}`);

const existing = await fetch(`${API}/repos/${REPO}/git/refs/heads/${BRANCH}`, {
  headers: { Authorization: `Bearer ${TOKEN}`, Accept: "application/vnd.github+json" },
});
if (existing.status === 404) {
  await api(`/repos/${REPO}/git/refs`, {
    method: "POST",
    body: JSON.stringify({ ref: `refs/heads/${BRANCH}`, sha: commit.sha }),
  });
  console.log(`created refs/heads/${BRANCH}`);
} else {
  // Replacing history (FORCE_ROOT) produces a non-descendant commit, which GitHub accepts only
  // when the ref update is explicitly forced ("Update is not a fast forward" otherwise).
  const force = forceRoot || process.env.FORCE_PUSH === "1";
  await api(`/repos/${REPO}/git/refs/heads/${BRANCH}`, {
    method: "PATCH",
    body: JSON.stringify({ sha: commit.sha, force }),
  });
  console.log(`updated refs/heads/${BRANCH}${force ? " (forced)" : ""}`);
}

console.log(`done: https://github.com/${REPO}/tree/${BRANCH}`);
