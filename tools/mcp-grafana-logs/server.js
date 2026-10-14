// MCP server: đọc trace (Tempo) + log (Loki) từ Grafana để hỗ trợ xác định lỗi theo trace id.
//
// ENV cần set trước khi chạy:
//   GRAFANA_URL   = https://grafana-sme-sit.abbank.vn
//   GRAFANA_TOKEN = glsa_xxx (Service Account Token, role Viewer đủ dùng)
//   GRAFANA_LOKI_DATASOURCE_UID  = uid datasource Loki
//   GRAFANA_TEMPO_DATASOURCE_UID = uid datasource Tempo
//
// Lấy UID (chú ý: UID khác ID — API /api/datasources trả cả 2 field, cần lấy "uid"):
//   curl -s -H "Authorization: Bearer $GRAFANA_TOKEN" "$GRAFANA_URL/api/datasources" | jq '.[] | {id, uid, name, type}'

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const GRAFANA_URL = process.env.GRAFANA_URL;
const GRAFANA_TOKEN = process.env.GRAFANA_TOKEN;
const LOKI_UID = process.env.GRAFANA_LOKI_DATASOURCE_UID;
const TEMPO_UID = process.env.GRAFANA_TEMPO_DATASOURCE_UID;

if (!GRAFANA_URL || !GRAFANA_TOKEN) {
  console.error(
    "Thiếu ENV: cần GRAFANA_URL và GRAFANA_TOKEN. Xem comment đầu file server.js."
  );
  process.exit(1);
}

async function grafanaFetch(path, options = {}) {
  const res = await fetch(`${GRAFANA_URL}${path}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${GRAFANA_TOKEN}`,
      "Content-Type": "application/json",
      ...(options.headers ?? {}),
    },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`Grafana API ${res.status} ${res.statusText}: ${text}`);
  }
  return res.json();
}

// Query log Loki qua Grafana datasource proxy (API ổn định hơn gọi trực tiếp Loki).
// Dùng endpoint /api/ds/query (Grafana >= 8), theo chuẩn backend query.
async function queryLoki({ logql, startMs, endMs, limit = 200 }) {
  const body = {
    queries: [
      {
        refId: "A",
        datasource: { type: "loki", uid: LOKI_UID },
        expr: logql,
        maxLines: limit,
      },
    ],
    from: String(startMs),
    to: String(endMs),
  };
  const data = await grafanaFetch("/api/ds/query", {
    method: "POST",
    body: JSON.stringify(body),
  });
  // Response là Arrow/DataFrame format; rút gọn lại thành list dòng log dễ đọc.
  const frames = data?.results?.A?.frames ?? [];
  const lines = [];
  for (const frame of frames) {
    const fields = frame?.data?.values ?? [];
    // Loki frame thường có field thứ 2 là body log, field đầu là timestamp (ns)
    const timeField = fields[0] ?? [];
    const bodyField = fields[1] ?? [];
    const labelsField = fields[2] ?? [];
    for (let i = 0; i < bodyField.length; i++) {
      lines.push({
        timestamp: timeField[i],
        line: bodyField[i],
        labels: labelsField?.[i] ?? null,
      });
    }
  }
  return lines;
}

// Query trace đầy đủ từ Tempo qua Grafana datasource proxy, dùng Tempo native API
// (không qua /api/ds/query vì format trả về Tempo trực tiếp dễ xử lý hơn: full span tree JSON).
async function queryTempoTraceById(traceId) {
  const path = `/api/datasources/proxy/uid/${TEMPO_UID}/api/traces/${encodeURIComponent(traceId)}`;
  return grafanaFetch(path);
}

// Rút gọn Tempo trace response (OTLP-ish JSON) thành danh sách span dễ đọc,
// sort theo thời gian bắt đầu, kèm service name, duration, status, lỗi (nếu có).
function flattenTempoTrace(traceData) {
  const batches = traceData?.batches ?? traceData?.resourceSpans ?? [];
  const spans = [];

  for (const batch of batches) {
    // Lấy service name từ resource attributes
    const resAttrs = batch?.resource?.attributes ?? [];
    const serviceNameAttr = resAttrs.find((a) => a.key === "service.name");
    const serviceName =
      serviceNameAttr?.value?.stringValue ?? serviceNameAttr?.value?.string_value ?? "unknown";

    const scopeSpansList = batch?.scopeSpans ?? batch?.instrumentationLibrarySpans ?? [];
    for (const scopeSpans of scopeSpansList) {
      const spanList = scopeSpans?.spans ?? [];
      for (const span of spanList) {
        const startNanos = Number(span.startTimeUnixNano ?? 0);
        const endNanos = Number(span.endTimeUnixNano ?? 0);
        const attrs = {};
        for (const a of span.attributes ?? []) {
          const v = a.value ?? {};
          attrs[a.key] =
            v.stringValue ?? v.string_value ?? v.intValue ?? v.int_value ?? v.boolValue ?? v.bool_value ?? JSON.stringify(v);
        }
        const statusCode = span?.status?.code ?? 0; // 0=UNSET,1=OK,2=ERROR (OTLP convention)
        spans.push({
          spanId: span.spanId ?? span.span_id,
          parentSpanId: span.parentSpanId ?? span.parent_span_id ?? null,
          service: serviceName,
          operationName: span.name,
          startTimeMs: startNanos / 1e6,
          durationMs: (endNanos - startNanos) / 1e6,
          isError: statusCode === 2,
          statusMessage: span?.status?.message ?? null,
          attributes: attrs,
        });
      }
    }
  }

  spans.sort((a, b) => a.startTimeMs - b.startTimeMs);
  return spans;
}

