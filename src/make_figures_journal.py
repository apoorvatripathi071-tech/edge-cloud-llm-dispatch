"""
Journal-submission variant of make_figures.py for the Cluster Computing
submission: identical plots, but with the "Figure N. ..." caption text
removed from inside the image (Springer requires captions to live in the
manuscript text only, not baked into the graphic). Writes to separate
*_journal.png files -- does not touch the original figures used by the
main manuscript.

Usage:
    python src/make_figures_journal.py
"""

from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import json
import glob

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
RESULTS = ROOT / "results"
FIG_DIR = RESULTS / "figures"
FIG_DIR.mkdir(parents=True, exist_ok=True)

STRONG = 10000.0
NET = 0.05
K = 5.0


def token_distributions():
    conv = pd.read_csv(DATA / "prepared" / "conv_eval1_load1.0_seed42" / "site0.csv")
    for i in range(1, 4):
        conv = pd.concat([conv, pd.read_csv(DATA / "prepared" / "conv_eval1_load1.0_seed42" / f"site{i}.csv")])
    code = pd.read_csv(DATA / "prepared" / "code_eval1_load1.0_seed42" / "site0.csv")
    for i in range(1, 4):
        code = pd.concat([code, pd.read_csv(DATA / "prepared" / "code_eval1_load1.0_seed42" / f"site{i}.csv")])

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    for ax, col, title in [(axes[0], "ctx", "Context (input) tokens"),
                           (axes[1], "gen", "Generated (output) tokens")]:
        for df, label, color in [(conv, "Conversational", "tab:blue"), (code, "Code-completion", "tab:orange")]:
            vals = np.sort(df[col].to_numpy())
            ccdf = 1.0 - np.arange(1, len(vals) + 1) / len(vals)
            ax.loglog(vals, np.maximum(ccdf, 1e-5), label=label, color=color, lw=1.6)
        ax.set_xlabel(title)
        ax.set_ylabel("P(X > x)")
        # Per-subplot axis title only -- not a "Figure N." caption, so this
        # stays (it's equivalent to an axis label, not a figure caption).
        ax.set_title(title)
        ax.grid(alpha=0.3, which="both")
        ax.legend()
    # No fig.suptitle() here: the "Figure 1 ..." caption belongs in the
    # manuscript text only, per journal formatting rules.
    fig.tight_layout()
    fig.savefig(FIG_DIR / "token_distributions_journal.png", dpi=150)
    plt.close(fig)
    print("Wrote token_distributions_journal.png")


def sla_violation_curve():
    def score(f):
        d = json.load(open(f))
        reqs = d["requests"]
        emit = np.array([r["emit"] for r in reqs], float)
        done = np.array([r["done"] for r in reqs], float)
        mi = np.array([r["mi"] for r in reqs], float)
        horizon = max(emit.max(), done.max())
        fin = done >= 0
        lat = np.where(fin, done - emit, horizon - emit)
        dl = NET + K * (NET + mi / STRONG)
        return float(((~fin) | (lat > dl)).mean() * 100)

    policies = ["P1", "P3", "P5", "P6", "P7"]
    labels = {"P1": "Cloud-only", "P3": "Edge-least-loaded", "P5": "Size-threshold",
              "P6": "Hybrid (proposed)", "P7": "Gen-oracle (ref.)"}
    styles = {"P1": ("tab:gray", "--"), "P3": ("tab:red", "--"), "P5": ("tab:orange", "-."),
              "P6": ("tab:blue", "-"), "P7": ("tab:green", ":")}
    loads = ["0.5", "1.0", "2.0"]

    fig, ax = plt.subplots(figsize=(6.5, 4.8))
    for pol in policies:
        means = []
        for load in loads:
            fs = glob.glob(str(RESULTS / f"run_conv_eval*_load{load}_T2_{pol}_seed*.json"))
            fs = [f for f in fs if not any(t in f for t in ["a0.5", "a2.0", "b0.5", "b2.0"])]
            vals = [score(f) for f in fs]
            means.append(np.mean(vals))
        color, ls = styles[pol]
        lw = 2.6 if pol == "P6" else 1.6
        ax.plot([0.5, 1.0, 2.0], means, marker="o", label=labels[pol], color=color, linestyle=ls, linewidth=lw)
    ax.set_xlabel("Offered load (multiplier of 2.8 req/s base rate; aggregate T2 utilization)")
    ax.set_ylabel("SLA-violation rate (%)")
    # No "Figure 2 ..." title baked into the axes -- caption lives in the
    # manuscript text only, per journal formatting rules.
    ax.set_xticks([0.5, 1.0, 2.0])
    ax.set_xticklabels(["0.5x\n(~19%)", "1.0x\n(~37%)", "2.0x\n(~74%)"])
    ax.grid(alpha=0.3)
    ax.legend(loc="upper left", fontsize=9)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "sla_violation_curve_journal.png", dpi=150)
    plt.close(fig)
    print("Wrote sla_violation_curve_journal.png")


if __name__ == "__main__":
    token_distributions()
    sla_violation_curve()
