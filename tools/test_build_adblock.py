#!/usr/bin/env python3
"""Unit tests for build_adblock.py:  python3 tools/test_build_adblock.py"""
import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_adblock import Builder, parse_psl  # noqa: E402


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
        self.assertEqual({'example.com'}, b.specific_hide[('.ad', ('sub.example.com',))])
        self.assertEqual(set(), b.specific_hide[('.banner', ('skip.example',))])
        self.assertIn('.global', b.generic_hide)

    def test_hosts_list(self):
        b = Builder()
        b.add_hosts_list(['0.0.0.0 ads.example.com', '*.wild.example', 'localhost', '# c'])
        self.assertEqual({'ads.example.com', 'wild.example'}, b.block_hosts)

    def test_psl(self):
        self.assertEqual(['com', '*.ck', '!www.ck'], parse_psl(['// x', 'com', '', '*.ck', '!www.ck']))


if __name__ == '__main__':
    unittest.main()
