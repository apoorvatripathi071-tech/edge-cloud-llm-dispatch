"""
Trace preprocessing for Paper 2 (DESIGN.md section 2).

Reads the raw Azure LLM inference trace (conv or code), selects a tuning
window and disjoint, strictly-later evaluation windows (chronological
discipline: tuning data always precedes evaluation data), applies load
scaling, assigns requests to edge sites per seed, and writes:

  data/prepared/<trace>_<window>_<load>_seed<k>/site<i>.csv   (arrival_offset_sec,ctx,gen)
  data/prepared/<trace>_<window>_<load>_seed<k>/manifest.json

CAPACITY-RELATIVE LOAD LEVELS (calibration decision 2026-07-10, logged in
DESIGN.md): the raw Azure trace is a full inference CLUSTER's load (~47
req/s), which trivially saturates a small edge topology. Placing that entire
load on the edge is unrealistic and makes every edge policy fail equally
(uninteresting). Instead we scale the arrival RATE to the T2 topology's
serving capacity while preserving the trace's real arrival PATTERN, defining
three load levels as fractions of total (edge+cloud) T2 capacity:
  0.5x -> ~35% offered load (edge alone nearly suffices)
  1x   -> ~70% offered load (edge alone CANNOT cope; cloud offload required
          -- the regime where placement decisions matter most)
  2x   -> ~140% offered load (overloaded; graceful-degradation regime)
Rate scaling is done by seeded per-request Bernoulli thinning (keep each
request with probability p = target_rate / window_rate), which preserves the
arrival process's burstiness/autocorrelation in expectation rather than
distorting timestamps. Load levels are ABSOLUTE req/s (tuned to T2), so the
topology-scale ablation (T1/T3) meaningfully shows smaller/larger edge
capacity under identical offered load.

Usage:
    python src/prepare_workload.py --trace conv --n-sites 4 --seeds 42 43 44 45 46
"""

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
PREPARED_DIR = DATA_DIR / "prepared"

TRACE_FILES = {
    "conv": DATA_DIR / "AzureLLMInferenceTrace_conv_1week.csv",
    "code": DATA_DIR / "AzureLLMInferenceTrace_code_1week.csv",
}

# Windows (hours from trace start). Tuning strictly precedes evaluation.
# Evaluation windows are 30 min each, drawn from three different days for
# temporal robustness (calibration decision 2026-07-10, DESIGN.md): 2-hour
# windows let per-node queues under overload grow deep enough that iFogSim's
# time-shared scheduler (O(queued^2) per tick) becomes intractable, and a
# request queued for >30 min is not operationally meaningful anyway (a real
# system would time it out). 30-min windows keep queues bounded, cut runtime,
# and still yield tens of thousands of requests per window. Unfinished-at-
# horizon requests are treated as SLA violations in analysis, not dropped.
TUNING_WINDOW_H = (0.0, 0.5)
EVAL_WINDOWS_H = {"eval1": (24.0, 24.5), "eval2": (72.0, 72.5), "eval3": (120.0, 120.5)}

# Absolute target aggregate arrival rates (req/s) at MATCHED T2-capacity
# utilization (18.6% / 37.1% / 74.2%), derived per trace from that trace's
# own mean per-request compute demand (external-review fix, 2026-07-10: an
# earlier revision used the SAME absolute req/s target for both traces,
# which meant the code trace -- whose mean request costs ~35% of conv's,
# since its outputs are far shorter -- was offered a much LOWER fraction of
# T2's capacity at the nominally "same" load label. That confounded "flatter
# output-length tail" with "lighter total offered load" in the cross-trace
# ablation, DESIGN.md's external-review response documents the fix.)
# T2 total capacity = 280,000 MIPS (120,000 edge + 160,000 cloud).
LOAD_TARGET_REQ_PER_SEC = {
    "conv": {0.5: 1.4, 1.0: 2.8, 2.0: 5.6},
    "code": {0.5: 4.021, 1.0: 8.043, 2.0: 16.085},
}


