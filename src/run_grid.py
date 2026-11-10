"""
Parallel experiment-grid runner for Paper 2 (DESIGN.md section 7). Runs every
(trace, window, load, topology, policy, seed) configuration through the Java
harness, one JSON per run, skipping any output that already exists (resumable).

Constants are frozen from tuning (per trace): mean-gen and the P6 gate theta
come from hybrid_theta_<trace>.json. Nothing here reads evaluation data to set
a parameter.

Usage:
    python src/run_grid.py --set main      # conv x 3 windows x 3 loads x T2 x 8 policies x 5 seeds
    python src/run_grid.py --set topo      # conv x 3 windows x 1x x {T1,T3} x 8 policies x 5 seeds
    python src/run_grid.py --set code      # code x 3 windows x 3 loads x T2 x 8 policies x 5 seeds
    python src/run_grid.py --set oracle_abl  # conv+code x 3 windows x 3 loads x T2 x [P8] x 5 seeds
    python src/run_grid.py --set all
"""

import argparse
import json
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
IFOG = ROOT / "iFogSim"
JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe"
CP = "bin;jars/*;jars/commons-math3-3.5/*"

WINDOWS = ["eval1", "eval2", "eval3"]
POLICIES = ["P1", "P2", "P3", "P4", "P5", "P6", "P6NS", "P7"]
SEEDS = [42, 43, 44, 45, 46]

# Calibration-sensitivity ablation (DESIGN.md section 7): does the P6-wins
# story survive if alpha (prefill MI/token) or beta (decode MI/token) are
# off by 2x in either direction? theta is held FROZEN at the value tuned
# under the DEFAULT calibration -- this deliberately tests robustness to
# calibration uncertainty, not a re-tuned-per-scenario best case.
CALIB_POINTS = [("a0.5", 0.5, 1.0), ("a2.0", 2.0, 1.0),
                ("b0.5", 1.0, 0.5), ("b2.0", 1.0, 2.0)]
CALIB_POLICIES = ["P1", "P3", "P5", "P6", "P7"]  # the policies that carry the headline claim

MEAN_GEN = {"conv": 112.99, "code": 26.59}

# Site count each topology's full offered load is distributed across
# (DESIGN.md section 9b addendum: T1/T3 need their OWN site-count prepared
# data, not a subsample/overflow of T2's 4-site split).
TOPO_NSITES = {"T1": 2, "T2": 4, "T3": 8}


def theta_for(trace):
    return int(json.load(open(RESULTS / f"hybrid_theta_{trace}.json"))["theta"])


def configs(which):
    trace_sets = {
        "main": [("conv", ["0.5", "1.0", "2.0"], ["T2"], POLICIES)],
        "topo": [("conv", ["1.0"], ["T1", "T3"], POLICIES)],
        "code": [("code", ["0.5", "1.0", "2.0"], ["T2"], POLICIES)],
        "oracle_abl": [("conv", ["0.5", "1.0", "2.0"], ["T2"], ["P8"]),
                       ("code", ["0.5", "1.0", "2.0"], ["T2"], ["P8"])],
        # Two-factor decomposition of P6-vs-P8 (external-review fix): P9/P10
        # isolate whether the gate input or the score input (or both) drives
        # P8's difference from P6, at the same frozen theta.
        "factorial": [("conv", ["0.5", "1.0", "2.0"], ["T2"], ["P9", "P10"]),
                      ("code", ["0.5", "1.0", "2.0"], ["T2"], ["P9", "P10"])],
    }
    sets = []
    if which == "all":
        for v in trace_sets.values():
            sets += v
    else:
        sets = trace_sets[which]
    for trace, loads, topos, policies in sets:
        th = theta_for(trace)
        mg = MEAN_GEN[trace]
        for window in WINDOWS:
            for load in loads:
                for topo in topos:
                    for policy in policies:
                        for seed in SEEDS:
                            yield dict(trace=trace, window=window, load=load, topo=topo,
                                       policy=policy, seed=seed, theta=th, mean_gen=mg)


def run_one(c):
    tag = c.get("calib_tag", "")
    out = RESULTS / (f"run_{c['trace']}_{c['window']}_load{c['load']}_"
                     f"{c['topo']}_{c['policy']}{tag}_seed{c['seed']}.json")
    if out.exists():
        return f"skip {out.name}"
    ns = TOPO_NSITES[c["topo"]]
    suffix = "" if ns == 4 else f"_ns{ns}"
    prepared = f"../data/prepared/{c['trace']}_{c['window']}_load{c['load']}_seed{c['seed']}{suffix}"
    cmd = [JAVA, "-Xmx2g", "-cp", CP, "org.paper2.harness.ExperimentRunner",
           "--prepared", prepared, "--topology", c["topo"], "--policy", c["policy"],
           "--seed", str(c["seed"]), "--mean-gen", str(c["mean_gen"]), "--out", str(out)]
    if c["policy"] in ("P6", "P8", "P9", "P10"):
        cmd += ["--theta", str(c["theta"])]
    if "alpha_scale" in c:
        cmd += ["--alpha-scale", str(c["alpha_scale"]), "--beta-scale", str(c["beta_scale"])]
    try:
        r = subprocess.run(cmd, cwd=IFOG, stdout=subprocess.DEVNULL,
                           stderr=subprocess.PIPE, text=True, timeout=180)
    except subprocess.TimeoutExpired:
        return f"TIMEOUT {out.name}: exceeded 180s (likely deep-queue scheduler blowup at this load)"
    if r.returncode != 0 or not out.exists():
        return f"FAIL {out.name}: {r.stderr[-200:] if r.stderr else 'no output'}"
    return f"ok   {out.name}"


def calib_configs():
    trace = "conv"
    th = theta_for(trace)  # FROZEN: tuned under default calibration, not re-tuned per point
    mg = MEAN_GEN[trace]
    for tag, a_scale, b_scale in CALIB_POINTS:
        for load in ["1.0", "2.0"]:
            for policy in CALIB_POLICIES:
                for seed in SEEDS:
                    yield dict(trace=trace, window="eval1", load=load, topo="T2",
                               policy=policy, seed=seed, theta=th, mean_gen=mg,
                               alpha_scale=a_scale, beta_scale=b_scale, calib_tag=f"_{tag}")


def main(which, workers):
    cfgs = list(calib_configs()) if which == "calib" else list(configs(which))
    print(f"[{which}] {len(cfgs)} configs, {workers} workers")
    done = fail = skip = timeout = 0
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(run_one, c): c for c in cfgs}
        for i, fut in enumerate(as_completed(futs), 1):
            msg = fut.result()
            if msg.startswith("TIMEOUT"):
                timeout += 1; print(f"  [{i}/{len(cfgs)}] {msg}")
            elif msg.startswith("FAIL"):
                fail += 1; print(f"  [{i}/{len(cfgs)}] {msg}")
            elif msg.startswith("skip"):
                skip += 1
            else:
                done += 1
            if i % 50 == 0:
                print(f"  ...{i}/{len(cfgs)} (ok={done} skip={skip} fail={fail} timeout={timeout})")
    print(f"[{which}] complete: ran={done} skipped={skip} failed={fail} timeout={timeout}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="main",
                    choices=["main", "topo", "code", "oracle_abl", "calib", "factorial", "all"])
    ap.add_argument("--workers", type=int, default=10)
    args = ap.parse_args()
    main(args.set, args.workers)
