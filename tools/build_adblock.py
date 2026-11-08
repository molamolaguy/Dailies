#!/usr/bin/env python3
"""
Compiles public ad/tracker filter lists into the compact files the game player's ad blocker loads
(app/src/main/assets/adblock/). Re-run to refresh the lists:

    python3 tools/build_adblock.py <dir-with-downloaded-lists>

Inputs (any missing file is skipped):
  hosts-style lists  : stevenblack_hosts, hagezi_pro.txt (plain domains / wildcard domains)
  Adblock Plus syntax: easylist_*.txt, easyprivacy_*.txt, adguard_*.txt
  public_suffix_list.dat (publicsuffix.org) for "same site" checks

Outputs:
  hosts.bin            sorted big-endian uint64 FNV-1a hashes of blocked hostnames (host + subdomains)
  hosts_3p.bin         same, but only blocked when requested by a page on another site ($third-party)
  hosts_important.bin  host rules marked $important (only @@…$important exceptions lift them)
  allow_hosts.txt      hostnames never blocked (@@||host^ exceptions with no options)
  document_allow.txt   sites exempt from all blocking (@@||site^$document)
  url_rules.txt        URL pattern rules the host lists can't express (normalised ABP subset)
  cosmetic_generic.txt   element-hiding selectors for every site
  cosmetic_specific.txt  "domain1,~domain2\tselector" site-specific hiding rules (~ = excluded;
                         a line with only ~domains applies everywhere except those)
  cosmetic_except.txt    "domain\tselector" hiding exceptions
  cosmetic_nohide.txt    "site\tgeneric|all" sites with generic (or all) element hiding disabled
  psl.txt              public suffix rules
  sources.json         what went in, with counts and licences
"""
import json, os, re, struct, sys, time

def fnv1a64(s: str) -> int:
    h = 0xcbf29ce484222325
    for b in s.encode('ascii', 'ignore'):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h

HOST_RE = re.compile(r'^(?=.{1,253}$)(?:[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$')
def valid_host(h): return bool(HOST_RE.match(h)) and not h.replace('.', '').isdigit()

# uBO / AdGuard aliases -> canonical ABP names (the app parses the canonical names).
ALIASES = {'xhr': 'xmlhttprequest', 'css': 'stylesheet', 'frame': 'subdocument', 'beacon': 'ping',
           '3p': 'third-party', '1p': 'first-party', 'doc': 'document', 'ghide': 'generichide',
           'ehide': 'elemhide', 'from': 'domain', 'object-subrequest': 'object'}
TYPE_OPTS = {'script', 'image', 'stylesheet', 'xmlhttprequest', 'subdocument', 'media', 'font', 'object', 'ping', 'websocket', 'other'}
OK_OPTS = TYPE_OPTS | {'third-party', 'first-party', 'match-case', 'important', 'all', 'domain'}
PAGE_OPTS = {'document', 'generichide', 'elemhide'}  # exception-only "page" options
EXT_COSMETIC = ('#?#', '#$#', '#%#', '#@$#', '#@%#', '#@?#', '$$', '#$?#')
BAD_SELECTOR = re.compile(r':(?:-abp-|has-text|contains|xpath|upward|remove|style|matches-css|min-text-length|watch-attr|nth-ancestor|others|if|if-not|matches-path|matches-attr|matches-media|remove-attr|remove-class)\b|\+js\(|^\s*$')
OPT_STR_RE = re.compile(r'[~a-zA-Z0-9_=|.,\-*]+')
HOST_ONLY_RE = re.compile(r'^\|\|([a-z0-9_-]+(?:\.[a-z0-9_-]+)*)\^?$')


def parse_opts(opt_str):
    """[(neg, canonical_name, value)] in order; empty entries skipped (same as the app)."""
    out = []
    for o in opt_str.split(','):
        o = o.strip()
        if not o: continue
        neg = o.startswith('~')
        name, _, val = o.lstrip('~').partition('=')
        name = name.lower()
        out.append((neg, ALIASES.get(name, name), val))
    return out


def opts_to_str(opts):
    return ','.join(('~' if n else '') + k + ('=' + v if v else '') for n, k, v in opts)


