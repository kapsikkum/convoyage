#!/usr/bin/env node
// Builds the grouped Markdown body published with every v* GitHub Release.
//   node scripts/changelog.mjs [FROM_TAG] [TO_TAG]
// TO_TAG defaults to GITHUB_REF_NAME, then the newest tag in the repo. FROM_TAG defaults to the
// newest non-beta tag reachable from TO_TAG^ (i.e. the previous stable release), else the root commit.
// Commits are grouped by the prefix before the first ':' in their subject; `release:` commits are skipped.
import { execFileSync } from 'node:child_process';

const REPO = process.env.GITHUB_REPOSITORY || 'kapsikkum/convoyage';
const REPO_URL = `https://github.com/${REPO}`;
const SKIPPED_PREFIX = 'release';

// Group order in the output. Anything without a known prefix lands in Other.
const GROUPS = [
  { title: 'Apps', prefixes: ['android', 'ios', 'clients', 'ui'] },
  { title: 'Server', prefixes: ['server'] },
  { title: 'Vehicles', prefixes: ['vehicles', 'bikes', 'design-tools'] },
  { title: 'Tooling & CI', prefixes: ['ci', 'test', 'protocol', 'scripts', 'deploy'] },
  { title: 'Docs', prefixes: ['docs', 'readme'] },
  { title: 'Other', prefixes: [] },
];

function die(message) {
  console.error(`changelog: ${message}`);
  process.exit(1);
}

/** Runs git and returns trimmed stdout; throws on failure. */
function git(args) {
  return execFileSync('git', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    maxBuffer: 32 * 1024 * 1024,
  }).trim();
}

/** Runs git, returning null instead of throwing (used for optional lookups). */
function tryGit(args) {
  try {
    return git(args);
  } catch {
    return null;
  }
}

function resolveCommit(rev) {
  return tryGit(['rev-parse', '--verify', `${rev}^{commit}`]);
}

/** The previous stable tag, or the root commit, or null when nothing can be used. */
function defaultFromTag(to) {
  const merged = tryGit(['tag', '--merged', `${to}^`, '--sort=-creatordate']);
  if (merged) {
    const tag = merged
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
      .find((candidate) => candidate !== to && !candidate.toLowerCase().includes('beta'));
    if (tag) return tag;
  }

  const roots = tryGit(['rev-list', '--max-parents=0', to]);
  return roots ? roots.split('\n').filter(Boolean).pop() : null;
}

/** Splits a subject into its lowercased group key and the text after it. */
function parseSubject(subject) {
  const colon = subject.indexOf(':');
  if (colon === -1) return { prefix: '', subject: subject.trim() };
  const prefix = subject.slice(0, colon).trim().toLowerCase();
  const rest = subject.slice(colon + 1).trim();
  if (!prefix || /\s/.test(prefix)) return { prefix: '', subject: subject.trim() };
  return { prefix, subject: rest || subject.trim() };
}

/** Escapes the characters that would break a Markdown link, leaving subjects readable. */
function escapeSubject(text) {
  return text.replace(/\\/g, '\\\\').replace(/[[\]]/g, (char) => `\\${char}`);
}

function groupFor(prefix) {
  if (!prefix) return 'Other';
  const group = GROUPS.find((candidate) => candidate.prefixes.includes(prefix));
  return group ? group.title : 'Other';
}

function readCommits(range) {
  const format = '--pretty=format:%H%x09%s';
  const raw = git(range ? ['log', '--no-merges', format, range] : ['log', '--no-merges', format]);
  if (!raw) return [];

  return raw
    .split('\n')
    .map((line) => {
      const tab = line.indexOf('\t');
      if (tab === -1) return null;
      const sha = line.slice(0, tab).trim();
      const subject = line.slice(tab + 1).trim();
      if (!sha || !subject) return null;
      const parsed = parseSubject(subject);
      if (parsed.prefix === SKIPPED_PREFIX) return null;
      return { sha, ...parsed, group: groupFor(parsed.prefix) };
    })
    .filter(Boolean);
}

function compareLink(from, to) {
  return `[${from}...${to}](${REPO_URL}/compare/${from}...${to})`;
}

function render(commits, from, to) {
  const lines = ['## What\'s changed', ''];

  if (commits.length === 0) {
    lines.push('No recorded changes.', '');
  } else {
    for (const group of GROUPS) {
      const entries = commits.filter((commit) => commit.group === group.title);
      if (entries.length === 0) continue;
      lines.push(`### ${group.title}`);
      for (const commit of entries) {
        const label = commit.prefix ? `**${escapeSubject(commit.prefix)}:** ` : '';
        lines.push(`- ${label}${escapeSubject(commit.subject)} ([${commit.sha.slice(0, 7)}](${REPO_URL}/commit/${commit.sha}))`);
      }
      lines.push('');
    }
  }

  lines.push(
    '### Install',
    '- **Android:** [F-Droid repo](https://kapsikkum.github.io/apps/fdroid/repo) or the APK on this release.',
    '- **iOS:** [SideStore source](https://kapsikkum.github.io/apps/sidestore.json) or the unsigned IPA on this release.',
    '',
    `**Full changelog:** ${compareLink(from, to)}`,
  );

  return `${lines.join('\n')}\n`;
}

function main() {
  try {
    execFileSync('git', ['--version'], { stdio: 'ignore', windowsHide: true });
  } catch {
    die('git is not installed or not on PATH.');
  }

  if (!tryGit(['rev-parse', '--git-dir'])) {
    die(`not a git repository (run this from the repository root, e.g. ${process.cwd()}).`);
  }

  const [argFrom, argTo] = process.argv.slice(2);
  const to = argTo || process.env.GITHUB_REF_NAME || tryGit(['describe', '--tags', '--abbrev=0']);
  if (!to) die('cannot determine TO_TAG: pass one as the second argument or set GITHUB_REF_NAME.');
  if (!resolveCommit(to)) die(`TO_TAG ${to} does not exist in this repository.`);

  let from = argFrom || null;
  if (from && !resolveCommit(from)) die(`FROM_TAG ${from} does not exist in this repository.`);
  if (!from) {
    from = defaultFromTag(to);
    if (!from) die(`no tags are reachable from ${to} and no root commit was found.`);
  }

  const commits = readCommits(`${from}..${to}`);
  process.stdout.write(render(commits, from, to));
}

main();
