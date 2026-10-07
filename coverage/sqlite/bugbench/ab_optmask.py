"""Summarises optimizer-mask A/B runs: throughput, plan divergence per layer, and every finding file.

Usage: python3 ab_optmask.py <run dir> [<run dir> ...]
"""
import os
import re
import sys


def count(path, pattern):
    if not os.path.exists(path):
        return 0
    with open(path, encoding="utf-8", errors="replace") as f:
        return sum(1 for line in f if re.search(pattern, line))


def checks(run):
    # Checks that ran their original query: the trace's original-exec done lines.
    return count(os.path.join(run, "egraph-trace.log"), r"original-exec done")


def plan_layers(run):
    """{layer: (one-plan checks, multi-plan checks)} from the workload hints report."""
    layers = {}
    path = os.path.join(run, "workload-hints.txt")
    if not os.path.exists(path):
        return layers
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*# (\S+) / (\d+) plans?\b.*?(\d+)\s*$", line)
        if not m:
            continue
        layer, plans, n = m.group(1), int(m.group(2)), int(m.group(3))
        one, multi = layers.get(layer, (0, 0))
        layers[layer] = (one + n, multi) if plans == 1 else (one, multi + n)
    return layers


def mask_summary(run):
    path = os.path.join(run, "workload-hints.txt")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8", errors="replace"):
            if "optMask mode=" in line:
                return line.strip()
    return "-"


def main(runs):
    for run in runs:
        name = os.path.basename(run.rstrip("/"))
        print("== %s" % name)
        print("   checks=%d  %s" % (checks(run), mask_summary(run)))
        print("   findings: sse=%d referee=%d knownBugs=%d optmask=%d variantOnly=%d asserts=%d" % (
            count(os.path.join(run, "single-side-empty-reproducers.sql"), r"EGRAPH_SINGLE_SIDE_EMPTY_BEGIN"),
            count(os.path.join(run, "referee.sql"), r"^-- EGRAPH_REFEREE"),
            count(os.path.join(run, "known-bugs.sql"), r"^-- EGRAPH_KNOWN_BUG"),
            count(os.path.join(run, "optmask-findings.log"), r"^-- EGRAPH_OPTMASK"),
            count(os.path.join(run, "variant-only-errors.log"), r"^-- EGRAPH_VARIANT_ONLY_ERROR"),
            count(os.path.join(run, "sqlancer.out.log"), r"^java\.lang\.AssertionError")))
        for layer, (one, multi) in sorted(plan_layers(run).items()):
            total = one + multi
            print("   %-32s multi-plan %5d / %6d = %5.1f%%" % (layer, multi, total, 100.0 * multi / max(total, 1)))


if __name__ == "__main__":
    main(sys.argv[1:])
