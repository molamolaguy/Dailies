#!/usr/bin/env python3
"""Unit tests for build_adblock.py:  python3 tools/test_build_adblock.py"""
import io, os, struct, sys, tempfile, unittest
from contextlib import redirect_stderr, redirect_stdout
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_adblock import Builder, fnv1a64, main, parse_psl, to_i64, write_hashes  # noqa: E402


def build(*lines):
    b = Builder()
    for ln in lines: b.add_line(ln)
    b.finish()
    return b


class BuilderTest(unittest.TestCase):
    def test_plain_host_rules(self):
        b = build('||ads.example.com^', '||track.example.net^$third-party', '||x.example.org^$3p')
        self.assertIn('ads.example.com', b.block_hosts)
        self.assertEqual({'track.example.net', 'x.example.org'}, b.block_hosts_3p)

    def test_trailing_dot_stays_url_rule(self):
        b = build('||ads.', '||.adserver.^')
        self.assertFalse(b.block_hosts)
        self.assertEqual(['||.adserver.^', '||ads.'], b.url_rules)

    def test_important_hosts(self):
        b = build('||bad.example^$important')
        self.assertEqual({'bad.example'}, b.important_hosts)
        self.assertNotIn('bad.example', b.block_hosts)

    def test_badfilter_cancels_rule(self):
        b = build('||ads.example.com^', '||ads.example.com^$badfilter', '/banner/*$script,3p', '/banner/*$script,third-party,badfilter')
        self.assertNotIn('ads.example.com', b.block_hosts)
        self.assertEqual([], b.url_rules)

    def test_conditional_exception_is_not_global(self):
        b = build('@@||cdn.example.com^$third-party', '@@||ok.example.com^')
        self.assertEqual({'ok.example.com'}, b.allow_hosts)
        self.assertEqual(['@@||cdn.example.com^$third-party'], b.url_rules)

    def test_page_exceptions(self):
        b = build('@@||game.example^$document', '@@||a.example^$generichide', '@@||b.example^$elemhide', '@@||c.example^$ghide')
        self.assertEqual({'game.example'}, b.document_allow)
        self.assertEqual({'a.example': 'generic', 'b.example': 'all', 'c.example': 'generic'}, b.nohide)

    def test_aliases_canonicalised(self):
        b = build('/ad.js$xhr,css,frame,beacon', '/px$1p')
        self.assertEqual(['/ad.js$xmlhttprequest,stylesheet,subdocument,ping', '/px$first-party'], b.url_rules)

    def test_unknown_options_dropped(self):
        b = build('/x$redirect=noop.js', '/y$csp=script-src', '/z$popup')
        self.assertEqual([], b.url_rules)

    def test_cosmetic_negations(self):
        b = build('example.com,~sub.example.com##.ad', '~skip.example##.banner', '##.global')
        self.assertEqual({'example.com'}, b.specific_hide[('.ad', ('sub.example.com',), True)])
        self.assertEqual(set(), b.specific_hide[('.banner', ('skip.example',), False)])
        self.assertIn('.global', b.generic_hide)

    def test_cosmetic_exclusion_only_does_not_collide_with_positive_rule(self):
        for lines in [
            ('~foo.com##.ad', 'bar.com,~foo.com##.ad'),
            ('bar.com,~foo.com##.ad', '~foo.com##.ad'),
        ]:
            b = build(*lines)
            self.assertEqual(set(), b.specific_hide[('.ad', ('foo.com',), False)])
            self.assertEqual({'bar.com'}, b.specific_hide[('.ad', ('foo.com',), True)])

        with tempfile.TemporaryDirectory() as td:
            src = os.path.join(td, 'src')
            out = os.path.join(td, 'out')
            os.makedirs(src)
            with open(os.path.join(src, 'easylist_test.txt'), 'w') as f:
                f.write('~foo.com##.ad\nbar.com,~foo.com##.ad\n')
            with redirect_stdout(io.StringIO()):
                main(src, out)
            with open(os.path.join(out, 'cosmetic_specific.txt')) as f:
                emitted = f.read().splitlines()
            self.assertEqual(['bar.com,~foo.com\t.ad', '~foo.com\t.ad'], emitted)

    def test_write_hashes_sorted_signed_int64(self):
        hosts = ['a.example.com', 'b.example.com', 'c.example.com', 'd.example.com', 'doubleclick.net', 'tracker.io']
        signed_expected = sorted(to_i64(fnv1a64(h)) for h in hosts)
        self.assertTrue(any(v < 0 for v in signed_expected), 'expected at least one negative signed int64 hash')
        self.assertTrue(any(v >= 0 for v in signed_expected), 'expected at least one non-negative signed int64 hash')
        with tempfile.TemporaryDirectory() as td:
            p = os.path.join(td, 'hosts.bin')
            n = write_hashes(p, hosts)
            self.assertEqual(len(hosts), n)
            with open(p, 'rb') as f:
                data = f.read()
            (count,) = struct.unpack_from('>I', data, 0)
            self.assertEqual(len(hosts), count)
            vals = list(struct.unpack_from(f'>{count}q', data, 4))
            self.assertEqual(signed_expected, vals)

    def test_main_fails_nonzero_when_zero_rules_loaded(self):
        with tempfile.TemporaryDirectory() as td:
            empty_src = os.path.join(td, 'empty_src')
            out1 = os.path.join(td, 'out1')
            os.makedirs(empty_src)
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as cm1:
                main(empty_src, out1)
            self.assertNotEqual(0, cm1.exception.code)
            self.assertFalse(os.path.exists(out1))

            comment_src = os.path.join(td, 'comment_src')
            out2 = os.path.join(td, 'out2')
            os.makedirs(comment_src)
            with open(os.path.join(comment_src, 'easylist_empty.txt'), 'w') as f:
                f.write('! comment only\n\n')
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as cm2:
                main(comment_src, out2)
            self.assertNotEqual(0, cm2.exception.code)
            self.assertFalse(os.path.exists(out2))

    def test_hosts_list(self):
        b = Builder()
        b.add_hosts_list(['0.0.0.0 ads.example.com', '*.wild.example', 'localhost', '# c'])
        self.assertEqual({'ads.example.com', 'wild.example'}, b.block_hosts)

    def test_psl(self):
        self.assertEqual(['com', '*.ck', '!www.ck'], parse_psl(['// x', 'com', '', '*.ck', '!www.ck']))


if __name__ == '__main__':
    unittest.main()
