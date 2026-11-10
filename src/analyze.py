"""
Analysis pipeline for Paper 2 (DESIGN.md sections 5-6). Reads the per-run
JSON grid from results/, computes latency + SLA metrics per run, aggregates
across the 5 seeds with 95% bootstrap CIs, and runs paired Wilcoxon tests
targeting the paper's actual claims (P6 vs each baseline; P6 vs the oracle).

Unfinished-at-horizon requests are CENSORED: counted as SLA violations, and
their latency set to (horizon - emit) as a conservative lower bound so tail
metrics reflect rather than drop the worst cases.

SLA/SLO (defined plainly for the manuscript): each request gets a deadline
(Service-Level Objective) proportional to its unloaded service time on a
strong edge node -- SLO = net_floor + K * (net_floor + MI / strong_edge_mips)
seconds, a bounded-slowdown criterion standard in inference serving. A request
"violates its SLA" if it finishes later than that deadline (or never
finishes). MI depends on generated tokens, but is used only to SCORE after
the fact, never for dispatch.

Usage:
    python src/analyze.py --trace conv --topo T2 --table main
    python src/analyze.py --trace conv --topo T2 --sig 1.0    # Wilcoxon at load 1.0
"""

import argparse
import glob
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
from scipy.stats import wilcoxon

RESULTS = Path(__file__).resolve().parent.parent / "results"

STRONG_MIPS = 10000.0
NET_FLOOR = 0.05
K_SLO = 5.0

POLICY_NAMES = {
    "P1": "Cloud-only", "P2": "Edge-nearest", "P3": "Edge-least-loaded",
    "P4": "Random", "P5": "Size-threshold", "P6NS": "Hybrid (no gate, abl.)",
    "P6": "Hybrid (P6, proposed)", "P7": "Gen-oracle (ref.)",
    "P8": "Segregating-oracle (RQ3 abl.)",
}
POLICY_ORDER = ["P1", "P2", "P3", "P4", "P5", "P6NS", "P6", "P7", "P8"]


def parse_name(path):
    # run_<trace>_<window>_load<load>_<topo>_<policy>[_<calibtag>]_seed<seed>.json
    # calibtag (calibration-sensitivity ablation only) is one of a0.5/a2.0/b0.5/b2.0.
    stem = Path(path).stem[len("run_"):]
    parts = stem.split("_")
    seed_tok = parts[-1]
    calib = None
    if len(parts) == 7:
        trace, window, load_tok, topo, policy, calib, _ = parts
    elif len(parts) == 6:
        trace, window, load_tok, topo, policy, _ = parts
    else:
        raise ValueError(f"unrecognized result filename: {path}")
    return dict(trace=trace, window=window, load=load_tok[len("load"):],
                topo=topo, policy=policy, calib=calib, seed=int(seed_tok[len("seed"):]))


def per_request(path):
    d = json.load(open(path))
    reqs = d["requests"]
    emit = np.array([r["emit"] for r in reqs], float)
    done = np.array([r["done"] for r in reqs], float)
    mi = np.array([r["mi"] for r in reqs], float)
    horizon = max(emit.max(), done.max()) if len(reqs) else 0.0
    fin = done >= 0
    lat = np.where(fin, done - emit, horizon - emit)
    deadline = NET_FLOOR + K_SLO * (NET_FLOOR + mi / STRONG_MIPS)
    viol = (~fin) | (lat > deadline)
    return dict(cfg=d["config"], meta=parse_name(path), lat=lat, fin=fin, viol=viol,
                abs_slack=lat - deadline)


def run_metrics(pr):
    lat, viol, fin = pr["lat"], pr["viol"], pr["fin"]
    return {
        "completion_rate": float(fin.mean()) * 100,
        "sla_violation_pct": float(viol.mean()) * 100,
        "p50": float(np.percentile(lat, 50)),
        "p95": float(np.percentile(lat, 95)),
        "p99": float(np.percentile(lat, 99)),
        "mean_lat": float(lat.mean()),
    }


