"use strict";

const { definition: scrapeTitleDefinition, handleInvoke } = require("./web_scraper");

function createRuntimeStatusTool(runtimeState) {
  return Object.freeze({
    name: "node.runtime.status",
    description: "Return bundled Node.js runtime metadata, health state, and immutable packaging info.",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {},
    },
    handler: async () => ({
      status: runtimeState.status,
      startedAt: runtimeState.startedAt,
      pid: runtimeState.pid,
      port: runtimeState.port,
      runtimeRoot: runtimeState.runtimeRoot,
      entryScript: runtimeState.entryScript,
      reloadable: runtimeState.reloadable,
      lastError: runtimeState.lastError,
      nodeVersion: process.version,
    }),
  });
}

function createToolRegistry(runtimeState) {
  const tools = Object.freeze([
    Object.freeze({
      ...scrapeTitleDefinition,
      handler: handleInvoke,
    }),
    createRuntimeStatusTool(runtimeState),
  ]);

  const toolsByName = new Map(tools.map((tool) => [tool.name, tool]));

  return Object.freeze({
    list() {
      return tools.map(({ handler, ...definition }) => definition);
    },
    async call(name, argumentsObject) {
      const tool = toolsByName.get(name);
      if (!tool) {
        throw new Error(`Unknown Node MCP tool: ${name}`);
      }
      return tool.handler(argumentsObject || {});
    },
  });
}

module.exports = {
  createToolRegistry,
};
