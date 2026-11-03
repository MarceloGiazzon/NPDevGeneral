"""Compare benchmark runs across arms (BENCH-3). Stdlib only.

`run_bench.py compare <run-dir> [<run-dir> ...]` reads the runs (each one arm: npdev, npdev-no-knowledge,
direct-python, direct-node, direct-spring) and writes comparison.json and comparison.html: per tier and per arm
the pass rate, the median / p90 / mean tokens per PASSING app, tokens per passing probe, the input / output split,
and the wall time, with a bootstrap 95 % interval over prompts for the two headline numbers.

The point is a claim a user or an AI provider can check, so the report is built to be honest about itself:
  * Only prompts that EVERY compared arm attempted (and finished: an infrastructure failure such as quota is not
    a model failure) are counted, unless --all-prompts is passed -- an arm must not look better because it was
    asked the easy half of the prompts.
  * Input and output tokens are reported separately; every token counts, the knowledge cards included.
  * The raw run folders are listed next to every number.
  * When NPDev loses a column, the report says so (the "reading" lines are computed, not written by hand).
"""
from __future__ import annotations

import html
import json
import random
import statistics
from pathlib import Path


def load_run(run_dir: Path) -> dict:
    run_dir = Path(run_dir)
    summary = {}
    if (run_dir / "summary.json").exists():
        summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    results = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(run_dir.glob("bench-*/result.json"))]
    arm = summary.get("arm") or next((r.get("arm") for r in results if r.get("arm")), None)
    if not arm:   # runs recorded before the arm existed: the ablation says which NPDev arm it was
        arm = "npdev-no-knowledge" if summary.get("ablation") == "no-knowledge" else "npdev"
    return {"dir": str(run_dir), "arm": arm, "summary": summary, "results": results}


def outcome(r: dict) -> str:
    return r.get("outcome") or ("pass" if r.get("passed") else "fail")


def probes_passed(r: dict) -> int:
    """Passing probes of the final (passing) attempt, from the acceptance stage's own counts."""
    for attempt in reversed(r.get("attempts") or []):
        for stage in reversed(attempt.get("stages") or []):
            if stage.get("stage") == "acceptance" and "probeCounts" in stage:
                return int(stage["probeCounts"].get("passed", 0))
    return 0


def _pct(sorted_values: list[float], q: float) -> float | None:
    if not sorted_values:
        return None
    index = min(len(sorted_values) - 1, max(0, round(q * (len(sorted_values) - 1))))
    return sorted_values[index]


def _bootstrap(values: list, stat, n: int = 1000, seed: int = 20261103) -> list | None:
    """95 % percentile interval of `stat` over resampled prompts. None with fewer than 3 prompts: an interval
    from two numbers would be decoration."""
    if len(values) < 3:
        return None
    rng = random.Random(seed)
    draws = []
    for _ in range(n):
        sample = [values[rng.randrange(len(values))] for _ in values]
        value = stat(sample)
        if value is not None:
            draws.append(value)
    if not draws:
        return None
    draws.sort()
    return [round(draws[int(0.025 * (len(draws) - 1))], 4), round(draws[int(0.975 * (len(draws) - 1))], 4)]


def _median_tokens(rows: list[dict]) -> float | None:
    totals = sorted(r["tokens"]["total"] for r in rows if outcome(r) == "pass")
    return statistics.median(totals) if totals else None


def _pass_rate(rows: list[dict]) -> float | None:
    done = [r for r in rows if outcome(r) != "infra"]
    return sum(1 for r in done if outcome(r) == "pass") / len(done) if done else None


