#!/usr/bin/env python3
"""VA-C01 camera_list evidence evaluator.

Reads the JSONL produced by the debug camera trace (see
project-docs/VA_C01_CAMERA_CAPTURE_FORMAT.md) and reports, without internet access:

  1. how many cameras the server returns that are already behind the vehicle at the
     response origin (i.e. whether the server pre-filters direction);
  2. what the *current* app logic selects between two responses: the nearest
     non-OTHER camera, regardless of bearing;
  3. how often that selected camera is behind the vehicle while still inside the
     800 m alert gate (the exact VA-C01 symptom);
  4. per-camera pass profiles: closest approach, and whether it remained selected
     after the vehicle passed it.

The 90-degree split below is a *reporting* convention only. It is not a proposed
filter threshold and must not be copied into production code without road evidence.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Any, Iterable

SPEED_TYPES = {1, 5, 9, 26, 40, 41, 53, 54, 55, 58}
ALERT_RANGE_M = 800.0
BEHIND_DEG = 90.0

A = 6378245.0
EE = 0.00669342162296594323


def _out_of_china(lat: float, lng: float) -> bool:
    return not (72.004 <= lng <= 137.8347 and 0.8293 <= lat <= 55.8271)


def _transform_lat(x: float, y: float) -> float:
    ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def _transform_lng(x: float, y: float) -> float:
    ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lat: float, lng: float) -> tuple[float, float]:
    if _out_of_china(lat, lng):
        return lat, lng
    d_lat = _transform_lat(lng - 105.0, lat - 35.0)
    d_lng = _transform_lng(lng - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * math.pi
    magic = 1 - EE * math.sin(rad_lat) ** 2
    sqrt_magic = math.sqrt(magic)
    d_lat = (d_lat * 180.0) / ((A * (1 - EE)) / (magic * sqrt_magic) * math.pi)
    d_lng = (d_lng * 180.0) / (A / sqrt_magic * math.cos(rad_lat) * math.pi)
    return lat + d_lat, lng + d_lng


def haversine(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def bearing_deg(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lng2 - lng1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def angle_delta(a: float, b: float) -> float:
    d = abs((a - b + 180.0) % 360.0 - 180.0)
    return d


@dataclass
class Camera:
    key: str
    raw_type: int
    link_id: int
    offset: int
    speed_limit: int | None
    lat: float
    lng: float

    @property
    def is_speed(self) -> bool:
        return self.raw_type in SPEED_TYPES


@dataclass
class Frame:
    ts: int
    lat: float
    lng: float
    bearing: float | None
    speed_mps: float | None


@dataclass
class Response:
    ts: int
    endpoint: str
    origin_lat: float
    origin_lng: float
    bearing: float | None
    cameras: list[Camera] = field(default_factory=list)
    status: int | None = None

    def speed_cameras(self) -> list[Camera]:
        return [c for c in self.cameras if c.is_speed]


def parse_cameras(route: dict[str, Any]) -> list[Camera]:
    out: list[Camera] = []
    for c in route.get("camera_list") or []:
        pos = str(c.get("position") or "")
        if "," not in pos:
            continue
        try:
            lng_s, lat_s = pos.split(",", 1)
            lng, lat = float(lng_s), float(lat_s)
        except ValueError:
            continue
        raw_type = int(c.get("type") or 0)
        link_id = int(c.get("linkid") or 0)
        offset = int(c.get("offset") or 0)
        out.append(
            Camera(
                key=f"{raw_type}:{link_id}:{offset}",
                raw_type=raw_type,
                link_id=link_id,
                offset=offset,
                speed_limit=c.get("speed_limit"),
                lat=lat,
                lng=lng,
            )
        )
    return out


def load(path: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], int]:
    meta: list[dict[str, Any]] = []
    responses: list[dict[str, Any]] = []
    frames: list[dict[str, Any]] = []
    bad = 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            kind = obj.get("kind")
            if kind == "meta":
                meta.append(obj)
            elif kind == "camera_list":
                responses.append(obj)
            elif kind == "gps":
                frames.append(obj)
    return meta, responses, frames, bad


def build_responses(raw: Iterable[dict[str, Any]]) -> list[Response]:
    out: list[Response] = []
    for item in raw:
        req = item.get("req") or {}
        origin = str(req.get("origin") or "")
        if "," not in origin:
            continue
        try:
            lat_s, lng_s = origin.split(",", 1)
            lat, lng = float(lat_s), float(lng_s)
        except ValueError:
            continue
        routes = ((item.get("resp") or {}).get("routes") or [])
        cameras: list[Camera] = []
        for r in routes:
            cameras.extend(parse_cameras(r))
        out.append(
            Response(
                ts=int(item.get("ts") or 0),
                endpoint=str(item.get("endpoint") or ""),
                origin_lat=lat,
                origin_lng=lng,
                bearing=float(req["gps_direction"]) if req.get("gps_direction") is not None else None,
                cameras=cameras,
                status=(item.get("resp") or {}).get("status"),
            )
        )
    out.sort(key=lambda r: r.ts)
    return out


def build_frames(raw: Iterable[dict[str, Any]]) -> list[Frame]:
    out: list[Frame] = []
    for item in raw:
        if not item.get("has_fix"):
            continue
        try:
            lat = float(item.get("lat"))
            lng = float(item.get("lng"))
        except (TypeError, ValueError):
            continue
        out.append(
            Frame(
                ts=int(item.get("ts") or 0),
                lat=lat,
                lng=lng,
                bearing=float(item["bearing"]) if item.get("has_bearing") else None,
                speed_mps=float(item["speed_mps"]) if item.get("has_speed") else None,
            )
        )
    out.sort(key=lambda f: f.ts)
    return out


def nearest_speed_camera(response: Response, lat: float, lng: float) -> tuple[Camera | None, float]:
    best: Camera | None = None
    best_d = float("inf")
    for cam in response.speed_cameras():
        d = haversine(lat, lng, cam.lat, cam.lng)
        if d < best_d:
            best, best_d = cam, d
    return best, best_d


def evaluate(responses: list[Response], frames: list[Frame]) -> dict[str, Any]:
    # --- server-side direction mix at the response origin -------------------------
    server = Counter()
    server_distances: dict[str, list[float]] = defaultdict(list)
    for resp in responses:
        if resp.bearing is None:
            continue
        rad = wgs84_to_gcj02(resp.origin_lat, resp.origin_lng)
        for cam in resp.cameras:
            d = haversine(rad[0], rad[1], cam.lat, cam.lng)
            brg = bearing_deg(rad[0], rad[1], cam.lat, cam.lng)
            delta = angle_delta(brg, resp.bearing)
            bucket = "ahead<=60" if delta <= 60 else ("side60-90" if delta <= 90 else "behind>90")
            server[bucket] += 1
            server_distances[bucket].append(d)

    # --- replay the app's selection between responses -----------------------------
    frame_idx = 0
    selected_total = 0
    selected_behind = 0
    selected_behind_within_gate = 0
    gate_total = 0
    behind_examples: list[dict[str, Any]] = []
    pass_profile: dict[str, dict[str, Any]] = {}

    for i, resp in enumerate(responses):
        next_ts = responses[i + 1].ts if i + 1 < len(responses) else resp.ts + 60_000
        while frame_idx < len(frames) and frames[frame_idx].ts <= resp.ts:
            frame_idx += 1
        j = frame_idx
        while j < len(frames) and frames[j].ts <= next_ts:
            frame = frames[j]
            j += 1
            if frame.bearing is None:
                continue
            g_lat, g_lng = wgs84_to_gcj02(frame.lat, frame.lng)
            cam, dist = nearest_speed_camera(resp, g_lat, g_lng)
            if cam is None:
                continue
            brg = bearing_deg(g_lat, g_lng, cam.lat, cam.lng)
            delta = angle_delta(brg, frame.bearing)
            selected_total += 1
            if dist <= ALERT_RANGE_M:
                gate_total += 1
            profile = pass_profile.setdefault(
                cam.key,
                {
                    "type": cam.raw_type,
                    "linkid": cam.link_id,
                    "offset": cam.offset,
                    "speed_limit": cam.speed_limit,
                    "min_distance_m": dist,
                    "frames": 0,
                    "behind_frames": 0,
                },
            )
            profile["frames"] += 1
            profile["min_distance_m"] = min(profile["min_distance_m"], dist)
            if delta > BEHIND_DEG:
                selected_behind += 1
                profile["behind_frames"] += 1
                if dist <= ALERT_RANGE_M:
                    selected_behind_within_gate += 1
                    if len(behind_examples) < 20:
                        behind_examples.append(
                            {
                                "ts": frame.ts,
                                "camera": cam.key,
                                "distance_m": round(dist, 1),
                                "bearing_delta_deg": round(delta, 1),
                                "speed_kmh": round((frame.speed_mps or 0) * 3.6, 1),
                            }
                        )

    def dist_stats(values: list[float]) -> dict[str, float]:
        if not values:
            return {}
        values = sorted(values)
        n = len(values)
        return {
            "count": n,
            "min": round(values[0], 1),
            "median": round(values[n // 2], 1),
            "max": round(values[-1], 1),
        }

    return {
        "responses": len(responses),
        "gps_frames": len(frames),
        "server_direction_mix": dict(server),
        "server_distance_by_bucket": {k: dist_stats(v) for k, v in server_distances.items()},
        "selection": {
            "frames_with_speed_camera": selected_total,
            "frames_within_800m": gate_total,
            "selected_behind": selected_behind,
            "selected_behind_within_800m": selected_behind_within_gate,
            "selected_behind_ratio": round(selected_behind / selected_total, 4) if selected_total else None,
        },
        "pass_profiles": sorted(pass_profile.values(), key=lambda p: -p["frames"]),
        "behind_examples": behind_examples,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Evaluate Velora VA-C01 camera trace JSONL")
    ap.add_argument("path", help="camera-trace.jsonl")
    ap.add_argument("--json", dest="json_out", help="write the summary as JSON")
    args = ap.parse_args()

    meta, raw_responses, raw_frames, bad = load(args.path)
    responses = build_responses(raw_responses)
    frames = build_frames(raw_frames)
    summary = evaluate(responses, frames)
    summary["file"] = {
        "path": args.path,
        "meta": meta[:3],
        "bad_lines": bad,
    }

    print("=== Velora camera trace ===")
    for m in meta[:3]:
        print(f"app={m.get('app')} device={m.get('device')} android={m.get('android')} sdk={m.get('sdk')}")
    print(f"responses={len(responses)} gps_frames={len(frames)} bad_lines={bad}")
    print()
    print("server direction mix at response origin (reporting split, not a filter rule):")
    for k, v in summary["server_direction_mix"].items():
        print(f"  {k}: {v}  {summary['server_distance_by_bucket'].get(k, {})}")
    print()
    sel = summary["selection"]
    print("current app selection (nearest non-OTHER, distance only):")
    print(f"  frames with a speed camera: {sel['frames_with_speed_camera']}")
    print(f"  within 800 m gate:          {sel['frames_within_800m']}")
    print(f"  selected camera behind:     {sel['selected_behind']}")
    print(f"  behind AND within 800 m:    {sel['selected_behind_within_800m']}  (VA-C01 symptom)")
    if sel["selected_behind_ratio"] is not None:
        print(f"  behind ratio:               {sel['selected_behind_ratio']:.1%}")
    print()
    print("pass profiles (top 10 by frames):")
    for p in summary["pass_profiles"][:10]:
        print(
            f"  {p['type']}:{p['linkid']}:{p['offset']} limit={p['speed_limit']} "
            f"frames={p['frames']} behind={p['behind_frames']} min_d={p['min_distance_m']:.1f}m"
        )
    if summary["behind_examples"]:
        print()
        print("behind examples (first 5):")
        for e in summary["behind_examples"][:5]:
            print(f"  ts={e['ts']} cam={e['camera']} d={e['distance_m']}m delta={e['bearing_delta_deg']}°")
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2)
        print(f"\nJSON written: {args.json_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