const server = new McpServer({
  name: "grafana-logs",
  version: "0.1.0",
});

server.registerTool(
  "grafana_list_datasources",
  {
    title: "List Grafana datasources",
    description:
      "Liệt kê datasource đang cấu hình trong Grafana (để lấy uid, type phục vụ query log).",
    inputSchema: {},
  },
  async () => {
    const list = await grafanaFetch("/api/datasources");
    const summary = list.map((d) => ({ uid: d.uid, name: d.name, type: d.type }));
    return {
      content: [{ type: "text", text: JSON.stringify(summary, null, 2) }],
    };
  }
);

server.registerTool(
  "grafana_query_logs",
  {
    title: "Query logs from Loki via Grafana",
    description:
      "Query log thô từ Loki datasource qua Grafana API. Dùng cú pháp LogQL, ví dụ: " +
      '`{app="approval-service"} |= "error"`. Trả về time range theo phút tính từ hiện tại (minutesAgo) hoặc theo mốc ISO.',
    inputSchema: {
      logql: z.string().describe('Biểu thức LogQL, ví dụ: {app="approval-service"} |= "500"'),
      minutesAgo: z
        .number()
        .optional()
        .describe("Lấy log từ N phút trước tới hiện tại (mặc định 60)"),
      fromIso: z.string().optional().describe("Thời điểm bắt đầu (ISO 8601), ưu tiên hơn minutesAgo nếu set"),
      toIso: z.string().optional().describe("Thời điểm kết thúc (ISO 8601), mặc định là hiện tại"),
      limit: z.number().optional().describe("Số dòng log tối đa (mặc định 200)"),
    },
  },
  async ({ logql, minutesAgo = 60, fromIso, toIso, limit = 200 }) => {
    if (!LOKI_UID) {
      throw new Error(
        "Thiếu GRAFANA_LOKI_DATASOURCE_UID. Chạy tool grafana_list_datasources trước để lấy uid."
      );
    }
    const now = Date.now();
    const endMs = toIso ? new Date(toIso).getTime() : now;
    const startMs = fromIso ? new Date(fromIso).getTime() : endMs - minutesAgo * 60_000;

    const lines = await queryLoki({ logql, startMs, endMs, limit });
    return {
      content: [{ type: "text", text: JSON.stringify(lines, null, 2) }],
    };
  }
);

server.registerTool(
  "grafana_analyze_errors",
  {
    title: "Analyze error logs",
    description:
      "Query log lỗi (level=error hoặc status>=500) trong khoảng thời gian, group theo nội dung message để tìm pattern lỗi lặp lại nhiều nhất.",
    inputSchema: {
      app: z.string().describe('Label app/service cần lọc, ví dụ: approval-service'),
      minutesAgo: z.number().optional().describe("Mặc định 60 phút"),
      limit: z.number().optional().describe("Số dòng log tối đa để quét, mặc định 500"),
    },
  },
  async ({ app, minutesAgo = 60, limit = 500 }) => {
    if (!LOKI_UID) {
      throw new Error(
        "Thiếu GRAFANA_LOKI_DATASOURCE_UID. Chạy tool grafana_list_datasources trước để lấy uid."
      );
    }
    const now = Date.now();
    const endMs = now;
    const startMs = now - minutesAgo * 60_000;

    // Điều chỉnh biểu thức LogQL theo convention log thật của bạn
    // (ví dụ nếu log là JSON có field "level", dùng `| json | level="error"`)
    const logql = `{app="${app}"} |~ "(?i)(error|exception|500|failed)"`;
    const lines = await queryLoki({ logql, startMs, endMs, limit });

    // Group đơn giản theo 60 ký tự đầu của message để tìm pattern lặp lại
    const groups = new Map();
    for (const l of lines) {
      const key = String(l.line ?? "").slice(0, 80);
      groups.set(key, (groups.get(key) ?? 0) + 1);
    }
    const top = [...groups.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 20)
      .map(([pattern, count]) => ({ count, pattern }));

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            { totalMatched: lines.length, topPatterns: top },
            null,
            2
          ),
        },
      ],
    };
  }
);