def cell(rows: list[dict]) -> dict:
    """The numbers for one (tier, arm)."""
    done = [r for r in rows if outcome(r) != "infra"]
    passed = [r for r in done if outcome(r) == "pass"]
    totals = sorted(r["tokens"]["total"] for r in passed)
    probes = sum(probes_passed(r) for r in passed)
    evolved = [r for r in passed if r.get("changes") is not None]          # BENCH-4: only prompts whose changes were run
    changes = [c for r in evolved for c in r["changes"]]
    declared = sum(r.get("changesDeclared", len(r["changes"])) for r in evolved)
    change_tokens = sorted(c["tokens"]["total"] for c in changes if c["passed"])
    return {
        "changesDeclared": declared,
        "changePassRate": round(sum(1 for c in changes if c["passed"]) / declared, 4) if declared else None,
        "medianTokensPerChange": statistics.median(change_tokens) if change_tokens else None,
        "changeRegressedProbes": sum(len(c.get("regressions") or []) for c in changes),
        "prompts": len(rows), "completed": len(done), "passed": len(passed),
        "passRate": round(len(passed) / len(done), 4) if done else None,
        "passRateInterval95": _bootstrap(done, _pass_rate),
        "firstAttemptPassRate": round(sum(1 for r in done if r.get("firstAttemptPassed")) / len(done), 4) if done else None,
        "medianTokensPerPassedApp": statistics.median(totals) if totals else None,
        "medianTokensInterval95": _bootstrap(passed, _median_tokens),
        "p90TokensPerPassedApp": _pct(totals, 0.9),
        "meanTokensPerPassedApp": round(statistics.mean(totals), 1) if totals else None,
        "inputTokensPerPassedApp": round(statistics.mean(r["tokens"]["prompt"] for r in passed), 1) if passed else None,
        "outputTokensPerPassedApp": round(statistics.mean(r["tokens"]["completion"] for r in passed), 1) if passed else None,
        "tokensPerPassedProbe": round(sum(r["tokens"]["total"] for r in passed) / probes, 1) if probes else None,
        "meanLoopsPassed": round(statistics.mean(r["loopsUsed"] for r in passed), 2) if passed else None,
        "meanWallSecondsPerPassedApp": round(statistics.mean(r["wallSeconds"] for r in passed), 1) if passed else None,
        "totalTokens": sum(r["tokens"]["total"] for r in rows),
        "totalCostUsd": round(sum(r.get("costUsd", 0) for r in rows), 6),
    }


def compare(runs: list[dict], common_only: bool = True) -> dict:
    by_arm: dict[str, dict[str, dict]] = {}     # arm -> prompt id -> result (the newest run wins)
    sources: dict[str, list[str]] = {}
    for run in runs:
        sources.setdefault(run["arm"], []).append(run["dir"])
        for r in run["results"]:
            by_arm.setdefault(run["arm"], {})[r["promptId"]] = r
    arms = sorted(by_arm, key=lambda a: (not a.startswith("npdev"), a))
    ids = [set(by_arm[a]) for a in arms]
    common = set.intersection(*ids) if ids else set()
    # a prompt counts only if every arm FINISHED it
    finished = {pid for pid in common if all(outcome(by_arm[a][pid]) != "infra" for a in arms)}
    chosen = finished if common_only else set().union(*ids)
    tiers = sorted({r.get("tier") or "?" for a in arms for r in by_arm[a].values()})
    table: dict[str, dict[str, dict]] = {}
    for tier in tiers + ["all"]:
        table[tier] = {}
        for arm in arms:
            rows = [r for pid, r in by_arm[arm].items() if pid in chosen and (tier == "all" or (r.get("tier") or "?") == tier)]
            table[tier][arm] = cell(rows)
    return {"schema": "npdev-bench-comparison.v1", "arms": arms, "tiers": tiers, "commonPromptsOnly": common_only,
            "promptsCompared": len(chosen), "promptsDroppedForNotBeingFinishedByEveryArm": len(set().union(*ids) - chosen) if ids else 0,
            "table": table, "runs": sources, "reading": reading(table, arms)}


def _fmt(value, suffix: str = "") -> str:
    return "–" if value is None else (f"{value:,.0f}{suffix}" if isinstance(value, (int, float)) and abs(value) >= 100
                                      else f"{value}{suffix}")


