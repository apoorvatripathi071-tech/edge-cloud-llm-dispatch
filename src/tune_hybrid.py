"""
Tunes the P6 hybrid's size-gate threshold theta ON THE TUNING WINDOW ONLY
(chronological discipline: evaluation windows never inform theta). Sweeps
theta over a grid, runs P6 on the tuning window at all load levels, and
selects the theta minimizing MEAN SLA-violation across loads (robust to the
operating point, not tuned to any single evaluation load).

Writes results/hybrid_theta.json = {"theta": <chosen>, "grid": {...}} which
the grid runner reads so every P6 evaluation run uses the tuning-selected
theta.

Usage:
    python src/tune_hybrid.py --mean-gen 112.99 --seed 42
"""

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
IFOG = ROOT / "iFogSim"
JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe"
CP = "bin;jars/*;jars/commons-math3-3.5/*"

STRONG_MIPS = 10000.0
NET_FLOOR = 0.05
K_SLO = 5.0
THETA_GRID = [10000, 14000, 18000, 22000, 30000, 36000, 38000, 40000, 42000, 45000, 50000, 60000]
LOADS = [0.5, 1.0, 2.0]


def run_p6(prepared, theta, mean_gen, out):
    subprocess.run([JAVA, "-Xmx4g", "-cp", CP, "org.paper2.harness.ExperimentRunner",
                    "--prepared", prepared, "--topology", "T2", "--policy", "P6",
                    "--theta", str(theta), "--seed", "42", "--mean-gen", str(mean_gen),
                    "--out", out], cwd=IFOG, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, check=True)


def sla_violation(path):
    d = json.load(open(path))
    reqs = d["requests"]
    emit = np.array([r["emit"] for r in reqs], float)
    done = np.array([r["done"] for r in reqs], float)
    mi = np.array([r["mi"] for r in reqs], float)
    horizon = max(emit.max(), done.max())
    fin = done >= 0
    lat = np.where(fin, done - emit, horizon - emit)
    deadline = NET_FLOOR + K_SLO * (NET_FLOOR + mi / STRONG_MIPS)
    return float(((~fin) | (lat > deadline)).mean() * 100)


def main(trace, mean_gen, seed):
    grid = {}
    for theta in THETA_GRID:
        viols = []
        for load in LOADS:
            prepared = f"../data/prepared/{trace}_tuning_load{load}_seed{seed}"
            out = str(RESULTS / f"_tune_{trace}_theta{theta}_load{load}.json")
            run_p6(prepared, theta, mean_gen, out)
            viols.append(sla_violation(out))
        grid[theta] = {"per_load": dict(zip(map(str, LOADS), viols)),
                       "mean": float(np.mean(viols)), "worst": float(np.max(viols))}
        print(f"theta={theta:6d}  per-load={['%.1f' % v for v in viols]}  mean={np.mean(viols):.2f}")

    chosen = min(grid, key=lambda t: grid[t]["mean"])
    print(f"\n[{trace}] Chosen theta (min mean SLA violation on tuning window): {chosen}")
    out = {"trace": trace, "theta": chosen,
           "criterion": "min mean SLA-violation across loads on tuning window",
           "grid": grid, "mean_gen_used": mean_gen}
    with open(RESULTS / f"hybrid_theta_{trace}.json", "w") as f:
        json.dump(out, f, indent=2)
    for f in RESULTS.glob(f"_tune_{trace}_theta*.json"):
        f.unlink()
    print(f"Wrote {RESULTS / f'hybrid_theta_{trace}.json'}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", default="conv")
    ap.add_argument("--mean-gen", type=float, required=True)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    main(args.trace, args.mean_gen, args.seed)
