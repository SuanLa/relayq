import exec from "k6/execution";
import http from "k6/http";
import { check } from "k6";

const baseUrls = (__ENV.BASE_URLS || "http://localhost:8081,http://localhost:8082")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
const rate = Number(__ENV.RATE || 1000);
const duration = __ENV.DURATION || "2m";
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || 100);
const maxVUs = Number(__ENV.MAX_VUS || 1000);
const p99Millis = Number(__ENV.P99_MS || 1000);
const runId = __ENV.RUN_ID || `relayq-${Date.now()}`;

export const options = {
    discardResponseBodies: true,
    scenarios: {
        submit: {
            executor: "constant-arrival-rate",
            rate,
            timeUnit: "1s",
            duration,
            preAllocatedVUs,
            maxVUs,
        },
    },
    thresholds: {
        http_req_failed: ["rate<0.01"],
        http_req_duration: [`p(99)<${p99Millis}`],
        checks: ["rate>0.99"],
        // constant-arrival-rate drops iterations when the SUT cannot keep up
        // with the requested rate. Treat any drop as a capacity-test failure
        // even when the latency of completed requests still looks healthy.
        dropped_iterations: ["count==0"],
    },
};

export default function () {
    const iteration = exec.scenario.iterationInTest;
    const baseUrl = baseUrls[iteration % baseUrls.length];
    const payload = JSON.stringify({
        biz_key: `${runId}-${iteration}`,
        handler_name: "load-test-handler",
        params: null,
        max_retry: 0,
    });

    const response = http.post(`${baseUrl}/api/tasks`, payload, {
        headers: {
            "Content-Type": "application/json",
        },
        tags: {
            operation: "submit-task",
        },
    });

    check(response, {
        "task accepted": (result) => result.status === 201,
    });
}