def reading(table: dict, arms: list[str]) -> list[str]:
    """Plain sentences computed from the numbers, including the ones where NPDev loses."""
    lines: list[str] = []
    overall = table.get("all") or {}
    npdev = overall.get("npdev")
    if not npdev:
        return ["No npdev arm in this comparison: nothing to compare against."]
    for arm in arms:
        if arm == "npdev":
            continue
        other = overall.get(arm) or {}
        rate_a, rate_b = npdev.get("passRate"), other.get("passRate")
        med_a, med_b = npdev.get("medianTokensPerPassedApp"), other.get("medianTokensPerPassedApp")
        if rate_a is not None and rate_b is not None:
            verdict = "higher" if rate_a > rate_b else "lower" if rate_a < rate_b else "the same"
            lines.append(f"Success: npdev reached a passing app for {rate_a:.0%} of prompts vs {rate_b:.0%} for {arm} "
                         f"({verdict} for npdev).")
        if med_a is not None and med_b is not None:
            verdict = "fewer" if med_a < med_b else "more" if med_a > med_b else "the same number of"
            lines.append(f"Cost per working app: npdev median {med_a:,.0f} tokens vs {med_b:,.0f} for {arm} "
                         f"({verdict} for npdev).")
        elif med_a is not None and med_b is None:
            lines.append(f"{arm} produced no passing app, so there is no cost per working app to compare.")
        elif med_a is None and med_b is not None:
            lines.append(f"npdev produced no passing app, so there is no cost per working app to compare against {arm}.")
    return lines


def render_html(c: dict) -> str:
    e = html.escape
    cols = [("passRate", "pass rate"), ("firstAttemptPassRate", "first-try rate"), ("medianTokensPerPassedApp", "median tokens / app"),
            ("p90TokensPerPassedApp", "p90 tokens / app"), ("inputTokensPerPassedApp", "input / app"),
            ("outputTokensPerPassedApp", "output / app"), ("tokensPerPassedProbe", "tokens / probe"),
            ("meanLoopsPassed", "loops"), ("meanWallSecondsPerPassedApp", "wall s / app"),
            ("changePassRate", "change pass rate"), ("medianTokensPerChange", "median tokens / change")]
    out = ["<!doctype html><meta charset=utf-8><title>NPDev benchmark comparison</title>",
           "<style>body{font:15px system-ui,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#1a1a1a}"
           "table{border-collapse:collapse;width:100%;margin:12px 0 28px}th,td{border:1px solid #ccc;padding:6px 8px;text-align:right}"
           "th:first-child,td:first-child{text-align:left}th{background:#f3f4f6}small{color:#555}</style>",
           "<h1>NPDev benchmark comparison</h1>",
           f"<p>{c['promptsCompared']} prompts compared"
           + (" (only prompts every arm finished)" if c["commonPromptsOnly"] else " (all prompts each arm attempted)")
           + ". Input and output tokens are counted separately and include NPDev's knowledge cards. "
             "No paid API spend: free providers only.</p>"]
    out.append("<h2>Reading</h2><ul>" + "".join(f"<li>{e(line)}</li>" for line in c["reading"]) + "</ul>")
    for tier in ["all"] + c["tiers"]:
        out.append(f"<h2>{'All tiers' if tier == 'all' else e(tier)}</h2><table><tr><th>arm</th><th>prompts</th><th>passed</th>"
                   + "".join(f"<th>{e(label)}</th>" for _, label in cols) + "</tr>")
        for arm in c["arms"]:
            row = c["table"][tier][arm]
            cells = []
            for key, _ in cols:
                value = row.get(key)
                text = f"{value:.0%}" if key in ("passRate", "firstAttemptPassRate", "changePassRate") and value is not None else _fmt(value)
                interval = row.get({"passRate": "passRateInterval95", "medianTokensPerPassedApp": "medianTokensInterval95"}.get(key, ""))
                if interval:
                    text += (f" <small>[{interval[0]:.0%}–{interval[1]:.0%}]</small>" if key == "passRate"
                             else f" <small>[{interval[0]:,.0f}–{interval[1]:,.0f}]</small>")
                cells.append(f"<td>{text}</td>")
            out.append(f"<tr><td>{e(arm)}</td><td>{row['completed']}</td><td>{row['passed']}</td>{''.join(cells)}</tr>")
        out.append("</table>")
    out.append("<h2>Raw runs</h2><ul>" + "".join(f"<li>{e(arm)}: {e(', '.join(dirs))}</li>" for arm, dirs in c["runs"].items()) + "</ul>")
    out.append("<p><small>Intervals are 95 % bootstrap intervals over prompts (shown only with at least 3 prompts). "
               "A tier with few prompts is a pilot, not a claim.</small></p>")
    return "\n".join(out)


def write(runs: list[dict], out_dir: Path, common_only: bool = True) -> dict:
    result = compare(runs, common_only)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "comparison.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    (out_dir / "comparison.html").write_text(render_html(result), encoding="utf-8")
    return result
