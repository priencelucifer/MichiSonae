# Simulator

The simulator produces deterministic, synthetic inputs for contracts, backend
load tests and Android/firmware replay. Synthetic data is visibly labeled and
must never be mixed into real road consensus.

Generate JSON Lines observations:

```text
python generate_road_observations.py --count 10 --seed 7
```
