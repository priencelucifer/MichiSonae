import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:8000";
const ingestLatency = new Trend("michi_ingest_duration", true);
const readLatency = new Trend("michi_read_duration", true);
const acceptedObservations = new Counter("michi_accepted_observations");
const duplicateObservations = new Counter("michi_duplicate_observations");

export const options = {
  scenarios: {
    alpha_ingest: {
      executor: "constant-vus",
      exec: "alphaIngest",
      vus: 2,
      duration: "15s",
      gracefulStop: "5s",
    },
    public_reads: {
      executor: "constant-vus",
      exec: "publicRead",
      vus: 2,
      duration: "15s",
      gracefulStop: "5s",
    },
    commute_spike: {
      executor: "ramping-arrival-rate",
      exec: "commuteSpike",
      startTime: "15s",
      startRate: 2,
      timeUnit: "1s",
      preAllocatedVUs: 5,
      maxVUs: 20,
      stages: [
        { target: 10, duration: "10s" },
        { target: 2, duration: "10s" },
      ],
      gracefulStop: "5s",
    },
    duplicate_retry: {
      executor: "shared-iterations",
      exec: "duplicateRetry",
      startTime: "35s",
      vus: 2,
      iterations: 10,
      maxDuration: "15s",
    },
  },
  thresholds: {
    checks: ["rate>0.99"],
    http_req_failed: ["rate<0.01"],
    michi_ingest_duration: ["p(95)<500", "p(99)<1000"],
    michi_read_duration: ["p(95)<250", "p(99)<500"],
    dropped_iterations: ["count==0"],
  },
};

const jsonHeaders = { headers: { "Content-Type": "application/json" } };

function uuid() {
  const hex = "0123456789abcdef";
  let value = "";
  for (let index = 0; index < 32; index += 1) {
    value += hex[Math.floor(Math.random() * hex.length)];
  }
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-4${value.slice(13, 16)}-` +
    `${(8 + Math.floor(Math.random() * 4)).toString(16)}${value.slice(17, 20)}-` +
    value.slice(20);
}

export function setup() {
  const response = http.post(
    `${baseUrl}/v1/installations:register`,
    JSON.stringify({ schema_version: "1.0" }),
    jsonHeaders,
  );
  const valid = check(response, {
    "load identity registered": (result) => result.status === 201,
  });
  if (!valid) {
    throw new Error(`registration failed: ${response.status} ${response.body}`);
  }
  const body = response.json();
  return {
    installationId: body.installation_id,
    accessToken: body.access_token,
  };
}

function observationBatch(installationId, eventId) {
  return {
    schema_version: "1.0",
    observations: [
      {
        event_id: eventId,
        installation_id: installationId,
        detected_at: new Date().toISOString(),
        latitude: 26.1445 + (Math.random() - 0.5) * 0.002,
        longitude: 91.7362 + (Math.random() - 0.5) * 0.002,
        location_accuracy_m: 8.0,
        speed_mps: 11.0,
        kind: Math.random() > 0.5 ? "road_damage" : "rough_road",
        severity: 0.65,
        confidence: 0.80,
        source: "phone",
        detector_version: "k6-alpha-v1",
      },
    ],
  };
}

function ingestBatch(data, batch) {
  const response = http.post(
    `${baseUrl}/v1/observations:batch`,
    JSON.stringify(batch),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${data.accessToken}`,
      },
      tags: { endpoint: "ingest" },
    },
  );
  ingestLatency.add(response.timings.duration);
  const valid = check(response, {
    "ingest durably accepted": (result) => result.status === 202,
  });
  if (valid) {
    const body = response.json();
    acceptedObservations.add(body.stored_count || 0);
    duplicateObservations.add(body.duplicate_count || 0);
  }
  return response;
}

export function alphaIngest(data) {
  ingestBatch(data, observationBatch(data.installationId, uuid()));
  sleep(0.2);
}

export function commuteSpike(data) {
  ingestBatch(data, observationBatch(data.installationId, uuid()));
}

export function duplicateRetry(data) {
  const eventId = uuid();
  const batch = observationBatch(data.installationId, eventId);
  const first = ingestBatch(data, batch);
  const second = ingestBatch(data, batch);
  check(second, {
    "retry is classified as duplicate": (result) =>
      first.status === 202 &&
      result.status === 202 &&
      result.json("duplicate_count") === 1,
  });
}

export function publicRead() {
  const response = http.get(
    `${baseUrl}/v1/regions/gh5:wh9hx/hazards`,
    { tags: { endpoint: "regional-read" } },
  );
  readLatency.add(response.timings.duration);
  check(response, {
    "regional snapshot read succeeds": (result) => result.status === 200,
    "regional response is cacheable": (result) => Boolean(result.headers["Cache-Control"]),
  });
  sleep(0.1);
}

export function handleSummary(data) {
  return {
    "/results/backend-load-summary.json": JSON.stringify(data, null, 2),
    stdout: `MichiSonae load gate complete: ${JSON.stringify(data.metrics.checks.values)}\n`,
  };
}
