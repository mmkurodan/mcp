"use strict";

const axios = require("axios");
const cheerio = require("cheerio");

const definition = Object.freeze({
  name: "node.scrape_title",
  description: "Fetch an external document with axios and return the title plus a small anchor preview.",
  inputSchema: {
    type: "object",
    additionalProperties: false,
    properties: {
      url: {
        type: "string",
        format: "uri",
        description: "Remote HTTP or HTTPS URL to fetch.",
      },
      timeoutMs: {
        type: "integer",
        minimum: 250,
        maximum: 15000,
        description: "Outbound fetch timeout in milliseconds.",
      },
    },
    required: ["url"],
  },
});

function sanitizeTimeout(timeoutMs) {
  const numeric = Number.parseInt(timeoutMs || "3000", 10);
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return 3000;
  }
  return Math.min(Math.max(numeric, 250), 15000);
}

async function handleInvoke(payload) {
  if (!payload || typeof payload.url !== "string" || payload.url.length === 0) {
    throw new Error("payload.url is required");
  }

  const timeout = sanitizeTimeout(payload.timeoutMs);
  const response = await axios.get(payload.url, {
    timeout,
    responseType: "text",
    maxContentLength: 512 * 1024,
    headers: {
      "User-Agent": "Android-Embedded-MCP/1.0",
    },
  });

  const $ = cheerio.load(response.data);
  const links = [];
  $("a[href]").slice(0, 5).each((_, element) => {
    links.push({
      href: $(element).attr("href"),
      text: $(element).text().trim(),
    });
  });

  return {
    url: payload.url,
    title: $("title").first().text().trim(),
    statusCode: response.status,
    links,
  };
}

module.exports = {
  definition,
  handleInvoke,
};
