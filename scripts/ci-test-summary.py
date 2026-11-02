#!/usr/bin/env python3
"""Prints failing tests (name, message, first stack lines) from JUnit XML files found under the given paths."""
import glob, sys
import xml.etree.ElementTree as ET

def main(roots):
    files = []
    for r in roots:
        files += glob.glob(r, recursive=True)
    total = failed = 0
    for f in sorted(set(files)):
        try:
            suite = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            total += 1
            bad = case.find("failure") if case.find("failure") is not None else case.find("error")
            if bad is not None:
                failed += 1
                print("FAILED %s.%s" % (case.get("classname"), case.get("name")))
                text = (bad.get("message") or "") + "\n" + (bad.text or "")
                print("\n".join("    " + l for l in text.strip().splitlines()[:14]))
    print("tests: %d, failed: %d" % (total, failed))

if __name__ == "__main__":
    main(sys.argv[1:])