server.registerTool(
  "grafana_find_by_trace_id",
  {
    title: "Find all logs for a trace/correlation ID",
    description:
      "Tìm tất cả log line có chứa traceId/correlationId/requestId cụ thể, trên tất cả service (không giới hạn theo 1 app), " +
      "sort theo thời gian tăng dần để dễ đọc lại trình tự request đi qua các service. " +
      "Dùng khi cần debug 1 request/giao dịch cụ thể (ví dụ luồng approval/signing) theo trace id.",
    inputSchema: {
      traceId: z.string().describe("Giá trị trace id / correlation id / request id cần tìm, ví dụ: abc-123-def"),
      appLabel: z
        .string()
        .optional()
        .describe(
          'Giới hạn theo 1 app cụ thể nếu biết, ví dụ "approval-service". Để trống sẽ quét tất cả app (dùng {} làm stream selector).'
        ),
      minutesAgo: z
        .number()
        .optional()
        .describe("Lấy log từ N phút trước tới hiện tại (mặc định 1440 = 24h, vì trace có thể cũ hơn mặc định 60 phút)"),
      fromIso: z.string().optional().describe("Thời điểm bắt đầu (ISO 8601), ưu tiên hơn minutesAgo nếu set"),
      toIso: z.string().optional().describe("Thời điểm kết thúc (ISO 8601), mặc định là hiện tại"),
      limit: z.number().optional().describe("Số dòng log tối đa (mặc định 500)"),
    },
  },
  async ({ traceId, appLabel, minutesAgo = 1440, fromIso, toIso, limit = 500 }) => {
    if (!LOKI_UID) {
      throw new Error(
        "Thiếu GRAFANA_LOKI_DATASOURCE_UID. Chạy tool grafana_list_datasources trước để lấy uid."
      );
    }
    const now = Date.now();
    const endMs = toIso ? new Date(toIso).getTime() : now;
    const startMs = fromIso ? new Date(fromIso).getTime() : endMs - minutesAgo * 60_000;

    // Stream selector: nếu biết app thì lọc theo app để nhanh hơn, không thì quét tất cả stream.
    // Loki yêu cầu stream selector có ít nhất 1 label; nếu không có appLabel dùng selector rộng.
    const selector = appLabel ? `{app="${appLabel}"}` : `{job=~".+"}`;
    // Escape traceId tránh ký tự đặc biệt phá regex LogQL
    const escaped = traceId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const logql = `${selector} |~ "${escaped}"`;

    const lines = await queryLoki({ logql, startMs, endMs, limit });

    // Sort theo timestamp tăng dần để đọc đúng trình tự diễn ra của request qua các service
    const sorted = [...lines].sort((a, b) => {
      const ta = Number(a.timestamp ?? 0);
      const tb = Number(b.timestamp ?? 0);
      return ta - tb;
    });

    if (sorted.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                traceId,
                found: 0,
                hint:
                  "Không tìm thấy log nào chứa traceId này trong khoảng thời gian đã quét. " +
                  "Thử: (1) tăng minutesAgo/mở rộng fromIso-toIso, (2) kiểm tra traceId có đúng format log thực tế không, " +
                  "(3) nếu biết chắc service nào xử lý, truyền appLabel để tránh miss do stream selector quá rộng.",
              },
              null,
              2
            ),
          },
        ],
      };
    }

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              traceId,
              found: sorted.length,
              timeline: sorted,
            },
            null,
            2
          ),
        },
      ],
    };
  }
);

server.registerTool(
  "grafana_get_trace",
  {
    title: "Get full trace tree from Tempo",
    description:
      "Lấy toàn bộ span tree của 1 trace id từ Tempo: thấy được request đi qua service nào, theo thứ tự nào, " +
      "span nào bị lỗi (isError), và duration (ms) từng bước. Đây là nguồn chính xác nhất để biết request đi qua đâu, " +
      "ưu tiên dùng tool này trước khi tìm log chi tiết bằng Loki.",
    inputSchema: {
      traceId: z.string().describe("Trace ID (hex string, thường 16 hoặc 32 ký tự, lấy từ header response hoặc log)"),
    },
  },
  async ({ traceId }) => {
    if (!TEMPO_UID) {
      throw new Error(
        "Thiếu GRAFANA_TEMPO_DATASOURCE_UID. Chạy grafana_list_datasources để lấy uid của Tempo."
      );
    }
    const raw = await queryTempoTraceById(traceId);
    const spans = flattenTempoTrace(raw);

    if (spans.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                traceId,
                found: 0,
                hint:
                  "Không tìm thấy span nào. Kiểm tra: (1) traceId đúng format hex chưa (không phải traceId dạng khác như request-id tự sinh), " +
                  "(2) trace có thể đã hết retention trong Tempo (thường chỉ giữ vài giờ tới vài ngày tùy config), " +
                  "(3) service có thực sự export trace vào Tempo không.",
              },
              null,
              2
            ),
          },
        ],
      };
    }

    const services = [...new Set(spans.map((s) => s.service))];
    const errorSpans = spans.filter((s) => s.isError);
    const totalDurationMs =
      Math.max(...spans.map((s) => s.startTimeMs + s.durationMs)) -
      Math.min(...spans.map((s) => s.startTimeMs));

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              traceId,
              totalSpans: spans.length,
              servicesInvolved: services,
              totalDurationMs,
              errorSpanCount: errorSpans.length,
              errorSpans,
              fullTimeline: spans,
            },
            null,
            2
          ),
        },
      ],
    };
  }
);

