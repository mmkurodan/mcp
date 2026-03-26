"use strict";

const http = require("http");
const { handleInvoke } = require("./services/web_scraper");

const port = Number.parseInt(process.argv[2] || process.env.MCP_NODE_PORT || "8766", 10);

function writeJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/health") {
      writeJson(res, 200, { status: "ok", port });
      return;
    }

    if (req.method === "POST" && req.url === "/invoke") {
      let body = "";
      req.setEncoding("utf8");
      req.on("data", (chunk) => {
        body += chunk;
      });
      req.on("end", async () => {
        try {
          const payload = JSON.parse(body || "{}");
          const result = await handleInvoke(payload);
          writeJson(res, 200, result);
        } catch (error) {
          writeJson(res, 500, {
            error: error.message,
            stack: error.stack,
          });
        }
      });
      return;
    }

    writeJson(res, 404, { error: "not_found" });
  } catch (error) {
    writeJson(res, 500, { error: error.message });
  }
});

server.listen(port, "127.0.0.1", () => {
  // Intentionally quiet: Java side probes /health for readiness.
});