def load_trace(trace: str) -> pd.DataFrame:
    df = pd.read_csv(TRACE_FILES[trace])
    df["TIMESTAMP"] = pd.to_datetime(df["TIMESTAMP"], format="ISO8601")
    t0 = df["TIMESTAMP"].min()
    df["offset_sec"] = (df["TIMESTAMP"] - t0).dt.total_seconds()
    return df[["offset_sec", "ContextTokens", "GeneratedTokens"]]


def slice_window(df: pd.DataFrame, start_h: float, end_h: float) -> pd.DataFrame:
    w = df[(df["offset_sec"] >= start_h * 3600) & (df["offset_sec"] < end_h * 3600)].copy()
    w["offset_sec"] -= start_h * 3600
    return w.reset_index(drop=True)


def scale_load(w: pd.DataFrame, load: float, span_sec: float, rng: np.random.Generator, trace: str) -> pd.DataFrame:
    """Bernoulli-thin the window to the target aggregate rate for this load
    level, preserving arrival-pattern burstiness (module docstring)."""
    target_rate = LOAD_TARGET_REQ_PER_SEC[trace][load]
    window_rate = len(w) / span_sec
    p = min(1.0, target_rate / window_rate)
    keep = rng.random(len(w)) < p
    return w[keep].reset_index(drop=True)


def prepare(trace: str, n_sites: int, seeds: list[int], loads: list[float]):
    df = load_trace(trace)
    windows = {"tuning": TUNING_WINDOW_H, **EVAL_WINDOWS_H}
    for wname, (s, e) in windows.items():
        base = slice_window(df, s, e)
        span = (e - s) * 3600
        for load in loads:  # tuning window emitted at all loads too, so theta
                            # can be tuned for robustness across the load range
            for seed in seeds:
                # independent RNG streams: thinning vs site assignment
                thin_rng = np.random.default_rng(seed * 100003 + 1)
                w = scale_load(base, load, span, thin_rng, trace)
                rng = np.random.default_rng(seed)
                sites = rng.integers(0, n_sites, len(w))
                # n_sites=4 (T2, the main topology) keeps the original,
                # already-consumed directory naming for backward compatibility
                # with the completed main/code grids. Other site counts (T1=2,
                # T3=8 in the topology ablation) get a distinct _ns<k> suffix
                # so each topology draws its FULL offered load distributed
                # across its OWN real gateway count -- not a subsample of a
                # 4-way split (which would silently halve T1's volume and
                # leave T3 missing files, DESIGN.md section 9b addendum).
                suffix = "" if n_sites == 4 else f"_ns{n_sites}"
                out_dir = PREPARED_DIR / f"{trace}_{wname}_load{load}_seed{seed}{suffix}"
                out_dir.mkdir(parents=True, exist_ok=True)
                for i in range(n_sites):
                    sub = w[sites == i]
                    sub.to_csv(out_dir / f"site{i}.csv", index=False,
                               header=["arrival_offset_sec", "ctx", "gen"])
                manifest = {
                    "trace": trace, "window": wname, "window_hours": [s, e],
                    "load": load, "seed": seed, "n_sites": n_sites,
                    "n_requests": int(len(w)), "span_sec": span,
                    "per_site_counts": {f"site{i}": int((sites == i).sum()) for i in range(n_sites)},
                }
                with open(out_dir / "manifest.json", "w") as f:
                    json.dump(manifest, f, indent=2)
                print(f"{out_dir.name}: {len(w)} requests, sites {manifest['per_site_counts']}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--trace", choices=["conv", "code"], required=True)
    p.add_argument("--n-sites", type=int, default=4)
    p.add_argument("--seeds", type=int, nargs="+", default=[42, 43, 44, 45, 46])
    p.add_argument("--loads", type=float, nargs="+", default=[0.5, 1.0, 2.0])
    args = p.parse_args()
    prepare(args.trace, args.n_sites, args.seeds, args.loads)
