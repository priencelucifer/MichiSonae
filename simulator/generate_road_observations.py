from __future__ import annotations

import argparse
import json
import random
import sys
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid5


NAMESPACE = UUID("34ba1885-45d7-48c0-8a87-41763dbbea1c")


def observation(index: int, rng: random.Random) -> dict[str, object]:
    base_time = datetime(2026, 1, 1, tzinfo=UTC)
    return {
        "event_id": str(uuid5(NAMESPACE, f"synthetic-{index}")),
        "installation_id": "synthetic-simulator",
        "detected_at": (base_time + timedelta(seconds=index * 3)).isoformat(),
        "latitude": 26.1445 + rng.uniform(-0.01, 0.01),
        "longitude": 91.7362 + rng.uniform(-0.01, 0.01),
        "location_accuracy_m": round(rng.uniform(3, 15), 2),
        "speed_mps": round(rng.uniform(2, 22), 2),
        "kind": rng.choice(["road_damage", "rough_road"]),
        "severity": round(rng.uniform(0.25, 0.95), 3),
        "confidence": round(rng.uniform(0.5, 0.95), 3),
        "source": "phone",
        "detector_version": "synthetic-v1",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument("--seed", type=int, default=1)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.count < 1 or args.count > 100_000:
        print("--count must be between 1 and 100000", file=sys.stderr)
        return 2

    rng = random.Random(args.seed)
    for index in range(args.count):
        print(json.dumps(observation(index, rng), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
