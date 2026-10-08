"""Finds a minimal set of server rules that still derives a given equality (rulegate's --novelty).

When e2e.py finds a variant that no single rule explains, the culprit is a chain. This disables rules
in halves (ddmin-style, greedy): whatever can stay disabled while the equality is still DERIVABLE was
not needed. What is left is the chain to look at.

Usage: python minimize.py "<lhs> => <rhs>" [path to egraph-server binary]
"""
import os
import re
import subprocess
import sys
import tempfile

SRC = os.path.join(os.path.dirname(__file__), "..", "..", "..", "egraph-server", "src", "sql_rewrite.rs")
BIN = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(SRC), "..", "target", "release",
                                                          "egraph-server")


def rules():
    names = []
    for line in open(SRC, encoding="utf-8"):
        m = re.match(r'\s*rewrite!\("([^"]+)"', line)
        if m:
            names.append(m.group(1))
    return names


def derivable(equation, disabled):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write("t ; %s\n" % equation)
        path = f.name
    env = dict(os.environ, EGRAPH_DISABLE_RULES=",".join(disabled))
    out = subprocess.run([BIN, "--novelty", path], capture_output=True, text=True, env=env).stdout
    os.unlink(path)
    return out.startswith("DERIVABLE")


def main():
    equation = sys.argv[1]
    all_rules = rules()
    if not derivable(equation, []):
        print("not derivable with every rule enabled")
        return
    needed = list(all_rules)
    chunk = len(needed) // 2
    while chunk >= 1:
        i = 0
        while i < len(needed):
            trial = needed[:i] + needed[i + chunk:]
            disabled = [r for r in all_rules if r not in trial]
            if derivable(equation, disabled):
                needed = trial
            else:
                i += chunk
        chunk //= 2
    print("minimal chain (%d rules):" % len(needed))
    for r in needed:
        print("  " + r)


if __name__ == "__main__":
    main()