class Builder:
    def __init__(self):
        self.block_hosts, self.block_hosts_3p, self.important_hosts = set(), set(), set()
        self.allow_hosts, self.document_allow = set(), set()
        self.url_rules = []
        self.generic_hide, self.hide_except = set(), set()
        self.specific_hide = {}  # (selector, negatives tuple) -> set(positive domains)
        self.nohide = {}         # site -> 'generic' | 'all'
        self.network_lines = []  # (line, src) collected first so $badfilter can cancel rules
        self.badfilters = set()

    # ------------------------------------------------------------ input
    def add_line(self, line, src=''):
        line = line.strip()
        if not line or line.startswith('!') or line.startswith('['):
            return
        if line.startswith('#') and not line.startswith('##') and not line.startswith('#@#'):
            return
        if any(m in line for m in EXT_COSMETIC):
            return
        if '#@#' in line or '##' in line:
            self._add_cosmetic(line)
            return
        # $badfilter: remember the rule it cancels (the same line without the badfilter option).
        body, opt_str = split_options(line)
        if opt_str is not None:
            opts = parse_opts(opt_str)
            if any(k == 'badfilter' for _, k, _ in opts):
                rest = [o for o in opts if o[1] != 'badfilter']
                self.badfilters.add(normalise(body, rest))
                return
        self.network_lines.append(line)

    def _add_cosmetic(self, line):
        if '#@#' in line:
            doms, sel = line.split('#@#', 1)
            sel = sel.strip()
            if BAD_SELECTOR.search(sel): return
            for d in doms.split(',') if doms else ['*']:
                d = d.strip().lower()
                if d and not d.startswith('~'): self.hide_except.add((d, sel))
            return
        doms, sel = line.split('##', 1)
        sel = sel.strip()
        if BAD_SELECTOR.search(sel) or sel.startswith('^'): return
        if not doms.strip():
            self.generic_hide.add(sel)
            return
        parts = [d.strip().lower() for d in doms.split(',') if d.strip()]
        pos = [d for d in parts if not d.startswith('~') and '*' not in d]
        neg = tuple(sorted(d[1:] for d in parts if d.startswith('~') and '*' not in d))
        had_pos = any(not d.startswith('~') for d in parts)
        if had_pos and not pos:
            return  # only wildcard domains (example.*): not supported
        if not pos and not neg:
            return
        self.specific_hide.setdefault((sel, neg), set()).update(pos)

    def _add_network(self, line):
        allow = line.startswith('@@')
        body, opt_str = split_options(line[2:] if allow else line)
        if '$' in body:
            return  # options we can't parse (e.g. $replace=/…/ or $redirect=…)
        if len(body) > 2 and body.startswith('/') and body.endswith('/'):
            return  # ABP regex rule: skipped (too slow to evaluate per request on a phone)
        opts = parse_opts(opt_str) if opt_str else []
        names = {k for _, k, _ in opts}
        if allow and names & PAGE_OPTS:
            self._add_page_exception(body, opts)
            return
        if any(k not in OK_OPTS for k in names):
            return
        if not body or body in ('*', '|', '||'):
            return
        host_only = HOST_ONLY_RE.match(body)
        has_types = bool(names & TYPE_OPTS)
        neg_of = {k: n for n, k, _ in opts}
        third_only = neg_of.get('third-party') is False or neg_of.get('first-party') is True
        first_only = neg_of.get('first-party') is False or neg_of.get('third-party') is True
        important = 'important' in names
        simple = host_only and 'domain' not in names and not has_types and not first_only and 'match-case' not in names
        if simple:
            h = host_only.group(1)
            if valid_host(h):
                if allow:
                    if not names:  # an unconditional exception; "@@||x^$third-party" stays a URL rule
                        self.allow_hosts.add(h)
                        return
                elif important and not third_only:
                    self.important_hosts.add(h)
                    return
                elif not important:
                    (self.block_hosts_3p if third_only else self.block_hosts).add(h)
                    return
        self.url_rules.append(('@@' if allow else '') + body + ('$' + opts_to_str(opts) if opts else ''))

    def _add_page_exception(self, body, opts):
        m = HOST_ONLY_RE.match(body)
        if not m or not valid_host(m.group(1)) or any(k == 'domain' for _, k, _ in opts):
            return
        site = m.group(1)
        names = {k for n, k, _ in opts if not n}
        if 'document' in names:
            self.document_allow.add(site)
        elif 'elemhide' in names:
            self.nohide[site] = 'all'
        elif 'generichide' in names and self.nohide.get(site) != 'all':
            self.nohide[site] = 'generic'

    def add_hosts_list(self, lines):
        n0 = len(self.block_hosts)
        for ln in lines:
            ln = ln.split('#', 1)[0].strip().lower()
            if not ln: continue
            parts = ln.split()
            h = parts[1] if len(parts) >= 2 and parts[0] in ('0.0.0.0', '127.0.0.1') else parts[0]
            if h.startswith('*.'): h = h[2:]
            h = h.strip('.')
            if h in ('localhost', 'localhost.localdomain', 'local', 'broadcasthost', '0.0.0.0'): continue
            if valid_host(h): self.block_hosts.add(h)
        return len(self.block_hosts) - n0

    # ------------------------------------------------------------ finish
    def finish(self):
        for line in self.network_lines:
            allow = line.startswith('@@')
            body, opt_str = split_options(line[2:] if allow else line)
            key = normalise(('@@' if allow else '') + body, parse_opts(opt_str) if opt_str else [])
            if key in self.badfilters:
                continue
            self._add_network(line)
        self.network_lines = []
        # Unconditional exceptions win over plain host blocks ($important blocks are kept).
        self.block_hosts -= self.allow_hosts
        self.block_hosts_3p -= self.allow_hosts
        self.block_hosts_3p -= self.block_hosts
        self.url_rules = sorted(set(self.url_rules))


