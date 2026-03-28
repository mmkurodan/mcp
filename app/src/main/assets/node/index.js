"use strict";

process.env.NODE_ENV = process.env.NODE_ENV || "production";

const http = require("http");
const path = require("path");
const { createToolRegistry } = require("./services/tool_registry");

const port = Number.parseInt(process.argv[2] || process.env.MCP_NODE_PORT || "8766", 10);
const runtimeRoot = process.argv[3] || __dirname;
const entryScript = path.join(runtimeRoot, "index.js");
const runtimeState = {
  startedAt: new Date().toISOString(),
  port,
  pid: process.pid,
  runtimeRoot,
  entryScript,
  reloadable: false,
  status: "starting",
  lastError: null,
};

const toolRegistry = createToolRegistry(runtimeState);

function writeJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

function parseJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 256 * 1024) {
        reject(new Error("request body exceeded 256 KB"));
      }
    });
    req.on("end", () => {
      try {
        resolve(JSON.parse(body || "{}"));
      } catch (error) {
        reject(new Error(`invalid JSON payload: ${error.message}`));
      }
    });
    req.on("error", reject);
  });
}

function buildHealthPayload() {
  return {
    status: runtimeState.status,
    port,
    pid: process.pid,
    nodeVersion: process.version,
    runtimeRoot,
    entryScript,
    reloadable: runtimeState.reloadable,
    lastError: runtimeState.lastError,
    tools: toolRegistry.list().map((tool) => tool.name),
  };
}

function jsonRpcSuccess(id, result) {
  return {
    jsonrpc: "2.0",
    id,
    result,
  };
}

function jsonRpcError(id, code, message, data) {
  return {
    jsonrpc: "2.0",
    id,
    error: {
      code,
      message,
      data,
    },
  };
}

async function handleJsonRpc(request) {
  const id = request && Object.prototype.hasOwnProperty.call(request, "id") ? request.id : null;
  if (!request || request.jsonrpc !== "2.0") {
    return jsonRpcError(id, -32600, "jsonrpc must be 2.0");
  }
  if (typeof request.method !== "string" || request.method.length === 0) {
    return jsonRpcError(id, -32600, "method must be a non-empty string");
  }

  const params = request.params && typeof request.params === "object" ? request.params : {};
  switch (request.method) {
    case "ping":
      return jsonRpcSuccess(id, {
        ok: true,
        pid: process.pid,
        port,
      });
    case "tools/list":
      return jsonRpcSuccess(id, {
        tools: toolRegistry.list(),
      });
    case "tools/call":
      if (typeof params.name !== "string" || params.name.length === 0) {
        return jsonRpcError(id, -32602, "params.name is required");
      }
      return jsonRpcSuccess(id, await toolRegistry.call(params.name, params.arguments || {}));
    default:
      return jsonRpcError(id, -32601, `method not found: ${request.method}`);
  }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/health") {
      writeJson(res, 200, buildHealthPayload());
      return;
    }

    if (req.method === "POST" && req.url === "/invoke") {
      const payload = await parseJsonBody(req);
      const result = await toolRegistry.call("node.scrape_title", payload);
      writeJson(res, 200, result);
      return;
    }

    if (req.method === "POST" && req.url === "/rpc") {
      const request = await parseJsonBody(req);
      writeJson(res, 200, await handleJsonRpc(request));
      return;
    }

    if (req.method === "POST" && req.url === "/shutdown") {
      writeJson(res, 200, { status: "shutting_down" });
      server.close(() => process.exit(0));
      return;
    }

    writeJson(res, 404, { error: "not_found" });
  } catch (error) {
    runtimeState.lastError = error && error.stack ? error.stack : String(error);
    writeJson(res, 500, {
      error: error.message,
      stack: error.stack,
    });
  }
});

process.on("uncaughtException", (error) => {
  runtimeState.lastError = `${error.name}: ${error.message}`;
});

process.on("unhandledRejection", (reason) => {
  if (reason instanceof Error) {
    runtimeState.lastError = `${reason.name}: ${reason.message}`;
    return;
  }
  runtimeState.lastError = String(reason);
});

server.listen(port, "127.0.0.1", () => {
  runtimeState.status = "ok";
});