server.registerTool(
  "grafana_investigate_trace",
  {
    title: "Investigate a trace: Tempo spans + matching Loki logs",
    description:
      "Tool tổng hợp dùng để debug 1 request/giao dịch cụ thể theo trace id: " +
      "(1) lấy span tree từ Tempo để biết request đi qua service nào, thứ tự nào, span nào lỗi, " +
      "(2) tự xác định time window chính xác từ span đó, " +
      "(3) query Loki trong đúng time window + theo tên các service liên quan để lấy log chi tiết (message, stacktrace). " +
      "Dùng tool này khi muốn hiểu toàn diện 1 trace, thay vì gọi riêng grafana_get_trace và grafana_query_logs.",
    inputSchema: {
      traceId: z.string().describe("Trace ID cần điều tra"),
      logPaddingSeconds: z
        .number()
        .optional()
        .describe("Nới rộng time window khi query Loki (giây) trước/sau trace, mặc định 5s để tránh miss log do lệch clock"),
      logLimitPerService: z
        .number()
        .optional()
        .describe("Số dòng log tối đa lấy mỗi service, mặc định 100"),
    },
  },
  async ({ traceId, logPaddingSeconds = 5, logLimitPerService = 100 }) => {
    if (!TEMPO_UID) {
      throw new Error("Thiếu GRAFANA_TEMPO_DATASOURCE_UID.");
    }

    const raw = await queryTempoTraceById(traceId);
    const spans = flattenTempoTrace(raw);

    if (spans.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                traceId,
                found: 0,
                hint: "Không tìm thấy trace trong Tempo. Xem hint chi tiết ở tool grafana_get_trace.",
              },
              null,
              2
            ),
          },
        ],
      };
    }

    const services = [...new Set(spans.map((s) => s.service))];
    const errorSpans = spans.filter((s) => s.isError);
    const minStartMs = Math.min(...spans.map((s) => s.startTimeMs));
    const maxEndMs = Math.max(...spans.map((s) => s.startTimeMs + s.durationMs));
    const windowStartMs = minStartMs - logPaddingSeconds * 1000;
    const windowEndMs = maxEndMs + logPaddingSeconds * 1000;

    // Query log của từng service liên quan, trong đúng time window của trace, có chứa traceId để giảm nhiễu.
    const escapedTraceId = traceId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const logsByService = {};
    let lokiError = null;

    if (LOKI_UID) {
      for (const svc of services) {
        try {
          const logql = `{app="${svc}"} |~ "${escapedTraceId}"`;
          const lines = await queryLoki({
            logql,
            startMs: windowStartMs,
            endMs: windowEndMs,
            limit: logLimitPerService,
          });
          logsByService[svc] = lines.sort(
            (a, b) => Number(a.timestamp ?? 0) - Number(b.timestamp ?? 0)
          );
        } catch (e) {
          logsByService[svc] = { error: String(e.message ?? e) };
        }
      }
    } else {
      lokiError = "Thiếu GRAFANA_LOKI_DATASOURCE_UID, bỏ qua phần log Loki, chỉ trả về trace Tempo.";
    }

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              traceId,
              summary: {
                totalSpans: spans.length,
                servicesInvolved: services,
                totalDurationMs: maxEndMs - minStartMs,
                errorSpanCount: errorSpans.length,
              },
              errorSpans,
              traceTimeline: spans,
              logsByService,
              lokiNote: lokiError,
              analysisHint:
                "Đọc traceTimeline theo thứ tự startTimeMs để biết request đi qua service nào trước/sau. " +
                "errorSpans cho biết chính xác span nào set status=ERROR. " +
                "logsByService chứa log line thật của từng service trong đúng khoảng thời gian trace diễn ra, " +
                "dùng để lấy message lỗi/stacktrace chi tiết mà span attributes không có.",
            },
            null,
            2
          ),
        },
      ],
    };
  }
);

const transport = new StdioServerTransport();
await server.connect(transport);