def split_options(body):
    """(pattern, options or None) — the `$` must be followed by a well-formed option list."""
    dollar = body.rfind('$')
    if dollar > 0 and OPT_STR_RE.fullmatch(body[dollar + 1:]):
        return body[:dollar], body[dollar + 1:]
    return body, None


def normalise(body, opts):
    return (body, frozenset((n, k, v.lower()) for n, k, v in opts))


def parse_psl(lines):
    out = []
    for ln in lines:
        ln = ln.strip()
        if not ln or ln.startswith('//'): continue
        out.append(ln.split()[0].lower())
    return out


def write_hashes(path, hosts):
    hs = sorted(set(fnv1a64(h) for h in hosts))
    with open(path, 'wb') as f:
        f.write(struct.pack('>I', len(hs)))
        for v in hs: f.write(struct.pack('>Q', v))
    return len(hs)


def main(src, out):
    os.makedirs(out, exist_ok=True)

    def read(name):
        p = os.path.join(src, name)
        if not os.path.exists(p): return None
        with open(p, encoding='utf-8', errors='ignore') as f: return f.read().splitlines()

    b = Builder()
    sources = []
    for name, lic in [('stevenblack_hosts', 'MIT / CC BY 3.0 (StevenBlack/hosts)'), ('hagezi_pro.txt', 'GPL-3.0 (hagezi/dns-blocklists)')]:
        lines = read(name)
        if lines is None: continue
        sources.append({'file': name, 'licence': lic, 'new_hosts': b.add_hosts_list(lines)})
    for name in sorted(os.listdir(src)):
        if not name.startswith(('easylist', 'easyprivacy', 'adguard')) or not name.endswith('.txt'):
            continue
        n = 0
        for ln in read(name):
            b.add_line(ln, name); n += 1
        lic = 'GPL-3.0 (AdguardTeam/AdguardFilters)' if name.startswith('adguard') else 'GPL-3.0 / CC BY-SA 3.0 (easylist)'
        sources.append({'file': name, 'licence': lic, 'lines': n})
    b.finish()

    psl = read('public_suffix_list.dat')
    if psl is not None:
        rules = parse_psl(psl)
        with open(os.path.join(out, 'psl.txt'), 'w') as f: f.write('\n'.join(rules) + '\n')
        sources.append({'file': 'public_suffix_list.dat', 'licence': 'MPL-2.0 (publicsuffix.org)', 'rules': len(rules)})

    n_hosts = write_hashes(os.path.join(out, 'hosts.bin'), b.block_hosts)
    n_hosts3 = write_hashes(os.path.join(out, 'hosts_3p.bin'), b.block_hosts_3p)
    n_imp = write_hashes(os.path.join(out, 'hosts_important.bin'), b.important_hosts)

    def write_lines(name, lines):
        with open(os.path.join(out, name), 'w') as f: f.write('\n'.join(lines) + ('\n' if lines else ''))

    write_lines('allow_hosts.txt', sorted(b.allow_hosts))
    write_lines('document_allow.txt', sorted(b.document_allow))
    write_lines('url_rules.txt', b.url_rules)
    write_lines('cosmetic_generic.txt', sorted(b.generic_hide))
    write_lines('cosmetic_specific.txt', sorted(
        ','.join(sorted(pos) + ['~' + d for d in neg]) + '\t' + sel for (sel, neg), pos in b.specific_hide.items()))
    write_lines('cosmetic_except.txt', sorted(d + '\t' + sel for d, sel in b.hide_except))
    write_lines('cosmetic_nohide.txt', sorted(s + '\t' + v for s, v in b.nohide.items()))
    summary = {'built': time.strftime('%Y-%m-%d'), 'blocked_hosts': n_hosts, 'third_party_hosts': n_hosts3,
               'important_hosts': n_imp, 'allowed_hosts': len(b.allow_hosts), 'document_allowed_sites': len(b.document_allow),
               'url_rules': len(b.url_rules), 'badfilters': len(b.badfilters), 'generic_hide': len(b.generic_hide),
               'specific_hide': len(b.specific_hide), 'hide_exceptions': len(b.hide_except), 'nohide_sites': len(b.nohide),
               'sources': sources}
    with open(os.path.join(out, 'sources.json'), 'w') as f: json.dump(summary, f, indent=1)
    print(json.dumps({k: v for k, v in summary.items() if k != 'sources'}, indent=1))


if __name__ == '__main__':
    SRC = sys.argv[1] if len(sys.argv) > 1 else '.'
    OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'adblock')
    main(SRC, OUT)