def boot_ci(vals, n=2000, seed=0):
    rng = np.random.default_rng(seed)
    vals = np.array(vals, float)
    means = [rng.choice(vals, len(vals), replace=True).mean() for _ in range(n)]
    return float(vals.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def load_grid(trace, topo):
    """Main/topo/code grid only -- excludes calibration-perturbed runs (calib
    tag set), which share the same (trace,window,load,topo,policy,seed) key
    space and would otherwise silently mix into the unperturbed results."""
    grid = defaultdict(list)  # (load, policy) -> [per_request dicts]
    for p in glob.glob(str(RESULTS / f"run_{trace}_*_{topo}_*.json")):
        m = parse_name(p)
        if m["topo"] != topo or m["calib"] is not None:
            continue
        pr = per_request(p)
        grid[(m["load"], m["policy"])].append(pr)
    return grid


CALIB_POINTS = [("baseline", None), ("a0.5", "a0.5"), ("a2.0", "a2.0"),
                ("b0.5", "b0.5"), ("b2.0", "b2.0")]
CALIB_POLICIES = ["P1", "P3", "P5", "P6", "P7"]


def calibration_table(trace="conv", topo="T2", load="2.0", window="eval1"):
    """Reproduces manuscript Table 8 exactly: SLA-violation % under each
    calibration perturbation, single window, 5 seeds, for the policies
    carrying the headline claim."""
    print(f"\n=== {trace}/{topo}/load {load}x, window {window}: calibration-sensitivity "
          f"(SLA violation %) ===")
    hdr = f"{'calibration':12}" + "".join(f"{p:>10}" for p in CALIB_POLICIES)
    print(hdr)
    for label, tag in CALIB_POINTS:
        row = f"{label:12}"
        for pol in CALIB_POLICIES:
            if tag is None:
                fs = glob.glob(str(RESULTS / f"run_{trace}_{window}_load{load}_{topo}_{pol}_seed*.json"))
                fs = [f for f in fs if parse_name(f)["calib"] is None]
            else:
                fs = glob.glob(str(RESULTS / f"run_{trace}_{window}_load{load}_{topo}_{pol}_{tag}_seed*.json"))
            vals = [run_metrics(per_request(f))["sla_violation_pct"] for f in fs]
            row += f"{np.mean(vals):>10.1f}" if vals else f"{'--':>10}"
        print(row)


def table(trace, topo):
    grid = load_grid(trace, topo)
    loads = sorted({k[0] for k in grid}, key=float)
    print(f"\n=== {trace} / {topo} : SLA violation % (mean [95% CI], n=seeds x windows) ===")
    hdr = f"{'policy':24}" + "".join(f"{l+'x':>18}" for l in loads)
    print(hdr)
    for pol in POLICY_ORDER:
        row = f"{POLICY_NAMES.get(pol, pol):24}"
        for load in loads:
            runs = grid.get((load, pol), [])
            if not runs:
                row += f"{'--':>18}"; continue
            vals = [run_metrics(r)["sla_violation_pct"] for r in runs]
            m, lo, hi = boot_ci(vals)
            row += f"{f'{m:.1f} [{lo:.1f},{hi:.1f}]':>18}"
        print(row)

    print(f"\n=== {trace} / {topo} : p95 latency (s), mean over seeds x windows ===")
    print(f"{'policy':24}" + "".join(f"{l+'x':>12}" for l in loads))
    for pol in POLICY_ORDER:
        row = f"{POLICY_NAMES.get(pol, pol):24}"
        for load in loads:
            runs = grid.get((load, pol), [])
            if not runs:
                row += f"{'--':>12}"; continue
            vals = [run_metrics(r)["p95"] for r in runs]
            row += f"{np.mean(vals):>12.1f}"
        print(row)


def significance(trace, topo, load):
    """Paired Wilcoxon on per-request absolute slack (latency - deadline),
    pooling matched runs across seeds x windows, P6 vs each other policy."""
    grid = load_grid(trace, topo)
    p6_runs = {(_key(r)): r for r in grid.get((load, "P6"), [])}
    print(f"\n=== {trace} / {topo} / load {load}x : paired Wilcoxon, P6 vs each policy ===")
    print(f"(negative median slack diff => P6 has lower per-request slack, i.e. better)")
    for pol in POLICY_ORDER:
        if pol == "P6":
            continue
        other = {(_key(r)): r for r in grid.get((load, pol), [])}
        keys = sorted(set(p6_runs) & set(other))
        if not keys:
            continue
        d6 = np.concatenate([p6_runs[k]["abs_slack"] for k in keys])
        do = np.concatenate([other[k]["abs_slack"] for k in keys])
        n = min(len(d6), len(do))
        diff = d6[:n] - do[:n]
        nz = diff[diff != 0]
        if len(nz) < 10:
            print(f"  P6 vs {POLICY_NAMES.get(pol,pol):24} n/a (too few)"); continue
        stat, p = wilcoxon(nz)
        med = float(np.median(diff))
        better = "P6 better" if med < 0 else "P6 worse"
        print(f"  P6 vs {POLICY_NAMES.get(pol,pol):24} median_slack_diff={med:8.2f}s  "
              f"p={p:.2e}  {better}  (n={n})")


def run_level_sig(trace, topo, load):
    """Paired Wilcoxon on per-RUN SLA-violation % across matched seed x window
    runs (P6 vs each policy). This targets the headline aggregate claim
    directly, without the median-dilution that per-request slack suffers when
    most requests are comfortably within deadline."""
    grid = load_grid(trace, topo)
    p6 = {_key(r): run_metrics(r)["sla_violation_pct"] for r in grid.get((load, "P6"), [])}
    print(f"\n=== {trace}/{topo}/load {load}x : run-level paired Wilcoxon on SLA-violation % "
          f"(n={len(p6)} runs) ===")
    for pol in POLICY_ORDER:
        if pol == "P6":
            continue
        other = {_key(r): run_metrics(r)["sla_violation_pct"] for r in grid.get((load, pol), [])}
        keys = sorted(set(p6) & set(other))
        if len(keys) < 6:
            continue
        d6 = np.array([p6[k] for k in keys])
        do = np.array([other[k] for k in keys])
        diff = d6 - do
        if np.all(diff == 0):
            print(f"  P6 vs {POLICY_NAMES.get(pol,pol):24} identical"); continue
        stat, p = wilcoxon(diff)
        verdict = "P6 better" if diff.mean() < 0 else "P6 worse"
        print(f"  P6 vs {POLICY_NAMES.get(pol,pol):24} mean_diff={diff.mean():7.1f}pp  "
              f"p={p:.2e}  {verdict}")


def _key(pr):
    m = pr["meta"]
    return (m["seed"], m["window"])


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", default="conv")
    ap.add_argument("--topo", default="T2")
    ap.add_argument("--table", action="store_true")
    ap.add_argument("--sig", default=None, help="load level for significance test, e.g. 2.0")
    ap.add_argument("--calib", action="store_true", help="reproduce manuscript Table 8")
    args = ap.parse_args()
    if args.calib:
        calibration_table(args.trace, args.topo)
    elif args.sig:
        significance(args.trace, args.topo, args.sig)
        run_level_sig(args.trace, args.topo, args.sig)
    else:
        table(args.trace, args.topo)
